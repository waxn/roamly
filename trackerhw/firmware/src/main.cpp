// Roamly hardware tracker — main loop.
//
// The loop is cooperative: GPS bytes, buttons, the gauge, the console and the
// setup hotspot are all polled here, the logger collects a point each interval,
// and between points — screen off, on battery, nothing pending — the ESP32
// light-sleeps until shortly before the next window. Uploads run in their own
// task (net.cpp) so a slow server never delays a point.
#include <Arduino.h>
#include "config.h"
#include "settings.h"
#include "storage.h"
#include "gps.h"
#include "power.h"
#include "logger.h"
#include "net.h"
#include "ui.h"
#include "util.h"
#include "console.h"

void applyTimezone() {
  char tz[64];
  if (cfg.tzAuto && cfg.tzPosix[0]) {
    setenv("TZ", cfg.tzPosix, 1);
    tzset();
    // The server also sends the zone's current offset. If newlib couldn't make
    // sense of the POSIX string (exotic <+05>-5 style names), the computed
    // offset won't match, and a fixed offset is better than UTC.
    time_t now = time(nullptr);
    if (now > 1600000000 && labs(localOffsetS(now) - cfg.tzOffsetS) <= 60) return;
  }
  long m = cfg.tzAuto ? cfg.tzOffsetS / 60 : cfg.tzManualMin;
  // POSIX offsets are west-positive: UTC+2 is "LOC-2:00".
  snprintf(tz, sizeof tz, "LOC%s%ld:%02ld", m > 0 ? "-" : "", labs(m) / 60, labs(m) % 60);
  setenv("TZ", tz, 1);
  tzset();
}

void appSettingChanged(int id) {
  switch (id) {
    case S_INTERVAL:
    case S_GPSMODE:
      gpsWake();
      gpsApplyMode(cfg.gpsMode, cfg.intervalS);
      loggerReschedule();
      break;
    case S_RECORDING:
      if (cfg.recording) { gpsWake(); loggerReschedule(); }
      break;
    case S_FLIP:
    case S_BRIGHT:
      uiApplyDisplaySettings();
      break;
    case S_TZ:
      applyTimezone();
      break;
    case S_BATT:
      powerPoll(true);
      break;
  }
}

static void syncScheduler() {
  static bool wasExt = false;
  static uint32_t extSince = 0;
  const PowerInfo& p = powerInfo();
  SyncStatus& ss = syncStatus();
  bool ext = p.external;
  if (ext && !wasExt) extSince = millis();
  wasExt = ext;
  if (!cfg.autoSync || !ext || !isPaired() || ss.running || netPortalActive()) return;
  if (millis() - extSince < 15000) return;   // let the charge state settle first
  uint32_t wait;
  if (!ss.lastAttemptMs || (int32_t)(ss.lastAttemptMs - extSince) < 0) wait = 0;   // new charging session
  else if (ss.unpaired) wait = 3600000;
  else if (ss.lastOk) wait = SYNC_REPEAT_MS;
  else wait = SYNC_RETRY_MS[min<int>(ss.fails ? ss.fails - 1 : 0, 2)];
  if (ss.lastAttemptMs && millis() - ss.lastAttemptMs < wait) return;
  netStartSync(false);
}

static void maybeSleep() {
  const PowerInfo& p = powerInfo();
  if (uiScreenOn() || uiWantsAwake() || syncStatus().running || pairState() == PAIR_RUNNING) return;
  // On USB/charger there's no battery to save, and a USB host would lose the
  // console across every sleep.
  if (p.usbHost || p.external) return;
  const LoggerState& ls = loggerState();
  if (ls.collecting) return;
  int32_t ms = (int32_t)(ls.nextDueMs - millis()) - 1200;   // wake early to flush stale UART bytes
  if (!cfg.recording) ms = 60000;
  if (ms < 400) return;
  Serial.flush();
  if (powerLightSleep((uint32_t)ms) == WAKE_BUTTON) uiWakeFromButton();
  powerPoll(true);
}

static void gpsWatchdog() {
  // A detected module that goes silent while it should be talking (loose
  // wire, brown-out) gets re-initialised rather than leaving the logger
  // waiting forever.
  static uint32_t lastCheck = 0;
  if (millis() - lastCheck < 30000) return;
  lastCheck = millis();
  GpsInfo& g = gpsInfo();
  if (!cfg.recording || g.asleep) return;
  if (!g.detected || millis() - g.lastSentenceMs > 60000) {
    Serial.println("[gps] silent; re-initialising");
    g.detected = gpsBegin();
  }
}

void setup() {
  Serial.begin(115200);
  setCpuFrequencyMhz(80);   // plenty for this; Wi-Fi needs >= 80
  settingsLoad();
  recordReset();
  powerBegin();
  uiBegin();
  uiBootMessage("Starting", "fw " FW_VERSION);
  if (!storageBegin()) uiBootMessage("Storage error!", "LittleFS mount failed");
  netBegin();
  applyTimezone();
  uiBootMessage("Looking for GPS...", cfg.gpsRx >= 0 ? "" : "first boot: auto-detecting pins");
  bool gpsOk = gpsBegin();
  if (!gpsOk) uiBootMessage("GPS not found", "check wiring; see trackerhw/README");
  Serial.printf("[boot] reset=%s fw %s id %s gps=%d rx=%d tx=%d baud=%lu ver=%s\n", lastResetReason(), FW_VERSION, hwId(), gpsOk,
                gpsInfo().rx, gpsInfo().tx, (unsigned long)gpsInfo().baud, gpsInfo().version);
  loggerReschedule();
  uiWake();
}

void loop() {
  gpsPoll();
  powerPoll();
  consolePoll();
  netPortalPoll();
  loggerTick();
  gpsPoll();
  uiPoll();
  syncScheduler();
  gpsWatchdog();
  maybeSleep();
  delay(5);
}
