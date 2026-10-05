// Wi-Fi, upload to Roamly, pairing, and the setup hotspot.
#pragma once
#include <Arduino.h>

struct SyncStatus {
  volatile bool running = false;
  volatile uint32_t sent = 0;        // points confirmed this run
  volatile uint32_t total = 0;       // pending at start of run
  uint32_t lastAttemptMs = 0;
  uint32_t lastOkUnix = 0;           // wall-clock time of last fully successful sync
  bool     lastOk = false;
  uint8_t  fails = 0;                // consecutive failed runs (drives backoff)
  bool     unpaired = false;         // server said 401: key revoked
  char     msg[64] = "never";
  int      rssi = 0;
};

enum PairState : uint8_t { PAIR_IDLE, PAIR_RUNNING, PAIR_OK, PAIR_FAIL };

void netBegin();
const char* hwId();                  // 12 upper-hex chars (Wi-Fi MAC)
bool netStartSync(bool manual);      // false if one is already running / impossible
SyncStatus& syncStatus();
bool netCanSync(char* why, size_t n);

void netStartPair(const char* code);
PairState pairState();
const char* pairMessage();

bool netPortalStart();
void netPortalStop();
void netPortalPoll();
bool netPortalActive();
const char* portalSsid();
const char* portalPass();
