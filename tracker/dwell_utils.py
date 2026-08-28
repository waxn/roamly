"""Shared rule for crediting time across a tracking gap.

When tracking stops — the phone is off, dead, force-stopped by an OEM battery
manager — and later resumes in essentially the same spot, the person did not
leave: they were there the whole time. Every time-attribution path in the app
independently cut a stay at a fixed time threshold and silently dropped that
interval, so a town visited with the phone off for an afternoon reported only
the tracked minutes either side of the hole.

`bridges_gap` is the single decision all of them now share: a gap counts as
time in place when the last fix before it and the first fix after it are
within `GAP_BRIDGE_RADIUS_M`, and the gap is no longer than
`GAP_BRIDGE_MAX_S`.

The ceiling is the load-bearing part. Two points a few hundred metres apart
prove nothing about the interval between them — the phone could have been off
while its owner flew somewhere and came back — so the bridge only covers spans
short enough that staying put is by far the likelier explanation. Twelve hours
covers a phone off overnight or through an afternoon; beyond that the stay is
still cut where it always was.

Lives in its own module rather than in views.py because `visit_tasks` needs it
too, and views.py imports visit_tasks.
"""

import math

GAP_BRIDGE_RADIUS_M = 300.0        # how close resumption must be to count as "never left"
GAP_BRIDGE_MAX_S = 12 * 3600       # ceiling: longer holes are not assumed to be a stay


def haversine_m(lat1, lon1, lat2, lon2):
    """Great-circle distance in metres."""
    r = 6371000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2.0) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2.0) ** 2
    return r * 2.0 * math.atan2(math.sqrt(a), math.sqrt(1.0 - a))


def bridges_gap(lat1, lon1, lat2, lon2, gap_s):
    """True if `gap_s` seconds between two fixes should count as time in place.

    Callers apply this as an escape hatch *after* their own normal cap, so it
    only ever adds time that was previously discarded — it never shortens a
    stay, and it never widens ordinary point-to-point clustering (which would
    let a slow walk chain into one endless "stay").
    """
    if gap_s <= 0 or gap_s > GAP_BRIDGE_MAX_S:
        return False
    if None in (lat1, lon1, lat2, lon2):
        return False
    return haversine_m(lat1, lon1, lat2, lon2) <= GAP_BRIDGE_RADIUS_M
