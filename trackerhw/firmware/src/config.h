// Roamly tracker — compile-time constants and pin map.
#pragma once
#include <Arduino.h>

#define FW_VERSION "0.1.0"
#define HW_MODEL   "feather-s3-revtft+neo6m"

// ── Buttons (Feather ESP32-S3 Reverse TFT) ──────────────────────────────────
// D0 is the BOOT button: pulled up, LOW when pressed. D1/D2 are pulled down,
// HIGH when pressed. Physically top-to-bottom: D0, D1, D2.
static constexpr int PIN_BTN_UP     = 0;   // D0  ▲
static constexpr int PIN_BTN_SELECT = 1;   // D1  ●
static constexpr int PIN_BTN_DOWN   = 2;   // D2  ▼

// ── GPS UART ────────────────────────────────────────────────────────────────
// Default wiring: GPS TX -> Feather RX (GPIO38), GPS RX -> Feather TX (GPIO39).
// gps.cpp auto-detects a swapped pair and the baud rate, and remembers it.
static constexpr int PIN_GPS_RX_DEFAULT = 38;
static constexpr int PIN_GPS_TX_DEFAULT = 39;

// ── Timing ──────────────────────────────────────────────────────────────────
static constexpr uint32_t FIX_WINDOW_MS        = 12000;  // how long to collect candidates per point
static constexpr float    GOOD_ENOUGH_ACC_M    = 8.0f;   // stop collecting early at/below this
static constexpr uint32_t GAUGE_POLL_MS        = 10000;
static constexpr uint32_t SYNC_RETRY_MS[]      = {120000, 300000, 900000};
static constexpr uint32_t SYNC_REPEAT_MS       = 15UL * 60 * 1000;  // while charging
static constexpr int      UPLOAD_BATCH         = 100;
static constexpr uint32_t WIFI_CONNECT_MS      = 20000;
static constexpr uint32_t HTTP_TIMEOUT_MS      = 20000;
static constexpr uint32_t PORTAL_TIMEOUT_MS    = 15UL * 60 * 1000;

// ── Adaptive interval (Log interval = Adaptive) ─────────────────────────────
// Doppler speed classes: below STILL is standing still, below FAST is walking
// or jogging, at/above FAST is cycling or a vehicle (~14 km/h).
// 0.8 m/s matches the phone app's DriftAnchor: a stationary NEO-6M's Doppler
// jitters up to ~0.65 m/s, while even a slow walk holds above ~1 m/s.
static constexpr float    ADAPT_STILL_MPS   = 0.8f;
static constexpr float    ADAPT_FAST_MPS    = 4.0f;
static constexpr uint32_t ADAPT_STILL_S     = 60;
static constexpr uint32_t ADAPT_SLOW_S      = 30;
static constexpr uint32_t ADAPT_FAST_S      = 10;
// While still, Accurate/Balanced peek this often for movement (one fix, not
// stored unless moving) so a start isn't noticed up to a minute late.
static constexpr uint32_t ADAPT_PEEK_S      = 20;
// A "still" reading this far from the last stored point counts as moving
// (catches slow walking whose Doppler reads under ADAPT_STILL_MPS).
static constexpr float    ADAPT_MOVE_M      = 40.0f;

// ── Storage ─────────────────────────────────────────────────────────────────
static constexpr int SEGMENT_RECORDS = 1024;   // 32 KB per segment file
