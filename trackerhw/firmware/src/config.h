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

// ── GPS: Seeed L76K GNSS module for XIAO ────────────────────────────────────
// Module pad -> Feather pin (see trackerhw/README.md):
//   D7 (GNSS TX)  -> RX  GPIO38      D6 (GNSS RX) -> TX  GPIO39
//   D0 (STANDBY)  -> A0  GPIO18      D2 (RESET)   -> A1  GPIO17
// STANDBY and RESET must be RTC-capable pins (GPIO0-21) so power-off can hold
// them through deep sleep. gps.cpp auto-detects the UART pins and baud rate if
// these don't answer, and remembers what it found.
static constexpr int PIN_GPS_RX_DEFAULT = 38;   // ESP receives (module D7)
static constexpr int PIN_GPS_TX_DEFAULT = 39;   // ESP transmits (module D6)
static constexpr int PIN_GPS_STANDBY    = 18;   // high = running, low = standby
static constexpr int PIN_GPS_RESET      = 17;   // active low

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
// 0.8 m/s matches the phone app's DriftAnchor: a stationary receiver's Doppler
// jitters up to ~0.65 m/s (measured on the NEO-6M), while even a slow walk holds above ~1 m/s.
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

// ── Stationary anchor (anchor.h) ────────────────────────────────────────────
// Windows in a row that read still (every reading under STILL_MPS), within
// ENTER_M of each other, before the position is pinned. While pinned, still windows store the anchor; a window
// whose slowest Doppler reading clears STILL_MPS releases it, as do
// BREAK_WINDOWS windows in a row more than BREAK_M away (the receiver's own
// jumps reached 60 m on the bench). A gap longer than MAX_GAP_S drops it.
// STILL_MPS is tighter than ADAPT_STILL_MPS (sized for the NEO-6M's noisy
// Doppler): the L76K reads 0.00-0.15 m/s at rest, even mid-jump, and a slow
// amble at ~0.5 m/s must neither form an anchor nor stay stuck to one.
static constexpr float    ANCHOR_STILL_MPS     = 0.3f;
static constexpr uint8_t  ANCHOR_ENTER_WINDOWS = 3;
static constexpr float    ANCHOR_ENTER_M       = 30.0f;
// The anchor keeps averaging fixes within REFINE_M of it, the newest weighted
// at least 1/REFINE_N, so it settles onto the true spot. REFINE_M is tighter
// than ENTER_M: on the device a fresh anchor (few fixes in its mean) followed
// a slow 30 m slide-back most of the way at 30 m.
static constexpr float    ANCHOR_REFINE_M      = 10.0f;
static constexpr uint16_t ANCHOR_REFINE_N      = 60;
static constexpr float    ANCHOR_BREAK_M       = 200.0f;
static constexpr uint8_t  ANCHOR_BREAK_WINDOWS = 2;
static constexpr uint32_t ANCHOR_MAX_GAP_S     = 600;

// ── Storage ─────────────────────────────────────────────────────────────────
static constexpr int SEGMENT_RECORDS = 1024;   // 32 KB per segment file
