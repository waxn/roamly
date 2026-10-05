// Display + buttons. ▲ (D0) / ● (D1) / ▼ (D2); hold ● = back.
#pragma once
#include <Arduino.h>

void uiBegin();
void uiPoll();
bool uiScreenOn();
void uiWake();
void uiWakeFromButton();
void uiBootMessage(const char* line1, const char* line2 = "");
void uiApplyDisplaySettings();
bool uiWantsAwake();
void uiScreenshot(Stream& out);
void uiInject(const char* name);   // up / sel / down / back     // a screen that must not be slept through (pairing, hotspot)

enum SetId { S_TIMEOUT, S_INTERVAL, S_GPSMODE, S_MINACC, S_UNITS, S_CLOCK, S_TZ, S_BRIGHT, S_FLIP, S_AUTOSYNC, S_BATT, S_COUNT };
static constexpr int S_RECORDING = -1;

// Implemented by main.cpp — applying a changed setting needs the GPS/logger.
void appSettingChanged(int id);
void applyTimezone();
