#include "anchor.h"
#include <math.h>
#include "config.h"

float anchorDistM(double lat1, double lon1, double lat2, double lon2) {
  const double r = M_PI / 180.0;
  double dlat = (lat2 - lat1) * r, dlon = (lon2 - lon1) * r;
  double a = sin(dlat / 2) * sin(dlat / 2) + cos(lat1 * r) * cos(lat2 * r) * sin(dlon / 2) * sin(dlon / 2);
  return (float)(6371000.0 * 2 * atan2(sqrt(a), sqrt(1 - a)));
}

void StillAnchor::reset() {
  anchored = false;
  stillStreak = farStreak = 0;
  lastT = 0;
}

AnchorVerdict StillAnchor::update(double& lat, double& lon, float minSpd, float maxSpd, uint32_t unixT) {
  // A long gap (paused, handed off to the phone, powered off) says nothing
  // about whether we stayed put: start over rather than snap a new place to
  // an old anchor.
  if (lastT && unixT - lastT > ANCHOR_MAX_GAP_S) reset();
  lastT = unixT;

  // Every reading in the window under the threshold. NAN (no Doppler) can't
  // vouch for stillness.
  bool still = !isnan(maxSpd) && maxSpd < ANCHOR_STILL_MPS;
  // The window's slowest reading over the threshold: sustained, not a spike.
  bool moving = !isnan(minSpd) && minSpd >= ANCHOR_STILL_MPS;

  if (anchored) {
    if (moving) {
      reset();
      releases++;
      return ANCHOR_RELEASED;
    }
    // Backstop for movement Doppler misses: far from the anchor in several
    // windows running. Set well beyond the receiver's own wander (60 m on the
    // bench), so drift is held and only a real relocation gets through.
    if (anchorDistM(aLat, aLon, lat, lon) > ANCHOR_BREAK_M) {
      if (++farStreak >= ANCHOR_BREAK_WINDOWS) {
        reset();
        releases++;
        return ANCHOR_RELEASED;
      }
    } else {
      farStreak = 0;
    }
    // Keep refining with fixes that land near it, so an anchor pinned while
    // the receiver was still settling (bench: 11 m off, converging over
    // minutes) follows it in. Drift and jumps sit beyond REFINE_M and are
    // left out.
    if (anchorDistM(aLat, aLon, lat, lon) <= ANCHOR_REFINE_M) {
      if (aN < ANCHOR_REFINE_N) aN++;
      aLat += (lat - aLat) / aN;
      aLon += (lon - aLon) / aN;
    }
    lat = aLat;
    lon = aLon;
    held++;
    return ANCHOR_HELD;
  }

  if (!still) {
    stillStreak = 0;
    return ANCHOR_PASS;
  }
  // Entering: the still windows must also sit together. One that lands away
  // from the others restarts the streak from itself.
  if (stillStreak && anchorDistM(sLat, sLon, lat, lon) > ANCHOR_ENTER_M) stillStreak = 0;
  if (!stillStreak) {
    sLat = lat;
    sLon = lon;
  } else {
    sLat += (lat - sLat) / (stillStreak + 1);
    sLon += (lon - sLon) / (stillStreak + 1);
  }
  if (++stillStreak >= ANCHOR_ENTER_WINDOWS) {
    anchored = true;
    aLat = sLat;
    aLon = sLon;
    aN = stillStreak;
    farStreak = 0;
  }
  return ANCHOR_PASS;
}
