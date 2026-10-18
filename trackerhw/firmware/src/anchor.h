// Stationary anchor: holds a stationary tracker's position still while the
// receiver's own solution wanders.
//
// Indoors with a weak antenna, the L76K's position can jump tens of metres in
// a few seconds and drift back over minutes while it reports 0.00 m/s and
// HDOP 0.5 (bench, ceramic patch: a 60 m jump, then a slow slide back).
// Nothing in a single fix flags it — the accuracy estimate is HDOP-based and
// stays ~2 m — but the Doppler speed comes from a separate measurement and
// stays honestly at zero. So: after a few windows whose every fix reads
// still, pin an anchor at their centroid and store later still fixes there.
// Movement on Doppler releases it at once; a large, repeated displacement
// releases it as a backstop.
//
// Pure logic, no Arduino calls, so it can be replayed against recorded NMEA
// on a host.
#pragma once
#include <stdint.h>

enum AnchorVerdict : uint8_t {
  ANCHOR_PASS = 0,    // no anchor: store the fix as measured
  ANCHOR_HELD,        // anchored and still: store at the anchor instead
  ANCHOR_RELEASED,    // was anchored, movement seen: store as measured
};

class StillAnchor {
 public:
  // One completed window. minSpd/maxSpd are the slowest and fastest Doppler
  // readings in it (NAN when the receiver gave none). On ANCHOR_HELD, lat/lon
  // are replaced with the anchor's position.
  AnchorVerdict update(double& lat, double& lon, float minSpd, float maxSpd, uint32_t unixT);
  void reset();
  bool active() const { return anchored; }
  double lat() const { return aLat; }
  double lon() const { return aLon; }
  uint32_t held = 0, releases = 0;   // counters, for the console

 private:
  bool anchored = false;
  double aLat = 0, aLon = 0;         // anchor: mean of its inlier fixes
  uint16_t aN = 0;                   // fixes in that mean (capped)
  double sLat = 0, sLon = 0;         // running centroid while entering
  uint8_t stillStreak = 0;
  uint8_t farStreak = 0;
  uint32_t lastT = 0;                // unix time of the last window seen
};

float anchorDistM(double lat1, double lon1, double lat2, double lon2);
