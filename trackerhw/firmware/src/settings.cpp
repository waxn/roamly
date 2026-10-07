#include "settings.h"
#include <Preferences.h>
#include <esp_system.h>

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

// ── Reset history ───────────────────────────────────────────────────────────
// A tracker in a pocket has no console attached, so a crash would otherwise be
// invisible: count every kind of reset and remember the last one.
static const char* resetName(esp_reset_reason_t r) {
  switch (r) {
    case ESP_RST_POWERON: return "power-on";
    case ESP_RST_SW: return "restart";
    case ESP_RST_PANIC: return "crash";
    case ESP_RST_INT_WDT: return "int-watchdog";
    case ESP_RST_TASK_WDT: return "task-watchdog";
    case ESP_RST_WDT: return "watchdog";
    case ESP_RST_BROWNOUT: return "brownout";
    case ESP_RST_DEEPSLEEP: return "deep-sleep";
    case ESP_RST_USB: return "usb";
    case ESP_RST_JTAG: return "jtag";
    default: return "other";
  }
}
static char lastReset[24] = "";
static uint32_t badResets = 0;

void recordReset() {
  esp_reset_reason_t r = esp_reset_reason();
  strlcpy(lastReset, resetName(r), sizeof lastReset);
  bool bad = r == ESP_RST_PANIC || r == ESP_RST_INT_WDT || r == ESP_RST_TASK_WDT ||
             r == ESP_RST_WDT || r == ESP_RST_BROWNOUT;
  badResets = prefs.getUInt("rst_bad", 0);
  if (bad) {
    badResets++;
    prefs.putUInt("rst_bad", badResets);
    String key = String("rst_") + (int)r;
    prefs.putUInt(key.c_str(), prefs.getUInt(key.c_str(), 0) + 1);
  }
}

const char* lastResetReason() { return lastReset; }
uint32_t crashCount() { return badResets; }

void resetSummary(char* out, size_t n) {
  snprintf(out, n, "last=%s crash=%lu taskwdt=%lu intwdt=%lu wdt=%lu brownout=%lu", lastReset,
           (unsigned long)prefs.getUInt("rst_4", 0), (unsigned long)prefs.getUInt("rst_6", 0),
           (unsigned long)prefs.getUInt("rst_5", 0), (unsigned long)prefs.getUInt("rst_7", 0),
           (unsigned long)prefs.getUInt("rst_9", 0));
}
