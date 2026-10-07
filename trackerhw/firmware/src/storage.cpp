#include "storage.h"
#include "config.h"
#include <LittleFS.h>
#include <freertos/semphr.h>

// The logger (main loop) appends while the uploader (its own task) reads and
// commits, so every entry point takes this lock.
static SemaphoreHandle_t mtx;
struct Lock {
  Lock() { xSemaphoreTake(mtx, portMAX_DELAY); }
  ~Lock() { xSemaphoreGive(mtx); }
};

static uint32_t firstSeg = 0, lastSeg = 0;   // inclusive range; 0 = none
static uint32_t cursor = 0;                  // records confirmed in firstSeg
static uint32_t lastSegCount = 0;            // records in the active segment
static uint32_t pendingCount = 0, quarantineCount = 0, corruptCount = 0;

static String segPath(uint32_t id) {
  char b[24];
  snprintf(b, sizeof b, "/p/%08lu.bin", (unsigned long)id);
  return String(b);
}

uint16_t crc16(const uint8_t* d, size_t n) {
  uint16_t c = 0xFFFF;
  while (n--) {
    c ^= (uint16_t)(*d++) << 8;
    for (int i = 0; i < 8; i++) c = (c & 0x8000) ? (c << 1) ^ 0x1021 : c << 1;
  }
  return c;
}

static bool recValid(const PointRec& r) {
  return crc16((const uint8_t*)&r, sizeof(PointRec) - 2) == r.crc;
}

static uint32_t segRecords(uint32_t id) {
  File f = LittleFS.open(segPath(id), "r");
  if (!f) return 0;
  // A torn tail (power lost mid-append) is ignored rather than truncated:
  // appends always go to a fresh segment after boot, so it is never extended.
  uint32_t n = f.size() / sizeof(PointRec);
  f.close();
  return n;
}

static void saveCursor() {
  File f = LittleFS.open("/p/cursor", "w");
  if (!f) return;
  f.printf("%lu %lu\n", (unsigned long)firstSeg, (unsigned long)cursor);
  f.close();
}

bool storageBegin() {
  mtx = xSemaphoreCreateMutex();
  if (!LittleFS.begin(true, "/littlefs", 10, "spiffs")) return false;
  LittleFS.mkdir("/p");
  Lock l;
  firstSeg = lastSeg = 0;
  File dir = LittleFS.open("/p");
  for (File f = dir.openNextFile(); f; f = dir.openNextFile()) {
    String n = f.name();
    f.close();
    if (!n.endsWith(".bin")) continue;
    uint32_t id = strtoul(n.c_str(), nullptr, 10);
    if (!id) continue;
    if (!firstSeg || id < firstSeg) firstSeg = id;
    if (id > lastSeg) lastSeg = id;
  }
  dir.close();
  cursor = 0;
  File c = LittleFS.open("/p/cursor", "r");
  if (c) {
    unsigned long seg = 0, cur = 0;
    String line = c.readStringUntil('\n');
    c.close();
    if (sscanf(line.c_str(), "%lu %lu", &seg, &cur) == 2 && seg == firstSeg) cursor = cur;
  }
  pendingCount = 0;
  for (uint32_t id = firstSeg; firstSeg && id <= lastSeg; id++) {
    uint32_t n = segRecords(id);
    pendingCount += (id == firstSeg) ? (n > cursor ? n - cursor : 0) : n;
  }
  // Always start appending to a new segment after boot (see segRecords).
  lastSeg = lastSeg ? lastSeg + 1 : 1;
  if (!firstSeg) { firstSeg = lastSeg; cursor = 0; }
  lastSegCount = 0;
  File q = LittleFS.open("/quarantine.bin", "r");
  quarantineCount = q ? q.size() / sizeof(PointRec) : 0;
  if (q) q.close();
  return true;
}

bool storageAppend(PointRec& r) {
  r.crc = crc16((const uint8_t*)&r, sizeof(PointRec) - 2);
  Lock l;
  if (lastSegCount >= (uint32_t)SEGMENT_RECORDS) { lastSeg++; lastSegCount = 0; }
  File f = LittleFS.open(segPath(lastSeg), "a");
  if (!f) return false;
  size_t w = f.write((const uint8_t*)&r, sizeof r);
  f.close();   // close = LittleFS commit; the point is durable from here
  if (w != sizeof r) return false;
  lastSegCount++;
  pendingCount++;
  return true;
}

StorageStats storageStats() {
  Lock l;
  StorageStats s{};
  s.pending = pendingCount;
  s.quarantined = quarantineCount;
  s.corrupt = corruptCount;
  s.segments = lastSeg >= firstSeg ? lastSeg - firstSeg + 1 : 0;
  s.totalKb = LittleFS.totalBytes() / 1024;
  s.freeKb = (LittleFS.totalBytes() - LittleFS.usedBytes()) / 1024;
  return s;
}

// Skip past segments that are fully confirmed (or empty) so the cursor always
// points at something real. Caller holds the lock.
static void normalise() {
  while (firstSeg < lastSeg) {
    uint32_t n = segRecords(firstSeg);
    if (cursor < n) break;
    LittleFS.remove(segPath(firstSeg));
    firstSeg++;
    cursor = 0;
    saveCursor();
  }
}

static uint32_t peekCorrupt = 0;   // corrupt slots in the last peek, counted on commit

int storagePeek(PointRec* out, int max, int& consumed) {
  Lock l;
  normalise();
  // A batch may span segments: every reboot starts a fresh one, and a tracker
  // that rebooted often would otherwise upload a handful of points per request.
  int filled = 0;
  consumed = 0;
  peekCorrupt = 0;
  uint32_t off = cursor;
  for (uint32_t seg = firstSeg; seg <= lastSeg && filled < max; seg++, off = 0) {
    File f = LittleFS.open(segPath(seg), "r");
    if (!f) continue;   // a gap in the ids (empty segment never written)
    uint32_t n = f.size() / sizeof(PointRec);
    if (off < n) {
      f.seek(off * sizeof(PointRec));
      for (; filled < max && off < n; off++) {
        PointRec r;
        if (f.read((uint8_t*)&r, sizeof r) != sizeof r) break;
        consumed++;
        if (recValid(r)) out[filled++] = r;
        else peekCorrupt++;
      }
    }
    f.close();
  }
  return filled;
}

bool storageCommit(int consumed, const PointRec* rejected, int nRejected) {
  Lock l;
  if (nRejected > 0) {
    // Refused points are kept, not dropped: the server rejecting a point is
    // a bug somewhere, and the evidence should survive it.
    File q = LittleFS.open("/quarantine.bin", "a");
    if (!q) return false;   // can't preserve them -> don't advance either
    size_t want = sizeof(PointRec) * nRejected;
    size_t w = q.write((const uint8_t*)rejected, want);
    q.close();
    if (w != want) return false;
    quarantineCount += nRejected;
  }
  corruptCount += peekCorrupt;
  peekCorrupt = 0;
  // Walk the cursor forward over `consumed` slots, retiring each segment it
  // passes — the same path storagePeek read them along.
  uint32_t left = consumed;
  while (left > 0) {
    uint32_t n = segRecords(firstSeg);
    uint32_t avail = n > cursor ? n - cursor : 0;
    uint32_t take = min(avail, left);
    cursor += take;
    left -= take;
    pendingCount = pendingCount > take ? pendingCount - take : 0;
    if (cursor < n) break;
    if (firstSeg < lastSeg) {
      LittleFS.remove(segPath(firstSeg));
      firstSeg++;
      cursor = 0;
    } else {
      break;   // active segment: handled below
    }
  }
  if (firstSeg == lastSeg && cursor >= lastSegCount && lastSegCount > 0) {
    // Everything in the active segment is confirmed: retire it and start fresh.
    LittleFS.remove(segPath(firstSeg));
    lastSeg++;
    firstSeg = lastSeg;
    lastSegCount = 0;
    cursor = 0;
  }
  saveCursor();
  normalise();
  return true;
}

bool storageEraseAll() {
  Lock l;
  for (uint32_t id = firstSeg; id <= lastSeg; id++) LittleFS.remove(segPath(id));
  LittleFS.remove("/p/cursor");
  lastSeg++;
  firstSeg = lastSeg;
  cursor = lastSegCount = pendingCount = 0;
  return true;
}

bool storageClearQuarantine() {
  Lock l;
  LittleFS.remove("/quarantine.bin");
  quarantineCount = 0;
  return true;
}

void storageDump(Stream& s, int maxRows) {
  Lock l;
  s.println("seq,t,lat,lon,alt_m,acc_m,spd_ms,crs,sats,hdop,batt,flags,crc_ok");
  int rows = 0;
  for (uint32_t id = firstSeg; id <= lastSeg && rows < maxRows; id++) {
    File f = LittleFS.open(segPath(id), "r");
    if (!f) continue;
    if (id == firstSeg) f.seek(cursor * sizeof(PointRec));
    PointRec r;
    while (rows < maxRows && f.read((uint8_t*)&r, sizeof r) == sizeof r) {
      s.printf("%lu,%lu,%.7f,%.7f,%.1f,%.1f,%.2f,%.1f,%u,%.1f,%u,%u,%d\n",
               (unsigned long)r.seq, (unsigned long)r.t, r.latE7 / 1e7, r.lonE7 / 1e7,
               r.altCm == INT32_MIN ? NAN : r.altCm / 100.0, r.accDm == 0xFFFF ? NAN : r.accDm / 10.0,
               r.spdCms == 0xFFFF ? NAN : r.spdCms / 100.0, r.crsCdeg == 0xFFFF ? NAN : r.crsCdeg / 100.0,
               r.sats, r.hdopX10 / 10.0, r.batt, r.flags, recValid(r));
      rows++;
    }
    f.close();
  }
}
