// Persistent settings (NVS via Preferences).
#pragma once
#include <Arduino.h>

enum GpsMode : uint8_t { GPS_ACCURATE = 0, GPS_BALANCED = 1, GPS_LOWPOWER = 2 };

static constexpr int MAX_WIFI = 5;

struct WifiCred {
  char ssid[33];
  char pass[65];
};

// Saved to NVS as one blob. ONLY APPEND new fields at the end: settingsLoad()
// reads an older, shorter record as a prefix of this layout.
struct Settings {
  // user-facing (menu)
  uint16_t screenTimeoutS = 5;
  uint16_t intervalS      = 0;      // 0 = adaptive (60/30/10 s by speed)
  uint8_t  gpsMode        = GPS_BALANCED;
  uint16_t minAccM        = 0;      // 0 = keep every fix
  bool     imperial       = false;
  bool     clock24        = true;
  bool     tzAuto         = true;
  int16_t  tzManualMin    = 0;      // used when !tzAuto, minutes east of UTC
  uint8_t  brightness     = 100;    // %
  bool     flip           = false;
  bool     autoSync       = true;
  uint16_t battMah        = 1500;
  bool     recording      = true;

  // provisioning
  char     server[128]    = "";
  char     apiKey[80]     = "";
  char     deviceName[64] = "";
  WifiCred wifi[MAX_WIFI] = {};
  uint8_t  wifiCount      = 0;

  // learned
  char     tzPosix[64]    = "UTC0";
  int32_t  tzOffsetS      = 0;
  int8_t   gpsRx          = -1;     // -1 = not yet detected
  int8_t   gpsTx          = -1;
  uint32_t gpsBaud        = 0;
  uint8_t  gpsLed         = 0;      // TIMEPULSE config that keeps the module LED dark
};

extern Settings cfg;

void settingsLoad();
void settingsSave();
bool isPaired();
bool addWifi(const char* ssid, const char* pass);
bool removeWifi(int idx);

// Monotonic point sequence numbers. Persisted in steps so NVS isn't written on
// every point; a reboot skips ahead by a step, leaving a harmless gap.
uint32_t nextSeq();
uint32_t bootCount();

void recordReset();                 // call once at boot, after settingsLoad()
const char* lastResetReason();
uint32_t crashCount();              // crashes + watchdogs + brownouts, ever
void resetSummary(char* out, size_t n);
