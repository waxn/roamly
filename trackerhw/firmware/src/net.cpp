#include "net.h"
#include "config.h"
#include "settings.h"
#include "storage.h"
#include "power.h"
#include "gps.h"
#include "logger.h"
#include <WiFi.h>
#include <HTTPClient.h>
#include <NetworkClientSecure.h>
#include <WebServer.h>
#include <DNSServer.h>
#include <ArduinoJson.h>
#include <esp_mac.h>
#include <sys/time.h>

static SyncStatus ss;
static char hwid[13] = "";
static volatile PairState pState = PAIR_IDLE;
static char pMsg[64] = "";
static char pCode[16] = "";

const char* hwId() { return hwid; }
SyncStatus& syncStatus() { return ss; }
PairState pairState() { return pState; }
const char* pairMessage() { return pMsg; }
void pairAcknowledge() { if (pState != PAIR_RUNNING) pState = PAIR_IDLE; }

void netBegin() {
  uint8_t mac[6];
  esp_read_mac(mac, ESP_MAC_WIFI_STA);
  snprintf(hwid, sizeof hwid, "%02X%02X%02X%02X%02X%02X", mac[0], mac[1], mac[2], mac[3], mac[4], mac[5]);
  WiFi.persistent(false);
  WiFi.mode(WIFI_OFF);
}

// ── Wi-Fi ───────────────────────────────────────────────────────────────────
static bool wifiConnect(char* err, size_t n) {
  if (!cfg.wifiCount) { snprintf(err, n, "no Wi-Fi saved"); return false; }
  WiFi.mode(WIFI_STA);
  WiFi.setSleep(false);
  int found = WiFi.scanNetworks(false, true);
  // Known networks in descending signal order; ones the scan missed (hidden,
  // or a scan that came back short) are still tried, last.
  int order[MAX_WIFI], rssi[MAX_WIFI], cnt = 0;
  for (int k = 0; k < cfg.wifiCount; k++) {
    int best = -1000;
    for (int i = 0; i < found; i++) if (WiFi.SSID(i) == cfg.wifi[k].ssid && WiFi.RSSI(i) > best) best = WiFi.RSSI(i);
    order[cnt] = k; rssi[cnt] = best; cnt++;
  }
  WiFi.scanDelete();
  for (int a = 0; a < cnt; a++) for (int b = a + 1; b < cnt; b++)
    if (rssi[b] > rssi[a]) { std::swap(order[a], order[b]); std::swap(rssi[a], rssi[b]); }
  for (int a = 0; a < cnt; a++) {
    const WifiCred& w = cfg.wifi[order[a]];
    WiFi.begin(w.ssid, w.pass);
    uint32_t t0 = millis();
    while (WiFi.status() != WL_CONNECTED && millis() - t0 < WIFI_CONNECT_MS) delay(100);
    if (WiFi.status() == WL_CONNECTED) { ss.rssi = WiFi.RSSI(); return true; }
    WiFi.disconnect();
  }
  snprintf(err, n, "Wi-Fi: couldn't join");
  return false;
}

static void wifiOff() {
  WiFi.disconnect(true);
  WiFi.mode(WIFI_OFF);
}

// ── HTTP ────────────────────────────────────────────────────────────────────
static String baseUrl() {
  String s = cfg.server;
  s.trim();
  while (s.endsWith("/")) s.remove(s.length() - 1);
  if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://" + s;
  return s;
}

// The connection outlives individual requests: a TLS handshake costs seconds,
// and a sync is dozens of requests to one host. Only one network task runs at
// a time, so a single session is enough; httpClose() ends it.
static HTTPClient http;
static NetworkClientSecure tls;
static NetworkClient plain;
static bool tlsReady = false;

static void httpClose() {
  http.end();
  tls.stop();
  plain.stop();
}

// One request. Returns the HTTP status (negative = transport error).
static int request(const char* method, const String& path, const String& body, String& resp, bool auth) {
  String url = baseUrl() + path;
  bool https = url.startsWith("https://");
  if (https && !tlsReady) {
    tls.useBuiltinCACertBundle();   // Mozilla roots compiled into the core
    tls.setHandshakeTimeout(HTTP_TIMEOUT_MS / 1000);
    tlsReady = true;
  }
  bool ok = https ? http.begin(tls, url) : http.begin(plain, url);
  if (!ok) return -100;
  http.setTimeout(HTTP_TIMEOUT_MS);
  http.setReuse(true);
  http.addHeader("X-Roamly-Client", "tracker/" FW_VERSION);
  if (auth) http.addHeader("Authorization", String("Bearer ") + cfg.apiKey);
  int code;
  if (strcmp(method, "POST") == 0) {
    http.addHeader("Content-Type", "application/json");
    code = http.POST(body);
  } else {
    code = http.GET();
  }
  resp = code > 0 ? http.getString() : String(http.errorToString(code));
  if (code <= 0) httpClose();   // a broken connection must not be reused
  return code;
}

static void applyTime(JsonDocument& d) {
  if (d["tz"].is<const char*>() && strlen(d["tz"]) > 0) strlcpy(cfg.tzPosix, d["tz"], sizeof cfg.tzPosix);
  else cfg.tzPosix[0] = 0;
  cfg.tzOffsetS = d["utc_offset_s"] | 0;
  if (d["name"].is<const char*>()) strlcpy(cfg.deviceName, d["name"], sizeof cfg.deviceName);
  settingsSave();
  // Only fall back to the server's clock when the GPS hasn't given us one.
  uint32_t st = d["server_time"] | 0;
  if (st > 1700000000 && !gpsInfo().timeValid) {
    struct timeval tv = {(time_t)st, 0};
    settimeofday(&tv, nullptr);
  }
  extern void applyTimezone();
  applyTimezone();
}

static void setMsg(const char* fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  vsnprintf(ss.msg, sizeof ss.msg, fmt, ap);
  va_end(ap);
  Serial.printf("[sync] %s\n", ss.msg);
}

static void statusJson(JsonObject st) {
  const PowerInfo& p = powerInfo();
  StorageStats s = storageStats();
  if (p.batteryPresent) { st["battery"] = roundf(p.percent * 10) / 10; st["voltage"] = roundf(p.voltage * 1000) / 1000; }
  st["charging"] = p.state == PWR_CHARGING;
  st["stored"] = (int)s.pending;
  st["quarantined"] = (int)s.quarantined;
  st["free_kb"] = (int)s.freeKb;
  st["uptime_s"] = (int)(millis() / 1000);
  st["gps_mode"] = cfg.gpsMode == GPS_ACCURATE ? "accurate" : cfg.gpsMode == GPS_LOWPOWER ? "low_power" : "balanced";
  st["interval_s"] = (int)loggerIntervalS();   // resolved, when Adaptive
  st["rssi"] = ss.rssi;
  st["boots"] = (int)bootCount();
  st["fw"] = FW_VERSION;
}

// ── Network task plumbing ───────────────────────────────────────────────────
// A TLS handshake is seconds of uninterrupted big-number maths. At priority 1
// that starved core 0's idle task, and the task watchdog rebooted the tracker
// mid-handshake on every sync. Network tasks therefore run at idle priority
// (time-sliced with IDLE0, so the watchdog stays fed) and at full clock, which
// only costs battery during a sync — and syncs only run on external power.
static void startNetTask(TaskFunction_t fn, const char* name) {
  setCpuFrequencyMhz(240);
  xTaskCreatePinnedToCore(fn, name, 16384, nullptr, tskIDLE_PRIORITY, nullptr, 0);
}

// ── Sync task ───────────────────────────────────────────────────────────────
static PointRec batchBuf[UPLOAD_BATCH];
static PointRec rejBuf[UPLOAD_BATCH];

static bool uploadBatch(int n, uint32_t batchId, int& nRej, bool& fatal) {
  JsonDocument doc;
  doc["batch_id"] = batchId;
  statusJson(doc["status"].to<JsonObject>());
  JsonArray arr = doc["points"].to<JsonArray>();
  char num[24];
  for (int i = 0; i < n; i++) {
    const PointRec& r = batchBuf[i];
    JsonObject o = arr.add<JsonObject>();
    o["seq"] = r.seq;
    o["t"] = r.t;
    // Fixed 7 decimals: exactly what was stored, so the server's read-back
    // comparison (rounded to 7 places) matches byte for byte.
    snprintf(num, sizeof num, "%.7f", r.latE7 / 1e7); o["lat"] = serialized(String(num));
    snprintf(num, sizeof num, "%.7f", r.lonE7 / 1e7); o["lon"] = serialized(String(num));
    if (r.altCm != INT32_MIN) o["alt"] = r.altCm / 100.0;
    if (r.accDm != 0xFFFF) o["acc"] = r.accDm / 10.0;
    if (r.spdCms != 0xFFFF) o["spd"] = r.spdCms / 100.0;
    if (r.crsCdeg != 0xFFFF) o["crs"] = r.crsCdeg / 100.0;
    o["sats"] = r.sats;
    o["hdop"] = r.hdopX10 / 10.0;
    if (r.batt != 0xFF) o["batt"] = r.batt;
  }
  String body;
  serializeJson(doc, body);
  String resp;
  int code = request("POST", "/api/hw/upload/", body, resp, true);
  nRej = 0;
  if (code == 401) { ss.unpaired = true; fatal = true; setMsg("unpaired by server"); return false; }
  if (code != 200) { setMsg("upload HTTP %d", code); return false; }
  JsonDocument rd;
  if (deserializeJson(rd, resp)) { setMsg("upload: bad JSON"); return false; }
  if (strcmp(rd["status"] | "", "ok") != 0) { setMsg("upload: not ok"); return false; }
  // Every point we sent must come back as confirmed or rejected. Anything
  // else means the server can't vouch for it, and it stays on flash.
  JsonArray conf = rd["confirmed"], rej = rd["rejected"];
  int accounted = 0;
  for (int i = 0; i < n; i++) {
    uint32_t seq = batchBuf[i].seq;
    bool ok = false;
    for (JsonVariant v : conf) if (v.as<uint32_t>() == seq) { ok = true; break; }
    if (!ok) {
      for (JsonObject o : rej) if ((o["seq"] | 0u) == seq) {
        Serial.printf("[sync] seq %lu rejected: %s\n", (unsigned long)seq, (const char*)(o["reason"] | "?"));
        rejBuf[nRej++] = batchBuf[i]; ok = true; break;
      }
    }
    if (ok) accounted++;
  }
  if (accounted != n) { setMsg("server confirmed %d/%d", accounted, n); return false; }
  return true;
}

static void syncTask(void*) {
  char err[64] = "";
  ss.sent = 0;
  ss.total = storageStats().pending;
  bool ok = false;
  do {
    setMsg("connecting Wi-Fi");
    if (!wifiConnect(err, sizeof err)) { setMsg("%s", err); break; }
    setMsg("checking server");
    String resp;
    int code = request("GET", "/api/hw/hello/", "", resp, true);
    if (code == 401) { ss.unpaired = true; setMsg("unpaired by server"); break; }
    if (code == 404) { setMsg("server lacks tracker API"); break; }
    if (code != 200) { setMsg("server HTTP %d", code); break; }
    JsonDocument hd;
    if (deserializeJson(hd, resp) || strcmp(hd["status"] | "", "ok") != 0) { setMsg("server: bad hello"); break; }
    ss.unpaired = false;
    applyTime(hd);

    uint32_t batchId = millis();
    bool failed = false;
    bool sentAny = false;
    for (;;) {
      int consumed = 0;
      int n = storagePeek(batchBuf, UPLOAD_BATCH, consumed);
      if (consumed == 0) break;
      if (n == 0) { storageCommit(consumed, nullptr, 0); continue; }   // only corrupt records
      bool done = false, fatal = false;
      int nRej = 0;
      for (int attempt = 0; attempt < 3 && !done && !fatal; attempt++) {
        setMsg("uploading %lu/%lu", (unsigned long)ss.sent, (unsigned long)ss.total);
        done = uploadBatch(n, batchId, nRej, fatal);
        if (!done && !fatal) delay(1500 * (attempt + 1));
      }
      if (!done) { failed = true; break; }
      if (!storageCommit(consumed, rejBuf, nRej)) { setMsg("flash commit failed"); failed = true; break; }
      ss.sent += n;
      sentAny = true;
      batchId++;
    }
    if (failed) break;
    if (!sentAny) {
      // Nothing to send: still report status so Settings shows the battery.
      int nRej; bool fatal = false;
      uploadBatch(0, batchId, nRej, fatal);
    }
    ok = true;
    setMsg(ss.sent ? "uploaded %lu" : "up to date", (unsigned long)ss.sent);
  } while (false);
  httpClose();
  wifiOff();
  ss.lastOk = ok;
  if (ok) { ss.fails = 0; ss.lastOkUnix = time(nullptr); }
  else if (ss.fails < 250) ss.fails++;
  setCpuFrequencyMhz(80);
  ss.running = false;
  vTaskDelete(nullptr);
}

bool netCanSync(char* why, size_t n) {
  if (!cfg.server[0]) { snprintf(why, n, "no server set"); return false; }
  if (!cfg.apiKey[0]) { snprintf(why, n, "not paired"); return false; }
  if (!cfg.wifiCount) { snprintf(why, n, "no Wi-Fi saved"); return false; }
  if (netPortalActive()) { snprintf(why, n, "setup hotspot on"); return false; }
  return true;
}

bool netStartSync(bool manual) {
  if (ss.running || pState == PAIR_RUNNING) return false;
  // A deliberate sync wins over a setup hotspot left running in the
  // background (it outlives its screen); the automatic one waits for it.
  if (manual && netPortalActive()) netPortalStop();
  char why[40];
  if (!netCanSync(why, sizeof why)) { if (manual) setMsg("%s", why); return false; }
  ss.running = true;
  ss.lastAttemptMs = millis();
  startNetTask(syncTask, "sync");
  return true;
}

// ── Pairing ─────────────────────────────────────────────────────────────────
static void pairTask(void*) {
  char err[64] = "";
  do {
    snprintf(pMsg, sizeof pMsg, "connecting Wi-Fi");
    if (!wifiConnect(err, sizeof err)) { snprintf(pMsg, sizeof pMsg, "%s", err); pState = PAIR_FAIL; break; }
    snprintf(pMsg, sizeof pMsg, "contacting server");
    JsonDocument d;
    d["code"] = pCode;
    d["hw_id"] = hwid;
    d["model"] = HW_MODEL;
    d["fw"] = FW_VERSION;
    String body, resp;
    serializeJson(d, body);
    int code = request("POST", "/api/hw/pair/claim/", body, resp, false);
    if (code == 404 && resp.indexOf("bad_code") >= 0) { snprintf(pMsg, sizeof pMsg, "wrong or expired code"); pState = PAIR_FAIL; break; }
    if (code == 429) { snprintf(pMsg, sizeof pMsg, "too many tries, wait"); pState = PAIR_FAIL; break; }
    if (code != 200) { snprintf(pMsg, sizeof pMsg, "server HTTP %d", code); pState = PAIR_FAIL; break; }
    JsonDocument rd;
    if (deserializeJson(rd, resp) || !rd["api_key"].is<const char*>()) { snprintf(pMsg, sizeof pMsg, "bad server reply"); pState = PAIR_FAIL; break; }
    strlcpy(cfg.apiKey, rd["api_key"], sizeof cfg.apiKey);
    settingsSave();
    applyTime(rd);
    ss.unpaired = false;
    snprintf(pMsg, sizeof pMsg, "paired as %s", cfg.deviceName);
    pState = PAIR_OK;
  } while (false);
  httpClose();
  wifiOff();
  setCpuFrequencyMhz(80);
  if (pState == PAIR_RUNNING) pState = PAIR_FAIL;
  vTaskDelete(nullptr);
}

void netStartPair(const char* code) {
  if (pState == PAIR_RUNNING || ss.running) return;
  if (netPortalActive()) netPortalStop();
  strlcpy(pCode, code, sizeof pCode);
  if (!cfg.server[0] || !cfg.wifiCount) {
    snprintf(pMsg, sizeof pMsg, "set Wi-Fi + server first");
    pState = PAIR_FAIL;
    return;
  }
  pState = PAIR_RUNNING;
  startNetTask(pairTask, "pair");
}

// ── Setup hotspot ───────────────────────────────────────────────────────────
static WebServer* web = nullptr;
static DNSServer* dns = nullptr;
static char apSsid[24], apPass[12];
static uint32_t portalStartMs = 0;

const char* portalSsid() { return apSsid; }
const char* portalPass() { return apPass; }
bool netPortalActive() { return web != nullptr; }

static String esc(const String& s) {
  String o;
  for (char c : s) {
    if (c == '<') o += "&lt;"; else if (c == '>') o += "&gt;"; else if (c == '&') o += "&amp;";
    else if (c == '"') o += "&quot;"; else o += c;
  }
  return o;
}

static void sendPage(const String& note = "") {
  String h = F("<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
               "<title>Roamly tracker</title><style>body{font:15px system-ui;background:#16130f;color:#e9e2d6;max-width:560px;margin:auto;padding:16px}"
               "input,button{font:inherit;padding:8px;margin:4px 0;width:100%;box-sizing:border-box;background:#221d17;color:#e9e2d6;border:1px solid #4a4036}"
               "button{background:#e8763d;color:#16130f;border:0;font-weight:600}h1{font-size:20px}h2{font-size:16px;margin-top:22px}"
               ".n{padding:6px 0;border-bottom:1px solid #3a322a;display:flex;justify-content:space-between}a{color:#e8763d}.m{color:#a89a88;font-size:13px}</style>"
               "<h1>Roamly tracker setup</h1>");
  h += "<p class=m>Tracker ID " + String(hwid) + " · firmware " FW_VERSION "</p>";
  if (note.length()) h += "<p><b>" + esc(note) + "</b></p>";
  h += F("<h2>Roamly server</h2><form method=post action=/server><input name=url placeholder='https://roamly.example.com' value='");
  h += esc(cfg.server);
  h += F("'><button>Save server</button></form><h2>Wi-Fi networks</h2>");
  for (int i = 0; i < cfg.wifiCount; i++)
    h += "<div class=n><span>" + esc(cfg.wifi[i].ssid) + "</span><a href='/del?i=" + String(i) + "'>remove</a></div>";
  if (!cfg.wifiCount) h += F("<p class=m>None saved yet.</p>");
  h += F("<form method=post action=/wifi><input name=ssid placeholder='Network name' list=nets>"
         "<input name=pass type=password placeholder='Password'><button>Add network</button></form><datalist id=nets>");
  int n = WiFi.scanComplete();
  for (int i = 0; i < n; i++) h += "<option value=\"" + esc(WiFi.SSID(i)) + "\">";
  h += F("</datalist><h2>Pairing</h2><p class=m>");
  h += cfg.apiKey[0] ? "Paired as " + esc(cfg.deviceName) : String("Not paired. After saving the above, exit this hotspot on the tracker and choose Pair with Roamly.");
  h += F("</p><p class=m>Up to 5 networks are kept; the tracker uses them only while charging.</p>");
  web->send(200, "text/html", h);
}

bool netPortalStart() {
  if (web) return true;
  uint32_t r = esp_random();
  snprintf(apSsid, sizeof apSsid, "Roamly-%s", hwid + 8);
  snprintf(apPass, sizeof apPass, "%08lu", (unsigned long)(r % 100000000UL));
  WiFi.mode(WIFI_AP_STA);
  WiFi.softAP(apSsid, apPass);
  WiFi.scanNetworks(true);   // async: fills the network-name suggestions
  dns = new DNSServer();
  dns->start(53, "*", WiFi.softAPIP());
  web = new WebServer(80);
  web->on("/", HTTP_GET, [] { sendPage(); });
  web->on("/server", HTTP_POST, [] {
    String u = web->arg("url");
    u.trim();
    if (u.length() && u.length() < sizeof(cfg.server)) {
      if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://" + u;
      if (strcmp(u.c_str(), cfg.server) != 0) cfg.apiKey[0] = 0;   // a new server needs a new pairing
      strlcpy(cfg.server, u.c_str(), sizeof cfg.server);
      settingsSave();
      sendPage("Server saved.");
    } else sendPage("That address is too long or empty.");
  });
  web->on("/wifi", HTTP_POST, [] {
    sendPage(addWifi(web->arg("ssid").c_str(), web->arg("pass").c_str()) ? "Network saved." : "Couldn't save that network.");
  });
  web->on("/del", HTTP_GET, [] { sendPage(removeWifi(web->arg("i").toInt()) ? "Removed." : "Not found."); });
  web->onNotFound([] {   // captive portal: send every stray URL to the form
    web->sendHeader("Location", String("http://") + WiFi.softAPIP().toString() + "/", true);
    web->send(302, "text/plain", "");
  });
  web->begin();
  portalStartMs = millis();
  return true;
}

void netPortalStop() {
  if (!web) return;
  web->stop();
  delete web; web = nullptr;
  dns->stop();
  delete dns; dns = nullptr;
  WiFi.softAPdisconnect(true);
  wifiOff();
}

void netPortalPoll() {
  if (!web) return;
  dns->processNextRequest();
  web->handleClient();
  if (millis() - portalStartMs > PORTAL_TIMEOUT_MS) netPortalStop();
}
