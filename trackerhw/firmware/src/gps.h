// NEO-6M driver: NMEA parsing, UBX power-mode control, auto-detect.
#pragma once
#include <Arduino.h>

struct GpsFix {
  bool     valid = false;
  uint32_t atMs = 0;        // millis() when this fix was parsed
  uint32_t unix = 0;        // GPS UTC time of the fix
  double   lat = 0, lon = 0;
  float    altM = NAN;
  float    accM = NAN;      // ~1-sigma horizontal, from GST (else HDOP-derived)
  bool     accFromGst = false;
  float    spdMs = NAN;
  float    crsDeg = NAN;
  uint8_t  sats = 0;        // used in the fix
  float    hdop = NAN;
  uint8_t  fixType = 0;     // 1 none, 2 = 2D, 3 = 3D (GSA)
};

struct GpsInfo {
  bool     detected = false;
  bool     configured = false;   // UBX power config ACKed
  bool     ledOff = false;       // timepulse (the module's blue LED) disabled
  int      rx = -1, tx = -1;
  uint32_t baud = 0;
  uint32_t bytes = 0, sentences = 0, badChecksum = 0;
  uint8_t  inView = 0;
  uint8_t  tracked = 0;          // satellites with SNR > 0
  uint8_t  bestSnr = 0;
  float    avgTop4Snr = 0;
  uint32_t lastSentenceMs = 0;
  uint32_t ttffMs = 0;           // time to first fix since power-up / wake
  bool     timeValid = false;
  bool     asleep = false;
  uint32_t ubxAcks = 0, ubxNaks = 0;
  char     version[32] = "";
};

bool gpsBegin();                 // detect (if needed) + configure
void gpsPoll();                  // feed UART bytes to parsers; call often
const GpsFix& gpsLatest();       // most recent parsed fix (check .valid/.atMs)
GpsInfo& gpsInfo();
bool gpsApplyMode(uint8_t mode, uint16_t intervalS);
void gpsBackup(uint32_t ms);     // low-power: backup mode for ms (0 = until woken)
void gpsWake();
void gpsSetRawEcho(Stream* s);   // mirror NMEA to a stream (console "gps raw")
bool gpsDetect(bool verbose);
