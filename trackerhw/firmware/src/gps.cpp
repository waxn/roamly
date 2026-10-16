#include "gps.h"
#include "config.h"
#include "settings.h"
#include "util.h"
#include <TinyGPSPlus.h>
#include <sys/time.h>
#include <time.h>

// The L76K speaks the CASIC protocol: configuration is plain NMEA-style
// $PCAS sentences, and none of them is acknowledged. So instead of waiting
// for an ACK, gpsApplyMode() watches the output change to what it asked for.
//
// Multi-GNSS changes the sentence names: position comes as $GNRMC/$GNGGA
// (TinyGPSPlus understands GN), but satellites arrive per constellation
// ($GPGSV, $BDGSV, $GLGSV) and the fix type as $GNGSA, which TinyGPSCustom
// can't match across talkers, so those go through the small parser below.

static HardwareSerial& GPS = Serial1;
static TinyGPSPlus nmea;

static GpsFix fix;
static GpsInfo info;
static Stream* rawEcho = nullptr;
static uint32_t wokeAtMs = 0;
static bool haveFixSinceWake = false;

// HDOP-to-metres factor (user equivalent range error). A rough, conventional
// figure for a modern multi-GNSS receiver; the L76K reports no error estimate.
static constexpr float UERE_M = 3.5f;

// ── Line parser for GSA / GSV / TXT ─────────────────────────────────────────
static char line[100];
static uint8_t lineLen = 0;
static uint8_t gsaFixMode = 0;
static uint32_t gsaAtMs = 0;
static uint32_t txtSeenMs = 0;
static bool sawGsv = false, sawVtg = false, sawGll = false, sawGn = false;

// Per-satellite SNR, keyed by constellation + PRN, aged out after a few seconds.
static constexpr int SNR_SLOTS = 3 * 100;
static uint8_t snr[SNR_SLOTS];
static uint32_t snrAt[SNR_SLOTS];
static uint32_t viewAt[3];
static uint8_t viewCount[3];

static int talkerIndex(const char* t) {
  if (!strncmp(t, "GP", 2)) return 0;
  if (!strncmp(t, "BD", 2) || !strncmp(t, "GB", 2)) return 1;
  if (!strncmp(t, "GL", 2)) return 2;
  return -1;
}

static int splitFields(char* s, char** f, int max) {
  int n = 0;
  f[n++] = s;
  for (char* p = s; *p && n < max; p++) {
    if (*p == ',' || *p == '*') { *p = 0; f[n++] = p + 1; }
  }
  return n;
}

static void satStats() {
  uint32_t now = millis();
  uint8_t top[4] = {0, 0, 0, 0}, tracked = 0, best = 0;
  for (int i = 0; i < SNR_SLOTS; i++) {
    if (!snrAt[i] || now - snrAt[i] > 5000 || !snr[i]) continue;
    tracked++;
    uint8_t s = snr[i];
    if (s > best) best = s;
    for (int k = 0; k < 4; k++) if (s > top[k]) { for (int j = 3; j > k; j--) top[j] = top[j - 1]; top[k] = s; break; }
  }
  info.tracked = tracked;
  info.bestSnr = best;
  int n = 0, sum = 0;
  for (int k = 0; k < 4; k++) if (top[k]) { sum += top[k]; n++; }
  info.avgTop4Snr = n ? (float)sum / n : 0;
  uint8_t v[3];
  for (int c = 0; c < 3; c++) v[c] = (viewAt[c] && now - viewAt[c] < 5000) ? viewCount[c] : 0;
  info.inViewGps = v[0]; info.inViewBds = v[1]; info.inViewGlo = v[2];
  info.inView = v[0] + v[1] + v[2];
}

static void onLine() {
  // Validate the checksum ourselves; TinyGPSPlus does so for its own sentences.
  char* star = strchr(line, '*');
  if (line[0] != '$' || !star) return;
  uint8_t cs = 0;
  for (char* p = line + 1; p < star; p++) cs ^= (uint8_t)*p;
  if (strtoul(star + 1, nullptr, 16) != cs) return;
  if (strlen(line) < 7) return;
  const char* type = line + 3;   // after "$" + 2-char talker
  if (line[1] == 'G' && line[2] == 'N') sawGn = true;
  if (!strncmp(type, "VTG", 3)) { sawVtg = true; return; }
  if (!strncmp(type, "GLL", 3)) { sawGll = true; return; }
  char* f[24];
  if (!strncmp(type, "GSA", 3)) {
    int n = splitFields(line, f, 24);
    if (n > 2) { gsaFixMode = atoi(f[2]); gsaAtMs = millis(); }
  } else if (!strncmp(type, "GSV", 3)) {
    sawGsv = true;
    int c = talkerIndex(line + 1);
    if (c < 0) return;
    int n = splitFields(line, f, 24);
    if (n > 3) { viewCount[c] = atoi(f[3]); viewAt[c] = millis(); }
    for (int k = 0; k < 4; k++) {
      int pi = 4 + k * 4, si = 7 + k * 4;
      if (si >= n) break;
      int prn = atoi(f[pi]);
      if (prn <= 0 || prn >= 100) continue;
      snr[c * 100 + prn] = (uint8_t)atoi(f[si]);
      snrAt[c * 100 + prn] = millis();
    }
    satStats();
  } else if (!strncmp(type, "TXT", 3)) {
    txtSeenMs = millis();
    // $GPTXT,01,01,02,SW=URANUS5,V5.x... answers $PCAS06,0 (firmware version)
    char* sw = strstr(line, "SW=");
    if (sw) {
      strlcpy(info.version, sw + 3, sizeof info.version);
      char* e = strchr(info.version, '*');
      if (e) *e = 0;
    }
  }
}

// ── Epoch assembly (RMC + GGA via TinyGPSPlus) ──────────────────────────────
// RMC and GGA both carry the position, so the fix is built per epoch (keyed by
// its timestamp) and published once both have arrived — or when the next
// epoch starts, if one of them never does.
static GpsFix pend;
static uint32_t pendEpoch = UINT32_MAX;
static bool pendPublished = true;
static uint8_t pendParts = 0;   // bit0 RMC-ish (speed), bit1 GGA-ish (sats/hdop)

static void publish() {
  pend.accM = isnan(pend.hdop) ? NAN : pend.hdop * UERE_M;
  pend.atMs = millis();
  fix = pend;
  pendPublished = true;
  if (fix.valid && !haveFixSinceWake) {
    haveFixSinceWake = true;
    info.ttffMs = millis() - wokeAtMs;
  }
}

static void onSentence() {
  info.sentences = nmea.passedChecksum();
  info.lastSentenceMs = millis();

  if (nmea.date.isValid() && nmea.time.isValid() && nmea.date.year() >= 2020 && nmea.time.isUpdated()) {
    time_t t = (time_t)civilToUnix(nmea.date.year(), nmea.date.month(), nmea.date.day(),
                                   nmea.time.hour(), nmea.time.minute(), nmea.time.second());
    fix.unix = (uint32_t)t;
    time_t now = time(nullptr);
    if (!info.timeValid || llabs((long long)now - (long long)t) > 2) {
      struct timeval tv = {t, 0};
      settimeofday(&tv, nullptr);
    }
    info.timeValid = true;
  }

  if (nmea.location.isUpdated()) {
    uint32_t ep = nmea.time.value();
    if (ep != pendEpoch) {
      if (!pendPublished) publish();
      pend = GpsFix();
      pendEpoch = ep;
      pendPublished = false;
      pendParts = 0;
    }
    // A position is only a measurement if the receiver says so: GGA quality 6
    // ("estimated" / dead reckoning, which the L76K reports for its first
    // seconds after a reset), 7 (manual) or 8 (simulated) and RMC mode E/N are
    // guesses, and storing them would put points where the tracker never was.
    TinyGPSLocation::Quality q = nmea.location.FixQuality();
    TinyGPSLocation::Mode md = nmea.location.FixMode();
    bool measured = q != TinyGPSLocation::Estimated && q != TinyGPSLocation::Manual &&
                    q != TinyGPSLocation::Simulated && md != TinyGPSLocation::E;
    pend.valid = nmea.location.isValid() && nmea.satellites.value() >= 3 && measured;
    pend.lat = nmea.location.lat();
    pend.lon = nmea.location.lng();
    pend.unix = fix.unix;
    if (nmea.altitude.isUpdated() || nmea.altitude.isValid()) pend.altM = nmea.altitude.meters();
    if (nmea.speed.isValid()) { pend.spdMs = nmea.speed.mps(); pendParts |= 1; }
    if (nmea.course.isValid()) pend.crsDeg = nmea.course.deg();
    if (nmea.satellites.isValid()) { pend.sats = nmea.satellites.value(); pendParts |= 2; }
    if (nmea.hdop.isValid()) pend.hdop = nmea.hdop.hdop();
    bool gsaFresh = gsaAtMs && millis() - gsaAtMs < 2500;
    pend.fixType = gsaFresh && gsaFixMode ? gsaFixMode : (pend.valid ? 2 : 1);
    if (pendParts == 3) publish();
  }
}

void gpsPoll() {
  while (GPS.available()) {
    int c = GPS.read();
    info.bytes++;
    if (rawEcho) rawEcho->write((uint8_t)c);
    uint32_t bad = nmea.failedChecksum();
    bool committed = nmea.encode((char)c);
    if (nmea.failedChecksum() != bad) info.badChecksum++;
    if (c == '$') lineLen = 0;
    if (c == '\n' || c == '\r') {
      if (lineLen) { line[lineLen] = 0; onLine(); lineLen = 0; }
    } else if (lineLen < sizeof(line) - 1) {
      line[lineLen++] = (char)c;
    }
    if (committed) onSentence();
  }
}

// ── Commands ────────────────────────────────────────────────────────────────
static void pcas(const char* body) {
  uint8_t cs = 0;
  for (const char* p = body; *p; p++) cs ^= (uint8_t)*p;
  char out[80];
  snprintf(out, sizeof out, "$%s*%02X\r\n", body, cs);
  GPS.print(out);
  GPS.flush();
}

static void pump(uint32_t ms) {
  uint32_t t0 = millis();
  while (millis() - t0 < ms) { gpsPoll(); delay(5); }
}

// ── Detection ───────────────────────────────────────────────────────────────
static bool listenFor(int rx, uint32_t baud, uint32_t ms) {
  GPS.end();
  GPS.begin(baud, SERIAL_8N1, rx, -1);
  TinyGPSPlus probe;
  uint32_t t0 = millis();
  while (millis() - t0 < ms) {
    while (GPS.available()) {
      if (probe.encode((char)GPS.read()) && probe.passedChecksum() > 0) return true;
    }
    delay(2);
  }
  return probe.passedChecksum() > 0;
}

// With RX found, find which pin reaches the module's RX. The L76K never
// acknowledges anything, and once configured it no longer answers a version
// query (TXT output is off), so prove the link by its effect: turn VTG on,
// watch for it, and turn it back off.
static bool txWorks(int rx, int tx, uint32_t baud) {
  GPS.end();
  GPS.begin(baud, SERIAL_8N1, rx, tx);
  sawVtg = false;
  pcas("PCAS03,1,0,1,1,1,1,0,0");
  pump(1800);
  bool ok = sawVtg;
  pcas("PCAS03,1,0,1,1,1,0,0,0");
  pump(200);
  return ok;
}

static int findTx(int rx, uint32_t baud) {
  const int cands[] = {PIN_GPS_TX_DEFAULT, 39, 38, 14, 15, 16, 17, 8, 5, 6, 9, 10, 11, 12, 13};
  for (int tx : cands) {
    if (tx == rx || tx == PIN_GPS_STANDBY || tx == PIN_GPS_RESET) continue;
    if (txWorks(rx, tx, baud)) return tx;
  }
  return PIN_GPS_TX_DEFAULT;   // nothing answered: trust the wiring guide
}

bool gpsDetect(bool verbose) {
  const int pins[] = {PIN_GPS_RX_DEFAULT, 39, 14, 15, 16, 17, 8, 5, 6, 9, 10, 11, 12, 13};
  const uint32_t bauds[] = {9600, 115200, 38400, 57600, 4800};
  for (uint32_t baud : bauds) {
    for (int rx : pins) {
      // Never reconfigure the control lines as UART inputs: a floating RESET
      // holds the module in reset for the rest of the scan.
      if (rx == PIN_GPS_STANDBY || rx == PIN_GPS_RESET) continue;
      if (verbose) Serial.printf("[gps] probe rx=%d @%lu\n", rx, (unsigned long)baud);
      if (listenFor(rx, baud, baud == 9600 ? 1300 : 900)) {
        int tx = findTx(rx, baud);
        if (verbose) Serial.printf("[gps] NMEA on rx=%d @%lu, tx=%d (%s)\n", rx, (unsigned long)baud, tx, info.version);
        cfg.gpsRx = rx;
        cfg.gpsTx = tx;
        cfg.gpsBaud = baud;
        settingsSave();
        return true;
      }
    }
  }
  return false;
}

// ── Configuration ───────────────────────────────────────────────────────────
bool gpsApplyMode(uint8_t mode, uint16_t intervalS) {
  (void)mode; (void)intervalS;   // power modes are driven from our side (standby pin)
  if (cfg.gpsTx < 0) return false;
  // GPS + BeiDou + GLONASS: the most satellites, which is what matters most
  // with the antenna in a pocket.
  pcas("PCAS04,7");
  delay(100);
  pcas("PCAS02,1000");                      // 1 Hz
  delay(100);
  // Exactly the sentences we parse: GGA, GSA, GSV, RMC. GLL, VTG, ZDA, TXT off.
  pcas("PCAS03,1,0,1,1,1,0,0,0");
  // Nothing is acknowledged, so confirm by watching the output change.
  sawGsv = sawVtg = sawGll = sawGn = false;
  pump(2500);
  // $GN talkers only appear once multi-GNSS is really on, so this can't be
  // fooled by a receiver that happened to be emitting the same sentence set.
  info.configured = sawGn && sawGsv && !sawVtg && !sawGll;
  // Persist to the module's flash once, so it also comes up configured after
  // a power cycle (and standby/reset can't lose it). Flash has finite write
  // cycles, so not on every boot.
  if (info.configured && !cfg.gpsCfgSaved) {
    pcas("PCAS00");
    delay(300);
    cfg.gpsCfgSaved = 1;
    settingsSave();
  }
  info.saved = cfg.gpsCfgSaved;
  return info.configured;
}

// ── Power ───────────────────────────────────────────────────────────────────
// STANDBY high = running, low = standby (~0.36 mA, time and ephemeris kept, so
// the next fix is a hot start). The module can't wake itself, so the logger
// wakes it gpsWakeLeadMs() ahead of each fix window.
uint32_t gpsWakeLeadMs() {
  switch (cfg.gpsMode) {
    case GPS_ACCURATE: return 0;        // never sleeps
    case GPS_LOWPOWER: return 5000;
    default:           return 9000;     // Balanced: a longer warm-up for better fixes
  }
}

static uint32_t awakeSinceMs = 0;   // when STANDBY last went high

void gpsStandby() {
  digitalWrite(PIN_GPS_STANDBY, LOW);
  if (!info.asleep) info.awakeMs += millis() - awakeSinceMs;
  info.asleep = true;
}

// Fraction of time the receiver has been awake since counting started (the
// receiver's draw is ~41 mA awake vs ~0.36 mA in standby, so this is most of
// the battery story). Resets with gpsDutyReset().
float gpsDuty() {
  uint32_t now = millis(), span = now - info.sinceMs;
  uint32_t on = info.awakeMs + (info.asleep ? 0 : now - awakeSinceMs);
  return span ? (float)on / span : 1.0f;
}

void gpsDutyReset() {
  info.awakeMs = 0;
  info.sinceMs = millis();
  awakeSinceMs = millis();
}

void gpsWake() {
  digitalWrite(PIN_GPS_STANDBY, HIGH);
  if (info.asleep) { wokeAtMs = millis(); haveFixSinceWake = false; awakeSinceMs = millis(); }
  info.asleep = false;
}

void gpsReset(bool cold) {
  pcas(cold ? "PCAS10,2" : "PCAS10,0");
  wokeAtMs = millis();
  haveFixSinceWake = false;
}

void gpsHardReset() {
  digitalWrite(PIN_GPS_RESET, LOW);
  delay(100);
  digitalWrite(PIN_GPS_RESET, HIGH);
  wokeAtMs = millis();
  haveFixSinceWake = false;
}

bool gpsBegin() {
  pinMode(PIN_GPS_RESET, OUTPUT);
  digitalWrite(PIN_GPS_RESET, HIGH);
  pinMode(PIN_GPS_STANDBY, OUTPUT);
  digitalWrite(PIN_GPS_STANDBY, HIGH);   // awake (it may be coming out of a power-off standby)
  info.asleep = false;
  wokeAtMs = millis();
  gpsDutyReset();
  delay(300);
  bool found = false;
  if (cfg.gpsRx >= 0 && cfg.gpsBaud) found = listenFor(cfg.gpsRx, cfg.gpsBaud, 2500) || listenFor(cfg.gpsRx, cfg.gpsBaud, 3000);
  if (!found) found = gpsDetect(true);
  info.detected = found;
  if (!found) return false;
  GPS.end();
  GPS.setRxBufferSize(1024);
  GPS.begin(cfg.gpsBaud, SERIAL_8N1, cfg.gpsRx, cfg.gpsTx);
  info.rx = cfg.gpsRx;
  info.tx = cfg.gpsTx;
  info.baud = cfg.gpsBaud;
  if (cfg.gpsTx >= 0 && !info.version[0]) { pcas("PCAS06,0"); pump(800); }
  if (!gpsApplyMode(cfg.gpsMode, cfg.intervalS)) {
    // Hearing it but unable to configure it: the remembered TX pin is stale
    // (rewired, or a module swap). Find it again and retry once.
    int tx = findTx(cfg.gpsRx, cfg.gpsBaud);
    if (tx != cfg.gpsTx) {
      Serial.printf("[gps] TX pin %d didn't configure the module; now using %d\n", cfg.gpsTx, tx);
      cfg.gpsTx = tx;
      settingsSave();
    }
    GPS.end();
    GPS.begin(cfg.gpsBaud, SERIAL_8N1, cfg.gpsRx, cfg.gpsTx);
    info.tx = cfg.gpsTx;
    gpsApplyMode(cfg.gpsMode, cfg.intervalS);
  }
  return true;
}

const GpsFix& gpsLatest() { return fix; }
GpsInfo& gpsInfo() { return info; }
void gpsSetRawEcho(Stream* s) { rawEcho = s; }
