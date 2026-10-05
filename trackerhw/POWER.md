# Power

Target: **30–40 h** on the 1500 mAh cell at a 30 s interval.

## Where the current goes

| Consumer | Current | Notes |
|---|---|---|
| NEO-6M, continuous tracking | ~37 mA | u-blox datasheet, plus ~2 mA for the board's power LED and regulator |
| NEO-6M, power save (cyclic, 1 Hz) | ~11 mA | RF front end duty-cycled between epochs, same 1 Hz output |
| NEO-6M, backup | < 0.1 mA | RTC + ephemeris kept; the board LED still draws ~1–2 mA |
| ESP32-S3 awake @ 80 MHz | ~25 mA | only during a fix window (~3 s per point) and while the screen is on |
| ESP32-S3 light sleep + board quiescent | ~0.5–1 mA | regulator, fuel gauge, sleeping TFT |
| TFT backlight | ~15–20 mA | 5 s per glance; negligible over a day |
| Wi-Fi | ~100 mA | only while charging |

## GPS modes

| Mode | What the receiver does | Board average @ 30 s | 1500 mAh lasts |
|---|---|---|---|
| **Accurate** | continuous tracking (`CFG-RXM` lpMode 0) | ~45 mA | ~30 h |
| **Balanced** (default) | cyclic power save at 1 Hz (`CFG-PM2` update 1 s / search 10 s, `CFG-RXM` lpMode 1) | ~15 mA | ~90 h |
| **Low power** | the ESP puts it into backup (`RXM-PMREQ`) after each point and it wakes itself 7 s before the next | ~15 mA at 30 s, ~5 mA at 2 min | ~90 h / ~270 h |

These are budget estimates (with 10 % of the cell held back), not
measurements — the fuel gauge can't measure current, only state of charge.
The tracker measures its real drain rate from its own state-of-charge history
and shows it on the Battery page once it has 30 minutes of data; until then the
estimate is marked *(est)* and comes from the table above.

**Low power only pays off at longer intervals.** Waking the receiver early
enough for a hot fix costs ~10 s of full-power tracking per point, which at
30 s is the same average as Balanced's cyclic tracking, with worse fixes right
after each wake. At 2–5 minute intervals it is far better. Low power also only
sleeps the receiver after a *successful* fix: with no fix it stays on, because
a receiver whose ephemeris has gone stale has to stay on long enough to
download a new one or it would never get a fix again.

## The ESP32 side

Between fix windows — screen off, on battery, no sync or hotspot running —
the ESP light-sleeps until 1.2 s before the next window, woken by the timer or
any of the three buttons. It never sleeps while a USB host is attached or on a
charger: there's no battery to save, and a USB host would lose the console on
every sleep. The CPU runs at 80 MHz.

A fix window ends at the first fix ≤ 8 m, after the best of three fresh
epochs, or after 12 s with whatever was best (nothing is stored if no fix
qualified: valid, ≥ 4 satellites, HDOP ≤ 10, real date).

## Charging and sync

Syncs happen only on external power, so Wi-Fi never touches the battery
budget. "External power" is a USB host enumerating the board, or the gauge
reporting charge going in (CRATE > 0.6 %/h), or a full cell (≥ 4.16 V) that
isn't draining — two readings 10 s apart must agree, so one noisy reading in a
pocket can't start Wi-Fi. The Feather charges at ~200 mA, so an empty 1500 mAh
cell takes roughly 8 hours.

## Bench measurements

First bench run, 2026-10-05: tracker indoors on a windowsill, NEO-6M patch
facing up, 30 s interval, 6 minutes per mode, on USB power (so no current
figures — see above). "Scatter" is each point's distance from the mean of all
points in the run: the tracker didn't move, so it is pure position error.

| Mode | Points | Missed windows | Scatter median / p90 / max | Reported accuracy (median) | Sats used | Window length (median) |
|---|---|---|---|---|---|---|
| Accurate | 11 | 0 | 2.9 / 4.7 / 9.6 m | 7.4 m | 8 | 0.6 s |
| Balanced | 12 | 0 | 4.5 / 7.3 / 7.7 m | 17.4 m | 7 | 2.5 s |
| Low power | 12 | 0 | 3.8 / 5.6 / 10.0 m | 7.1 m | 7 | 0.3 s |

* Every window produced a point in every mode.
* Balanced's *reported* accuracy is about 4× pessimistic: the receiver's GST
  error estimate is more conservative in power-save mode than the real scatter.
* Low power's windows are the shortest because the receiver already has a fix
  when the ESP wakes (it woke itself 7 s earlier).
* Time to first fix after a reboot was 1.2–1.9 s (warm, thanks to the module's
  backup cell).
* This run found a real bug, since fixed: right after waking from backup the
  NEO-6 re-emitted its previous epoch once, and the logger stored it as a new
  point. Fixes must now be current (within 5 s of the clock) and newer than the
  last stored point.
* Light sleep was verified on the bench: no reset, and both uptime and wall
  clock advance correctly through it.

Still to measure with the battery: the real drain rate per mode (the Battery
page shows it after 30 minutes unplugged), and a pocket run, which will be
noticeably worse than a windowsill.
