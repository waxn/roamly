# Roamly hardware tracker

A pocket GPS logger for the days you don't want to carry your phone. It records
a point every 30 seconds to its own flash, and uploads to your Roamly server
over Wi-Fi **only while it's charging** — deleting each point from flash only
after the server has confirmed, by reading it back out of the database, that
the point is stored.

```
trackerhw/
├── README.md          ← you are here: hardware, setup, usage
├── PROTOCOL.md        ← the server API and the no-data-loss upload design
├── POWER.md           ← battery budget and the GPS power modes
└── firmware/          ← PlatformIO project (Arduino-ESP32 3.x)
    ├── platformio.ini
    ├── partitions.csv
    └── src/
```

## Hardware

| Part | Notes |
|---|---|
| Adafruit ESP32-S3 Reverse TFT Feather ([#5691](https://www.adafruit.com/product/5691)) | 240×135 TFT, 3 buttons, MAX17048 fuel gauge, LiPo charger, 4 MB flash / 2 MB PSRAM |
| Seeed Studio L76K GNSS Module for XIAO ([wiki](https://wiki.seeedstudio.com/get_start_l76k_gnss/)) + its U.FL patch antenna | Quectel L76K: GPS + BeiDou + GLONASS + QZSS, ~41 mA tracking, ~0.36 mA standby. CASIC protocol (`$PCAS` commands) |
| EEMB 1500 mAh 3.7 V LiPo | plugs into the Feather's JST-PH battery socket |

The first build used a HiLetgo NEO-6M (u-blox 6); its firmware is in git history
before the L76K port. The L76K sees three constellations instead of one, which
matters most in a pocket.

### Wiring

The module is made to plug onto a Seeed XIAO, so its pads are labelled with
the **XIAO's** pin names. Wire it to the Feather like this:

| L76K module pad | What it is | Feather pin |
|---|---|---|
| **3V3** | power in | **3V** |
| **GND** | ground | **GND** |
| **D7** | GNSS TX (data out) | **RX** (GPIO38) |
| **D6** | GNSS RX (data in) | **TX** (GPIO39) |
| **D0** | STANDBY (high = run, low = standby) | **A0** (GPIO18) |
| **D2** | RESET (active low) | **A1** (GPIO17) |

Any free GPIO would do for the data lines (the ESP32-S3 routes its UART
anywhere, and the firmware searches for them if these don't answer), but
STANDBY and RESET need RTC-capable pins (GPIO0–21) so power-off can hold them
through deep sleep. Avoid SCK/MOSI/MISO (display), SDA/SCL (fuel gauge) and
D13 (red LED). Pin choices live in `firmware/src/config.h`.

Leave the module's **5V** pad unconnected. Screw/clip the antenna onto the
module's U.FL connector before powering it: the patch side (the ceramic square)
should face the sky — in a pocket, away from your body.

* Seeed note: an earlier revision of the module had RESET on **D10** instead of
  D2. If yours says so on the silkscreen or schematic, wire D10 to A1 instead.
* D0 and D2 must not float: Seeed's troubleshooting note is that an unpulled
  STANDBY/RESET leaves the green LED stuck on. The firmware drives both high.
* The module's green LED is its 1PPS indicator (blinks once a second with a
  fix). The L76K has no command to turn it off; desolder it if it bothers you.

The firmware auto-detects the UART pins and baud rate if the wiring above
doesn't answer, and remembers what it found. The L76K never acknowledges a
command, so the transmit pin is found by its effect: switch an extra sentence
on, watch for it, switch it off.

## Buttons

Holding the board with the screen facing you, the buttons on the edge are, top
to bottom:

| Button | Name | On the status screen | In menus | Pairing |
|---|---|---|---|---|
| D0 (top) | ▲ up | previous status page | move up / increase | ▲ |
| D1 (middle) | ● select | open the menu | choose / save | ● (**hold** = delete last) |
| D2 (bottom) | ▼ down | next status page | move down / decrease | ▼ |

**Hold ● to go back** anywhere. Holding ▲/▼ auto-repeats in lists and value
editors. When the screen is off, the first press only wakes it; it doesn't act.

The screen turns off after the screen timeout (5 s by default) and returns to
the status screen, like a 3D printer's.

## Screens

The status screen has four pages (▲/▼ to flip; dots on the right edge show
which):

1. **Overview** — local time and date, battery % with icon (⚡ while charging),
   current coordinates, GPS state with time since the last fix and accuracy
   (`~12m`), satellites seen / used now and in the last stored point (`pt 7`),
   estimated time to empty — or to full while charging — and how many points
   are stored and when the last sync was.
2. **GPS** — fix type, HDOP, accuracy, altitude, speed, heading, satellites
   used/heard/seen, best and top-4 signal strength (dB-Hz), points
   stored/missed/filtered, GPS mode.
3. **Battery** — %, voltage, charging state, time estimate, measured drain rate
   (marked *(est)* until 30 min of history exists), the gauge's own rate.
4. **Storage & sync** — points waiting, free flash, points the server refused
   (kept, never deleted), last sync result, paired name, server, saved Wi-Fi.

## Menu

| Item | |
|---|---|
| Sync now | Upload immediately (still needs Wi-Fi + pairing). |
| Pair with Roamly | Enter the 8-press code from Roamly → Settings → Data & Tracking → Hardware Trackers. |
| Wi-Fi setup | Starts a hotspot (`Roamly-XXXX`, password on screen). Join it from your phone; the setup page opens (or browse to `192.168.4.1`). Set the server URL and up to 5 Wi-Fi networks. Hold ● to stop. Stops itself after 15 min. |
| Bluetooth | Pair a phone (shows a 6-digit passkey for 2 min), forget paired phones. |
| Settings | See below. |
| Pause / Resume recording | Paused puts the GPS in standby (~0.36 mA). |
| Storage | Counts; clear refused points; erase everything (confirms, shows how many are not uploaded). |
| About | Firmware, tracker ID, GPS port + module version, boot count. |
| Restart | |

### Adaptive interval

The default. The tracker classifies each fix by the GPS's Doppler speed and
picks the interval from it:

| You are | Speed | Point every |
|---|---|---|
| still | < 0.8 m/s (and within 40 m of the last point) | 60 s |
| walking / jogging | 0.8 – 4 m/s | 30 s |
| cycling / driving | ≥ 4 m/s (~14 km/h) | 10 s |

Speed is the *slowest* reading across the fixes in a window (at least two),
so a single noisy Doppler spike from a stationary receiver can't register as
movement. Speeding up then switches immediately; slowing down needs two slower readings in a
row, so a red light or a pause doesn't drop you to one point a minute. While
still, Accurate and Balanced *peek* every 20 s — one fix, not stored — so
setting off is recorded within ~20 s rather than a minute late. Low power
doesn't peek (a peek would cost a full GPS wake-up), so there a start can lag
by up to 60 s. Thresholds live in `firmware/src/config.h` (`ADAPT_*`).

### Settings

| Setting | Options | Default |
|---|---|---|
| Screen timeout | 3 s – 2 min | 5 s |
| Log interval | **Adaptive**, or fixed 10 s – 5 min | Adaptive |
| GPS mode | Accurate (always on) / Balanced (standby between points, wakes 9 s early) / Low power (wakes 5 s early) — see [POWER.md](POWER.md) | Balanced |
| Min accuracy | keep all, 15/25/50/100 m (worse fixes are not stored) | keep all |
| Units | metric / imperial | metric |
| Clock | 24 h / 12 h | 24 h |
| Time zone | Auto (from the server: your zone as Roamly works it out from your latest point) or a fixed UTC offset | Auto |
| Brightness | 10–100 % | 100 % |
| Flip screen | rotate 180° | off |
| Auto-sync | when charging / off | when charging |
| Battery size | 500–3000 mAh (used for the time estimate before the drain rate has been measured) | 1500 mAh |

## First-time setup

1. **Flash** (from `trackerhw/firmware`):
   ```bash
   pio run -t upload
   ```
   The first flash over Adafruit's factory firmware needs the board in the ROM
   bootloader: hold D0 (top) while tapping RESET. After that, uploads reset it
   automatically (the firmware uses the S3's built-in USB-Serial-JTAG).
2. **Wi-Fi + server:** Menu → Wi-Fi setup, join the hotspot from your phone,
   enter your Roamly address (e.g. `https://roamly.example.com`) and your home
   Wi-Fi. Or over USB serial: `server https://…` and `wifi add "My WiFi" password`.
3. **Pair:** in Roamly open Settings → Data & Tracking → Hardware Trackers →
   **Pair a tracker**. On the tracker: Menu → Pair with Roamly, then press the
   8 buttons shown. The tracker joins Wi-Fi, submits the code and gets its own
   API key. Settings flips to "Paired" by itself.
4. Unplug it and go. Plug it in to charge and it uploads on its own.

The tracker appears in Roamly as its own device (`hw-<id>`, named "Roamly
Tracker XXXX" — rename it in Settings), so its track sits alongside your phone's
on the map and in stats.

## How syncing works

* **When:** about 15 s after the tracker sees external power (USB host
  enumerating it, or the gauge showing charge going in / a full cell not
  draining), then every 15 minutes while it stays plugged in. A failed attempt
  retries after 2, 5, then 15 minutes.
* **Checks before deleting anything** (details in [PROTOCOL.md](PROTOCOL.md)):
  a pre-flight `hello` must succeed (wrong server, unpaired key or an old
  server without the tracker API all stop the sync *before* any point is
  touched); each batch of 100 must come back with **every** point accounted
  for as confirmed or rejected; confirmation is the server reading the rows back
  from the database, not trusting the insert; a batch is retried up to 3 times;
  the flash cursor only advances after the batch is fully accounted for.
* Points the server **rejects** (impossible coordinates or time) are moved to
  `/quarantine.bin`, not deleted; the count shows on the Storage page and in
  Roamly Settings.
* Records carry a CRC; a corrupt record (power lost mid-write) is skipped and
  counted, never uploaded as garbage.
* Storage holds ~30,000 points (≈10 days at 30 s) in the 960 KB LittleFS
  partition.

## Bluetooth and the phone app

The Roamly Android app pairs with the tracker over BLE (Menu → Bluetooth →
Pair a phone on the tracker, then Settings → Roamly Tracker in the app; type
the passkey the tracker shows). The bond is encrypted and authenticated —
nothing on the GATT service is readable without it. After pairing the app
gets a **Tracker** tab: live status, diagnostics, every setting, Sync / Pause
/ Restart / Power off, and the serial console.

**Hand-off.** The tracker advertises for 6.5 s every `ble` seconds (default
60). The app's low-power scan treats it as *together* while it keeps hearing
it, and *apart* after `max(200 s, 3.5 × interval)` of silence.

* Together, `handoff=tracker` (default): the tracker records, the phone's GPS
  rests (only once the tracker has a fix and is recording).
* Together, `handoff=phone`: the phone sends a 5-minute lease every 2 min; the
  tracker pauses while the lease is valid, so it resumes by itself if the
  phone vanishes.
* Apart: both record, and Roamly asks — on the site's bell and in the app's
  Tracker tab — which one you had, hiding the other device's points for that
  stretch. Phone activity recordings are never handed off.

**Never during a fix.** The radio is scheduled around the GPS rather than
alongside it: no advertising inside a fix window; in Balanced / Low power a
window opens only while the GPS is in standby and only if it ends before the
next GPS wake; in Accurate it opens right after a fix. A connection the app
holds open stays up, but new windows still wait for the fix to finish. Between
windows the BLE stack is shut down entirely so the ESP can light-sleep.

GATT layout and advert format: [PROTOCOL.md](PROTOCOL.md#bluetooth).

## Serial console

115200 baud on the USB port (`pio device monitor`). Type `help`. Useful:
`status`, `trace on` (print each point as stored), `gps raw on`, `dump 20`,
`sync`, `set interval 30`, `set mode 1`, `shot` (dump the screen) and
`btn up|sel|down|back` (press a button) — enough to drive the whole UI remotely.

> On Linux, open the port *without* toggling DTR/RTS (plain `pyserial`
> `Serial(port)` is fine). Toggling RTS on the S3's USB-Serial-JTAG resets the
> chip.

## Measured results

From the first bench run, Feather on a windowsill, NEO-6M patch facing up,
Balanced mode, 30 s interval:

* First fix 1.2–1.9 s after a reboot (module backup cell kept ephemeris).
* No missed points in 36 minutes across the three GPS modes; stationary
  position scatter 3–5 m median. Table per mode in
  [POWER.md](POWER.md#bench-measurements-neo-6m-before-the-l76k-port).

## Roadmap / ideas (not built yet)

* Store course / satellites / HDOP server-side (sent today, not yet persisted:
  `Location` has no columns for them).
* OTA updates over Wi-Fi while charging. The two app slots were merged into
  one 2.75 MB slot to fit NimBLE, so OTA would need the partitions split
  again (the LittleFS partition stayed where it was, so points survived).
