# L76K bench tests

**Date:** 2026-10-16
**Hardware:** Adafruit ESP32-S3 Reverse TFT Feather + Seeed L76K GNSS module for XIAO, with its patch antenna.
**Setup:** indoors on a windowsill, on USB power, wired as in the README:

| Module pad | Feather pin |
|---|---|
| D7 | RX |
| D6 | TX |
| D0 | A0 |
| D2 | A1 |

**Firmware:** `main` at `8c7e1ef`.

All numbers come from the tracker's own serial console, logged and analysed on the host.

## Summary

| Test | Result |
|---|---|
| Detection on the documented pins | ✅ RX 38 / TX 39 at 9600 baud, found in ~5 s from boot |
| Module identity | `AT6558R-5N-32` chip, firmware `URANUS5,V5.3.0.0` |
| Configuration applied | ✅ `$GN` talkers, per-constellation GSV, VTG/GLL off |
| Configuration saved to the module's flash (`$PCAS00`) | ✅ survives a hardware reset with nothing reapplied |
| RESET pin (A1) | ✅ the module reboots and prints its startup banner |
| STANDBY pin (A0) | ✅ zero output while low; real fix 2.7–4.0 s after waking |
| First fix after a cold firmware boot | 3.8 s |
| Satellites | 15–19 used out of up to 22 in view (GPS + BeiDou + GLONASS) |
| Missed fix windows | **0** in 24 minutes across 4 runs |
| Upload to roamly.cyou | ✅ 70 points confirmed in ~14 s including joining Wi-Fi; 0 refused, 0 corrupt |
| Crashes / watchdog resets | 0 |

## 1. Detection and configuration

Boot log:

```
[boot] reset=usb fw 0.1.0 id D0CF130AFFCC gps=1 rx=38 tx=39 baud=9600 ver=URANUS5,V5.3.0.0
gps: det=1 cfg=1 saved=1 rx=38 tx=39 baud=9600 ver='URANUS5,V5.3.0.0' ... view gps/bds/glo=5/3/3
fix: valid=1 type=3 ... acc=4.2 sats=8/11 hdop=1.20 ttff=3843ms
```

- **Version query:** the module answered `$PCAS06,0`, so the transmit line was confirmed working, not assumed.
- **Configuration check:** the L76K never acknowledges a command. `cfg=1` means the firmware watched the output change to what it asked for: `$GN` talkers present, GLL and VTG gone.

## 2. RESET pin and persisted configuration

I pulsed RESET low and read the raw output before the firmware could reapply anything:

```
$GPTXT,01,01,02,IC=AT6558R-5N-32-1C580901*13
$GPTXT,01,01,02,SW=URANUS5,V5.3.0.0*1D
$GPTXT,01,01,02,TB=2020-03-26,13:25:12*4B
$GPTXT,01,01,02,MO=GBR*25          <- GPS + BeiDou + GLONASS
$GNGGA,212208.487,,,,,0,00,25.5,,,,,,*7A
```

Sentence types over the next 12 s: `GNGGA, GNGSA, GPGSV, BDGSV, GLGSV, GNRMC` — **no VTG, no GLL**. The configuration was written to the module's flash and survives a reset or power loss.

The first position appeared 2 s after reset. It carried GGA quality **6**, meaning "estimated" (dead reckoning), not a real fix. The firmware now rejects quality 6/7/8 and RMC mode `E` (commit `498d303`). A second reset went straight from quality 0 to 1, so the guard couldn't be exercised on demand: it's in place but unverified.

## 3. STANDBY pin

Each trial held STANDBY low, counted sentences, then raised it and timed the return. The logger was set to a 5-minute interval during this test: in Accurate mode it wakes the GPS at every window, which spoiled a first attempt.

| Standby for | Sentences while in standby | First sentence after wake | First real fix after wake |
|---|---|---|---|
| 10 s | 0 | 0.3 s | 2.7 s |
| 30 s | 0 | 0.3 s | 3.9 s |
| 60 s | 0 | 0.3 s | 4.0 s |

Standby keeps time and satellite data, so every wake is a hot start. Both warm-up leads cover this on a windowsill: Balanced wakes the GPS 9 s before each point, Low power 5 s. In a pocket the fix will take longer.

## 4. Fix quality and duty cycle per mode

Each run lasted 6 minutes with the tracker stationary. **Scatter** is each point's distance from the mean position of its run, so with the tracker not moving it is pure position error. **GPS awake** is the measured share of time the STANDBY pin was high.

| Mode | Interval | Points | Scatter median / p90 / max | Estimated accuracy (median) | Sats used (median) | HDOP | Window | GPS awake |
|---|---|---|---|---|---|---|---|---|
| Accurate | 30 s | 12 | **1.1 / 1.9 / 6.4 m** | 2.5 m | 19 | 0.70 | 0.2 s | 100 % |
| Balanced | 30 s | 11 | 5.7 / 7.3 / 7.7 m | 2.5 m | 18 | 0.70 | 0.7 s | 30.9 % |
| Low power | 30 s | 11 | 4.6 / 15.9 / 32.7 m | 2.8 m | 15 | 0.80 | 0.5 s | 17.8 % |
| Adaptive, Balanced | 60 s (still) | 6 | 3.5 / 12.9 / 16.2 m | 2.6 m | 18 | 0.75 | 0.7 s | 16.8 % |

Point spacing was exact in every run: 29–31 s at 30 s, and exactly 60 s in Adaptive, which correctly classified the stationary tracker as `still`.

### Observations

- **Accurate is excellent:** ~1 m median scatter with 19 satellites.
- **Low power's 5 s warm-up shows:** a small run, but it produced the only large outliers (up to 33 m). Balanced's 9 s warm-up stayed under 8 m. If Low power proves too rough in daily use, the first thing to try is a longer lead (`gpsWakeLeadMs()` in `gps.cpp`).
- **The duty cycles match the design:**

  | Mode | Expected | Measured |
  |---|---|---|
  | Balanced | (9 s + 0.7 s) / 30 s ≈ 32 % | 30.9 % |
  | Low power | (5 s + 0.5 s) / 30 s ≈ 18 % | 17.8 % |
  | Adaptive still | (9 s + 0.7 s) / 60 s ≈ 16 % | 16.8 % |

  So the receiver really is standing down between points.
- **The accuracy figure is optimistic:** HDOP × 3.5 m gave ~2.5 m, while the measured scatter was 1–6 m depending on mode. With no error estimate from the module, it's an approximation.

## 5. Battery estimate from the measured duty cycles

Each figure is the GPS current (41 mA × awake share + 0.36 mA × asleep share) plus ~3 mA for the ESP32 and board. Hours assume 1350 mAh usable from the 1500 mAh cell.

| Mode | GPS awake | Estimated average | Estimated life |
|---|---|---|---|
| Accurate | 100 % | ~44 mA | ~31 h |
| Balanced @ 30 s | 30.9 % | ~16 mA | ~85 h |
| Low power @ 30 s | 17.8 % | ~11 mA | ~125 h |
| Adaptive (Balanced), while still | 16.8 % | ~10 mA | ~130 h |

These are still estimates: the module's currents are datasheet figures, and the fuel gauge can't measure current. With the battery in and USB unplugged, the Battery page shows the real drain rate after 30 minutes.

## 6. Upload

`sync` with 70 points stored:

```
fs: pending=0 quarantined=0 corrupt=0
net: ... msg='uploaded 70' fails=0
```

All 70 points were confirmed by the server's database read-back before being deleted from the tracker.

Note: **Auto-sync is switched off** in this tracker's settings, so it won't upload on its own while charging. Turn it on under Settings → Auto-sync.

## Not tested yet (needs hands or a pocket)

- **Power off:** Menu → Power off, a short tap staying off, and a 3 s middle-button hold to turn back on.
- **Button wake from light sleep:** this only happens on battery with USB unplugged.
- **Real battery drain per mode,** on battery.
- **A pocket run:** fix rate and outliers with a body in the way.
- **Adaptive while moving:** switching to 30 s points walking and 10 s cycling or driving.
- **The estimated-fix guard:** couldn't trigger quality 6 on demand.

## Antenna comparison (2026-10-18)

Same windowsill, Accurate mode, 30 s points, 10 minutes each, run back to
back. **Signal** is the mean C/N0 of the four strongest satellites, from the
raw GSV sentences. **Scatter** is each stored point's distance from the run's
mean position.

| Antenna | Signal, top 4 | Signal, all (median) | Satellites used (median) | Scatter median / p90 / max |
|---|---|---|---|---|
| Small PCB antenna | 38.5 dB-Hz | 30 dB-Hz | 16 (from a cold boot; 20 once settled) | **0.8 / 1.8 / 2.6 m** |
| Stock ceramic patch, on top | 34.5 dB-Hz | 28 dB-Hz | 24 | 14.8 / 46.3 / 50.1 m |
| Stock ceramic patch, under the module near the ESP32 | 38.5 dB-Hz | 29 dB-Hz | 21 | 2.9 / 6.3 / 10.7 m |

- **The PCB antenna was best:** about 4 dB stronger than the ceramic patch in
  its original spot, with no jumps.
- **On the ceramic run, the error was the receiver jumping, not noise.** It
  held still for 7 minutes, then moved its own solution 60 m in about 10 s and
  slid back over the next few minutes. Throughout, it reported 0.00 m/s, HDOP
  0.5 and 22 satellites, so the HDOP-based accuracy figure stayed at 1.8 m.
  An overnight run on that antenna left a long trail of points around the
  house that looks just like this. This is what the stationary anchor
  (README) now holds still.
- **Moving the ceramic patch under the module** raised its signal to match
  the PCB antenna. Its scatter (11 m worst case) came from a slow settling
  drift, not a jump.
- Each run is only 10 minutes, so these show which antenna to prefer, not how
  often each one jumps.
