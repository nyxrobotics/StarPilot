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
