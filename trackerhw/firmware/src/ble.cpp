#include "ble.h"
#include "config.h"
#include "settings.h"
#include "gps.h"
#include "logger.h"
#include "power.h"
#include "storage.h"
#include "net.h"
#include "console.h"
#include "ui.h"
#include <NimBLEDevice.h>
#include <ArduinoJson.h>
#include <sys/time.h>

// ── Scheduling: GPS fixes always win ────────────────────────────────────────
// The radio is woken in short windows rather than left on, because continuous
// advertising roughly doubles the tracker's drain. Each window is placed
// where it can't disturb a fix: never while the logger is collecting, and in
// the standby modes only while the GPS is asleep -- a window starts right after
// a fix has put the receiver into standby and must end before its next
// warm-up. If a fix comes due mid-window, advertising stops on the spot (an
// open connection is allowed to finish). On external power the window is
// unbounded, but still steps aside for every fix.
// 6.5 s: longer than one Android low-power scan cycle (~0.5 s every ~5.1 s),
// so a phone scanning in the background always catches at least one.
static constexpr uint32_t WINDOW_MS = 6500;         // one advertising window
static constexpr uint32_t WINDOW_MARGIN_MS = 1500;  // clearance before the GPS wakes
static constexpr uint32_t PAIRING_MS = 120000;
static constexpr uint32_t CONNECTED_MAX_MS = 10UL * 60 * 1000;   // safety cap on a held connection
static constexpr uint32_t NO_SLEEP_FALLBACK_MS = 5UL * 60 * 1000;

static bool stackOn = false, advertising = false;
static uint32_t windowEndMs = 0, nextWindowMs = 0, connectedSinceMs = 0;
static uint8_t advSeq = 0;
static NimBLEServer* srv = nullptr;
static NimBLECharacteristic *chStatus = nullptr, *chSettings = nullptr, *chCommand = nullptr, *chConsole = nullptr;
static volatile int conns = 0;
static volatile bool pairing = false;
static uint32_t pairingUntilMs = 0;
static volatile uint32_t passkey = 0;
static uint32_t leaseUntilMs = 0;
static uint32_t gpsAwakeSinceMs = 0;
static uint32_t lastStatusMs = 0;
static uint8_t bonds = 0;

// Writes arrive on NimBLE's task; everything they trigger runs on the main
// loop, which owns the GPS, storage and settings. One slot per characteristic.
static portMUX_TYPE qMux = portMUX_INITIALIZER_UNLOCKED;
static String qCommand, qSettings, qConsole;
static volatile bool hasCommand = false, hasSettings = false, hasConsole = false;

// ── Status / settings JSON ──────────────────────────────────────────────────
static String statusJson() {
  JsonDocument d;
  const PowerInfo& p = powerInfo();
  const GpsFix& f = gpsLatest();
  const GpsInfo& g = gpsInfo();
  const LoggerState& ls = loggerState();
  StorageStats st = storageStats();
  const SyncStatus& sy = syncStatus();
  d["fw"] = FW_VERSION;
  d["id"] = hwId();
  d["name"] = cfg.deviceName;
  d["up"] = millis() / 1000;
  d["time"] = (uint32_t)time(nullptr);
  JsonObject b = d["batt"].to<JsonObject>();
  if (p.batteryPresent) { b["pct"] = roundf(p.percent); b["v"] = roundf(p.voltage * 100) / 100; }
  b["state"] = p.state == PWR_CHARGING ? "charging" : p.state == PWR_FULL ? "full" : p.state == PWR_BATTERY ? "battery" : "unknown";
  if (!isnan(p.hoursLeft)) b["hours"] = roundf(p.hoursLeft * 10) / 10;
  b["measured"] = p.estimateMeasured;
  JsonObject r = d["rec"].to<JsonObject>();
  r["user"] = (bool)cfg.recording;
  r["phone_paused"] = bleHandoffPaused();
  r["handoff"] = cfg.handoff ? "phone" : "tracker";
  r["lease_s"] = bleLeaseRemainingS();
  r["interval"] = loggerIntervalS();
  r["adaptive"] = cfg.intervalS == 0;
  r["motion"] = motionName(ls.motion);
  r["recorded"] = ls.recorded;
  r["missed"] = ls.missed;
  if (ls.haveLast) r["last_age"] = (millis() - ls.lastAtMs) / 1000;
  JsonObject gj = d["gps"].to<JsonObject>();
  gj["ok"] = g.detected;
  gj["fix"] = f.valid ? (f.fixType >= 3 ? 3 : 2) : 0;
  gj["sats"] = f.sats;
  gj["view"] = g.inView;
  gj["gps"] = g.inViewGps; gj["bds"] = g.inViewBds; gj["glo"] = g.inViewGlo;
  if (!isnan(f.hdop)) gj["hdop"] = roundf(f.hdop * 100) / 100;
  if (!isnan(f.accM)) gj["acc"] = roundf(f.accM * 10) / 10;
  if (f.valid) { gj["lat"] = f.lat; gj["lon"] = f.lon; }
  if (!isnan(f.altM)) gj["alt"] = roundf(f.altM);
  if (!isnan(f.spdMs)) gj["spd"] = roundf(f.spdMs * 100) / 100;
  if (f.atMs) gj["age"] = (millis() - f.atMs) / 1000;
  gj["snr"] = g.bestSnr;
  gj["asleep"] = g.asleep;
  gj["duty"] = roundf(gpsDuty() * 1000) / 10;
  gj["mode"] = cfg.gpsMode;
  gj["ver"] = g.version;
  gj["aid"] = g.aid;
  JsonObject sj = d["store"].to<JsonObject>();
  sj["pending"] = st.pending;
  sj["held"] = st.quarantined;
  sj["corrupt"] = st.corrupt;
  sj["free_kb"] = st.freeKb;
  JsonObject yj = d["sync"].to<JsonObject>();
  yj["msg"] = sy.msg;
  yj["running"] = (bool)sy.running;
  yj["last_ok"] = sy.lastOkUnix;
  yj["paired"] = isPaired();
  yj["unpaired"] = sy.unpaired;
  yj["wifi"] = cfg.wifiCount;
  yj["auto"] = (bool)cfg.autoSync;
  d["boots"] = bootCount();
  d["crashes"] = crashCount();
  d["last_reset"] = lastResetReason();
  String out;
  serializeJson(d, out);
  return out;
}

static String settingsJson() {
  JsonDocument d;
  d["interval"] = cfg.intervalS;
  d["mode"] = cfg.gpsMode;
  d["timeout"] = cfg.screenTimeoutS;
  d["minacc"] = cfg.minAccM;
  d["units"] = cfg.imperial ? 1 : 0;
  d["clock"] = cfg.clock24 ? 1 : 0;
  d["tz"] = cfg.tzAuto ? String("auto") : String(cfg.tzManualMin);
  d["bright"] = cfg.brightness;
  d["flip"] = cfg.flip ? 1 : 0;
  d["autosync"] = cfg.autoSync ? 1 : 0;
  d["batt"] = cfg.battMah;
  d["recording"] = cfg.recording ? 1 : 0;
  d["ble"] = cfg.bleIntervalS;
  d["handoff"] = cfg.handoff ? "phone" : "tracker";
  String out;
  serializeJson(d, out);
  return out;
}

// ── NimBLE callbacks (run on NimBLE's task: keep them tiny) ─────────────────
class ServerCB : public NimBLEServerCallbacks {
  void onConnect(NimBLEServer*, NimBLEConnInfo&) override {
    conns = conns + 1;
    connectedSinceMs = millis();
  }
  void onDisconnect(NimBLEServer*, NimBLEConnInfo&, int) override {
    conns = conns > 0 ? conns - 1 : 0;
  }
  uint32_t onPassKeyDisplay() override {
    // Only shown on screen in pairing mode. Outside it the number is random
    // and shown nowhere, so a stranger nearby can't complete a pairing.
    uint32_t pk = 100000 + esp_random() % 900000;
    passkey = pairing ? pk : 0;
    return pk;
  }
  void onAuthenticationComplete(NimBLEConnInfo& ci) override {
    passkey = 0;
    if (!ci.isEncrypted() || !ci.isAuthenticated()) {
      srv->disconnect(ci);
      return;
    }
    if (pairing) pairing = false;   // one phone per pairing session
  }
};

class WriteCB : public NimBLECharacteristicCallbacks {
  void onWrite(NimBLECharacteristic* c, NimBLEConnInfo&) override {
    String v = String(c->getValue().c_str());
    portENTER_CRITICAL(&qMux);
    if (c == chCommand) { qCommand = v; hasCommand = true; }
    else if (c == chSettings) { qSettings = v; hasSettings = true; }
    else if (c == chConsole) { qConsole = v; hasConsole = true; }
    portEXIT_CRITICAL(&qMux);
  }
};

static ServerCB serverCB;
static WriteCB writeCB;

// ── Stack lifecycle ─────────────────────────────────────────────────────────
static void buildAdvert() {
  const PowerInfo& p = powerInfo();
  uint8_t flags = 0;
  bool recordingNow = cfg.recording && !bleHandoffPaused();
  if (recordingNow) flags |= 0x01;
  if (cfg.handoff == 0) flags |= 0x02;
  if (p.state == PWR_CHARGING || p.state == PWR_FULL) flags |= 0x04;
  if (gpsLatest().valid && gpsLatest().atMs && millis() - gpsLatest().atMs < 120000) flags |= 0x08;
  if (bleHandoffPaused()) flags |= 0x10;
  if (!cfg.recording) flags |= 0x20;
  if (pairing) flags |= 0x40;
  uint8_t ivl = (uint8_t)min<uint32_t>(255, (cfg.bleIntervalS + 9) / 10);   // tens of seconds
  uint8_t md[7] = {0xFF, 0xFF, 1, flags, batteryPercentByte(), ++advSeq, ivl};
  NimBLEAdvertisementData adv, scan;
  adv.setFlags(BLE_HS_ADV_F_DISC_GEN | BLE_HS_ADV_F_BREDR_UNSUP);
  adv.addServiceUUID(NimBLEUUID(ROAMLY_BLE_SERVICE));
  adv.setManufacturerData(md, sizeof md);
  char name[24];
  snprintf(name, sizeof name, "Roamly-%s", hwId() + 8);
  scan.setName(name);
  NimBLEAdvertising* a = NimBLEDevice::getAdvertising();
  a->setAdvertisementData(adv);
  a->setScanResponseData(scan);
  a->setMinInterval(160);   // 100 ms (units of 0.625 ms)
  a->setMaxInterval(240);   // 150 ms
}

static void stackStart() {
  if (stackOn) return;
  char name[24];
  snprintf(name, sizeof name, "Roamly-%s", hwId() + 8);
  NimBLEDevice::init(name);
  NimBLEDevice::setMTU(517);
  NimBLEDevice::setSecurityAuth(true, true, true);              // bond, MITM, secure connections
  NimBLEDevice::setSecurityIOCap(BLE_HS_IO_DISPLAY_ONLY);       // we show a passkey, the phone types it
  srv = NimBLEDevice::createServer();
  srv->setCallbacks(&serverCB, false);
  srv->advertiseOnDisconnect(false);
  NimBLEService* svc = srv->createService(ROAMLY_BLE_SERVICE);
  const uint32_t R = NIMBLE_PROPERTY::READ | NIMBLE_PROPERTY::READ_ENC | NIMBLE_PROPERTY::READ_AUTHEN;
  const uint32_t W = NIMBLE_PROPERTY::WRITE | NIMBLE_PROPERTY::WRITE_ENC | NIMBLE_PROPERTY::WRITE_AUTHEN;
  chStatus = svc->createCharacteristic(ROAMLY_BLE_STATUS, R | NIMBLE_PROPERTY::NOTIFY);
  chSettings = svc->createCharacteristic(ROAMLY_BLE_SETTINGS, R | W | NIMBLE_PROPERTY::NOTIFY);
  chCommand = svc->createCharacteristic(ROAMLY_BLE_COMMAND, W | NIMBLE_PROPERTY::NOTIFY);
  chConsole = svc->createCharacteristic(ROAMLY_BLE_CONSOLE, W | NIMBLE_PROPERTY::NOTIFY);
  chSettings->setCallbacks(&writeCB);
  chCommand->setCallbacks(&writeCB);
  chConsole->setCallbacks(&writeCB);
  chStatus->setValue(statusJson());
  chSettings->setValue(settingsJson());
  svc->start();
  stackOn = true;
  advertising = false;
  bonds = NimBLEDevice::getNumBonds();
}

static void stackStop() {
  if (!stackOn || conns > 0) return;
  NimBLEDevice::deinit(true);
  srv = nullptr;
  chStatus = chSettings = chCommand = chConsole = nullptr;
  stackOn = false;
  advertising = false;
}

bool bleTrace = false;

static void advStart() {
  if (!stackOn || advertising) return;
  buildAdvert();
  advertising = NimBLEDevice::getAdvertising()->start();
  if (bleTrace) Serial.printf("[ble] %lu adv on\n", (unsigned long)millis());
}

static void advStop() {
  if (!stackOn || !advertising) return;
  NimBLEDevice::getAdvertising()->stop();
  advertising = false;
  if (bleTrace) Serial.printf("[ble] %lu adv off\n", (unsigned long)millis());
}

// ── Main-loop work ──────────────────────────────────────────────────────────
static void notifyChunks(NimBLECharacteristic* c, const String& text) {
  // Under the negotiated MTU. A long reply (status, dump) arrives in pieces
  // and the app reassembles until the 0x04 terminator.
  size_t chunk = 180;
  if (srv && srv->getConnectedCount()) {
    for (uint16_t h : srv->getPeerDevices()) { uint16_t m = srv->getPeerMTU(h); if (m > 23) chunk = m - 3; break; }
  }
  for (size_t i = 0; i < text.length(); i += chunk) {
    String part = text.substring(i, min(text.length(), i + chunk));
    c->setValue((const uint8_t*)part.c_str(), part.length());
    c->notify();
    delay(8);
  }
  const uint8_t eot = 0x04;
  c->setValue(&eot, 1);
  c->notify();
}

struct StringPrint : public Print {
  String s;
  size_t write(uint8_t b) override { s += (char)b; return 1; }
  size_t write(const uint8_t* b, size_t n) override { s.concat((const char*)b, n); return n; }
};

static void reply(bool ok, const char* cmd, const String& msg) {
  if (!chCommand) return;
  JsonDocument d;
  d["ok"] = ok;
  d["cmd"] = cmd;
  d["msg"] = msg;
  String out;
  serializeJson(d, out);
  chCommand->setValue(out);
  chCommand->notify();
}

static void runCommand(const String& text) {
  JsonDocument d;
  if (deserializeJson(d, text)) { reply(false, "?", "bad JSON"); return; }
  String cmd = d["cmd"] | "";
  if (cmd == "lease") {
    // "The phone is here." Only acted on when the tracker is set to let the
    // phone record while together; otherwise it is just a clock source.
    uint32_t ttl = constrain((uint32_t)(d["ttl"] | 300), 30u, 900u);
    leaseUntilMs = millis() + ttl * 1000;
    uint32_t t = d["t"] | 0;
    // The phone's clock is network-synced. Take it when the GPS hasn't given
    // us one yet (it also feeds the GPS start-up hint).
    if (t > 1700000000 && !gpsInfo().timeValid) {
      struct timeval tv = {(time_t)t, 0};
      settimeofday(&tv, nullptr);
    }
    reply(true, "lease", bleHandoffPaused() ? "phone records" : "tracker records");
  } else if (cmd == "sync") {
    bool ok = netStartSync(true);
    reply(ok, "sync", ok ? "started" : syncStatus().msg);
  } else if (cmd == "pause" || cmd == "resume") {
    cfg.recording = cmd == "resume";
    settingsSave();
    appSettingChanged(S_RECORDING);
    reply(true, cmd.c_str(), cfg.recording ? "recording" : "paused");
  } else if (cmd == "forget") {
    reply(true, "forget", "forgetting all phones");
    delay(100);
    bleForgetPhones();
  } else if (cmd == "restart") {
    reply(true, "restart", "restarting");
    delay(300);
    ESP.restart();
  } else if (cmd == "poweroff") {
    reply(true, "poweroff", "powering off");
    delay(300);
    appPowerOff();
  } else {
    reply(false, cmd.c_str(), "unknown command");
  }
}

static void runSettings(const String& text) {
  JsonDocument d;
  if (deserializeJson(d, text) || !d.is<JsonObject>()) return;
  for (JsonPair kv : d.as<JsonObject>()) {
    String v = kv.value().is<const char*>() ? String(kv.value().as<const char*>()) : String(kv.value().as<long>());
    consoleSetKey(String(kv.key().c_str()), v);
  }
  if (chSettings) { chSettings->setValue(settingsJson()); chSettings->notify(); }
}

static void drainQueue() {
  String cmd, set, con;
  bool c1, c2, c3;
  portENTER_CRITICAL(&qMux);
  c1 = hasCommand; c2 = hasSettings; c3 = hasConsole;
  if (c1) { cmd = qCommand; hasCommand = false; }
  if (c2) { set = qSettings; hasSettings = false; }
  if (c3) { con = qConsole; hasConsole = false; }
  portEXIT_CRITICAL(&qMux);
  if (c2) runSettings(set);
  if (c1) runCommand(cmd);
  if (c3 && chConsole) {
    StringPrint sp;
    consoleExec(con, sp);
    notifyChunks(chConsole, sp.s);
  }
}

// Is the GPS busy enough that the radio must stay quiet?
static bool gpsBusy() {
  const LoggerState& ls = loggerState();
  if (ls.collecting) return true;
  bool recordingNow = cfg.recording && !bleHandoffPaused();
  if (!recordingNow) return false;
  if (gpsWakeLeadMs() == 0) return false;          // Accurate: only the window itself
  const GpsInfo& g = gpsInfo();
  if (g.asleep) { gpsAwakeSinceMs = 0; return false; }
  // Awake in a standby mode = warming up for the next fix. Unless it has been
  // awake for ages (no fix, so it never stands down): then the radio would
  // never get a turn, and presence matters more than a slightly better fix.
  if (!gpsAwakeSinceMs) gpsAwakeSinceMs = millis();
  return millis() - gpsAwakeSinceMs < NO_SLEEP_FALLBACK_MS;
}

void bleTick() {
  uint32_t now = millis();
  if (pairing && (int32_t)(now - pairingUntilMs) >= 0) { pairing = false; passkey = 0; }
  if (cfg.bleIntervalS == 0 && !pairing) {
    if (stackOn) { advStop(); stackStop(); }
    return;
  }
  if (stackOn) drainQueue();

  bool busy = gpsBusy();
  if (bleTrace) {
    static bool lastCollecting = false;
    bool c = loggerState().collecting;
    if (c != lastCollecting) Serial.printf("[ble] %lu fix window %s\n", (unsigned long)now, c ? "start" : "end");
    lastCollecting = c;
  }
  bool external = powerInfo().external;
  bool connected = conns > 0;
  if (connected && now - connectedSinceMs > CONNECTED_MAX_MS) {
    // A held-open connection keeps the ESP awake; a forgotten app screen
    // shouldn't drain the battery.
    for (uint16_t h : srv->getPeerDevices()) srv->disconnect(h);
  }

  // Open a window when one is due and it fits before the GPS needs the air.
  bool windowOpen = (int32_t)(windowEndMs - now) > 0;
  if (!windowOpen && cfg.bleIntervalS && (int32_t)(now - nextWindowMs) >= 0 && !busy) {
    uint32_t gpsNext = loggerNextWakeMs();
    bool fits = !cfg.recording || bleHandoffPaused() || (int32_t)(gpsNext - now) > (int32_t)(WINDOW_MS + WINDOW_MARGIN_MS);
    if (fits) {
      windowEndMs = now + WINDOW_MS;
      windowOpen = true;
      nextWindowMs = now + (uint32_t)cfg.bleIntervalS * 1000;
    }
  }
  bool want = pairing || connected || external || windowOpen;
  if (!want) {
    advStop();
    stackStop();
    return;
  }
  if (!stackOn) stackStart();
  // Fixes win: no advertising while the GPS is busy (a connection may stay).
  if (busy && !pairing) { advStop(); if (!connected) windowEndMs = now; }
  else if (!connected) advStart();

  // Live status for a connected app, every 2 s.
  if (connected && chStatus && now - lastStatusMs > 2000) {
    lastStatusMs = now;
    chStatus->setValue(statusJson());
    chStatus->notify();
  }
  // Refresh the advert's battery/flags on a long-running (external power) session.
  static uint32_t lastAdvRefresh = 0;
  if (advertising && now - lastAdvRefresh > 30000) {
    lastAdvRefresh = now;
    advStop();
    advStart();
  }
}

bool bleActive() { return stackOn; }
uint32_t bleNextWakeMs() { return cfg.bleIntervalS ? nextWindowMs : 0; }
bool bleHandoffPaused() { return cfg.handoff == 1 && leaseUntilMs && (int32_t)(leaseUntilMs - millis()) > 0; }
uint32_t bleLeaseRemainingS() {
  int32_t r = (int32_t)(leaseUntilMs - millis());
  return leaseUntilMs && r > 0 ? r / 1000 : 0;
}
bool bleConnected() { return conns > 0; }
uint8_t bleBondCount() { return bonds; }
bool blePairing() { return pairing; }
uint32_t blePasskey() { return passkey; }

void bleEnterPairing() {
  pairing = true;
  passkey = 0;
  pairingUntilMs = millis() + PAIRING_MS;
}

void bleCancelPairing() { pairing = false; passkey = 0; }

void bleForgetPhones() {
  bool wasOn = stackOn;
  if (!wasOn) stackStart();
  NimBLEDevice::deleteAllBonds();
  bonds = 0;
  if (!wasOn) stackStop();
}

const char* bleStateText() {
  if (cfg.bleIntervalS == 0 && !pairing) return "off";
  if (conns > 0) return "connected";
  if (advertising) return "advertising";
  return "sleeping";
}
