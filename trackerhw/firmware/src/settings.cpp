#include "settings.h"
#include <Preferences.h>

Settings cfg;
static Preferences prefs;
static uint32_t seqNext = 0, seqPersisted = 0, boots = 0;
static constexpr uint32_t SEQ_STEP = 256;

void settingsLoad() {
  prefs.begin("roamly", false);
  if (prefs.isKey("cfg")) {
    // A struct blob, versioned by size: a firmware with a different layout
    // starts from defaults but keeps provisioning, which is read separately.
    if (prefs.getBytesLength("cfg") == sizeof(Settings)) {
      prefs.getBytes("cfg", &cfg, sizeof(Settings));
    } else {
      prefs.getString("server", cfg.server, sizeof(cfg.server));
      prefs.getString("apikey", cfg.apiKey, sizeof(cfg.apiKey));
    }
  }
  seqPersisted = prefs.getUInt("seq", 1);
  seqNext = seqPersisted;
  seqPersisted += SEQ_STEP;
  prefs.putUInt("seq", seqPersisted);
  boots = prefs.getUInt("boots", 0) + 1;
  prefs.putUInt("boots", boots);
}

void settingsSave() {
  prefs.putBytes("cfg", &cfg, sizeof(Settings));
  // Duplicated outside the blob so a layout change never loses the pairing.
  prefs.putString("server", cfg.server);
  prefs.putString("apikey", cfg.apiKey);
}

bool isPaired() { return cfg.apiKey[0] && cfg.server[0]; }

bool addWifi(const char* ssid, const char* pass) {
  if (!ssid || !*ssid || strlen(ssid) > 32 || strlen(pass) > 64) return false;
  for (int i = 0; i < cfg.wifiCount; i++) {
    if (strcmp(cfg.wifi[i].ssid, ssid) == 0) {
      strlcpy(cfg.wifi[i].pass, pass, sizeof(cfg.wifi[i].pass));
      settingsSave();
      return true;
    }
  }
  if (cfg.wifiCount >= MAX_WIFI) {  // drop the oldest
    memmove(&cfg.wifi[0], &cfg.wifi[1], sizeof(WifiCred) * (MAX_WIFI - 1));
    cfg.wifiCount = MAX_WIFI - 1;
  }
  strlcpy(cfg.wifi[cfg.wifiCount].ssid, ssid, sizeof(cfg.wifi[0].ssid));
  strlcpy(cfg.wifi[cfg.wifiCount].pass, pass, sizeof(cfg.wifi[0].pass));
  cfg.wifiCount++;
  settingsSave();
  return true;
}

bool removeWifi(int idx) {
  if (idx < 0 || idx >= cfg.wifiCount) return false;
  memmove(&cfg.wifi[idx], &cfg.wifi[idx + 1], sizeof(WifiCred) * (cfg.wifiCount - idx - 1));
  cfg.wifiCount--;
  memset(&cfg.wifi[cfg.wifiCount], 0, sizeof(WifiCred));
  settingsSave();
  return true;
}

uint32_t nextSeq() {
  uint32_t s = seqNext++;
  if (seqNext >= seqPersisted) {
    seqPersisted = seqNext + SEQ_STEP;
    prefs.putUInt("seq", seqPersisted);
  }
  return s;
}

uint32_t bootCount() { return boots; }
