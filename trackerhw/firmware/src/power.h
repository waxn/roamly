// Fuel gauge, charge detection, battery estimates, light sleep.
#pragma once
#include <Arduino.h>

enum PowerState : uint8_t { PWR_BATTERY = 0, PWR_CHARGING = 1, PWR_FULL = 2, PWR_UNKNOWN = 3 };

struct PowerInfo {
  bool     gauge = false;        // fuel gauge present
  const char* gaugeName = "none";
  bool     batteryPresent = false;
  float    voltage = NAN;
  float    percent = NAN;
  float    gaugeRate = NAN;      // %/h from the gauge (MAX17048 CRATE)
  float    measuredRate = NAN;   // %/h from our own SOC history (regression)
  float    hoursLeft = NAN;      // to empty (battery) or to full (charging)
  bool     estimateMeasured = false;
  bool     usbHost = false;      // a USB host is enumerating us
  bool     external = false;     // on external power (USB host or charger)
  PowerState state = PWR_UNKNOWN;
};

bool powerBegin();
void powerPoll(bool force = false);
const PowerInfo& powerInfo();
uint8_t batteryPercentByte();   // 0..100, or 0xFF unknown

enum WakeCause : uint8_t { WAKE_TIMER, WAKE_BUTTON, WAKE_OTHER };
WakeCause powerLightSleep(uint32_t ms);
void powerSetBacklight(uint8_t pct);   // 0 = off
float powerModelCurrentMa();
