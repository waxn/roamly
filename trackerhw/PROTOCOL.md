# Tracker ⇄ Roamly protocol

All device calls send `X-Roamly-Client: tracker/<fw>`. Authenticated calls send
`Authorization: Bearer <key>`, where the key is the tracker's **own** `APIKey`
(one per tracker — unpairing revokes exactly that tracker). Server code lives at
the end of `tracker/views.py` (search for "Hardware tracker"); the model is
`HardwareTracker` (migration `0094`).

## Pairing

The code travels from the signed-in browser to the device, through the user's
fingers — a device-authorisation flow run backwards.

1. **Settings → Pair a tracker** → `POST /api/hw/pair/start/` (session +
   CSRF). Returns `{code: "TMBBTMTB", expires_in: 600}`. `T`/`M`/`B` =
   top/middle/bottom button. A new start invalidates the user's previous code.
2. User presses the sequence on the tracker.
3. Tracker → `POST /api/hw/pair/claim/` (public, JSON):
   ```json
   {"code": "TMBBTMTB", "hw_id": "D0CF130AFFCC", "model": "feather-s3-revtft+neo6m", "fw": "0.1.0"}
   ```
   → `200 {status:"ok", api_key, device_id:"hw-d0cf130affcc", name, max_batch, server_time, tz, tz_name, utc_offset_s}`
   or `404 {error:"bad_code"}`, `429 {error:"rate_limited"}`.
4. Settings polls `GET /api/hw/pair/status/` → `waiting | paired | expired`.

Codes are single-use (burned on the first matching claim), live 10 minutes, and
are unique across concurrently-pairing accounts (`cache.add`). 3⁸ = 6,561
codes is small, so guessing is bounded by rate limits rather than length: 20
claims per IP per 10 min **and** 60 failed claims per 10 min instance-wide,
which IP rotation can't reset. Re-pairing the same board (same `hw_id`) to the
same account reuses its `Device` (one continuous track) and rotates its key.

## Pre-flight: `GET /api/hw/hello/`

Called before every sync, before anything is read off flash.

* `200 {status:"ok", device_id, name, max_batch, server_time, tz, tz_name, utc_offset_s}` → proceed.
* `401` → the key was revoked (unpaired in Settings). The tracker stops,
  keeps every point, and shows "unpaired!".
* `404` → server predates this API. Nothing is sent.

`tz` is a POSIX TZ string (`EST5EDT,M3.2.0,M11.1.0`) read from the footer of
the zone's TZif file; the zone itself is Roamly's usual per-user zone (from the
newest fix). `utc_offset_s` is a fallback for zones newlib can't parse.

## Upload: `POST /api/hw/upload/`

```json
{
  "batch_id": 123456,
  "status": {"battery": 81.5, "voltage": 3.95, "charging": true, "stored": 2210,
             "quarantined": 0, "free_kb": 870, "uptime_s": 86000, "gps_mode": "balanced",
             "interval_s": 30, "rssi": -61, "boots": 3, "fw": "0.1.0"},
  "points": [
    {"seq": 4865, "t": 1791237597, "lat": 44.4703005, "lon": -69.1195368,
     "alt": 99.6, "acc": 17.0, "spd": 0.39, "crs": 271.5, "sats": 7, "hdop": 1.2, "batt": 81}
  ]
}
```

* `seq` — the tracker's monotonic record id; the only thing the response refers to.
* `t` — unix seconds UTC from GPS time. `lat`/`lon` always 7 decimals (exactly
  what is stored on flash).
* `acc` — ~1σ horizontal metres from the NMEA `GST` sentence. `spd` m/s.
  `batt` %. `crs`/`sats`/`hdop` are sent but not yet stored server-side.
* Up to 200 points per request (the tracker sends 100); more → `413`.

Response:

```json
{"status": "ok", "batch_id": 123456, "received": 100,
 "confirmed": [4865, 4866, …], "rejected": [{"seq": 4870, "reason": "bad_time"}]}
```

### Why it can't lose points

* **Confirmation is a read-back.** After `bulk_create(ignore_conflicts=True)`
  the server queries `Location` for this device and these timestamps, and a
  `seq` is confirmed only if a row with that exact timestamp/lat/lon exists.
  `bulk_create`'s return value is useless for this — with `ignore_conflicts`
  it returns every object whether or not it was written.
* **Idempotent.** If the response is lost and the tracker re-sends, the rows
  are already there: `Location.unique_together = (device, lat, lon, timestamp)`
  makes the insert a no-op and the read-back confirms them again.
* **All-or-nothing per batch.** The tracker requires every `seq` it sent to come
  back in `confirmed` ∪ `rejected`. Anything missing → retry (×3), then abort the
  sync with everything still on flash.
* **The cursor moves only after that check.** `/p/cursor` (segment id + offset)
  is written after the batch is accounted for; whole segment files are deleted
  only once fully behind the cursor.
* **Rejections are kept.** Only impossible data is rejected (`bad_coords`,
  `bad_time` — before 2020 or more than a day in the future, `missing_field`),
  and those records go to `/quarantine.bin` rather than being deleted.
* **Torn writes are caught.** Every 32-byte record has a CRC-16; after a reboot
  appends go to a fresh segment, so a half-written tail is never extended.

## Settings endpoints (browser)

`GET /api/hw/trackers/`, `POST /api/hw/trackers/<id>/rename/` `{name}`,
`POST /api/hw/trackers/<id>/unpair/` (deletes the key and the pairing row;
the `Device` and every uploaded point stay), `POST /api/hw/pair/cancel/`.

## Flash record layout (`PointRec`, 32 bytes, little-endian)

| off | type | field |
|---|---|---|
| 0 | u32 | seq |
| 4 | u32 | t (unix s) |
| 8 | i32 | lat × 1e7 |
| 12 | i32 | lon × 1e7 |
| 16 | i32 | altitude cm (INT32_MIN = unknown) |
| 20 | u16 | accuracy dm (0xFFFF = unknown) |
| 22 | u16 | speed cm/s |
| 24 | u16 | course centi-degrees |
| 26 | u8 | satellites used |
| 27 | u8 | HDOP × 10 |
| 28 | u8 | battery % (0xFF = unknown) |
| 29 | u8 | flags: bit0 3D fix, bit1 accuracy from GST |
| 30 | u16 | CRC-16/CCITT of bytes 0–29 |
