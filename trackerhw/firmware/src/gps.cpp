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

// When the ESP clock was last set from GNSS time. In RTC memory: it survives a
// deep sleep (power off) and a reset, but not a power loss -- exactly when the
// clock itself is trustworthy. Drives how much a time hint can be trusted.
RTC_DATA_ATTR static uint32_t rtcClockSetUnix = 0;
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
    rtcClockSetUnix = (uint32_t)t;
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

// ── CASIC binary frames (only ACK/NACK are read) ────────────────────────────
// 0xBA 0xCE, len (U2 LE), class, id, payload, checksum (U4). The text parsers
// above see these bytes too; checksums keep them from producing anything.
static uint8_t cbuf[16];
static int cpos = -1;
static uint16_t clen = 0;
static volatile int casicAck = 0;    // 1 ACK, -1 NACK, 0 none
static uint8_t casicAckCls = 0, casicAckId = 0;

static void casicFeed(uint8_t c) {
  if (cpos < 0) { if (c == 0xBA) { cbuf[0] = c; cpos = 1; } return; }
  if (cpos == 1 && c != 0xCE) { cpos = c == 0xBA ? 1 : -1; return; }
  if (cpos < (int)sizeof cbuf) cbuf[cpos] = c;
  cpos++;
  if (cpos == 4) { clen = cbuf[2] | (cbuf[3] << 8); if (clen > 8) { cpos = -1; return; } }
  if (cpos >= 6 && cpos == 6 + clen + 4) {
    uint8_t cls = cbuf[4], id = cbuf[5];
    if (cls == 0x05 && clen == 4) {
      uint32_t ck = ((uint32_t)cls << 24) + ((uint32_t)id << 16) + clen;
      uint32_t w = cbuf[6] | (cbuf[7] << 8) | (cbuf[8] << 16) | ((uint32_t)cbuf[9] << 24);
      uint32_t got = cbuf[10] | (cbuf[11] << 8) | (cbuf[12] << 16) | ((uint32_t)cbuf[13] << 24);
      if (ck + w == got) { casicAckCls = cbuf[6]; casicAckId = cbuf[7]; casicAck = id == 0x01 ? 1 : -1; }
    }
    cpos = -1;
  }
}

void gpsPoll() {
  while (GPS.available()) {
    int c = GPS.read();
    info.bytes++;
    casicFeed((uint8_t)c);
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

static void casicSend(uint8_t cls, uint8_t id, const uint8_t* p, uint16_t len) {
  uint8_t hdr[6] = {0xBA, 0xCE, (uint8_t)(len & 0xFF), (uint8_t)(len >> 8), cls, id};
  uint32_t ck = ((uint32_t)cls << 24) + ((uint32_t)id << 16) + len;
  for (uint16_t i = 0; i + 3 < len; i += 4)
    ck += p[i] | (p[i + 1] << 8) | (p[i + 2] << 16) | ((uint32_t)p[i + 3] << 24);
  uint8_t tail[4] = {(uint8_t)ck, (uint8_t)(ck >> 8), (uint8_t)(ck >> 16), (uint8_t)(ck >> 24)};
  GPS.write(hdr, 6);
  GPS.write(p, len);
  GPS.write(tail, 4);
  GPS.flush();
}

// A start-up hint: roughly where we are and what time it is (CASIC AID-INI).
// Without it a receiver that has lost its almanac/time searches the whole sky
// blind; with it, it knows which satellites should be up. No internet needed --
// the position is the last fix (saved to flash), the time is the ESP clock.
//
// The time is only sent when it can be trusted: the clock must have been set
// from GNSS since the last power loss, and the ESP's RC sleep clock drifts, so
// the accuracy claimed grows with time since that sync; past a minute of
// uncertainty it is left out rather than misleading the receiver. A wrong hint
// is worse than none.
bool gpsAid() {
  if (cfg.gpsTx < 0) { strlcpy(info.aid, "no TX line", sizeof info.aid); return false; }
  uint8_t p[56] = {0};
  uint8_t flags = 0;
  if (cfg.lastPosUnix) {
    double lat = cfg.lastLatE7 / 1e7, lon = cfg.lastLonE7 / 1e7, alt = cfg.lastAltM;
    memcpy(p + 0, &lat, 8);
    memcpy(p + 8, &lon, 8);
    memcpy(p + 16, &alt, 8);
    float pAcc = 5000;                 // we may have moved since; stay generous
    memcpy(p + 36, &pAcc, 4);
    flags |= 0x01 | 0x20;              // position valid, in LLA
  }
  time_t now = time(nullptr);
  float tAcc = -1;
  if (rtcClockSetUnix && now > 1600000000 && now >= (time_t)rtcClockSetUnix) {
    tAcc = 1.0f + 0.02f * (float)(now - rtcClockSetUnix);   // 2 % drift budget
    if (tAcc <= 60.0f) {
      // GPS time = UTC + 18 leap seconds (unchanged since 2017), from 1980-01-06.
      uint64_t gps = (uint64_t)now - 315964800ULL + 18;
      double tow = (double)(gps % 604800ULL);
      uint16_t wn = (uint16_t)(gps / 604800ULL);
      memcpy(p + 24, &tow, 8);
      memcpy(p + 40, &tAcc, 4);
      memcpy(p + 52, &wn, 2);
      flags |= 0x02;                   // time valid
    } else {
      tAcc = -1;
    }
  }
  if (!flags) { strlcpy(info.aid, "nothing to send", sizeof info.aid); return false; }
  p[55] = flags;
  for (int attempt = 0; attempt < 3; attempt++) {
    casicAck = 0;
    casicSend(0x0B, 0x01, p, 56);
    uint32_t t0 = millis();
    while (millis() - t0 < 700 && !(casicAck && casicAckCls == 0x0B && casicAckId == 0x01)) { gpsPoll(); delay(5); }
    if (casicAck && casicAckCls == 0x0B && casicAckId == 0x01) {
      snprintf(info.aid, sizeof info.aid, "%s%s%s", casicAck > 0 ? "ACK" : "NACK",
               (flags & 0x01) ? " pos" : "", (flags & 0x02) ? " time" : "");
      return casicAck > 0;
    }
  }
  snprintf(info.aid, sizeof info.aid, "no reply (pos=%d time=%d)", !!(flags & 1), !!(flags & 2));
  return false;
}

void gpsHardReset() {
  digitalWrite(PIN_GPS_RESET, LOW);
  delay(100);
  digitalWrite(PIN_GPS_RESET, HIGH);
  wokeAtMs = millis();
  haveFixSinceWake = false;
  pump(400);   // let it boot before talking to it
  gpsAid();
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
  gpsAid();
  return true;
}

const GpsFix& gpsLatest() { return fix; }
GpsInfo& gpsInfo() { return info; }
void gpsSetRawEcho(Stream* s) { rawEcho = s; }
