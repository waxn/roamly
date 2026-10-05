// Point logger: every interval, collect fixes for a short window and store the best one.
#pragma once
#include <Arduino.h>
#include "storage.h"

struct LoggerState {
  bool     collecting = false;
  uint32_t windowStartMs = 0;
  uint32_t nextDueMs = 0;
  bool     haveLast = false;
  PointRec last{};             // last stored point
  uint32_t lastAtMs = 0;       // millis() when it was stored
  uint32_t windows = 0, recorded = 0, missed = 0, filtered = 0, writeErrors = 0;
  uint32_t lastWindowMs = 0;   // how long the last window took
  uint8_t  consecutiveMisses = 0;
};

void loggerTick();
LoggerState& loggerState();
void loggerReschedule();       // after an interval/mode change: start a window now
