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
| HiLetgo NEO-6M GPS module (GY-GPS6MV2) with the stock 25 mm ceramic patch | u-blox 6, firmware 7.03; has a small backup cell that keeps ephemeris for fast restarts |
| EEMB 1500 mAh 3.7 V LiPo | plugs into the Feather's JST-PH battery socket |

### Wiring

| NEO-6M | Feather |
|---|---|
| VCC | 3V |
| GND | GND |
| TX | RX (GPIO38) |
| RX | TX (GPIO39) |

The firmware auto-detects the pins and baud rate on first boot (it probes the
free header pins at 9600/38400/115200/4800/57600 and then finds the TX line by
asking the module for its version), and remembers the result. If GPS RX isn't
connected the tracker still logs, but can't configure the module, so it runs in
the module's default continuous mode and battery life drops to ~30 h.

**Antenna:** the ceramic patch must face the sky — in a pocket, put the patch
side away from your body. GPS through a body is weak (expect ±10–30 m and more
misses than in the open).

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
| Settings | See below. |
| Pause / Resume recording | Paused puts the GPS into backup mode (~0 mA). |
| Storage | Counts; clear refused points; erase everything (confirms, shows how many are not uploaded). |
| About | Firmware, tracker ID, GPS port + module version, boot count. |
| Restart | |

### Settings

| Setting | Options | Default |
|---|---|---|
| Screen timeout | 3 s – 2 min | 5 s |
| Log interval | 10 s – 5 min | 30 s |
| GPS mode | Accurate / Balanced / Low power — see [POWER.md](POWER.md) | Balanced |
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
  [POWER.md](POWER.md#bench-measurements).

## Roadmap / ideas (not built yet)

* **BLE beacon:** advertise battery %, tracking state and last-fix age so the
  phone app can show the tracker's battery.
* **Phone hand-off:** when the app sees the tracker advertising "tracking", the
  phone pauses its own GPS; when the tracker disappears or stops, the phone
  resumes — so something is always tracking. The advertisement should carry a
  per-pairing token (derived from the API key) so the app only trusts *your*
  tracker, and a monotonic counter so a replayed advert can't keep the phone
  paused.
* Store course / satellites / HDOP server-side (sent today, not yet persisted:
  `Location` has no columns for them).
* OTA updates over Wi-Fi while charging (the partition table keeps two app
  slots for this, but the image is already 94 % of one slot).
