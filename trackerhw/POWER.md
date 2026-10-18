# Power

Target: **30–40 h** on the 1500 mAh cell at a 30 s interval.

## Where the current goes

| Consumer | Current | Notes |
|---|---|---|
| L76K, tracking | ~41 mA | Seeed's figure, GPS + BeiDou + GLONASS |
| L76K, standby | ~0.36 mA | STANDBY pin low; keeps time and ephemeris, so waking is a hot start |
| L76K green 1PPS LED | ~1 mA while blinking | on the module; no command turns it off |
| ESP32-S3 awake @ 80 MHz | ~25 mA | only during a fix window (~2–3 s per point) and while the screen is on |
| ESP32-S3 light sleep + board quiescent | ~0.5–1 mA | regulator, fuel gauge, sleeping TFT |
| TFT backlight | ~15–20 mA | 5 s per glance; negligible over a day |
| Wi-Fi | ~100 mA | only while charging |

## GPS modes

The L76K has no u-blox-style cyclic power-save mode. Instead it has a hardware
STANDBY pin, and it can't wake itself, so the ESP puts it in standby after
each good fix and wakes it a fixed lead time before the next window.

| Mode | Receiver | GPS awake (measured) | Board average @ 30 s | 1500 mAh lasts |
|---|---|---|---|---|
| **Accurate** | always on | 100 % | ~44 mA | ~31 h |
| **Balanced** (default) | standby between points, woken 9 s early | 30.9 % | ~16 mA | ~85 h |
| **Low power** | standby, woken 5 s early | 17.8 % | ~11 mA | ~125 h |

The awake share is measured on the bench (see [l76ktests.md](l76ktests.md));
the currents are the module's datasheet figures plus ~3 mA for the ESP32 and
board, with 10 % of the cell held back — the fuel gauge can't measure current. The Battery page shows the measured drain rate after 30
minutes unplugged. The receiver is only stood down after a *successful* fix:
with no fix it stays on, since stale ephemeris needs time with the sky to
refresh. At the 10 s Adaptive interval (moving fast) there's no time to stand
it down, so it runs continuously — about Accurate's draw while you're moving.

## Adaptive interval

With the L76K, Adaptive matters for battery in Balanced and Low power, because
the receiver stands down between points: a still period (60 s points) spends
most of its time in standby, while driving (10 s points) keeps it on
throughout. Only Accurate peeks for movement every 20 s while still — in the
standby modes each peek would cost a full wake-up, so there a start of
movement is noticed at the next 60 s point.

## The ESP32 side

Between fix windows — screen off, on battery, no sync or hotspot running —
the ESP light-sleeps until 1.2 s before the next window, woken by the timer or
any of the three buttons. It never sleeps while a USB host is attached or on a
charger: there's no battery to save, and a USB host would lose the console on
every sleep. The CPU runs at 80 MHz.

A fix window ends at the first fix ≤ 8 m, after the best of three fresh
epochs, or after 12 s with whatever was best (nothing is stored if no fix
qualified: valid, ≥ 4 satellites, HDOP ≤ 10, real date).

**Bluetooth** costs one 6.5 s advertising window per `ble` interval (default
60 s), placed while the GPS is in standby so the two never draw at once —
roughly 1–2 % extra on top of the GPS duty above. Between windows the BLE
stack is deinitialised, so light sleep is unaffected; `ble 0` turns it off.

## Charging and sync

Syncs happen only on external power, so Wi-Fi never touches the battery
budget. "External power" is a USB host enumerating the board, or the gauge
reporting charge going in (CRATE > 0.6 %/h), or a full cell (≥ 4.16 V) that
isn't draining — two readings 10 s apart must agree, so one noisy reading in a
pocket can't start Wi-Fi. The Feather charges at ~200 mA, so an empty 1500 mAh
cell takes roughly 8 hours.

## Bench measurements (NEO-6M, before the L76K port)

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
