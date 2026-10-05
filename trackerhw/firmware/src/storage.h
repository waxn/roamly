// LittleFS point log.
//
// Points are appended to segment files (/p/<id>.bin, SEGMENT_RECORDS each).
// Upload walks them oldest-first; /p/cursor records how far into the oldest
// segment the server has confirmed. A point leaves flash only via
// storageCommit(), which the uploader calls with the server's confirmation —
// never on the strength of having been *sent*.
#pragma once
#include <Arduino.h>

struct __attribute__((packed)) PointRec {
  uint32_t seq;
  uint32_t t;         // unix seconds, UTC
  int32_t  latE7;
  int32_t  lonE7;
  int32_t  altCm;     // INT32_MIN = unknown
  uint16_t accDm;     // horizontal accuracy, decimetres; 0xFFFF = unknown
  uint16_t spdCms;    // cm/s; 0xFFFF = unknown
  uint16_t crsCdeg;   // course, centidegrees; 0xFFFF = unknown
  uint8_t  sats;
  uint8_t  hdopX10;
  uint8_t  batt;      // %; 0xFF = unknown
  uint8_t  flags;     // bit0 = 3D fix
  uint16_t crc;       // CRC-16/CCITT over the preceding 30 bytes
};
static_assert(sizeof(PointRec) == 32, "PointRec must stay 32 bytes");

struct StorageStats {
  uint32_t pending;      // stored, not yet confirmed by the server
  uint32_t quarantined;  // refused by the server, kept in /quarantine.bin
  uint32_t corrupt;      // records that failed CRC (skipped, counted)
  uint32_t segments;
  uint32_t freeKb;
  uint32_t totalKb;
};

bool storageBegin();
bool storageAppend(PointRec& r);          // fills crc
StorageStats storageStats();

// Upload interface. storagePeek fills up to `max` records starting at the
// cursor, skipping corrupt ones; it returns how many it filled and sets
// `consumed` to how many *slots* that covered (corrupt ones included), which is
// what storageCommit advances by.
int  storagePeek(PointRec* out, int max, int& consumed);
bool storageCommit(int consumed, const PointRec* rejected, int nRejected);

bool storageEraseAll();
bool storageClearQuarantine();
void storageDump(Stream& s, int maxRows);
uint16_t crc16(const uint8_t* d, size_t n);
