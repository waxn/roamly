// Bluetooth LE link to the Roamly phone app: presence beacon, settings,
// diagnostics, console, and the phone/tracker recording hand-off.
#pragma once
#include <Arduino.h>

// GATT layout (all characteristics require an encrypted, authenticated bond):
//   service  7f6c0001-8a2b-4c3d-9e5f-726f616d6c79
//   status   ...0002  read / notify   JSON snapshot (see bleStatusJson)
//   settings ...0003  read / write    JSON object of settings; write {"key":value,...}
//   command  ...0004  write / notify  {"cmd":"sync|pause|resume|lease|poweroff|restart|forget"}
//   console  ...0005  write / notify  a console command line; output streamed back, 0x04 ends it
#define ROAMLY_BLE_SERVICE   "7f6c0001-8a2b-4c3d-9e5f-726f616d6c79"
#define ROAMLY_BLE_STATUS    "7f6c0002-8a2b-4c3d-9e5f-726f616d6c79"
#define ROAMLY_BLE_SETTINGS  "7f6c0003-8a2b-4c3d-9e5f-726f616d6c79"
#define ROAMLY_BLE_COMMAND   "7f6c0004-8a2b-4c3d-9e5f-726f616d6c79"
#define ROAMLY_BLE_CONSOLE   "7f6c0005-8a2b-4c3d-9e5f-726f616d6c79"

// Advertisement manufacturer data (company id 0xFFFF, "testing/unassigned"):
//   [0] version (1)
//   [1] flags: bit0 recording right now, bit1 handoff=tracker records when together,
//              bit2 charging, bit3 has a fix, bit4 paused because the phone holds
//              the lease, bit5 paused by the user, bit6 pairing mode
//   [2] battery % (0xFF unknown)
//   [3] advert sequence (low byte), changes every window
//   [4] advertising interval in tens of seconds (the phone scales its "gone" timeout)

void bleTick();                  // call from the main loop
bool bleActive();                // radio in use: don't light-sleep
uint32_t bleNextWakeMs();        // millis() of the next advertising window (0 = none)
bool bleHandoffPaused();         // phone holds a valid lease and is recording instead
uint32_t bleLeaseRemainingS();
bool bleConnected();
uint8_t bleBondCount();
void bleEnterPairing();          // accept a new phone for 2 minutes (shows the passkey)
void bleCancelPairing();
bool blePairing();
uint32_t blePasskey();           // passkey to show while pairing, 0 = none
void bleForgetPhones();
const char* bleStateText();      // "off", "sleeping", "advertising", "connected"
