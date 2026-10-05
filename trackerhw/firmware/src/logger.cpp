#include "logger.h"
#include "config.h"
#include "settings.h"
#include "gps.h"
#include "power.h"

static LoggerState ls;
static GpsFix best;
static bool haveBest = false;
static uint8_t goodInWindow = 0;
// Slowest Doppler speed seen this window. Movement is judged on this, not on
// one reading: real movement stays above a threshold for seconds, while a
// stationary receiver's Doppler spikes for single epochs (bench: up to 0.65 m/s).
static float winMinSpd = NAN;
// Window start of the last stored point: the still-interval is measured from
// here, on the same clock the windows are scheduled on (measuring from the
// store itself, ~2.5 s later, made every 60 s point land at 80 s).
static uint32_t lastStoreWindowMs = 0;
static uint32_t lastSeenFixAt = 0;

// Low-power mode: wake the receiver this long before a window so it has a
// hot fix ready (u-blox 6 hot start ~1 s; more in a pocket).
static constexpr uint32_t LP_LEAD_MS = 7000;

LoggerState& loggerState() { return ls; }

const char* motionName(Motion m) { return m == MOTION_FAST ? "fast" : m == MOTION_SLOW ? "moving" : "still"; }

static bool adaptive() { return cfg.intervalS == 0; }

uint32_t loggerIntervalS() {
  if (!adaptive()) return cfg.intervalS;
  return ls.motion == MOTION_FAST ? ADAPT_FAST_S : ls.motion == MOTION_SLOW ? ADAPT_SLOW_S : ADAPT_STILL_S;
}

// Gap until the next window: the interval, except while still in a mode where
// the receiver is on anyway — then peek for movement more often than we store.
static uint32_t nextGapS() {
  if (adaptive() && ls.motion == MOTION_STILL && cfg.gpsMode != GPS_LOWPOWER) return ADAPT_PEEK_S;
  return loggerIntervalS();
}

static float distM(double lat1, double lon1, double lat2, double lon2) {
  double dlat = (lat2 - lat1) * DEG_TO_RAD, dlon = (lon2 - lon1) * DEG_TO_RAD;
  double a = sin(dlat / 2) * sin(dlat / 2) + cos(lat1 * DEG_TO_RAD) * cos(lat2 * DEG_TO_RAD) * sin(dlon / 2) * sin(dlon / 2);
  return (float)(6371000.0 * 2 * atan2(sqrt(a), sqrt(1 - a)));
}

static Motion classify(const GpsFix& f) {
  float v = isnan(winMinSpd) ? 0 : winMinSpd;
  Motion m = v >= ADAPT_FAST_MPS ? MOTION_FAST : v >= ADAPT_STILL_MPS ? MOTION_SLOW : MOTION_STILL;
  if (m == MOTION_STILL && ls.haveLast) {
    float d = distM(ls.last.latE7 / 1e7, ls.last.lonE7 / 1e7, f.lat, f.lon);
    float acc = isnan(f.accM) ? 0 : f.accM;
    if (d > max(ADAPT_MOVE_M, 2 * acc)) m = MOTION_SLOW;
  }
  return m;
}

// Speed up at once (a start must not be missed); slow down only after two
// slower readings in a row, so a red light doesn't drop to one point a minute.
static void updateMotion(Motion m) {
  if (m >= ls.motion) { ls.motion = m; ls.slowerStreak = 0; return; }
  if (++ls.slowerStreak >= 2) { ls.motion = m; ls.slowerStreak = 0; }
}

void loggerReschedule() {
  ls.nextDueMs = millis();
  ls.collecting = false;
}

static bool acceptable(const GpsFix& f) {
  if (!f.valid || f.fixType < 2 || f.sats < 4) return false;
  if (!isnan(f.hdop) && f.hdop > 10.0f) return false;
  if (f.unix < 1577836800UL) return false;   // GPS hasn't decoded a real date yet
  // Waking from backup, the NEO-6 can re-emit its last epoch before tracking
  // resumes; bench testing caught one stored as a "new" point one second after
  // the real one. A fix must be newer than the last stored point, and (once
  // the clock is set) current.
  if (ls.haveLast && f.unix <= ls.last.t) return false;
  time_t now = time(nullptr);
  if (gpsInfo().timeValid && llabs((long long)now - (long long)f.unix) > 5) return false;
  return true;
}

static void store(const GpsFix& f) {
  PointRec r{};
  r.seq = nextSeq();
  r.t = f.unix;
  r.latE7 = (int32_t)llround(f.lat * 1e7);
  r.lonE7 = (int32_t)llround(f.lon * 1e7);
  r.altCm = isnan(f.altM) ? INT32_MIN : (int32_t)lroundf(f.altM * 100);
  r.accDm = isnan(f.accM) ? 0xFFFF : (uint16_t)min(65534L, lroundf(f.accM * 10));
  r.spdCms = isnan(f.spdMs) ? 0xFFFF : (uint16_t)min(65534L, lroundf(f.spdMs * 100));
  r.crsCdeg = isnan(f.crsDeg) ? 0xFFFF : (uint16_t)lroundf(fmodf(f.crsDeg, 360.0f) * 100);
  r.sats = f.sats;
  r.hdopX10 = isnan(f.hdop) ? 0xFF : (uint8_t)min(254L, lroundf(f.hdop * 10));
  r.batt = batteryPercentByte();
  r.flags = (f.fixType >= 3 ? 1 : 0) | (f.accFromGst ? 2 : 0);
  if (storageAppend(r)) {
    ls.last = r;
    ls.haveLast = true;
    ls.lastAtMs = millis();
    lastStoreWindowMs = ls.windowStartMs;
    ls.recorded++;
  } else {
    ls.writeErrors++;
  }
}

static void finishWindow(bool gotFix) {
  ls.collecting = false;
  ls.lastWindowMs = millis() - ls.windowStartMs;
  uint32_t interval = nextGapS() * 1000;
  // Anchor the schedule to the window start, not its end, so a slow fix
  // doesn't stretch the interval.
  ls.nextDueMs = ls.windowStartMs + interval;
  if ((int32_t)(ls.nextDueMs - millis()) < 500) ls.nextDueMs = millis() + 500;
  if (cfg.gpsMode == GPS_LOWPOWER && gotFix) {
    int32_t off = (int32_t)(ls.nextDueMs - millis()) - (int32_t)LP_LEAD_MS;
    // Only sleep the receiver after a good fix: with stale ephemeris it needs
    // to stay on to download a fresh one, or it would never get a fix again.
    if (off > 4000) gpsBackup((uint32_t)off);
  }
}

void loggerTick() {
  if (!cfg.recording) {
    if (!gpsInfo().asleep) gpsBackup(0);
    ls.collecting = false;
    return;
  }
  uint32_t now = millis();
  if (!ls.collecting) {
    if ((int32_t)(now - ls.nextDueMs) < 0) return;
    ls.collecting = true;
    ls.windowStartMs = now;
    ls.windows++;
    haveBest = false;
    goodInWindow = 0;
    winMinSpd = NAN;
    // While still, a window only stores once a full still-interval has passed
    // since the last point; earlier windows just look for movement.
    ls.peekOnly = adaptive() && ls.motion == MOTION_STILL && ls.haveLast &&
                  now - lastStoreWindowMs < ADAPT_STILL_S * 1000 - 1500;
    lastSeenFixAt = gpsLatest().atMs;
    // A timed backup wakes the receiver by itself; this covers an indefinite
    // one (recording was paused) and is harmless otherwise.
    if (gpsInfo().asleep) gpsWake();
  }
  const GpsFix& f = gpsLatest();
  if (f.atMs != lastSeenFixAt) {
    lastSeenFixAt = f.atMs;
    if ((int32_t)(f.atMs - ls.windowStartMs) >= 0 && acceptable(f)) {
      goodInWindow++;
      if (!isnan(f.spdMs) && (isnan(winMinSpd) || f.spdMs < winMinSpd)) winMinSpd = f.spdMs;
      if (!haveBest || (!isnan(f.accM) && (isnan(best.accM) || f.accM < best.accM))) {
        best = f;
        haveBest = true;
      }
    }
  }
  bool timeUp = now - ls.windowStartMs >= FIX_WINDOW_MS;
  if (ls.peekOnly) {
    // Two fresh fixes: enough for the minimum-speed test, short enough to keep
    // a peek cheap.
    if (goodInWindow < 2 && !timeUp) return;
    if (haveBest) {
      Motion m = classify(best);
      updateMotion(m);
      if (m != MOTION_STILL) store(best);   // started moving: record it now
      else ls.peeks++;
      ls.consecutiveMisses = 0;
    }
    finishWindow(haveBest);
    return;
  }
  // Stop early on a clearly good fix, or once three fresh fixes have been seen
  // (the best of three is as good as waiting longer usually gets, and every
  // second awake costs battery).
  bool goodEnough = haveBest && ((!isnan(best.accM) && best.accM <= GOOD_ENOUGH_ACC_M) || goodInWindow >= 3);
  // Adaptive judges speed on the window's slowest fix, which needs two.
  if (adaptive() && goodInWindow < 2) goodEnough = false;
  if (goodEnough || timeUp) {
    if (haveBest) {
      if (adaptive()) updateMotion(classify(best));
      if (cfg.minAccM && !isnan(best.accM) && best.accM > cfg.minAccM) {
        ls.filtered++;
      } else {
        store(best);
      }
      ls.consecutiveMisses = 0;
    } else {
      ls.missed++;
      if (ls.consecutiveMisses < 255) ls.consecutiveMisses++;
    }
    finishWindow(haveBest);
  }
}
