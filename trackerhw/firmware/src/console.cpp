// USB serial console: diagnostics and provisioning (115200 baud, line-based).
#include "console.h"
#include "config.h"
#include "settings.h"
#include "gps.h"
#include "power.h"
#include "storage.h"
#include "logger.h"
#include "net.h"
#include "ui.h"
#include <LittleFS.h>
#include "ble.h"

static String line;
static bool tracePoints = false;
static uint32_t lastRecorded = 0;

static void help(Print& out) {
  out.println(F(
      "Roamly tracker console\n"
      "  status                 one-shot status dump\n"
      "  gps raw on|off         mirror NMEA to this console\n"
      "  gps detect             re-run pin/baud auto-detect\n"
      "  gps mode 0|1|2         accurate / balanced / low power\n"
      "  gps reset hot|cold|hard | standby | wake | config | aid | duty reset\n"
      "  set <key> <value>      ble (secs, 0=off) handoff (tracker|phone) interval (0/adaptive) timeout minacc units clock tz bright flip autosync batt recording\n"
      "  wifi add \"ssid\" pass   save a network\n"
      "  wifi list | wifi del N\n"
      "  server <url>           Roamly server base URL\n"
      "  pair <code>            claim a pairing code (letters T/M/B)\n"
      "  unpair                 forget the API key\n"
      "  sync                   upload now\n"
      "  dump [n]               print stored points as CSV\n"
      "  trace on|off           print each point as it's stored\n"
      "  trace ble on|off       print advert and fix-window edges\n"
      "  erase                  delete ALL stored points\n"
      "  sleeptest <ms>         light-sleep once (USB will drop)\n"
      "  shot                   dump the screen (host converts to PNG)\n"
      "  btn up|sel|down|back   press a button\n"
      "  shot                   dump the screen (host converts to PNG)\n"
      "  btn up|sel|down|back   press a button\n"
      "  poweroff               power off (hold middle 3 s to turn on)\n"
      "  reboot"));
}

static void status(Print& out) {
  const GpsFix& f = gpsLatest();
  const GpsInfo& g = gpsInfo();
  const PowerInfo& p = powerInfo();
  const LoggerState& ls = loggerState();
  StorageStats s = storageStats();
  const SyncStatus& sy = syncStatus();
  time_t now = time(nullptr);
  out.printf("fw %s id %s boots %lu up %lus time %ld (%s)\n", FW_VERSION, hwId(), (unsigned long)bootCount(),
                (unsigned long)(millis() / 1000), (long)now, ctime(&now));
  char rs[120];
  resetSummary(rs, sizeof rs);
  out.printf("resets: %s\n", rs);
  out.printf("gps: det=%d cfg=%d saved=%d rx=%d tx=%d baud=%lu ver='%s' bytes=%lu sent=%lu bad=%lu asleep=%d view gps/bds/glo=%u/%u/%u\n",
                g.detected, g.configured, g.saved, g.rx, g.tx, (unsigned long)g.baud, g.version, (unsigned long)g.bytes,
                (unsigned long)g.sentences, (unsigned long)g.badChecksum, g.asleep, g.inViewGps, g.inViewBds, g.inViewGlo);
  out.printf("fix: valid=%d type=%u age=%lums %.7f,%.7f alt=%.1f acc=%.1f(%s) spd=%.2f crs=%.0f sats=%u/%u heard=%u hdop=%.2f snr best=%u top4=%.1f ttff=%lums\n",
                f.valid, f.fixType, f.atMs ? (unsigned long)(millis() - f.atMs) : 0UL, f.lat, f.lon, f.altM, f.accM,
                "hdop", f.spdMs, f.crsDeg, f.sats, g.inView, g.tracked, f.hdop, g.bestSnr, g.avgTop4Snr,
                (unsigned long)g.ttffMs);
  out.printf("ble: %s interval=%us bonds=%u pairing=%d handoff=%s phone_paused=%d lease=%lus\n", bleStateText(), cfg.bleIntervalS,
             bleBondCount(), blePairing(), cfg.handoff ? "phone" : "tracker", bleHandoffPaused(), (unsigned long)bleLeaseRemainingS());
  out.printf("gps duty=%.1f%% mode=%u lead=%lums aid='%s' lastpos=%.4f,%.4f@%lu\n", gpsDuty() * 100, cfg.gpsMode,
                (unsigned long)gpsWakeLeadMs(), g.aid, cfg.lastLatE7 / 1e7, cfg.lastLonE7 / 1e7, (unsigned long)cfg.lastPosUnix);
  out.printf("adaptive=%d motion=%s interval=%lus peeks=%lu\n", cfg.intervalS == 0, motionName(ls.motion),
                (unsigned long)loggerIntervalS(), (unsigned long)ls.peeks);
  out.printf("log: rec=%d windows=%lu stored=%lu missed=%lu filtered=%lu werr=%lu lastwin=%lums next in %ldms\n",
                cfg.recording, (unsigned long)ls.windows, (unsigned long)ls.recorded, (unsigned long)ls.missed,
                (unsigned long)ls.filtered, (unsigned long)ls.writeErrors, (unsigned long)ls.lastWindowMs,
                (long)(ls.nextDueMs - millis()));
  out.printf("pwr: gauge=%s batt=%d %.3fV %.1f%% gaugeRate=%.2f measured=%.2f state=%u ext=%d usb=%d left=%.2fh (%s)\n",
                p.gaugeName, p.batteryPresent, p.voltage, p.percent, p.gaugeRate, p.measuredRate, p.state, p.external,
                p.usbHost, p.hoursLeft, p.estimateMeasured ? "measured" : "model");
  out.printf("fs: pending=%lu quarantined=%lu corrupt=%lu segs=%lu free=%luK/%luK\n", (unsigned long)s.pending,
                (unsigned long)s.quarantined, (unsigned long)s.corrupt, (unsigned long)s.segments, (unsigned long)s.freeKb,
                (unsigned long)s.totalKb);
  out.printf("net: server='%s' paired=%d name='%s' wifi=%u sync running=%d msg='%s' fails=%u unpaired=%d tz='%s' off=%ld\n",
                cfg.server, isPaired(), cfg.deviceName, cfg.wifiCount, sy.running, sy.msg, sy.fails, sy.unpaired,
                cfg.tzPosix, (long)cfg.tzOffsetS);
}

bool consoleSetKey(const String& k, const String& v) {
  int n = v.toInt();
  struct { const char* key; int id; } map[] = {
      {"timeout", S_TIMEOUT}, {"interval", S_INTERVAL}, {"mode", S_GPSMODE}, {"minacc", S_MINACC},
      {"units", S_UNITS}, {"clock", S_CLOCK}, {"bright", S_BRIGHT}, {"flip", S_FLIP},
      {"autosync", S_AUTOSYNC}, {"batt", S_BATT}};
  if (k == "tz") {
    if (v == "auto") cfg.tzAuto = true;
    else { cfg.tzAuto = false; cfg.tzManualMin = n; }
    settingsSave();
    appSettingChanged(S_TZ);
    return true;
  }
  if (k == "recording") { cfg.recording = n; settingsSave(); appSettingChanged(S_RECORDING); return true; }
  if (k == "ble") { cfg.bleIntervalS = n <= 0 ? 0 : constrain(n, 15, 600); settingsSave(); return true; }
  if (k == "handoff") {
    cfg.handoff = (v == "phone" || n == 1) ? 1 : 0;
    settingsSave();
    return true;
  }
  for (auto& m : map) {
    if (k != m.key) continue;
    switch (m.id) {
      case S_TIMEOUT: cfg.screenTimeoutS = constrain(n, 2, 3600); break;
      case S_INTERVAL: cfg.intervalS = (v == "adaptive" || n == 0) ? 0 : constrain(n, 5, 3600); break;
      case S_GPSMODE: cfg.gpsMode = constrain(n, 0, 2); break;
      case S_MINACC: cfg.minAccM = constrain(n, 0, 1000); break;
      case S_UNITS: cfg.imperial = n; break;
      case S_CLOCK: cfg.clock24 = n; break;
      case S_BRIGHT: cfg.brightness = constrain(n, 1, 100); break;
      case S_FLIP: cfg.flip = n; break;
      case S_AUTOSYNC: cfg.autoSync = n; break;
      case S_BATT: cfg.battMah = constrain(n, 100, 20000); break;
    }
    settingsSave();
    appSettingChanged(m.id);
    return true;
  }
  return false;
}

void consoleExec(String cmd, Print& out) {
  cmd.trim();
  if (!cmd.length()) return;
  String arg;
  int sp = cmd.indexOf(' ');
  String head = sp < 0 ? cmd : cmd.substring(0, sp);
  if (sp >= 0) { arg = cmd.substring(sp + 1); arg.trim(); }

  if (head == "help" || head == "?") help(out);
  else if (head == "status") status(out);
  else if (head == "gps") {
    if (arg == "raw on") gpsSetRawEcho(&Serial);
    else if (arg == "raw off") gpsSetRawEcho(nullptr);
    else if (arg == "detect") { cfg.gpsRx = -1; out.println(gpsBegin() ? "ok" : "not found"); }
    else if (arg.startsWith("mode ")) consoleSetKey("mode", arg.substring(5));
    else if (arg == "reset hot" || arg == "reset cold") { gpsReset(arg == "reset cold"); out.println("restart sent"); }
    else if (arg == "reset hard") { gpsHardReset(); out.println("RESET pin pulsed"); }
    else if (arg == "aid") { out.println(gpsAid() ? "aid ACKed" : "aid not accepted"); out.println(gpsInfo().aid); }
    else if (arg == "duty reset") { gpsDutyReset(); out.println("duty counter reset"); }
    else if (arg == "standby") { gpsStandby(); out.println("standby (pin low)"); }
    else if (arg == "wake") { gpsWake(); out.println("awake (pin high)"); }
    else if (arg == "config") { cfg.gpsCfgSaved = 0; out.println(gpsApplyMode(cfg.gpsMode, cfg.intervalS) ? "configured" : "output didn't change"); }
    else out.println("gps raw on|off | detect | mode N");
  } else if (head == "set") {
    int s2 = arg.indexOf(' ');
    if (s2 < 0 || !consoleSetKey(arg.substring(0, s2), arg.substring(s2 + 1))) out.println("usage: set <key> <value>");
    else out.println("ok");
  } else if (head == "wifi") {
    if (arg == "list") {
      for (int i = 0; i < cfg.wifiCount; i++) out.printf("%d: %s\n", i, cfg.wifi[i].ssid);
    } else if (arg.startsWith("del ")) {
      out.println(removeWifi(arg.substring(4).toInt()) ? "ok" : "no such entry");
    } else if (arg.startsWith("add ")) {
      String rest = arg.substring(4);
      rest.trim();
      String ssid, pass;
      if (rest.startsWith("\"")) {
        int q = rest.indexOf('"', 1);
        ssid = rest.substring(1, q);
        pass = rest.substring(q + 1);
      } else {
        int s2 = rest.indexOf(' ');
        ssid = s2 < 0 ? rest : rest.substring(0, s2);
        pass = s2 < 0 ? "" : rest.substring(s2 + 1);
      }
      pass.trim();
      out.println(addWifi(ssid.c_str(), pass.c_str()) ? "saved" : "invalid");
    } else out.println("wifi add \"ssid\" pass | list | del N");
  } else if (head == "server") {
    if (arg.length() && arg.length() < sizeof(cfg.server)) {
      if (strcmp(arg.c_str(), cfg.server) != 0) cfg.apiKey[0] = 0;
      strlcpy(cfg.server, arg.c_str(), sizeof cfg.server);
      settingsSave();
    }
    out.printf("server = '%s'\n", cfg.server);
  } else if (head == "pair") {
    arg.toUpperCase();
    netStartPair(arg.c_str());
    while (pairState() == PAIR_RUNNING) delay(100);
    out.printf("pair: %s\n", pairMessage());
  } else if (head == "unpair") {
    cfg.apiKey[0] = 0;
    settingsSave();
    out.println("api key cleared");
  } else if (head == "ble") {
    if (arg == "pair") { bleEnterPairing(); out.println("pairing mode for 2 min (passkey shows on screen)"); }
    else if (arg == "forget") { bleForgetPhones(); out.println("all phones forgotten"); }
    else out.println("ble pair | ble forget");
  } else if (head == "sync") {
    if (!netStartSync(true)) { out.printf("can't: %s\n", syncStatus().msg); return; }
    while (syncStatus().running) { delay(200); }
    out.printf("sync: %s (ok=%d sent=%lu)\n", syncStatus().msg, syncStatus().lastOk, (unsigned long)syncStatus().sent);
  } else if (head == "dump") storageDump(Serial, arg.length() ? arg.toInt() : 50);
  else if (head == "trace") {
    if (arg.startsWith("ble")) bleTrace = arg.endsWith("on");
    else tracePoints = arg == "on";
  }
  else if (head == "erase") { storageEraseAll(); out.println("erased"); }
  else if (head == "sleeptest") {
    uint32_t ms = arg.toInt() ? arg.toInt() : 3000;
    out.printf("sleeping %lums\n", (unsigned long)ms);
    out.flush();
    uint32_t t0 = millis();
    WakeCause c = powerLightSleep(ms);
    out.printf("woke: cause=%d after %lums\n", c, (unsigned long)(millis() - t0));
  } else if (head == "shot") uiScreenshot(Serial);
  else if (head == "btn") { uiInject(arg.c_str()); out.println("ok"); }
  else if (head == "poweroff") appPowerOff();
  else if (head == "reboot") ESP.restart();
  else out.println("unknown command; try 'help'");
}

void consolePoll() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\n' || c == '\r') {
      if (line.length()) { consoleExec(line, Serial); line = ""; }
    } else if (line.length() < 200) line += c;
  }
  const LoggerState& ls = loggerState();
  if (tracePoints && ls.recorded != lastRecorded) {
    // (motion state is printed with each point below)
    lastRecorded = ls.recorded;
    const PointRec& r = ls.last;
    Serial.printf("[pt] seq=%lu t=%lu %.7f,%.7f acc=%.1f sats=%u hdop=%.1f spd=%.2f %s/%lus win=%lums\n", (unsigned long)r.seq,
                  (unsigned long)r.t, r.latE7 / 1e7, r.lonE7 / 1e7, r.accDm == 0xFFFF ? NAN : r.accDm / 10.0, r.sats,
                  r.hdopX10 / 10.0, r.spdCms == 0xFFFF ? NAN : r.spdCms / 100.0, motionName(ls.motion),
                  (unsigned long)loggerIntervalS(), (unsigned long)ls.lastWindowMs);
  }
}
