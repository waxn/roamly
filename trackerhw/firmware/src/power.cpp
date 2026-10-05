#include "power.h"
#include "config.h"
#include "settings.h"
#include <Wire.h>
#include <Adafruit_MAX1704X.h>
#include <Adafruit_LC709203F.h>
#include <driver/gpio.h>
#include <esp_sleep.h>

static Adafruit_MAX17048 maxg;
static Adafruit_LC709203F lcg;
static bool isMax = false, isLc = false;
static PowerInfo pi;
static uint32_t lastPollMs = 0;
static int extVotes = 0;

// SOC history for our own drain/charge rate: one sample per 2 minutes, 3 h.
static constexpr int HIST = 90;
static constexpr uint32_t HIST_EVERY_MS = 120000;
static uint32_t histT[HIST];
static float histP[HIST];
static int histN = 0, histHead = 0;
static uint32_t lastHistMs = 0;
static PowerState histState = PWR_UNKNOWN;

bool powerBegin() {
  pinMode(TFT_I2C_POWER, OUTPUT);
  digitalWrite(TFT_I2C_POWER, HIGH);
  pinMode(NEOPIXEL_POWER, OUTPUT);
  digitalWrite(NEOPIXEL_POWER, LOW);   // the NeoPixel is unused: keep it unpowered
  delay(10);
  Wire.begin();
  if (maxg.begin(&Wire)) {
    isMax = true;
    pi.gaugeName = "MAX17048";
  } else if (lcg.begin(&Wire)) {
    isLc = true;
    pi.gaugeName = "LC709203F";
    lcg.setPackSize(cfg.battMah >= 1500 ? LC709203F_APA_2000MAH : LC709203F_APA_1000MAH);
  }
  pi.gauge = isMax || isLc;
  ledcAttach(TFT_BACKLITE, 5000, 8);
  powerPoll(true);
  return pi.gauge;
}

float powerModelCurrentMa() {
  // Rough board-level averages per GPS mode (NEO-6M + ESP32-S3 light-sleeping
  // between points + board LEDs/regulators). Only used until enough SOC
  // history exists to measure the real rate.
  switch (cfg.gpsMode) {
    case GPS_ACCURATE: return 48;
    case GPS_LOWPOWER: return 12;
    default:           return 20;
  }
}

static void addHistory(uint32_t now, float pct) {
  histT[histHead] = now;
  histP[histHead] = pct;
  histHead = (histHead + 1) % HIST;
  if (histN < HIST) histN++;
}

// Least-squares slope (%/h) over samples in the last `windowMs`.
static float historySlope(uint32_t now, uint32_t windowMs, uint32_t& span) {
  double sx = 0, sy = 0, sxx = 0, sxy = 0;
  int n = 0;
  uint32_t oldest = now;
  for (int i = 0; i < histN; i++) {
    uint32_t t = histT[i];
    if (now - t > windowMs) continue;
    double x = (double)(int32_t)(t - now) / 3600000.0;
    double y = histP[i];
    sx += x; sy += y; sxx += x * x; sxy += x * y; n++;
    if ((int32_t)(t - oldest) < 0) oldest = t;
  }
  span = now - oldest;
  if (n < 5) return NAN;
  double den = n * sxx - sx * sx;
  if (fabs(den) < 1e-12) return NAN;
  return (float)((n * sxy - sx * sy) / den);
}

void powerPoll(bool force) {
  uint32_t now = millis();
  pi.usbHost = HWCDC::isPlugged();
  if (!force && now - lastPollMs < GAUGE_POLL_MS) return;
  lastPollMs = now;
  if (isMax) {
    pi.voltage = maxg.cellVoltage();
    pi.percent = maxg.cellPercent();
    pi.gaugeRate = maxg.chargeRate();
  } else if (isLc) {
    pi.voltage = lcg.cellVoltage();
    pi.percent = lcg.cellPercent();
    pi.gaugeRate = NAN;
  }
  // With no cell attached the charger's output floats and the gauge reports
  // nonsense (often >100% or <2.5 V). Treat that as "no battery".
  pi.batteryPresent = pi.gauge && pi.voltage > 2.8f && pi.voltage < 4.45f && pi.percent >= 0 && pi.percent < 125;
  if (pi.batteryPresent) pi.percent = constrain(pi.percent, 0.0f, 100.0f);

  // External power: a USB host proves it outright. Otherwise infer it from
  // the gauge — rising charge, or sitting at a full cell's voltage without
  // draining. Two consecutive votes so one noisy CRATE reading can't start
  // a Wi-Fi sync in a pocket.
  bool vote = false;
  if (pi.batteryPresent) {
    float r = isnan(pi.gaugeRate) ? 0 : pi.gaugeRate;
    vote = r > 0.6f || (pi.voltage >= 4.16f && r > -0.4f);
  }
  extVotes = vote ? min(extVotes + 1, 3) : 0;
  pi.external = pi.usbHost || extVotes >= 2 || !pi.batteryPresent;

  PowerState st;
  if (!pi.batteryPresent) st = pi.external ? PWR_FULL : PWR_UNKNOWN;
  else if (!pi.external) st = PWR_BATTERY;
  else st = (pi.percent >= 99.0f && (isnan(pi.gaugeRate) || pi.gaugeRate < 1.0f)) ? PWR_FULL : PWR_CHARGING;
  if (st != histState) { histN = 0; histHead = 0; histState = st; }   // rate restarts on a state change
  pi.state = st;

  if (pi.batteryPresent && (now - lastHistMs >= HIST_EVERY_MS || histN == 0)) {
    lastHistMs = now;
    addHistory(now, pi.percent);
  }

  uint32_t span = 0;
  pi.measuredRate = historySlope(now, 3UL * 3600 * 1000, span);
  bool measuredOk = !isnan(pi.measuredRate) && span >= 30UL * 60 * 1000;
  pi.estimateMeasured = false;
  pi.hoursLeft = NAN;
  if (!pi.batteryPresent) return;
  if (st == PWR_BATTERY) {
    float rate = NAN;
    if (measuredOk && pi.measuredRate < -0.05f) { rate = -pi.measuredRate; pi.estimateMeasured = true; }
    else rate = powerModelCurrentMa() / cfg.battMah * 100.0f;   // %/h
    pi.hoursLeft = pi.percent / rate;
  } else if (st == PWR_CHARGING) {
    float rate = NAN;
    if (measuredOk && pi.measuredRate > 0.5f) { rate = pi.measuredRate; pi.estimateMeasured = true; }
    else if (!isnan(pi.gaugeRate) && pi.gaugeRate > 1.0f) rate = pi.gaugeRate;
    else rate = 200.0f / cfg.battMah * 100.0f * 0.85f;   // ~200 mA charger, tapering
    pi.hoursLeft = (100.0f - pi.percent) / rate;
  }
}

const PowerInfo& powerInfo() { return pi; }

uint8_t batteryPercentByte() {
  return pi.batteryPresent ? (uint8_t)lroundf(pi.percent) : 0xFF;
}

void powerSetBacklight(uint8_t pct) {
  ledcWrite(TFT_BACKLITE, pct ? map(constrain(pct, 1, 100), 1, 100, 8, 255) : 0);
}

WakeCause powerLightSleep(uint32_t ms) {
  // Keep the buttons, display/I2C power and GPS UART pins in their run-time
  // configuration across sleep; otherwise sleep-select would float them.
  const gpio_num_t keep[] = {GPIO_NUM_0, GPIO_NUM_1, GPIO_NUM_2, (gpio_num_t)TFT_I2C_POWER,
                             (gpio_num_t)TFT_BACKLITE, (gpio_num_t)NEOPIXEL_POWER};
  for (auto g : keep) gpio_sleep_sel_dis(g);
  gpio_wakeup_enable(GPIO_NUM_0, GPIO_INTR_LOW_LEVEL);
  gpio_wakeup_enable(GPIO_NUM_1, GPIO_INTR_HIGH_LEVEL);
  gpio_wakeup_enable(GPIO_NUM_2, GPIO_INTR_HIGH_LEVEL);
  esp_sleep_enable_gpio_wakeup();
  esp_sleep_enable_timer_wakeup((uint64_t)ms * 1000ULL);
  esp_light_sleep_start();
  esp_sleep_wakeup_cause_t c = esp_sleep_get_wakeup_cause();
  gpio_wakeup_disable(GPIO_NUM_0);
  gpio_wakeup_disable(GPIO_NUM_1);
  gpio_wakeup_disable(GPIO_NUM_2);
  esp_sleep_disable_wakeup_source(ESP_SLEEP_WAKEUP_ALL);
  if (c == ESP_SLEEP_WAKEUP_TIMER) return WAKE_TIMER;
  if (c == ESP_SLEEP_WAKEUP_GPIO) return WAKE_BUTTON;
  return WAKE_OTHER;
}
