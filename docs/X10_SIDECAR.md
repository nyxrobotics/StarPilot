# X10 sidecar navigation on SecretGoodStarPilot

This branch integrates the X10 route server with StarPilot's 7.0 navigation runtime and adds a narrow Galaxy destination API for the X10 app. It is a development branch. Vehicle qualification for StarPilot 7.0 is still in progress, including on comma four; check the on-device build and navigation while parked before relying on it.

## Connection and settings

The comma and X10 must be able to reach each other on the same trusted local network. Give each a stable IP address. Two things have to match on both devices:

- **The X10's address.** The comma needs the **X10 IP**, not its own IP, and the port of the X10 route server (8765 by default). In the **X10 app**, set **comma manual IP** to the comma's IP.
- **A shared API token** of 10–128 printable ASCII characters (letters, digits and symbols, no spaces). It is used for both X10 route requests and destination commands. Enter the same value in the X10 app (**API token**) and on the comma.

A short token is easier to guess: 10 characters is the minimum the comma accepts, and a random value of 24 or more characters is recommended on any shared network. Use a newly generated value, for example `openssl rand -hex 24` on a trusted machine; hexadecimal characters have no look-alikes when you compare the token on both screens. Do not reuse a Mapbox token. If the X10 app still refuses a token shorter than 20 characters, update the X10 app; the comma accepts 10–128.

### Set it up on the comma screen

Open the **X10 sidecar** page:

- **comma four (compact UI):** Settings, then swipe sideways to the **X10 sidecar** card (after Galaxy).
- **comma 3 / 3X (large UI):** Settings, **Device**, **X10 sidecar**, **OPEN**.

| Item | What it does |
| --- | --- |
| Status | Shows whether the X10 answers, refreshed every 5 seconds while the page is open, with the time of the last check. |
| Use the X10 | Switches the sidecar on or off. It can be turned on only when an address and a valid token are saved. Off means standard StarPilot routing. |
| X10 address | The X10's IPv4 address. Private network addresses are accepted (`10.x.x.x`, `172.16–31.x.x`, `192.168.x.x`; loopback and link-local pass the same rule the navigation runtime applies). `auto` is not offered, because the destination bridge rejects it. |
| Port | The X10 route server's port, 8765 unless you changed it in the X10 app. |
| Token | Replaces the token. The entry starts empty and is masked: on the comma four, tap the typed text to show or hide it; on the large UI use the eye. The page shows only whether a token is set and how long it is. |
| Show token | Lists the saved token in groups of four characters so that you can compare it with the X10 app. Type it without the spaces. |
| Test connection | Checks the X10 right now. |
| Clear | Turns the sidecar off and removes the saved token; the address and port stay. It asks for confirmation. If the settings file is damaged it is replaced by a clean one, and the old file is kept as `/data/sidecar.json.bak-<number>`. |

The address and port keyboards open on the digit keys. A line under the typed text says what is wrong while you type (for example `must be a private address` or `need 10 characters (7)`), and the keyboard's check mark applies the change only when the value is valid. The switch applies when it is tapped.

Changes can only be made while parked; the page can still be read while driving. Each change is written to `/data/sidecar.json` (mode 0600) in one atomic step, keeps any other keys that are in the file, and reaches navigation and Galaxy within about a second, so no restart is needed. A change made over SSH shows on the page within half a second. The token is never shown on the page except by **Show token**.

### What the status line says

The check is a plain HTTP `GET /status` to the saved address and port, with short time limits. It sends no token, no position and no destination.

| Status | Meaning |
| --- | --- |
| X10 ready | An X10 route server answered and its route engine is ready. |
| X10 warming up | It answered; the engine is still starting. |
| X10 has no map data | It answered; no route tiles are installed on the X10. |
| X10 engine error | It answered; its engine reports an error. |
| X10 answered, not ready | It answered; for another reason it is not ready (for example its test route failed). |
| X10 not reachable | No answer: connection refused (the X10 app's route service is not running, or the port is wrong), no answer in time, or no route (check Wi-Fi and the address). |
| Not the X10 route server | Something answered at that address, but it is not the X10 route server (a wrong address or port, or another device). |
| Comma's own address | The address belongs to this comma. Enter the X10's address. |

`/status` needs no token, so **the check cannot tell whether the token matches**. A wrong token shows up as no route in navigation, not on this page. If the X10 is ready but navigation finds no route, compare the token with **Show token**.

### The settings file (SSH alternative)

The screen edits the same file the navigation runtime reads, so you can still create it by hand. On the **comma**, create `/data/sidecar.json` with mode 0600. `host` is the **X10 IP**, not the comma IP; `token` must match the X10 app setting.

```json
{
  "enabled": true,
  "host": "<X10 IPv4 address>",
  "port": 8765,
  "token": "<same 10–128 character token as X10>"
}
```

`enabled` must be the JSON boolean `true`. A missing or disabled file leaves standard StarPilot routing active. A fixed private IP is required for X10 destination commands. The route engine also supports `"host": "auto"` discovery, but the X10 destination bridge deliberately rejects that setting, so use a fixed address for this integration. An existing file with `"host": "auto"` is still read and is left as it is until you enter an address on the screen. The bridge accepts commands only from the configured X10 IP with the matching bearer token, over a direct local connection. Keep the Wi-Fi network private: the route API uses HTTP on the LAN.

In the X10 app, set **comma manual IP** to the comma's IP, set the **API token** to the same value, and start the route service. An older X10 APK that does not send bearer authentication cannot control this branch's Galaxy destination API. Do not put the token in a URL or send it to discovered comma candidates.

## Installation and check

For a new comma installation, choose **Custom Software** and enter `nyxrobotics/SecretGoodStarPilot`. This branch has no checked-in `prebuilt` receipt because its Python sources differ from upstream; the comma builds from source on its first startup. Keep power connected and allow the build to finish. To stay on the existing stable branch, use `nyxrobotics/StarPilot` instead.

With SSH enabled, check the installed branch and the sidecar setting while parked:

```sh
ssh comma@<comma IP> 'git -C /data/openpilot status --short --branch'
ssh comma@<comma IP> 'python3 -m json.tool /data/sidecar.json >/dev/null && echo sidecar-config-ok'
```

The second command validates JSON without printing the token. In Galaxy navigation, the sidecar route source should appear as available without a Mapbox key. Send a destination from X10 and confirm that Galaxy shows the same destination and a route. Mapbox map tiles and place search still require a separate public Mapbox key; saved destinations and X10 destination sending do not. Test clear and a short route while parked before use.

After updating a comma that is already running to a build that changes the token rule or the screen, restart openpilot (for example `sudo systemctl restart comma`) or reboot, so that the running UI and Galaxy load the new code; the navigation runtime rereads the settings file by itself.

This X10 integration changes navigation and Galaxy interaction only. It does not establish that the 7.0 vehicle interface is qualified for a particular car. Do not treat a successful route preview as evidence of steering or longitudinal control performance.

## Diagnostics and behavior

**A 403 is not a 401.** The destination bridge looks at who is asking before it looks at the token. A peer that is not the configured `host` (a laptop, or an X10 whose DHCP address changed), a missing or disabled `/data/sidecar.json`, a request with a query string or an `Origin` header, a `Host` header that is not the comma's own `<comma IP>:8082`, and any proxy header all get `403 {"message": "Sidecar navigation unavailable"}`. Only the right peer with a wrong, missing or duplicated `Authorization` header gets `401` with the same message. So a 403 says that the comma is there but does not accept this caller (check `enabled`, that `host` is the X10's current address, and the Wi-Fi network), and a 401 says that the tokens differ. The other direction shows a wrong token too: the X10 answers the comma's route request with `401`, and the comma logs `sidecar: no route: the route server answered HTTP 401` (status `routeUnavailable`, a new try after 30 s).

**A phone position over HTTP needs a Galaxy session.** `POST /api/companion/location` is how a phone gives a comma without GPS fix a position. It needs a signed-in Galaxy session (and a direct local connection); the X10 bridge token opens `/api/navigation` only and deliberately not this. A bench comma therefore cannot be given a position from the X10. Without any position the status is `waitingForLocation`; with a saved position the comma draws a route preview from it (no guidance, no control).

**The saved position.** Every valid live fix is recorded in `/data/starpilot/navigation/last-position.json`. `navigationd` follows that file: deleting it, or replacing it with something that is not a position, makes it forget the position at its next update (within a second, no restart), and the preview requests stop. On a bench, a file of the form `{"version":1,"longitude":<deg>,"latitude":<deg>,"bearing":<deg>,"recordedAt":<epoch seconds>}` (`bearing` is optional, `recordedAt` must be positive; write it with mode 0600 and `mv` it into place) gives a parked comma a preview position; put it on a road.

**Route requests.** Alternatives are asked for only when the straight line from the car to the destination is at most 100 km; beyond that the X10 is asked for the main route only (on the bench a 501 km trip took 1.8 s that way, against more than 4 s and a refusal with alternatives). Every request has one 5 second deadline for connect, request and the whole answer. The X10 snaps the start of a route to its road network (up to 300 m). A route whose start the car never came within 40 m of is not a deviation: it stays on display, without giving the car control, while the car stays within 100 m of where it asked, and a new request waits until 10 s after the last one, then 20 s, 40 s and 60 s for every repeat. A route the car was on is dropped two seconds after the car left it and asked for again at once.

**Restarts.** The manager restarts `navigationd`, `galaxy` and `navtilesd` when they die, after 1, 2, 4, 8, 16 and then every 30 seconds, and forgets earlier failures after a minute of running.
