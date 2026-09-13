#!/usr/bin/env python3
"""
Read a points.csv exported from Diagnostics and report capture health.

The v2 CSV (mobile 1.25.0+) carries per-point context the server can never see:
which source produced the point, whether the screen was on, what accuracy mode
the fix cycle used, and the gap that preceded it. The server only ever sees
points that were captured *and* uploaded, with no idea which were dwell
substitutions rather than real fixes -- so a track can look complete there while
being partly fabricated.

Usage:  python3 analyse_points_csv.py points.csv

Baseline to compare against (measured before the 1.24.1 regression, 10s interval):
    median gap 10.0s   p90 gap 12-14s   median accuracy ~20m
"""
import csv
import sys
from collections import Counter


def pct(values, p):
    if not values:
        return None
    s = sorted(values)
    k = (len(s) - 1) * p
    lo, hi = int(k), min(int(k) + 1, len(s) - 1)
    return s[lo] + (s[hi] - s[lo]) * (k - lo)


def num(row, key):
    v = (row.get(key) or "").strip()
    if not v:
        return None
    try:
        return float(v)
    except ValueError:
        return None


def report(label, rows):
    gaps = [g / 1000.0 for g in (num(r, "gap_ms") for r in rows) if g is not None and 0 < g < 3600_000]
    accs = [a for a in (num(r, "accuracy_m") for r in rows) if a is not None]
    if not rows:
        return
    print(f"\n{label}  ({len(rows)} points)")
    if gaps:
        print(f"  gap    median {pct(gaps,0.5):6.1f}s   p90 {pct(gaps,0.9):6.1f}s   p99 {pct(gaps,0.99):7.1f}s")
    if accs:
        print(f"  accuracy median {pct(accs,0.5):5.1f}m   p90 {pct(accs,0.9):5.1f}m")


def main(path):
    with open(path, newline="", encoding="utf-8") as fh:
        rows = list(csv.DictReader(fh))
    if not rows:
        print("empty file")
        return
    if "gap_ms" not in rows[0]:
        print("This is a v1 CSV (no gap_ms/source columns) — it predates 1.25.0.")
        print("Columns found:", ", ".join(rows[0].keys()))
        return

    report("ALL", rows)
    report("screen ON", [r for r in rows if r.get("screen_on") == "1"])
    report("screen OFF", [r for r in rows if r.get("screen_on") == "0"])

    print("\nby source:")
    for src, n in Counter(r.get("source", "?") for r in rows).most_common():
        print(f"  {src:8} {n:7}  ({100*n/len(rows):5.1f}%)")
    dwell = sum(1 for r in rows if r.get("source") == "dwell")
    if dwell:
        print(f"  -> {100*dwell/len(rows):.1f}% of this track is re-stamped, not real fixes")

    cyc = [r for r in rows if r.get("source") == "cycle"]
    if cyc:
        print("\nfix-cycle accuracy mode:")
        for mode, n in Counter(r.get("fix_accuracy") or "?" for r in cyc).most_common():
            print(f"  {mode:10} {n:6}")
        counts = [c for c in (num(r, "fix_count") for r in cyc) if c]
        if counts:
            print(f"  fixes collected per burst: median {pct(counts,0.5):.0f} "
                  f"(1 means the burst kept a lone first fix)")

    misses = [m for m in (num(r, "consecutive_misses") for r in rows) if m is not None]
    if misses:
        print(f"\nconsecutive_misses at save time: median {pct(misses,0.5):.0f}  max {max(misses):.0f}")
        print("  (pinned high = the degrade loop; small and oscillating = healthy)")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
