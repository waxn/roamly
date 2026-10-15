// Seeed L76K GNSS module for XIAO (Quectel L76K, CASIC protocol): NMEA
// parsing, $PCAS configuration, STANDBY/RESET pins, auto-detect.
#pragma once
#include <Arduino.h>

struct GpsFix {
  bool     valid = false;
  uint32_t atMs = 0;        // millis() when this fix was parsed
  uint32_t unix = 0;        // GNSS UTC time of the fix
  double   lat = 0, lon = 0;
  float    altM = NAN;
  float    accM = NAN;      // estimated horizontal accuracy: HDOP x UERE (the L76K sends no GST)
  float    spdMs = NAN;
  float    crsDeg = NAN;
  uint8_t  sats = 0;        // used in the fix
  float    hdop = NAN;
  uint8_t  fixType = 0;     // 1 none, 2 = 2D, 3 = 3D (GSA)
};

struct GpsInfo {
  bool     detected = false;
  bool     configured = false;   // the module's output changed to what we asked for
  bool     saved = false;        // configuration saved to the module's flash ($PCAS00)
  int      rx = -1, tx = -1;
  uint32_t baud = 0;
  uint32_t bytes = 0, sentences = 0, badChecksum = 0;
  uint8_t  inView = 0;           // all constellations together
  uint8_t  inViewGps = 0, inViewBds = 0, inViewGlo = 0;
  uint8_t  tracked = 0;          // satellites with SNR > 0
  uint8_t  bestSnr = 0;
  float    avgTop4Snr = 0;
  uint32_t lastSentenceMs = 0;
  uint32_t ttffMs = 0;           // time to first fix since power-up / wake
  bool     timeValid = false;
  bool     asleep = false;       // STANDBY pin held low
  char     version[40] = "";
};

bool gpsBegin();                 // wake, detect (if needed), configure
void gpsPoll();                  // feed UART bytes to the parsers; call often
const GpsFix& gpsLatest();       // most recent fix (check .valid/.atMs)
GpsInfo& gpsInfo();
bool gpsApplyMode(uint8_t mode, uint16_t intervalS);
void gpsStandby();               // STANDBY low: ~0.36 mA, keeps time + ephemeris
void gpsWake();                  // STANDBY high
uint32_t gpsWakeLeadMs();        // how long before a fix window to wake it (0 = never sleep it)
void gpsReset(bool cold);        // $PCAS10 hot / cold start
void gpsHardReset();             // pulse the RESET pin
void gpsSetRawEcho(Stream* s);   // mirror NMEA to a stream (console "gps raw")
bool gpsDetect(bool verbose);
