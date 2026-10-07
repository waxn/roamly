#include "gps.h"
#include "config.h"
#include "settings.h"
#include "util.h"
#include <TinyGPSPlus.h>
#include <sys/time.h>
#include <time.h>

static HardwareSerial& GPS = Serial1;
static TinyGPSPlus nmea;
// NEO-6 talks as "GP"; TinyGPSCustom matches the full sentence name.
static TinyGPSCustom gstTime(nmea, "GPGST", 1), gstLat(nmea, "GPGST", 6), gstLon(nmea, "GPGST", 7);
static TinyGPSCustom gsaMode(nmea, "GPGSA", 2);
static TinyGPSCustom gsvTotal(nmea, "GPGSV", 3), gsvMsg(nmea, "GPGSV", 2);
static TinyGPSCustom gsvPrn[4] = {{nmea, "GPGSV", 4}, {nmea, "GPGSV", 8}, {nmea, "GPGSV", 12}, {nmea, "GPGSV", 16}};
static TinyGPSCustom gsvSnr[4] = {{nmea, "GPGSV", 7}, {nmea, "GPGSV", 11}, {nmea, "GPGSV", 15}, {nmea, "GPGSV", 19}};

static GpsFix fix;
static GpsInfo info;
static Stream* rawEcho = nullptr;
static uint32_t wokeAtMs = 0;
static bool haveFixSinceWake = false;
static uint32_t lastPassed = 0;

// Per-PRN SNR, aged out after a few seconds so a satellite that set is dropped.
static uint8_t snr[100];
static uint32_t snrAt[100];

// ── UBX framing ─────────────────────────────────────────────────────────────
static uint8_t ubxBuf[128];
static int ubxPos = -1;   // -1 = not in a UBX frame
static uint16_t ubxLen = 0;
static volatile int lastAck = 0;   // 1 ack, -1 nak, 0 none
static uint8_t lastAckCls = 0, lastAckId = 0;
static uint8_t pm2[44];
static bool pm2Valid = false;
static uint8_t tp5[32];
static bool tp5Valid = false;

static void ubxChecksum(const uint8_t* d, size_t n, uint8_t& a, uint8_t& b) {
  a = b = 0;
  for (size_t i = 0; i < n; i++) { a += d[i]; b += a; }
}

static void ubxSend(uint8_t cls, uint8_t id, const uint8_t* payload, uint16_t len) {
  uint8_t hdr[6] = {0xB5, 0x62, cls, id, (uint8_t)(len & 0xFF), (uint8_t)(len >> 8)};
  // checksum covers cls, id, len, payload
  uint8_t a = 0, b = 0;
  for (int i = 2; i < 6; i++) { a += hdr[i]; b += a; }
  for (uint16_t i = 0; i < len; i++) { a += payload[i]; b += a; }
  GPS.write(hdr, 6);
  if (len) GPS.write(payload, len);
  GPS.write(a);
  GPS.write(b);
  GPS.flush();
}

static void ubxHandle(uint8_t cls, uint8_t id, const uint8_t* p, uint16_t len) {
  if (cls == 0x05 && len >= 2) {
    lastAckCls = p[0];
    lastAckId = p[1];
    lastAck = id == 0x01 ? 1 : -1;
    if (id == 0x01) info.ubxAcks++; else info.ubxNaks++;
  } else if (cls == 0x06 && id == 0x31 && len == 32) {
    memcpy(tp5, p, 32);
    tp5Valid = true;
  } else if (cls == 0x06 && id == 0x3B && len == 44) {
    memcpy(pm2, p, 44);
    pm2Valid = true;
  } else if (cls == 0x0A && id == 0x04 && len >= 40) {
    // MON-VER: swVersion[30], hwVersion[10]
    char sw[31];
    memcpy(sw, p, 30); sw[30] = 0;
    char hw[11];
    memcpy(hw, p + 30, 10); hw[10] = 0;
    snprintf(info.version, sizeof info.version, "%s/%s", sw, hw);
  }
}

static bool ubxFeed(uint8_t c) {
  if (ubxPos < 0) return false;
  if (ubxPos == 1 && c != 0x62) { ubxPos = -1; return false; }
  if (ubxPos < (int)sizeof ubxBuf) ubxBuf[ubxPos] = c;
  ubxPos++;
  if (ubxPos == 6) {
    ubxLen = ubxBuf[4] | (ubxBuf[5] << 8);
    if (ubxLen > sizeof ubxBuf - 8) { ubxPos = -1; return true; }
  }
  if (ubxPos >= 6 && ubxPos == 6 + ubxLen + 2) {
    uint8_t a, b;
    ubxChecksum(ubxBuf + 2, 4 + ubxLen, a, b);
    if (a == ubxBuf[6 + ubxLen] && b == ubxBuf[7 + ubxLen])
      ubxHandle(ubxBuf[2], ubxBuf[3], ubxBuf + 6, ubxLen);
    ubxPos = -1;
  }
  return true;
}

static bool waitAck(uint8_t cls, uint8_t id, uint32_t ms) {
  uint32_t t0 = millis();
  while (millis() - t0 < ms) {
    gpsPoll();
    if (lastAck && lastAckCls == cls && lastAckId == id) return lastAck > 0;
    delay(5);
  }
  return false;
}

static bool ubxCmd(uint8_t cls, uint8_t id, const uint8_t* p, uint16_t len, int tries = 3) {
  for (int i = 0; i < tries; i++) {
    lastAck = 0;
    ubxSend(cls, id, p, len);
    if (waitAck(cls, id, 600)) return true;
  }
  return false;
}

// ── NMEA side ───────────────────────────────────────────────────────────────
static GpsFix pend;
static uint32_t pendEpoch = UINT32_MAX;
static bool pendPublished = true;

static void publish() {
  if (!pend.accFromGst) {
    // No GST this epoch: a coarse, conventional stand-in (HDOP x UERE).
    pend.accM = isnan(pend.hdop) ? NAN : pend.hdop * 5.0f;
  }
  pend.atMs = millis();
  fix = pend;
  pendPublished = true;
  if (fix.valid && !haveFixSinceWake) {
    haveFixSinceWake = true;
    info.ttffMs = millis() - wokeAtMs;
  }
}

static void onSentence() {
  if (nmea.passedChecksum() != lastPassed) {
    lastPassed = nmea.passedChecksum();
    info.sentences = lastPassed;
    info.lastSentenceMs = millis();
  }

  if (gsvMsg.isUpdated()) {
    for (int i = 0; i < 4; i++) {
      int prn = atoi(gsvPrn[i].value());
      if (prn > 0 && prn < 100) {
        snr[prn] = (uint8_t)atoi(gsvSnr[i].value());
        snrAt[prn] = millis();
      }
    }
    info.inView = atoi(gsvTotal.value());
    uint8_t top[4] = {0, 0, 0, 0};
    uint8_t tracked = 0, best = 0;
    for (int p = 1; p < 100; p++) {
      if (!snrAt[p] || millis() - snrAt[p] > 5000 || !snr[p]) continue;
      tracked++;
      uint8_t s = snr[p];
      if (s > best) best = s;
      for (int k = 0; k < 4; k++) if (s > top[k]) { for (int j = 3; j > k; j--) top[j] = top[j - 1]; top[k] = s; break; }
    }
    info.tracked = tracked;
    info.bestSnr = best;
    int n = 0, sum = 0;
    for (int k = 0; k < 4; k++) if (top[k]) { sum += top[k]; n++; }
    info.avgTop4Snr = n ? (float)sum / n : 0;
  }

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

  // One fix per epoch. RMC and GGA both carry the position, so publishing on
  // every location update would count each epoch twice (and the early-stop
  // "best of three" would end after one and a half seconds). Build the epoch
  // up from whichever sentences carry its timestamp, and publish it when that
  // epoch's GST (accuracy) arrives — or when the next epoch starts, if the
  // module didn't send GST.
  if (nmea.location.isUpdated()) {
    uint32_t ep = nmea.time.value();
    if (ep != pendEpoch) {
      if (!pendPublished) publish();
      pend = GpsFix();
      pendEpoch = ep;
      pendPublished = false;
    }
    pend.valid = nmea.location.isValid() && nmea.satellites.value() >= 3;
    pend.lat = nmea.location.lat();
    pend.lon = nmea.location.lng();
    pend.unix = fix.unix;   // set by the time block above for this epoch
    if (nmea.altitude.isValid()) pend.altM = nmea.altitude.meters();
    if (nmea.speed.isValid()) pend.spdMs = nmea.speed.mps();
    if (nmea.course.isValid()) pend.crsDeg = nmea.course.deg();
    pend.sats = nmea.satellites.value();
    if (nmea.hdop.isValid()) pend.hdop = nmea.hdop.hdop();
    uint8_t ft = atoi(gsaMode.value());
    pend.fixType = ft ? ft : (pend.valid ? 2 : 1);
  }
  if ((gstLat.isUpdated() || gstLon.isUpdated()) && !pendPublished) {
    uint32_t gt = (uint32_t)lroundf(atof(gstTime.value()) * 100.0f);
    float a = atof(gstLat.value()), b = atof(gstLon.value());
    if (gt == pendEpoch && (a > 0 || b > 0)) {
      pend.accM = sqrtf(a * a + b * b);
      pend.accFromGst = true;
      publish();
    }
  }
}

void gpsPoll() {
  while (GPS.available()) {
    int c = GPS.read();
    info.bytes++;
    if (c == 0xB5 && ubxPos < 0) { ubxPos = 0; ubxBuf[0] = 0xB5; ubxPos = 1; continue; }
    if (ubxFeed((uint8_t)c)) continue;
    if (rawEcho) rawEcho->write((uint8_t)c);
    uint32_t bad = nmea.failedChecksum();
    bool committed = nmea.encode((char)c);
    if (nmea.failedChecksum() != bad) info.badChecksum++;
    // encode() only returns true for the sentences TinyGPSPlus itself decodes
    // (GGA/RMC); GSV/GSA/GST arrive as custom fields and need checking at
    // every line end, or satellites-in-view never updates.
    if (committed || c == '\n') onSentence();
  }
}

// ── Detection ───────────────────────────────────────────────────────────────
// Listen on a pin/baud for a checksum-valid NMEA sentence.
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

// With RX found, find which pin reaches the module's RX by asking for MON-VER.
static int findTx(int rx, uint32_t baud) {
  const int cands[] = {39, 38, 17, 18, 16, 15, 14, 8, 5, 6, 9, 10, 11, 12, 13, 37};
  for (int tx : cands) {
    if (tx == rx) continue;
    GPS.end();
    GPS.begin(baud, SERIAL_8N1, rx, tx);
    info.version[0] = 0;
    for (int k = 0; k < 2 && !info.version[0]; k++) {
      ubxSend(0x0A, 0x04, nullptr, 0);
      uint32_t t0 = millis();
      while (millis() - t0 < 700 && !info.version[0]) { gpsPoll(); delay(5); }
    }
    if (info.version[0]) return tx;
  }
  return -1;
}

bool gpsDetect(bool verbose) {
  const int pins[] = {38, 39, 17, 18, 16, 15, 14, 8, 5, 6, 9, 10, 11, 12, 13, 37};
  const uint32_t bauds[] = {9600, 38400, 115200, 4800, 57600};
  for (uint32_t baud : bauds) {
    for (int rx : pins) {
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

static void enableNmea(uint8_t id, uint8_t rate) {
  uint8_t p[3] = {0xF0, id, rate};   // CFG-MSG, rate on the current port
  ubxCmd(0x06, 0x01, p, 3);
}

bool gpsApplyMode(uint8_t mode, uint16_t intervalS) {
  if (cfg.gpsTx < 0) return false;   // can't talk to it: runs on module defaults
  // Exactly the sentences we parse: RMC/GGA (position, time), GSA (fix
  // type), GSV (satellites in view), GST (accuracy); GLL and VTG off. Don't
  // trust the module's stored defaults — the first NEO-6M tested shipped
  // with GSA and GSV disabled.
  enableNmea(0x00, 1);   // GGA
  enableNmea(0x01, 0);   // GLL
  enableNmea(0x02, 1);   // GSA
  enableNmea(0x03, 1);   // GSV
  enableNmea(0x04, 1);   // RMC
  enableNmea(0x05, 0);   // VTG
  enableNmea(0x07, 1);   // GST
  // Power save mode works off CFG-PM2; read the module's own copy and patch
  // only the periods rather than trusting a hard-coded blob.
  pm2Valid = false;
  for (int i = 0; i < 3 && !pm2Valid; i++) {
    ubxSend(0x06, 0x3B, nullptr, 0);
    uint32_t t0 = millis();
    while (millis() - t0 < 600 && !pm2Valid) { gpsPoll(); delay(5); }
  }
  bool ok = true;
  if (mode == GPS_BALANCED && pm2Valid) {
    // u-blox 6: an update period under 10 s selects *cyclic tracking* — the
    // receiver keeps tracking at 1 Hz but powers down its RF/baseband between
    // epochs. ~11 mA instead of ~40 mA, same 1 Hz output.
    uint32_t upd = 1000, search = 10000;
    memcpy(pm2 + 8, &upd, 4);
    memcpy(pm2 + 12, &search, 4);
    ok &= ubxCmd(0x06, 0x3B, pm2, 44);
  }
  // The GY-NEO6MV2's blue LED hangs off the TIMEPULSE pin, so it is a 1 Hz
  // "I have a fix" blinker that never turns off on its own. Turn the time
  // pulse off (CFG-TP5 flags bit0 = active), patching the module's own copy.
  tp5Valid = false;
  uint8_t tpIdx = 0;
  for (int i = 0; i < 3 && !tp5Valid; i++) {
    ubxSend(0x06, 0x31, &tpIdx, 1);
    uint32_t t0 = millis();
    while (millis() - t0 < 600 && !tp5Valid) { gpsPoll(); delay(5); }
  }
  if (tp5Valid) {
    tp5[28] &= ~0x01;                        // flags: active = 0
    info.ledOff = ubxCmd(0x06, 0x31, tp5, 32);
  } else {
    // Older protocol: CFG-TP with status 0 = time pulse off.
    uint8_t tp[20] = {0x40, 0x42, 0x0F, 0x00, 0x00, 0x00, 0x00, 0x00};   // interval 1 s, length 0
    info.ledOff = ubxCmd(0x06, 0x07, tp, 20);
  }

  // CFG-RXM: lpMode 0 = continuous (max performance), 1 = power save.
  // Low-power mode is driven from our side with RXM-PMREQ, so the receiver
  // itself runs continuous while it is on.
  uint8_t rxm[2] = {0x08, (uint8_t)(mode == GPS_BALANCED ? 1 : 0)};
  ok &= ubxCmd(0x06, 0x11, rxm, 2);
  info.configured = ok;
  (void)intervalS;
  return ok;
}

void gpsBackup(uint32_t ms) {
  if (cfg.gpsTx < 0) return;
  // RXM-PMREQ: duration (ms, 0 = until woken), flags bit1 = enter backup.
  uint8_t p[8] = {0};
  memcpy(p, &ms, 4);
  p[4] = 0x02;
  ubxSend(0x02, 0x41, p, 8);
  info.asleep = true;
}

void gpsWake() {
  if (!info.asleep) return;
  // Any UART activity wakes a u-blox 6 from an indefinite backup; a timed
  // backup wakes by itself. Both resume from battery-backed ephemeris.
  for (int i = 0; i < 8; i++) GPS.write(0xFF);
  GPS.flush();
  info.asleep = false;
  wokeAtMs = millis();
  haveFixSinceWake = false;
}

bool gpsBegin() {
  wokeAtMs = millis();
  bool found = false;
  if (cfg.gpsRx >= 0 && cfg.gpsBaud) {
    found = listenFor(cfg.gpsRx, cfg.gpsBaud, 2500);
  }
  if (!found) found = gpsDetect(true);
  info.detected = found;
  if (!found) return false;
  GPS.end();
  GPS.setRxBufferSize(1024);
  GPS.begin(cfg.gpsBaud, SERIAL_8N1, cfg.gpsRx, cfg.gpsTx);
  info.rx = cfg.gpsRx;
  info.tx = cfg.gpsTx;
  info.baud = cfg.gpsBaud;
  if (cfg.gpsTx >= 0 && !info.version[0]) {
    ubxSend(0x0A, 0x04, nullptr, 0);
    uint32_t t0 = millis();
    while (millis() - t0 < 800 && !info.version[0]) { gpsPoll(); delay(5); }
  }
  gpsApplyMode(cfg.gpsMode, cfg.intervalS);
  return true;
}

const GpsFix& gpsLatest() { return fix; }
GpsInfo& gpsInfo() { return info; }
void gpsSetRawEcho(Stream* s) { rawEcho = s; }
