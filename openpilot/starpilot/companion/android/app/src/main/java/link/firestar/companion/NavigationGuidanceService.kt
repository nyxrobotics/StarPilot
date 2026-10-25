package link.firestar.companion

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Keeps a short StarPilot maneuver on Android Auto while X10's Wi-Fi belongs to T11. */
class NavigationGuidanceService : Service() {
    companion object {
        private const val SERVICE_CHANNEL = "navigation-bridge"
        private const val GUIDANCE_CHANNEL = "navigation-guidance"
        private const val SERVICE_ID = 4
        private const val GUIDANCE_ID = 5
        private const val MAX_SAMPLE_AGE_MS = 10_000L
        // AARE reads these bounded extras from our existing navigation notification.
        private const val EXTRA_DESTINATION_LATITUDE = "link.firestar.companion.destinationLatitude"
        private const val EXTRA_DESTINATION_LONGITUDE = "link.firestar.companion.destinationLongitude"
        private const val EXTRA_ROUTE_KEY = "link.firestar.companion.routeKey"
        private const val EXTRA_ROUTE_COORDINATES = "link.firestar.companion.routeCoordinates"
        fun guidanceEnabled(prefs: android.content.SharedPreferences) = prefs.getBoolean("androidAutoGuidance", false)
        fun relayEnabled(prefs: android.content.SharedPreferences) = prefs.getBoolean("sidecarRelay", false)
        fun enabled(prefs: android.content.SharedPreferences) = guidanceEnabled(prefs) || relayEnabled(prefs)
    }

    private val prefs by lazy { getSharedPreferences("companion", MODE_PRIVATE) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var stopped = false
    @Volatile private var lastSample = 0L
    private var lastConnectAttempt = 0L
    private var own: BleTransport? = null
    @Volatile private var sidecarRelay: SidecarLoopbackServer? = null
    private var routeWorker: SidecarRouteWorker? = null
    private var lastRelayBindAttempt = -10_000L
    private val routeCache = NavigationRouteCache()
    private var status = ""
    private val staleCheck = object : Runnable {
        override fun run() {
            if (stopped) return
            if (lastSample == 0L || SystemClock.elapsedRealtime() - lastSample > MAX_SAMPLE_AGE_MS) clearGuidance()
            handler.postDelayed(this, 2_000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(NotificationChannel(SERVICE_CHANNEL, "StarPilot connection", NotificationManager.IMPORTANCE_LOW))
        notifications.createNotificationChannel(NotificationChannel(GUIDANCE_CHANNEL, "StarPilot guidance", NotificationManager.IMPORTANCE_LOW).apply {
            setSound(null, null)
            enableVibration(false)
        })
        try {
            startForeground(SERVICE_ID, serviceNotice("Waiting for comma"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (_: SecurityException) {
            prefs.edit().putBoolean("androidAutoGuidance", false).apply()
            stopSelf()
            return
        }
        refreshRelay()
        handler.post(staleCheck)
        worker.scheduleWithFixedDelay({ tick() }, 0, 3, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop" || !enabled(prefs)) {
            prefs.edit().putBoolean("androidAutoGuidance", false).apply()
            prefs.edit().putBoolean("sidecarRelay", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        refreshRelay()
        return START_STICKY
    }

    @Synchronized private fun refreshRelay() {
        if (!relayEnabled(prefs)) {
            sidecarRelay?.close()
            sidecarRelay = null
            routeWorker?.close()
            routeWorker = null
            return
        }
        if (routeWorker == null) routeWorker = SidecarRouteWorker {
            // The guidance service owns reconnects. Idle route polling must not
            // start another GATT handshake while Sidecar is waiting for status.
            BleTransport.active?.takeIf { it.open }
        }.apply {
            warning = { message ->
                val binding = if (message.startsWith("Route fetch")) {
                    val manager = Networks.manager(this@NavigationGuidanceService)
                    val bound = manager.boundNetworkForProcess
                    " (boundNetwork=${bound ?: "none"}, boundActive=${bound == null || manager.getNetworkCapabilities(bound) != null})"
                } else ""
                android.util.Log.w("StarPilotSidecarBLE", message + binding)
            }
        }
        val now = SystemClock.elapsedRealtime()
        if (sidecarRelay == null && now - lastRelayBindAttempt >= 10_000L) {
            lastRelayBindAttempt = now
            sidecarRelay = try {
                SidecarLoopbackServer { request ->
                    val transport = BleTransport.active?.takeIf { it.open } ?: connect()
                        ?: throw IOException("comma Bluetooth unavailable")
                    val reply = transport.request(request, 15)
                    if (request.optString("method") == "POST" && reply.optInt("status") == 200)
                        routeWorker?.routeSelected()
                    reply
                }
            } catch (error: IOException) {
                android.util.Log.w("StarPilotSidecarBLE", "Could not bind Sidecar loopback relay", error)
                null
            }
        }
    }

    private fun tick() {
        if (stopped) return
        if (relayEnabled(prefs) && sidecarRelay == null) refreshRelay()
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            updateStatus("Allow Nearby devices in StarPilot")
            clearGuidance()
            return
        }
        val transport = BleTransport.active?.takeIf { it.open } ?: connect() ?: run {
            clearGuidance()
            return
        }
        if (!guidanceEnabled(prefs)) {
            clearGuidance()
            updateStatus(if (sidecarRelay == null) "Sidecar Bluetooth relay unavailable" else "Sidecar Bluetooth ready")
            return
        }
        try {
            val response = transport.request(JSONObject().put("method", "GET").put("path", routeCache.queryPath()), 20)
            if (response.getInt("status") != 200) error("Guidance unavailable")
            val encoded = response.getString("body")
            if (encoded.length > 44_000) error("Guidance exceeds limit")
            val sample = NavigationGuidancePolicy.parseMapSample(Base64.getDecoder().decode(encoded))
                ?: error("Invalid map guidance")
            routeCache.apply(sample)
            val guidance = sample.guidance
            if (guidance == null) {
                clearGuidance()
                updateStatus("Connected; waiting for route")
            } else {
                lastSample = SystemClock.elapsedRealtime()
                notifications.notify(GUIDANCE_ID, guidanceNotice(guidance, sample.destinationCoordinates))
                updateStatus("Connected to comma")
            }
        } catch (error: Exception) {
            android.util.Log.w("StarPilotSidecarBLE", "Guidance poll failed (${error.javaClass.simpleName})")
            if (transport === own) { transport.close(); own = null }
            clearGuidance()
            updateStatus("Waiting for comma")
        }
    }

    /** Reuses Galaxy's authenticated BLE session, including one held by the companion UI or phone GPS. */
    @Synchronized private fun connect(): BleTransport? {
        val now = SystemClock.elapsedRealtime()
        if (BleTransport.active != null || BleTransport.busy || now - lastConnectAttempt < 10_000L) return null
        val pairing = PairingStore(this)
        if (pairing.stale()) { updateStatus("Pair comma again in StarPilot"); return null }
        val key = pairing.load() ?: run { updateStatus("Pair comma in StarPilot"); return null }
        lastConnectAttempt = now
        var phase = "start"
        fun newTransport() = BleTransport(this, key).apply {
            onPhase = { text -> phase = when {
                text.startsWith("Looking") -> "scan"
                text.startsWith("Found") -> "gatt-connect"
                text.startsWith("Checking") -> "service-discovery"
                text.startsWith("Reading") -> "challenge-read"
                text.startsWith("Authenticating") -> "bridge-health"
                else -> "other"
            } }
        }
        fun failure(error: Exception): String {
            val gatt = error as? GattEventFailure
            return "${error.javaClass.simpleName}, phase=$phase" +
                (gatt?.let { ", expected=${it.expected}, observed=${it.observed}, status=${it.status}" } ?: "")
        }
        var transport = newTransport()
        return try {
            val saved = pairing.deviceAddress()
            val address = try { transport.connect(saved) } catch (first: Exception) {
                if (saved == null || first is PairingNotRecognized || first is GattDenied) throw first
                android.util.Log.w("StarPilotSidecarBLE", "BLE direct connect failed (${failure(first)}); scanning")
                transport.close()
                pairing.forgetDeviceAddress()
                phase = "start"
                transport = newTransport()
                transport.connect()
            }
            pairing.rememberDevice(address)
            own = transport
            transport
        } catch (error: Exception) {
            android.util.Log.w("StarPilotSidecarBLE", "BLE connect failed (${failure(error)})")
            transport.close()
            updateStatus("Waiting for comma")
            null
        }
    }

    private fun serviceNotice(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, NavigationGuidanceService::class.java).setAction("stop"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, SERVICE_CHANNEL).setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("StarPilot guidance bridge").setContentText(text).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build()).build()
    }

    private fun guidanceNotice(guidance: NavigationGuidance, destination: DoubleArray?): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val mapExtras = Bundle().apply {
            if (destination != null) {
                putDouble(EXTRA_DESTINATION_LONGITUDE, destination[0])
                putDouble(EXTRA_DESTINATION_LATITUDE, destination[1])
            }
            routeCache.routeKey?.let { putString(EXTRA_ROUTE_KEY, it) }
            putDoubleArray(EXTRA_ROUTE_COORDINATES, routeCache.coordinates)
        }
        return Notification.Builder(this, GUIDANCE_CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(guidance.distanceLabel()).setContentText(guidance.instruction)
            .setSubText("To ${guidance.destination}").setContentIntent(open)
            .setCategory(Notification.CATEGORY_NAVIGATION).setOngoing(true).setOnlyAlertOnce(true)
            .setShowWhen(false).addExtras(mapExtras).build()
    }

    private fun updateStatus(text: String) {
        if (stopped || status == text) return
        status = text
        notifications.notify(SERVICE_ID, serviceNotice(text))
    }

    private fun clearGuidance() {
        lastSample = 0L
        notifications.cancel(GUIDANCE_ID)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopped = true
        sidecarRelay?.close()
        routeWorker?.close()
        handler.removeCallbacks(staleCheck)
        worker.shutdownNow()
        clearGuidance()
        own?.takeIf { it === BleTransport.active }?.close()
        super.onDestroy()
    }
}
