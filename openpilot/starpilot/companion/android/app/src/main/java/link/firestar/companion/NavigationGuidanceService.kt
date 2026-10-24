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
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
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
        fun enabled(prefs: android.content.SharedPreferences) = prefs.getBoolean("androidAutoGuidance", false)
    }

    private val prefs by lazy { getSharedPreferences("companion", MODE_PRIVATE) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val worker = Executors.newSingleThreadScheduledExecutor()
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var stopped = false
    @Volatile private var lastSample = 0L
    private var lastConnectAttempt = 0L
    private var own: BleTransport? = null
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
        handler.post(staleCheck)
        worker.scheduleWithFixedDelay({ tick() }, 0, 3, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "stop" || !enabled(prefs)) {
            prefs.edit().putBoolean("androidAutoGuidance", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun tick() {
        if (stopped) return
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            updateStatus("Allow Nearby devices in StarPilot")
            clearGuidance()
            return
        }
        val transport = BleTransport.active?.takeIf { it.open } ?: connect() ?: run {
            clearGuidance()
            return
        }
        try {
            val response = transport.request(JSONObject().put("method", "GET").put("path", "/api/navigation/guidance"), 10)
            if (response.getInt("status") != 200) error("Guidance unavailable")
            val encoded = response.getString("body")
            if (encoded.length > 5_500) error("Guidance exceeds limit")
            val guidance = NavigationGuidancePolicy.parse(Base64.getDecoder().decode(encoded))
            if (guidance == null) {
                clearGuidance()
                updateStatus("Connected; waiting for route")
            } else {
                lastSample = SystemClock.elapsedRealtime()
                notifications.notify(GUIDANCE_ID, guidanceNotice(guidance))
                updateStatus("Connected to comma")
            }
        } catch (_: Exception) {
            if (transport === own) { transport.close(); own = null }
            clearGuidance()
            updateStatus("Waiting for comma")
        }
    }

    /** Reuses Galaxy's authenticated BLE session, including one held by the companion UI or phone GPS. */
    private fun connect(): BleTransport? {
        val now = SystemClock.elapsedRealtime()
        if (BleTransport.active != null || BleTransport.busy || now - lastConnectAttempt < 10_000L) return null
        val pairing = PairingStore(this)
        if (pairing.stale()) { updateStatus("Pair comma again in StarPilot"); return null }
        val key = pairing.load() ?: run { updateStatus("Pair comma in StarPilot"); return null }
        lastConnectAttempt = now
        val transport = BleTransport(this, key)
        return try {
            val address = transport.connect(pairing.deviceAddress())
            pairing.rememberDevice(address)
            own = transport
            transport
        } catch (_: Exception) {
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

    private fun guidanceNotice(guidance: NavigationGuidance): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, GUIDANCE_CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(guidance.distanceLabel()).setContentText(guidance.instruction)
            .setSubText("To ${guidance.destination}").setContentIntent(open)
            .setCategory(Notification.CATEGORY_NAVIGATION).setOngoing(true).setOnlyAlertOnce(true)
            .setShowWhen(false).build()
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
        handler.removeCallbacks(staleCheck)
        worker.shutdownNow()
        clearGuidance()
        own?.takeIf { it === BleTransport.active }?.close()
        super.onDestroy()
    }
}
