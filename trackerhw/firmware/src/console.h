#pragma once
#include <Arduino.h>
void consolePoll();
// Run one console command, writing its output to `out` (USB serial, or a BLE
// connection's buffer). Shared so the app gets exactly the commands the serial
// console has.
void consoleExec(String cmd, Print& out);
// Apply a setting by console name ("interval", "mode", ...); false if unknown.
bool consoleSetKey(const String& key, const String& value);
