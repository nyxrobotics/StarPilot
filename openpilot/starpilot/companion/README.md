# StarPilot companion apps

Phone apps for Galaxy. Both talk to the comma over Wi-Fi/LAN or Bluetooth and
share one pairing key.

```
companion/
├── android/   Android app (Gradle project, build_apk.sh, host tests)
├── ios/       iPhone app (Xcode project, CarPlay, tests) — see ios/README.md
└── run_host_tests.py
```

The comma side lives elsewhere:

- `openpilot/starpilot/galaxy_bluetooth/` — Bluetooth bridge, LAN discovery and
  the pairing screen, run by the `galaxy_phone` process.
- `openpilot/starpilot/galaxy/companion*.py` — phone internet sharing.

Over Bluetooth, pairing, phone internet sharing, APK downloads, recordings,
replay, cameras and map tiles are blocked; they need Wi-Fi.

## Pairing and phone music

One step pairs the app and phone music. On comma open Settings → Bluetooth →
**Pair phone**; comma opens a two-minute window that takes one phone.

Before pairing over Wi-Fi/LAN the app asks comma `GET /api/companion/bluetooth-ready` and waits out a boot or a
Bluetooth bridge restart; it stops with the reason (not parked, Bluetooth off or unavailable) instead of scanning.
If the app already reaches comma over Wi-Fi/LAN, **Add Bluetooth Phone Pairing** (Android) or **Pair with comma** (iPhone) opens that window itself (the call
Galaxy's Bluetooth page makes), so nothing needs touching on comma. Without a Wi-Fi/LAN session it waits for
the window as above.

- Android: tap **Add Bluetooth Phone Pairing** in the app. If the app can't reach comma over Wi-Fi it first asks you to open
  **Pair phone** on comma and keep it open. It then gets its key, bonds for music and shows one system prompt to approve.
  The first time Galaxy opens over Wi-Fi/LAN on a phone with no comma key (or an expired one), the app offers
  **Add pairing**. It opens comma's pairing window itself, shows each step, and asks for the Nearby devices permission if
  needed. **Not now** asks again next launch; **Don't ask again** leaves it to **Add Bluetooth Phone Pairing** in the menu.
- iPhone: tap **Pair with comma** in the app, then tap comma once in
  Settings → Bluetooth (iOS cannot start a Bluetooth bond from an app).
- No app: tap comma in the phone's Bluetooth settings to pair music only.

Forgetting a Bluetooth device in comma Settings also removes its app key. On Android, forgetting comma in the phone's
Bluetooth settings removes the app pairing too, and if comma no longer recognizes the phone's key the app stops retrying
and says the pairing expired until you pair again. Installing
Spotify (`/api/spotify/upload`) is blocked over Bluetooth like the other large uploads.

## Phone GPS

Each app can send the phone's GPS to comma about once a second, over Bluetooth or
Galaxy Wi-Fi. Comma carries it inside the navigation message as `phoneGps`, keeps the last fix for
10 seconds, and navigation uses it only when comma's own GPS and the car's GPS have no fix. Galaxy → Navigation → GPS Source can
instead ignore the phone or prefer it; mapd follows the same choice.

The 10-second hold is an expiry allowance, not an update interval. Navigation
checks for new phone fixes five times a second and forwards each new measurement
at the next check; without new fixes it publishes its usual once-a-second heartbeat.
The car map smooths movement between fixes using speed and heading for at most
2.5 seconds, then holds the position until another fix arrives or the hold expires.
Stationary phones retain the full 10-second allowance without predicted movement.

- Android: Connections → **Share phone GPS with comma**. Runs as a foreground
  service with a notification.

`POST /api/companion/location` with JSON: `latitude`, `longitude`,
`horizontalAccuracy` (m) and `ageMs` (phone-measured age, at most 2000) are
required; `altitude`, `verticalAccuracy`, `speed`, `speedAccuracy`, `bearing`,
`bearingAccuracy` and `timestamp` (Unix ms) are optional. Leave out unknown
values. Comma dates the fix by its own clock minus `ageMs`, never by `timestamp`.
Refused through the remote tunnel.

## Android

### T11 map route over Bluetooth

The X10 Companion requests authenticated Galaxy `GET /api/navigation/guidance?map=1` over its paired BLE session. The reply keeps the usual short maneuver text and adds `destinationCoordinates` and the active route in GeoJSON order (`[longitude, latitude]`). The route is reduced to at most 512 points and the whole map reply is limited to 32 KiB in the Android parser. Companion sends `routeKey=<16 hex characters>` on later polls; Galaxy returns `routeUnchanged: true` without the line until the route changes. No Sidecar APK or Sidecar API token is involved in this display path.

Companion caches the line and adds these extras to **every active** `CATEGORY_NAVIGATION` notification, including after a `routeUnchanged` BLE reply:

| Extra key prefix: `link.firestar.companion.` | Android type | Meaning |
| --- | --- | --- |
| `destinationLatitude`, `destinationLongitude` | `Double` | Destination marker; absent when no valid destination is available. |
| `routeKey` | `String` | Galaxy's current route version. |
| `routeCoordinates` | `DoubleArray` | Up to 512 interleaved `[longitude, latitude]` pairs; empty means clear the line. |

The map extras are present only while Companion has current `guiding` or `arrived` maneuver guidance; it cancels that notification while waiting for a route.

As of 2026-10-24, the bounded route and incremental reply passed Galaxy tests, and Companion unit tests and a debug APK build passed. Projection with a **live selected route** on the T11 has not yet been checked.

### Sidecar over Bluetooth while T11 uses Wi-Fi

Pair the X10 Companion with comma, then in Companion → **Connections** enable **Use Sidecar over Bluetooth**. In the X10 Sidecar app, select its Bluetooth connection option. Companion keeps a foreground service running and binds `127.0.0.1:8082` on the X10 only. The Sidecar app sends its existing token-protected `GET`, `POST`, and `DELETE /api/navigation` calls to that local listener; Companion forwards these calls over the authenticated BLE session to comma. The relay switch is saved and the service restarts after X10 boots. If port 8082 is briefly occupied, the service retries binding it. **Stop Sidecar Bluetooth relay** closes the local listener. StarPilot guidance has a separate switch and can run at the same time.

When comma needs an X10 route while their Wi-Fi link is unavailable, it queues one relative directions URL for Companion. Companion requests that URL from the X10 Sidecar server on `127.0.0.1:8765` with `Connection: close`, compresses the bounded response, and sends numbered 2 KiB chunks over the same BLE pairing. The route request has a 90-second deadline and an 8 MiB uncompressed limit. A transient local socket error before the first chunk leaves the job pending for a fresh GET; a non-200 HTTP answer or invalid/oversized body fails it. After a transient GATT disconnect, Companion keeps the exact compressed payload and resumes from the broker's first uncommitted chunk. If the Companion process lost that payload, it fails the partial job instead of combining bytes from separate downloads. When the Wi-Fi used by Galaxy disconnects, Companion releases its process-wide network binding so the local route fetch can continue while X10 projects to T11. Route-fetch warnings report whether a process network binding is still active. Map tiles and large media stay on local storage or Wi-Fi; this route transfer carries only the directions response.

As of 2026-10-26, the loopback relay and route target validation have host unit tests, and the Android debug build compiles. The X10 and comma exchanged a 55,772-byte live Sidecar route over Bluetooth in about nine seconds while X10 Wi-Fi was connected to T11 and AARE was projecting. Sidecar destination POST and DELETE also passed over Bluetooth on the home Wi-Fi. Vehicle navigation remains to be checked after installation in the car.

### Sidecar link over Bluetooth alone (Companion 1.1.40)

With a bridge whose `GET /_bridge/health` answer has the flags `poll2` and `results2` (and `push` for the car position), Companion carries the whole Sidecar exchange over
the one BLE pairing. The flags decide, never `protocol`: the bridge keeps `protocol` 1 because the iOS Galaxy app refuses any other value, and adds `sidecarProtocol` 2. A bridge
without the flags is the old one: the poll and chunk calls described above stay as they are, and nothing below is used.

- **One lane.** Every request of a connection (relay calls, guidance poll, phone GPS, Galaxy screens, job poll, route chunks) goes through one lane (`BleLane`): one
  request in flight, three classes in strict priority (interactive, small, bulk), first come first served inside a class, never preempting a request that has started.
  A route chunk is sized from the measured rate so that it takes at most 5 s (whole packets, 1 to 64 KiB, the first chunk 6 KiB); nothing waits longer than one
  chunk. A request that fails or stalls closes the link and fails every request that is queued; nothing is replayed.
- **Connection priority.** The lane asks for the high priority (15 ms interval) when a bulk transfer starts or a job is listed, keeps it while it is busy and gives
  it back after 8 s of idle. Single exchanges (state poll, job poll) run at the interval comma chooses.
- **The turn signal and the turn help (`nav`).** When the health answer has the flag `pushNav`, the position frames may carry the beacon's `nav` object (the turn signal, the
  state of the comma's turn help, the distance to the next manoeuvre). Companion says so in the body of `push-enable` (`{"nav":true}`), accepts `nav` in a frame only then, and
  passes the report on as the bridge sent it; the ages in `nav` grow with the time the frame waited, like the position's `age_ms`. A bridge without the flag, or an older Companion,
  gets and sends the position alone.
- **Jobs.** `GET /_bridge/sidecar/poll2` lists the open jobs, small ones first. A job is one of three shapes: a route (`GET /directions/v5/mapbox/driving-traffic/<lon,lat;lon,lat...>?<query>`),
  `GET /status` or `GET /health`, fetched from `127.0.0.1:8765` only (one allow-list for the Companion, the bridge and the X10's route API; `SidecarJobs.kt`,
  `SidecarContract.kt`). The answer goes back with the X10's HTTP status (200, 204, 400, 401, 403, 404, 422, 429, 503, 504), as `identity`, `gzip` or `xz`, in raw
  bytes (codec 2: no base64), the last chunk declaring the decoded size and its SHA-256. Companion checks its own result against the contract before the first chunk
  goes out. A job held by another phone is left alone; after a link loss the upload continues from comma's cursor while Companion holds the bytes, otherwise the job
  is failed (no second download is spliced on).
- **Write without response.** When the RX characteristic has the flag, all data packets of a raw-body request but the last go without a response each (measured
  41 to 58 KB/s against 0.6 KB/s with a response per packet). Without it a route goes as `xz` at the high priority (the fallback).
- **Car position.** Companion switches the notifications of characteristic number 6 on and sends `push-enable` after every handshake. comma pushes one frame per
  second (sealed with this phone's key under the label `push`); Companion reassembles, opens and checks it, measures how long it waited (`late` against the best
  frame of the last 600, plus its own dwell), drops a frame that is later than 1 s and POSTs the newest fresh report to `127.0.0.1:8765/v1/vehicle` (one POST in flight,
  `age_ms` raised by the wait). A `wake` frame ("a job is waiting") starts a job poll at once.
- **Reconnect.** After a drop the service retries at once, then after 1, 2, 4 and 8 s, then every 10 s.

Tests: `app/src/test/java/link/firestar/companion/` (`BleLaneTest`, `BleExchangeTest`, `SidecarJobsTest`, `VehiclePushTest`, `AllowlistTest`, `JobContractTest`,
`ContractVectorsTest`, `BleWireRawTest`, `StrictJsonTest`, `LinkMiscTest`) with the shared vectors of the Sidecar project in `app/src/test/resources/btlink/` (see the
README there). `tests/test_ble_wire_vectors.py` opens the raw-body frames that the app makes with the bridge's own `open_message`. Not yet seen on a unit or in a car.

Install: join the Galaxy hotspot, open Galaxy → **Galaxy Access & Install** →
**Android companion**, and install the APK. Later updates install from inside
the app: tap **Update app** while connected over Galaxy Wi-Fi/LAN, allow
installation from StarPilot if Android asks, and confirm the Android update dialog.
The APK streams into Android's installer; there is no file to find in Downloads.
After replacement, an **Update installed → Reopen** notification opens the new app.
Enable app notifications for this prompt. If notifications are disabled, open
StarPilot normally; the app confirms the completed update on launch.

### When an APK rebuild is needed

| Change | Rebuild Android APK? |
| --- | --- |
| Kotlin/Java code, native bridge, updater, phone GPS, Bluetooth or services | Yes |
| Android manifest, permissions, resources, icons, Gradle config or dependencies | Yes |
| Files in `android/app/src/main/assets/`, including `bluetooth-fetch.js` | Yes |
| Galaxy HTML/CSS/JS used over Bluetooth | No: the app downloads these pages from comma over Wi-Fi/LAN (see below) |
| Galaxy HTML/CSS/JS used over Wi-Fi/LAN | No: screens load from comma |
| Comma-side Python/services/API changes only | No, unless the phone protocol also needs a change |

### Galaxy screens over Bluetooth

Neither app bundles Galaxy's pages. Bluetooth Galaxy shows a copy the app saved from comma, so **connect to comma over
Wi-Fi/LAN once** before using Bluetooth. Until then the Bluetooth route says it needs one Wi-Fi visit.

- Whenever the phone reaches comma over Wi-Fi/LAN (and from **Refresh Galaxy screens** in the menu), the app reads
  `GET /api/companion/web-manifest`, which lists every file in `galaxy/web` with its SHA-256 and comma's commit. It downloads
  only the files that changed, verifies each hash, and keeps the old copy if anything fails.
- The pages come from comma itself, so custom and unpushed branches work and nothing depends on GitHub.
- If comma updated since the last Wi-Fi visit, Bluetooth still opens the saved copy and says it is from an older comma build.
  Join comma's Wi-Fi to refresh.
- A comma without the manifest route is too old to share its screens; update comma.

### Handing the signing key to another developer

Updates install over an existing app only when signed with the same `companion.jks`; a different key forces every user to
uninstall first. To pass signing on, copy `~/.starpilot/companion.jks` and `~/.starpilot/companion-signing-password` to the
new developer over a private channel (a password manager share, not git or chat), or have them point `COMPANION_KEYSTORE`
and `COMPANION_PASSWORD_FILE` at wherever the files live. Keep a backup: losing the key means a forced reinstall.

## Tests

From the repo root:

```sh
uv run --extra testing --with pycryptodome==3.23.0 --with dbus-next==0.2.3 --with zeroconf==0.151.3 \
  python openpilot/starpilot/companion/run_host_tests.py
node --test openpilot/starpilot/companion/android/tests/test_bluetooth_fetch.mjs
(cd openpilot/starpilot/companion/android && ./gradlew testDebugUnitTest)
```

`run_host_tests.py` stubs native modules so it runs on a laptop; it also takes
explicit test paths. iPhone tests are in `ios/README.md`.
