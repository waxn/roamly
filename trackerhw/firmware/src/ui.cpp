#include "ui.h"
#include "config.h"
#include "settings.h"
#include "gps.h"
#include "power.h"
#include "storage.h"
#include "logger.h"
#include "net.h"
#include "util.h"
#include <SPI.h>
#include <Adafruit_GFX.h>
#include <Adafruit_ST7789.h>
#include <Fonts/FreeSans9pt7b.h>
#include <Fonts/FreeSansBold9pt7b.h>
#include <Fonts/FreeSansBold18pt7b.h>

// ── Palette (the web app's Field Journal tokens, in RGB565) ─────────────────
static constexpr uint16_t rgb(uint8_t r, uint8_t g, uint8_t b) {
  return ((r & 0xF8) << 8) | ((g & 0xFC) << 3) | (b >> 3);
}
static constexpr uint16_t C_BG = rgb(0x16, 0x13, 0x0f);
static constexpr uint16_t C_PANEL = rgb(0x2a, 0x24, 0x1d);
static constexpr uint16_t C_TEXT = rgb(0xe9, 0xe2, 0xd6);
static constexpr uint16_t C_MUTED = rgb(0xa8, 0x9a, 0x88);
static constexpr uint16_t C_DIM = rgb(0x6e, 0x63, 0x56);
static constexpr uint16_t C_PRIMARY = rgb(0xe8, 0x76, 0x3d);
static constexpr uint16_t C_GOOD = rgb(0x8f, 0xb0, 0x6a);
static constexpr uint16_t C_WARN = rgb(0xd9, 0xa4, 0x41);
static constexpr uint16_t C_BAD = rgb(0xd0, 0x69, 0x4a);

static Adafruit_ST7789 tft(TFT_CS, TFT_DC, TFT_RST);
static GFXcanvas16* cv = nullptr;
static constexpr int W = 240, H = 135;
static constexpr int XR = 229;   // right text edge: clears the page dots at x=237

// ── Buttons ─────────────────────────────────────────────────────────────────
enum Ev : uint8_t { EV_NONE, EV_UP, EV_SEL, EV_DOWN, EV_SEL_LONG, EV_UP_LONG, EV_DOWN_LONG, EV_UP_REP, EV_DOWN_REP };
struct Btn {
  uint8_t pin;
  bool activeHigh;
  bool stable = false, raw = false;
  uint32_t changedAt = 0, downAt = 0, lastRep = 0;
  bool longDone = false, swallow = false;
};
static Btn btns[3] = {{(uint8_t)PIN_BTN_UP, false}, {(uint8_t)PIN_BTN_SELECT, true}, {(uint8_t)PIN_BTN_DOWN, true}};

static bool screenOn = false;
static uint32_t lastActivityMs = 0;
static bool needFull = true;

// ── Screens ─────────────────────────────────────────────────────────────────
enum Scr : uint8_t { SCR_STATUS, SCR_MENU, SCR_SETTINGS, SCR_EDIT, SCR_PAIR, SCR_PORTAL, SCR_STORAGE, SCR_ABOUT, SCR_CONFIRM };
static Scr scr = SCR_STATUS;
static int statusPage = 0;
static constexpr int STATUS_PAGES = 4;
static int menuSel = 0, menuTop = 0;
static int setSel = 0, setTop = 0;
static int editIdx = 0;
static int storSel = 0;
static char toast[48] = "";
static uint32_t toastUntil = 0;
static int confirmWhat = 0;   // 1 = erase points, 2 = clear quarantine, 3 = restart
static char pairBuf[9] = "";
static int pairLen = 0;

static void showToast(const char* s) {
  strlcpy(toast, s, sizeof toast);
  toastUntil = millis() + 1800;
}

// ── Settings model ──────────────────────────────────────────────────────────
static const char* SET_LABEL[S_COUNT] = {"Screen timeout", "Log interval", "GPS mode", "Min accuracy", "Units", "Clock",
                                         "Time zone", "Brightness", "Flip screen", "Auto-sync", "Battery size"};
static constexpr int TZ_AUTO = 9999;

static int setValues(int id, int* out) {
  int n = 0;
  auto add = [&](int v) { out[n++] = v; };
  switch (id) {
    case S_TIMEOUT: for (int v : {3, 5, 10, 15, 30, 60, 120}) add(v); break;
    case S_INTERVAL: for (int v : {0, 10, 15, 30, 60, 120, 300}) add(v); break;
    case S_GPSMODE: for (int v : {0, 1, 2}) add(v); break;
    case S_MINACC: for (int v : {0, 15, 25, 50, 100}) add(v); break;
    case S_UNITS: case S_FLIP: add(0); add(1); break;
    case S_CLOCK: case S_AUTOSYNC: add(1); add(0); break;
    case S_TZ: add(TZ_AUTO); for (int m = -720; m <= 840; m += 30) add(m); break;
    case S_BRIGHT: for (int v = 10; v <= 100; v += 10) add(v); break;
    case S_BATT: for (int v = 500; v <= 3000; v += 100) add(v); break;
  }
  return n;
}

static int setGet(int id) {
  switch (id) {
    case S_TIMEOUT: return cfg.screenTimeoutS;
    case S_INTERVAL: return cfg.intervalS;
    case S_GPSMODE: return cfg.gpsMode;
    case S_MINACC: return cfg.minAccM;
    case S_UNITS: return cfg.imperial;
    case S_CLOCK: return cfg.clock24;
    case S_TZ: return cfg.tzAuto ? TZ_AUTO : cfg.tzManualMin;
    case S_BRIGHT: return cfg.brightness;
    case S_FLIP: return cfg.flip;
    case S_AUTOSYNC: return cfg.autoSync;
    case S_BATT: return cfg.battMah;
  }
  return 0;
}

static void setPut(int id, int v) {
  switch (id) {
    case S_TIMEOUT: cfg.screenTimeoutS = v; break;
    case S_INTERVAL: cfg.intervalS = v; break;
    case S_GPSMODE: cfg.gpsMode = v; break;
    case S_MINACC: cfg.minAccM = v; break;
    case S_UNITS: cfg.imperial = v; break;
    case S_CLOCK: cfg.clock24 = v; break;
    case S_TZ: cfg.tzAuto = v == TZ_AUTO; if (v != TZ_AUTO) cfg.tzManualMin = v; break;
    case S_BRIGHT: cfg.brightness = v; break;
    case S_FLIP: cfg.flip = v; break;
    case S_AUTOSYNC: cfg.autoSync = v; break;
    case S_BATT: cfg.battMah = v; break;
  }
  settingsSave();
  appSettingChanged(id);
}

static String setFmt(int id, int v) {
  char b[24];
  switch (id) {
    case S_INTERVAL: if (!v) return "Adaptive";   // fall through
    case S_TIMEOUT: snprintf(b, sizeof b, v >= 60 ? "%d min" : "%d s", v >= 60 ? v / 60 : v); return b;
    case S_GPSMODE: return v == GPS_ACCURATE ? "Accurate" : v == GPS_LOWPOWER ? "Low power" : "Balanced";
    case S_MINACC: if (!v) return "Keep all"; snprintf(b, sizeof b, cfg.imperial ? "%d ft" : "%d m", cfg.imperial ? (int)lroundf(v * 3.281f) : v); return b;
    case S_UNITS: return v ? "Imperial" : "Metric";
    case S_CLOCK: return v ? "24 hour" : "12 hour";
    case S_TZ:
      if (v == TZ_AUTO) return "Auto";
      snprintf(b, sizeof b, "UTC%c%d:%02d", v < 0 ? '-' : '+', abs(v) / 60, abs(v) % 60); return b;
    case S_BRIGHT: snprintf(b, sizeof b, "%d%%", v); return b;
    case S_FLIP: return v ? "On" : "Off";
    case S_AUTOSYNC: return v ? "When charging" : "Off";
    case S_BATT: snprintf(b, sizeof b, "%d mAh", v); return b;
  }
  return "";
}

// ── Menu model ──────────────────────────────────────────────────────────────
enum MenuId { M_SYNC, M_PAIR, M_WIFI, M_SETTINGS, M_RECORD, M_STORAGE, M_ABOUT, M_RESTART, M_BACK, M_COUNT };
static String menuLabel(int i) {
  switch (i) {
    case M_SYNC: return "Sync now";
    case M_PAIR: return isPaired() ? "Re-pair with Roamly" : "Pair with Roamly";
    case M_WIFI: return "Wi-Fi setup";
    case M_SETTINGS: return "Settings";
    case M_RECORD: return cfg.recording ? "Pause recording" : "Resume recording";
    case M_STORAGE: return "Storage";
    case M_ABOUT: return "About";
    case M_RESTART: return "Restart";
    case M_BACK: return "Back";
  }
  return "";
}

// ── Drawing helpers ─────────────────────────────────────────────────────────
static int textW(const String& s) {
  int16_t x1, y1;
  uint16_t w, h;
  cv->getTextBounds(s.c_str(), 0, 0, &x1, &y1, &w, &h);
  return w + x1;
}

static void txt(int x, int y, const String& s, uint16_t c, const GFXfont* f = &FreeSans9pt7b) {
  cv->setFont(f);
  cv->setTextColor(c);
  cv->setCursor(x, y);
  cv->print(s);
}

static void txtR(int xr, int y, const String& s, uint16_t c, const GFXfont* f = &FreeSans9pt7b) {
  cv->setFont(f);
  txt(xr - textW(s), y, s, c, f);
}

static void title(const String& s) {
  cv->fillRect(0, 0, W, 22, C_PANEL);
  cv->drawFastHLine(0, 22, W, C_PRIMARY);
  txt(6, 16, s, C_TEXT, &FreeSansBold9pt7b);
}

static void symbolShape(int cx, int cy, char sym, uint16_t c) {
  if (sym == 'T') cv->fillTriangle(cx, cy - 7, cx - 7, cy + 6, cx + 7, cy + 6, c);
  else if (sym == 'B') cv->fillTriangle(cx, cy + 7, cx - 7, cy - 6, cx + 7, cy - 6, c);
  else cv->fillCircle(cx, cy, 6, c);
}

static void batteryIcon(int x, int y, const PowerInfo& p) {
  cv->drawRect(x, y, 26, 13, C_TEXT);
  cv->fillRect(x + 26, y + 4, 3, 5, C_TEXT);
  if (p.batteryPresent) {
    int w = (int)lroundf(22 * constrain(p.percent, 0.0f, 100.0f) / 100.0f);
    uint16_t c = p.percent < 15 ? C_BAD : p.percent < 35 ? C_WARN : C_GOOD;
    cv->fillRect(x + 2, y + 2, w, 9, c);
  }
  if (p.state == PWR_CHARGING || p.state == PWR_FULL) {
    // lightning bolt
    cv->fillTriangle(x + 15, y + 1, x + 9, y + 7, x + 13, y + 7, C_PRIMARY);
    cv->fillTriangle(x + 13, y + 6, x + 17, y + 6, x + 11, y + 12, C_PRIMARY);
  }
}

static String distFmt(float m) {
  if (isnan(m)) return "--";
  char b[16];
  if (cfg.imperial) snprintf(b, sizeof b, "%.0fft", m * 3.281f);
  else snprintf(b, sizeof b, m < 10 ? "%.1fm" : "%.0fm", m);
  return b;
}

static String speedFmt(float ms) {
  if (isnan(ms)) return "--";
  char b[16];
  if (cfg.imperial) snprintf(b, sizeof b, "%.1f mph", ms * 2.23694f);
  else snprintf(b, sizeof b, "%.1f km/h", ms * 3.6f);
  return b;
}

static const char* compass(float deg) {
  static const char* pts[8] = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
  if (isnan(deg)) return "";
  return pts[((int)lroundf(deg / 45.0f)) & 7];
}

static bool clockValid() { return time(nullptr) > 1600000000; }

static String clockStr() {
  if (!clockValid()) return "--:--";
  time_t now = time(nullptr);
  struct tm lt;
  localtime_r(&now, &lt);
  char b[12];
  if (cfg.clock24) snprintf(b, sizeof b, "%02d:%02d", lt.tm_hour, lt.tm_min);
  else snprintf(b, sizeof b, "%d:%02d%s", lt.tm_hour % 12 ? lt.tm_hour % 12 : 12, lt.tm_min, lt.tm_hour < 12 ? "a" : "p");
  return b;
}

static String dateStr() {
  if (!clockValid()) return "no time yet";
  time_t now = time(nullptr);
  struct tm lt;
  localtime_r(&now, &lt);
  char b[20];
  strftime(b, sizeof b, "%a %d %b", &lt);
  return b;
}

// ── Status pages ────────────────────────────────────────────────────────────
static void gpsLine(int y) {
  const GpsFix& f = gpsLatest();
  const GpsInfo& gi = gpsInfo();
  uint32_t age = f.atMs ? (millis() - f.atMs) / 1000 : 0;
  String state;
  uint16_t c;
  if (!cfg.recording) { state = "Paused"; c = C_MUTED; }
  else if (!gi.detected) { state = "No GPS!"; c = C_BAD; }
  else if (f.valid && age < 90) { state = f.fixType >= 3 ? "3D fix" : "2D fix"; c = f.fixType >= 3 ? C_GOOD : C_WARN; }
  else if (loggerState().haveLast) { state = "Searching"; c = C_WARN; }
  else { state = "Acquiring"; c = C_WARN; }
  txt(6, y, state, c);
  String rest;
  if (f.atMs && f.valid) rest = agoShort(age) + " ago";
  else if (loggerState().haveLast) rest = "last " + agoShort((millis() - loggerState().lastAtMs) / 1000) + " ago";
  if (f.valid && !isnan(f.accM)) rest += "  ~" + distFmt(f.accM);   // GFX fonts are ASCII-only: no "±"
  txtR(XR, y, rest, C_MUTED);
}

static void drawStatusOverview() {
  const PowerInfo& p = powerInfo();
  const GpsFix& f = gpsLatest();
  const GpsInfo& gi = gpsInfo();
  const LoggerState& ls = loggerState();
  txt(6, 30, clockStr(), C_TEXT, &FreeSansBold18pt7b);
  batteryIcon(205, 5, p);
  txtR(200, 17, p.batteryPresent ? String((int)lroundf(p.percent)) + "%" : String("USB"), C_TEXT);
  txtR(XR, 36, dateStr(), C_MUTED);

  char b[48];
  if (ls.haveLast) snprintf(b, sizeof b, "%.5f, %.5f", ls.last.latE7 / 1e7, ls.last.lonE7 / 1e7);
  else if (f.valid) snprintf(b, sizeof b, "%.5f, %.5f", f.lat, f.lon);
  else snprintf(b, sizeof b, "no position yet");
  txt(6, 56, b, C_TEXT);

  gpsLine(75);

  snprintf(b, sizeof b, "Sats %u seen / %u used", gi.inView, f.valid ? f.sats : 0);
  txt(6, 94, b, C_TEXT);
  if (ls.haveLast) txtR(XR, 94, "last " + String(ls.last.sats), C_MUTED);

  String bl;
  if (!p.batteryPresent) bl = p.gauge ? "No battery" : "No fuel gauge";
  else if (p.state == PWR_CHARGING) bl = "Full in " + hoursFmt(p.hoursLeft);
  else if (p.state == PWR_FULL) bl = "Charged";
  else bl = hoursFmt(p.hoursLeft) + " left" + (p.estimateMeasured ? "" : " (est)");
  txt(6, 113, bl, p.state == PWR_BATTERY && p.hoursLeft < 3 ? C_BAD : C_TEXT);
  if (p.batteryPresent) { snprintf(b, sizeof b, "%.2fV", p.voltage); txtR(XR, 113, b, C_MUTED); }

  StorageStats s = storageStats();
  const SyncStatus& sy = syncStatus();
  snprintf(b, sizeof b, "%lu stored", (unsigned long)s.pending);
  txt(6, 132, b, C_TEXT);
  String sl;
  if (sy.running) sl = "syncing " + String(sy.sent) + "/" + String(sy.total);
  else if (sy.unpaired) sl = "unpaired!";
  else if (sy.lastOkUnix && clockValid()) sl = "sync " + agoShort(time(nullptr) - sy.lastOkUnix) + " ago";
  else if (!isPaired()) sl = "not paired";
  else sl = "never synced";
  txtR(XR, 132, sl, sy.unpaired ? C_BAD : C_MUTED);
}

static void drawStatusGps() {
  title("GPS");
  const GpsFix& f = gpsLatest();
  const GpsInfo& gi = gpsInfo();
  const LoggerState& ls = loggerState();
  char b[64];
  int y = 40;
  if (!gi.detected) {
    txt(6, y, "Module not detected", C_BAD); y += 18;
    txt(6, y, "Check wiring: GPS TX -> XR", C_MUTED);
    return;
  }
  snprintf(b, sizeof b, "%s  HDOP %.1f  ~%s", f.valid ? (f.fixType >= 3 ? "3D" : "2D") : "No fix", f.hdop,
           distFmt(f.accM).c_str());
  txt(6, y, b, f.valid ? C_TEXT : C_WARN); y += 18;
  snprintf(b, sizeof b, "Alt %s  %s  %s", distFmt(f.altM).c_str(), speedFmt(f.spdMs).c_str(), compass(f.crsDeg));
  txt(6, y, b, C_TEXT); y += 18;
  snprintf(b, sizeof b, "Sats %u used / %u seen", f.sats, gi.inView);
  txt(6, y, b, C_TEXT); y += 18;
  snprintf(b, sizeof b, "Signal %u best, %.0f top-4", gi.bestSnr, gi.avgTop4Snr);
  txt(6, y, b, C_TEXT); y += 18;
  snprintf(b, sizeof b, "Pts %lu  miss %lu  filtered %lu", (unsigned long)ls.recorded, (unsigned long)ls.missed, (unsigned long)ls.filtered);
  txt(6, y, b, C_MUTED); y += 18;
  if (!cfg.intervalS) snprintf(b, sizeof b, "%s, %s: every %lus", setFmt(S_GPSMODE, cfg.gpsMode).c_str(), motionName(ls.motion), (unsigned long)loggerIntervalS());
  else snprintf(b, sizeof b, "%s, every %us%s", setFmt(S_GPSMODE, cfg.gpsMode).c_str(), cfg.intervalS, gi.configured ? "" : " (defaults)");
  txt(6, y, b, C_MUTED);
}

static void drawStatusBattery() {
  title("Battery");
  const PowerInfo& p = powerInfo();
  char b[64];
  int y = 40;
  if (!p.gauge) { txt(6, y, "No fuel gauge found", C_BAD); return; }
  if (!p.batteryPresent) {
    txt(6, y, "No battery connected", C_WARN); y += 18;
    snprintf(b, sizeof b, "Gauge reads %.2f V", p.voltage);
    txt(6, y, b, C_MUTED);
    return;
  }
  snprintf(b, sizeof b, "%.1f%%  %.3f V", p.percent, p.voltage);
  txt(6, y, b, C_TEXT); y += 18;
  const char* st = p.state == PWR_CHARGING ? "Charging" : p.state == PWR_FULL ? "On power, full" : "On battery";
  txt(6, y, String(st) + (p.usbHost ? " (USB host)" : ""), C_TEXT); y += 18;
  if (p.state == PWR_CHARGING) txt(6, y, "Full in " + hoursFmt(p.hoursLeft), C_GOOD);
  else if (p.state == PWR_BATTERY) txt(6, y, hoursFmt(p.hoursLeft) + " to empty", C_TEXT);
  y += 18;
  if (!isnan(p.measuredRate)) snprintf(b, sizeof b, "Rate %+.1f%%/h measured", p.measuredRate);
  else snprintf(b, sizeof b, "Rate: measuring (model %.0f mA)", powerModelCurrentMa());
  txt(6, y, b, C_MUTED); y += 18;
  snprintf(b, sizeof b, "Gauge %+.1f%%/h  %s", p.gaugeRate, p.gaugeName);
  txt(6, y, b, C_MUTED); y += 18;
  snprintf(b, sizeof b, "Capacity %u mAh", cfg.battMah);
  txt(6, y, b, C_MUTED);
}

static void drawStatusSync() {
  title("Storage & sync");
  StorageStats s = storageStats();
  const SyncStatus& sy = syncStatus();
  char b[64];
  int y = 40;
  snprintf(b, sizeof b, "%lu points stored", (unsigned long)s.pending);
  txt(6, y, b, C_TEXT);
  snprintf(b, sizeof b, "%luK free", (unsigned long)s.freeKb);
  txtR(XR, y, b, C_MUTED); y += 18;
  if (s.quarantined) { snprintf(b, sizeof b, "%lu refused by server (kept)", (unsigned long)s.quarantined); txt(6, y, b, C_BAD); y += 18; }
  String last = String("Last: ") + sy.msg;
  if (sy.lastOkUnix && clockValid()) last += ", ok " + agoShort(time(nullptr) - sy.lastOkUnix) + " ago";
  txt(6, y, last, sy.lastOk || !sy.lastAttemptMs ? C_TEXT : C_WARN); y += 18;
  txt(6, y, isPaired() ? String("As ") + (cfg.deviceName[0] ? cfg.deviceName : "tracker") : String("Not paired"),
      isPaired() ? C_TEXT : C_WARN); y += 18;
  String host = cfg.server;
  host.replace("https://", "");
  host.replace("http://", "");
  txt(6, y, host.length() ? host : String("No server set"), C_MUTED); y += 18;
  snprintf(b, sizeof b, "%u Wi-Fi saved", cfg.wifiCount);
  txt(6, y, b, C_MUTED);
  txtR(XR, y, String("#") + (hwId() + 8), C_DIM);
}

static void drawStatus() {
  switch (statusPage) {
    case 0: drawStatusOverview(); break;
    case 1: drawStatusGps(); break;
    case 2: drawStatusBattery(); break;
    case 3: drawStatusSync(); break;
  }
  for (int i = 0; i < STATUS_PAGES; i++)   // page dots
    cv->fillCircle(W - 3, 52 + i * 8, 2, i == statusPage ? C_PRIMARY : C_DIM);
}

// ── List screens ────────────────────────────────────────────────────────────
static void drawList(const char* t, int count, int sel, int& top, std::function<String(int)> label,
                     std::function<String(int)> value) {
  title(t);
  const int rows = 5, rowH = 22, y0 = 25;
  if (sel < top) top = sel;
  if (sel >= top + rows) top = sel - rows + 1;
  for (int r = 0; r < rows && top + r < count; r++) {
    int i = top + r;
    int y = y0 + r * rowH;
    if (i == sel) cv->fillRect(0, y, W - 6, rowH, C_PANEL), cv->fillRect(0, y, 3, rowH, C_PRIMARY);
    txt(9, y + 16, label(i), i == sel ? C_TEXT : C_MUTED);
    String v = value(i);
    if (v.length()) txtR(W - 10, y + 16, v, i == sel ? C_PRIMARY : C_DIM);
  }
  if (count > rows) {   // scrollbar
    int h = (H - y0) * rows / count;
    int y = y0 + (H - y0 - h) * top / max(1, count - rows);
    cv->fillRect(W - 3, y, 3, h, C_DIM);
  }
}

static void drawEdit() {
  title(SET_LABEL[setSel]);
  int vals[64];
  int n = setValues(setSel, vals);
  editIdx = constrain(editIdx, 0, n - 1);
  String v = setFmt(setSel, vals[editIdx]);
  cv->setFont(&FreeSansBold18pt7b);
  int w = textW(v);
  txt((W - w) / 2, 80, v, C_PRIMARY, &FreeSansBold18pt7b);
  symbolShape(W / 2, 40, 'T', editIdx > 0 ? C_TEXT : C_DIM);
  symbolShape(W / 2, 100, 'B', editIdx < n - 1 ? C_TEXT : C_DIM);
  txt(6, 130, "mid: save", C_MUTED);
  txtR(XR, 130, "hold mid: cancel", C_MUTED);
}

static void drawPair() {
  title("Pair with Roamly");
  PairState ps = pairState();
  if (ps == PAIR_RUNNING || ps == PAIR_OK || ps == PAIR_FAIL) {
    uint16_t c = ps == PAIR_OK ? C_GOOD : ps == PAIR_FAIL ? C_BAD : C_TEXT;
    txt(6, 56, ps == PAIR_OK ? "Paired!" : ps == PAIR_FAIL ? "Pairing failed" : "Pairing...", c, &FreeSansBold9pt7b);
    txt(6, 80, pairMessage(), C_TEXT);
    if (ps != PAIR_RUNNING) txt(6, 128, "Press any button", C_MUTED);
    return;
  }
  if (!cfg.server[0] || !cfg.wifiCount) {
    txt(6, 50, "Set up Wi-Fi and the server", C_WARN);
    txt(6, 70, "first: Menu > Wi-Fi setup.", C_WARN);
    txt(6, 128, "mid: Wi-Fi setup", C_PRIMARY);
    txtR(XR, 128, "hold: back", C_MUTED);
    return;
  }
  txt(6, 42, "Enter the code from Roamly", C_MUTED);
  txt(6, 60, "Settings > HW Trackers", C_MUTED);
  const int bw = 26, gap = 3, x0 = (W - (8 * bw + 7 * gap)) / 2, y = 70;
  for (int i = 0; i < 8; i++) {
    int x = x0 + i * (bw + gap);
    cv->drawRect(x, y, bw, 30, i == pairLen ? C_PRIMARY : C_DIM);
    if (i < pairLen) symbolShape(x + bw / 2, y + 15, pairBuf[i], C_TEXT);
  }
  txt(6, 128, "hold mid: delete last", C_MUTED);
}

static void drawPortal() {
  title("Wi-Fi setup hotspot");
  if (!netPortalActive()) { txt(6, 60, "Hotspot stopped.", C_MUTED); return; }
  txt(6, 44, "Join this Wi-Fi network:", C_MUTED);
  txt(6, 64, portalSsid(), C_TEXT, &FreeSansBold9pt7b);
  txt(6, 84, String("Password  ") + portalPass(), C_TEXT);
  txt(6, 106, "then open 192.168.4.1", C_PRIMARY);
  txt(6, 128, "hold mid: stop hotspot", C_MUTED);
}

static void drawStorage() {
  title("Storage");
  StorageStats s = storageStats();
  char b[48];
  snprintf(b, sizeof b, "%lu waiting to upload", (unsigned long)s.pending);
  txt(6, 42, b, C_TEXT);
  snprintf(b, sizeof b, "%lu refused, %lu corrupt", (unsigned long)s.quarantined, (unsigned long)s.corrupt);
  txt(6, 60, b, C_MUTED);
  snprintf(b, sizeof b, "%luK of %luK free", (unsigned long)s.freeKb, (unsigned long)s.totalKb);
  txt(6, 78, b, C_MUTED);
  const char* items[3] = {"Back", "Clear refused", "Erase all points"};
  for (int i = 0; i < 3; i++) {
    int y = 86 + i * 16;
    if (i == storSel) cv->fillRect(0, y, W, 16, C_PANEL);
    txt(9, y + 13, items[i], i == storSel ? (i == 2 ? C_BAD : C_TEXT) : C_DIM);
  }
}

static void drawAbout() {
  title("About");
  txt(6, 42, "Roamly tracker  fw " FW_VERSION, C_TEXT);
  txt(6, 60, String("ID ") + hwId(), C_MUTED);
  const GpsInfo& g = gpsInfo();
  txt(6, 78, String("GPS rx") + g.rx + " tx" + g.tx + " @" + g.baud, C_MUTED);
  txt(6, 96, String(g.version[0] ? g.version : "u-blox (version unknown)"), C_MUTED);
  txt(6, 114, String("Boots ") + bootCount() + "  crashes " + crashCount() + "  up " + agoShort(millis() / 1000), crashCount() ? C_WARN : C_MUTED);
  txtR(XR, 132, String("last: ") + lastResetReason(), C_MUTED);
  txt(6, 132, powerInfo().gaugeName, C_MUTED);
}

static void drawConfirm() {
  title("Are you sure?");
  const char* q = confirmWhat == 1 ? "Erase ALL stored points?" : confirmWhat == 2 ? "Delete refused points?" : "Restart the tracker?";
  txt(6, 56, q, C_TEXT, &FreeSansBold9pt7b);
  if (confirmWhat == 1) {
    char b[40];
    snprintf(b, sizeof b, "%lu not uploaded yet!", (unsigned long)storageStats().pending);
    txt(6, 78, b, C_BAD);
  }
  txt(6, 128, "mid: yes", C_BAD);
  txtR(XR, 128, "up/down: no", C_MUTED);
}

static void render() {
  cv->fillScreen(C_BG);
  switch (scr) {
    case SCR_STATUS: drawStatus(); break;
    case SCR_MENU:
      drawList("Menu", M_COUNT, menuSel, menuTop, menuLabel, [](int i) {
        if (i == M_SYNC && syncStatus().running) return String("running");
        if (i == M_STORAGE) return String(storageStats().pending);
        return String("");
      });
      break;
    case SCR_SETTINGS:
      drawList("Settings", S_COUNT + 1, setSel, setTop,
               [](int i) { return i == S_COUNT ? String("Back") : String(SET_LABEL[i]); },
               [](int i) { return i == S_COUNT ? String("") : setFmt(i, setGet(i)); });
      break;
    case SCR_EDIT: drawEdit(); break;
    case SCR_PAIR: drawPair(); break;
    case SCR_PORTAL: drawPortal(); break;
    case SCR_STORAGE: drawStorage(); break;
    case SCR_ABOUT: drawAbout(); break;
    case SCR_CONFIRM: drawConfirm(); break;
  }
  if (toast[0] && millis() < toastUntil) {
    cv->fillRect(0, H - 24, W, 24, C_PRIMARY);
    txt(6, H - 7, toast, C_BG, &FreeSansBold9pt7b);
  }
  tft.drawRGBBitmap(0, 0, cv->getBuffer(), W, H);
}

// ── Screen power ────────────────────────────────────────────────────────────
static void screenSetOn(bool on) {
  if (on == screenOn) return;
  screenOn = on;
  if (on) {
    tft.enableSleep(false);
    render();                     // draw first, so the backlight never shows a stale frame
    powerSetBacklight(cfg.brightness);
  } else {
    powerSetBacklight(0);
    tft.enableSleep(true);
    scr = SCR_STATUS;             // like a 3D printer: idle returns to the status screen
    statusPage = 0;
  }
}

void uiWake() {
  lastActivityMs = millis();
  screenSetOn(true);
}

void uiWakeFromButton() {
  // Light sleep woke us on a button level. Whatever is held now is the waking
  // press: mark it consumed so releasing it doesn't also act on the screen.
  uint32_t now = millis();
  for (Btn& b : btns) {
    bool r = digitalRead(b.pin) == (b.activeHigh ? HIGH : LOW);
    if (r) { b.raw = b.stable = true; b.swallow = true; b.downAt = b.changedAt = now; }
  }
  uiWake();
}

bool uiScreenOn() { return screenOn; }

bool uiWantsAwake() {
  return scr == SCR_PAIR || scr == SCR_PORTAL || netPortalActive();
}

void uiApplyDisplaySettings() {
  tft.setRotation(cfg.flip ? 1 : 3);
  if (screenOn) powerSetBacklight(cfg.brightness);
}

void uiBootMessage(const char* l1, const char* l2) {
  cv->fillScreen(C_BG);
  txt(6, 30, "Roamly", C_PRIMARY, &FreeSansBold18pt7b);
  txt(6, 70, l1, C_TEXT);
  txt(6, 92, l2, C_MUTED);
  tft.drawRGBBitmap(0, 0, cv->getBuffer(), W, H);
  powerSetBacklight(cfg.brightness);
  screenOn = true;
  lastActivityMs = millis();
}

void uiBegin() {
  pinMode(PIN_BTN_UP, INPUT_PULLUP);
  pinMode(PIN_BTN_SELECT, INPUT_PULLDOWN);
  pinMode(PIN_BTN_DOWN, INPUT_PULLDOWN);
  tft.init(135, 240);
  tft.setSPISpeed(40000000);
  tft.setRotation(cfg.flip ? 1 : 3);
  cv = new GFXcanvas16(W, H);
  cv->setTextWrap(false);
  tft.fillScreen(C_BG);
}

// ── Input handling ──────────────────────────────────────────────────────────
static void enterScreen(Scr s) {
  scr = s;
  needFull = true;
}

static void onMenuSelect() {
  switch (menuSel) {
    case M_SYNC:
      if (netStartSync(true)) showToast("Sync started");
      else showToast(syncStatus().running ? "Already syncing" : syncStatus().msg);
      enterScreen(SCR_STATUS);
      statusPage = 3;
      break;
    case M_PAIR:
      pairLen = 0;
      pairBuf[0] = 0;
      enterScreen(SCR_PAIR);
      break;
    case M_WIFI:
      netPortalStart();
      enterScreen(SCR_PORTAL);
      break;
    case M_SETTINGS: setSel = 0; setTop = 0; enterScreen(SCR_SETTINGS); break;
    case M_RECORD:
      cfg.recording = !cfg.recording;
      settingsSave();
      appSettingChanged(-1);
      showToast(cfg.recording ? "Recording" : "Paused");
      break;
    case M_STORAGE: storSel = 0; enterScreen(SCR_STORAGE); break;
    case M_ABOUT: enterScreen(SCR_ABOUT); break;
    case M_RESTART: confirmWhat = 3; enterScreen(SCR_CONFIRM); break;
    case M_BACK: enterScreen(SCR_STATUS); break;
  }
}

static void handle(Ev e) {
  switch (scr) {
    case SCR_STATUS:
      if (e == EV_UP) statusPage = (statusPage + STATUS_PAGES - 1) % STATUS_PAGES;
      else if (e == EV_DOWN) statusPage = (statusPage + 1) % STATUS_PAGES;
      else if (e == EV_SEL) { menuSel = 0; menuTop = 0; enterScreen(SCR_MENU); }
      break;
    case SCR_MENU:
      if (e == EV_UP || e == EV_UP_REP) menuSel = (menuSel + M_COUNT - 1) % M_COUNT;
      else if (e == EV_DOWN || e == EV_DOWN_REP) menuSel = (menuSel + 1) % M_COUNT;
      else if (e == EV_SEL) onMenuSelect();
      else if (e == EV_SEL_LONG) enterScreen(SCR_STATUS);
      break;
    case SCR_SETTINGS:
      if (e == EV_UP || e == EV_UP_REP) setSel = (setSel + S_COUNT) % (S_COUNT + 1);
      else if (e == EV_DOWN || e == EV_DOWN_REP) setSel = (setSel + 1) % (S_COUNT + 1);
      else if (e == EV_SEL) {
        if (setSel == S_COUNT) { enterScreen(SCR_MENU); break; }
        int vals[64];
        int n = setValues(setSel, vals);
        int cur = setGet(setSel);
        editIdx = 0;
        for (int i = 0; i < n; i++) if (vals[i] == cur) editIdx = i;
        enterScreen(SCR_EDIT);
      } else if (e == EV_SEL_LONG) enterScreen(SCR_MENU);
      break;
    case SCR_EDIT: {
      int vals[64];
      int n = setValues(setSel, vals);
      if (e == EV_UP || e == EV_UP_REP) editIdx = max(0, editIdx - 1);
      else if (e == EV_DOWN || e == EV_DOWN_REP) editIdx = min(n - 1, editIdx + 1);
      else if (e == EV_SEL) { setPut(setSel, vals[editIdx]); showToast("Saved"); enterScreen(SCR_SETTINGS); }
      else if (e == EV_SEL_LONG) enterScreen(SCR_SETTINGS);
      break;
    }
    case SCR_PAIR: {
      PairState ps = pairState();
      if (ps == PAIR_RUNNING) break;
      if (ps == PAIR_OK || ps == PAIR_FAIL) {
        extern void pairAcknowledge();
        pairAcknowledge();
        enterScreen(ps == PAIR_OK ? SCR_STATUS : SCR_PAIR);
        pairLen = 0;
        break;
      }
      if (!cfg.server[0] || !cfg.wifiCount) {
        // Pairing needs Wi-Fi: a press goes straight to setting it up.
        if (e == EV_SEL) { netPortalStart(); enterScreen(SCR_PORTAL); }
        else if (e == EV_SEL_LONG) enterScreen(SCR_MENU);
        break;
      }
      if (e == EV_SEL_LONG) {
        if (pairLen == 0) enterScreen(SCR_MENU);
        else pairBuf[--pairLen] = 0;
        break;
      }
      char sym = e == EV_UP ? 'T' : e == EV_SEL ? 'M' : e == EV_DOWN ? 'B' : 0;
      if (sym && pairLen < 8) {
        pairBuf[pairLen++] = sym;
        pairBuf[pairLen] = 0;
        if (pairLen == 8) netStartPair(pairBuf);
      }
      break;
    }
    case SCR_PORTAL:
      if (e == EV_SEL_LONG) { netPortalStop(); showToast("Hotspot off"); enterScreen(SCR_MENU); }
      break;
    case SCR_STORAGE:
      if (e == EV_UP) storSel = (storSel + 2) % 3;
      else if (e == EV_DOWN) storSel = (storSel + 1) % 3;
      else if (e == EV_SEL_LONG || (e == EV_SEL && storSel == 0)) enterScreen(SCR_MENU);
      else if (e == EV_SEL) { confirmWhat = storSel == 2 ? 1 : 2; enterScreen(SCR_CONFIRM); }
      break;
    case SCR_ABOUT:
      if (e == EV_SEL || e == EV_SEL_LONG) enterScreen(SCR_MENU);
      break;
    case SCR_CONFIRM:
      if (e == EV_SEL) {
        if (confirmWhat == 1) { storageEraseAll(); showToast("Erased"); }
        else if (confirmWhat == 2) { storageClearQuarantine(); showToast("Cleared"); }
        else if (confirmWhat == 3) { uiBootMessage("Restarting..."); delay(300); ESP.restart(); }
        enterScreen(SCR_STORAGE);
      } else if (e == EV_UP || e == EV_DOWN || e == EV_SEL_LONG) enterScreen(confirmWhat == 3 ? SCR_MENU : SCR_STORAGE);
      break;
  }
}

static Ev pollButtons() {
  uint32_t now = millis();
  Ev out = EV_NONE;
  for (int i = 0; i < 3; i++) {
    Btn& b = btns[i];
    bool r = digitalRead(b.pin) == (b.activeHigh ? HIGH : LOW);
    if (r != b.raw) { b.raw = r; b.changedAt = now; }
    if (r != b.stable && now - b.changedAt >= 25) {
      b.stable = r;
      if (r) {   // pressed
        b.downAt = now;
        b.longDone = false;
        b.lastRep = now;
        lastActivityMs = now;
        if (!screenOn) { b.swallow = true; uiWake(); }   // the waking press does nothing else
      } else {   // released
        lastActivityMs = now;
        if (!b.swallow && !b.longDone) out = i == 0 ? EV_UP : i == 1 ? EV_SEL : EV_DOWN;
        b.swallow = false;
      }
    }
    if (b.stable && !b.swallow) {
      uint32_t held = now - b.downAt;
      if (!b.longDone && held >= 700) {
        b.longDone = true;
        b.lastRep = now;
        out = i == 0 ? EV_UP_LONG : i == 1 ? EV_SEL_LONG : EV_DOWN_LONG;
        if (i != 1) out = i == 0 ? EV_UP_REP : EV_DOWN_REP;   // ▲/▼ held = auto-repeat
        lastActivityMs = now;
      } else if (b.longDone && i != 1 && now - b.lastRep >= 140) {
        b.lastRep = now;
        out = i == 0 ? EV_UP_REP : EV_DOWN_REP;
        lastActivityMs = now;
      }
    }
  }
  return out;
}

// Console hooks: render the current screen to the serial port (the host turns
// it into a PNG) and inject button events, so the UI can be checked and driven
// without looking at the hardware.
void uiScreenshot(Stream& out) {
  render();
  const uint16_t* px = cv->getBuffer();
  out.printf("SHOT %d %d\n", W, H);
  static const char* hex = "0123456789abcdef";
  char row[W * 4 + 1];
  for (int y = 0; y < H; y++) {
    for (int x = 0; x < W; x++) {
      uint16_t v = px[y * W + x];
      row[x * 4] = hex[v >> 12]; row[x * 4 + 1] = hex[(v >> 8) & 15];
      row[x * 4 + 2] = hex[(v >> 4) & 15]; row[x * 4 + 3] = hex[v & 15];
    }
    row[W * 4] = 0;
    out.println(row);
  }
  out.println("END");
}

void uiInject(const char* name) {
  Ev e = EV_NONE;
  if (!strcmp(name, "up")) e = EV_UP;
  else if (!strcmp(name, "sel")) e = EV_SEL;
  else if (!strcmp(name, "down")) e = EV_DOWN;
  else if (!strcmp(name, "back")) e = EV_SEL_LONG;
  uiWake();
  if (e != EV_NONE) { handle(e); needFull = true; }
}

void uiPoll() {
  static uint32_t lastRender = 0;
  Ev e = pollButtons();
  if (e != EV_NONE && screenOn) { handle(e); needFull = true; }
  uint32_t timeout = (uint32_t)cfg.screenTimeoutS * 1000;
  if (scr == SCR_PAIR || scr == SCR_EDIT) timeout = max<uint32_t>(timeout, 60000);
  if (scr == SCR_PORTAL) timeout = max<uint32_t>(timeout, 120000);
  if (pairState() == PAIR_RUNNING) lastActivityMs = millis();
  if (screenOn && millis() - lastActivityMs > timeout) {
    if (scr == SCR_PORTAL) {}   // the hotspot keeps running with the screen off
    screenSetOn(false);
    return;
  }
  if (screenOn && (needFull || millis() - lastRender >= 500)) {
    render();
    lastRender = millis();
    needFull = false;
  }
}
