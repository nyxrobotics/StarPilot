# X10 sidecar navigation on SecretGoodStarPilot

This branch integrates the X10 route server with StarPilot's 7.0 navigation runtime and adds a narrow Galaxy destination API for the X10 app. It is a development branch. Vehicle qualification for StarPilot 7.0 is still in progress, including on comma four; check the on-device build and navigation while parked before relying on it.

## Connection and settings

The comma and X10 must be able to reach each other on the same trusted local network. Give each a stable IP address. In the **X10 app**, set the comma IP manually and set a shared API token of 10–128 printable ASCII characters (no spaces). The token is used for both X10 route requests and destination commands. The X10 route server listens on port 8765 by default.

On the **comma**, create `/data/sidecar.json` with mode 0600. `host` is the **X10 IP**, not the comma IP; `token` must match the X10 app setting. Use a newly generated value, for example `openssl rand -hex 24` on a trusted machine. Do not reuse a Mapbox token. A short token is easier to guess: 10 characters is the minimum the comma accepts, and a random value of 24 or more characters is recommended on any shared network. If the X10 app still refuses a token shorter than 20 characters, update the X10 app; the comma accepts 10–128.

```json
{
  "enabled": true,
  "host": "<X10 IPv4 address>",
  "port": 8765,
  "token": "<same 10–128 character token as X10>"
}
```

`enabled` must be the JSON boolean `true`. A missing or disabled file leaves standard StarPilot routing active. A fixed private IP is required for X10 destination commands. The route engine also supports `"host": "auto"` discovery, but the X10 destination bridge deliberately rejects that setting, so use a fixed address for this integration. The bridge accepts commands only from the configured X10 IP with the matching bearer token, over a direct local connection. Keep the Wi-Fi network private: the route API uses HTTP on the LAN.

In the X10 app, set **comma manual IP** to the comma's IP, set the **API token** to the same value, and start the route service. An older X10 APK that does not send bearer authentication cannot control this branch's Galaxy destination API. Do not put the token in a URL or send it to discovered comma candidates.

## Installation and check

For a new comma installation, choose **Custom Software** and enter `nyxrobotics/SecretGoodStarPilot`. This branch has no checked-in `prebuilt` receipt because its Python sources differ from upstream; the comma builds from source on its first startup. Keep power connected and allow the build to finish. To stay on the existing stable branch, use `nyxrobotics/StarPilot` instead.

With SSH enabled, check the installed branch and the sidecar setting while parked:

```sh
ssh comma@<comma IP> 'git -C /data/openpilot status --short --branch'
ssh comma@<comma IP> 'python3 -m json.tool /data/sidecar.json >/dev/null && echo sidecar-config-ok'
```

The second command validates JSON without printing the token. In Galaxy navigation, the sidecar route source should appear as available without a Mapbox key. Send a destination from X10 and confirm that Galaxy shows the same destination and a route. Mapbox map tiles and place search still require a separate public Mapbox key; saved destinations and X10 destination sending do not. Test clear and a short route while parked before use.

This X10 integration changes navigation and Galaxy interaction only. It does not establish that the 7.0 vehicle interface is qualified for a particular car. Do not treat a successful route preview as evidence of steering or longitudinal control performance.
