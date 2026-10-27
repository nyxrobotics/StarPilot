package link.firestar.companion

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import org.json.JSONObject
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** comma refused GATT access: it has no paired phones, or not this one. */
class GattDenied(status: Int) : IOException("Bluetooth access denied ($status)")
/** Android's GATT callback failed before a Galaxy response; fields are safe to log without credentials. */
class GattEventFailure(val expected: String, val observed: String, val status: Int) : IOException("Bluetooth GATT operation failed")
/** The link works but comma never answered the authenticated check: it no longer knows this phone's key. */
class PairingNotRecognized(message: String) : IOException(message)

internal data class GattEvent(val kind: String, val status: Int, val data: ByteArray = byteArrayOf())

/** MTU updates can arrive before service discovery (or between later requests). */
internal class GattEventMailbox {
    private val events = LinkedBlockingQueue<GattEvent>()
    fun offer(event: GattEvent) { events.offer(event) }
    fun clear() { events.clear() }
    fun await(kind: String, seconds: Long, closed: () -> Boolean): ByteArray {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds)
        while (true) {
            val remaining = deadline - System.nanoTime()
            val event = (if (remaining > 0) events.poll(remaining, TimeUnit.NANOSECONDS) else null)
                ?: throw IOException("Bluetooth timed out. Reconnect before retrying; a change may already have applied.")
            // This callback is informational; it is not the completion of a
            // service discovery, characteristic read or write.
            if (!closed() && event.kind == "mtu" && event.status == BluetoothGatt.GATT_SUCCESS && kind != "mtu") continue
            if (!closed() && event.kind == kind && event.status in setOf(5, 8, 15)) throw GattDenied(event.status)
            if (closed() || event.kind != kind || event.status != BluetoothGatt.GATT_SUCCESS)
                throw GattEventFailure(kind, event.kind, event.status)
            return event.data
        }
    }
}

/** Serialized GATT operations; a failed mutation is never replayed. Call from a worker thread. */
@SuppressLint("MissingPermission")
/** [hello] ({"name", "replaces"}) is sent once with the first request so comma can label this phone and drop its old key. */
class BleTransport(context: Context, private val key: ByteArray?, private val hello: JSONObject? = null) : Closeable, RouteLink {
    /** Reports what the connection is doing, so a slow pairing shows where it waits. */
    @Volatile var onPhase: (String) -> Unit = {}
    private val context: Context = context.applicationContext
    companion object {
        fun uuid(part: String): UUID = UUID.fromString("bd49000$part-6dc1-4de7-a7d0-6cdb441f7650")
        // comma keeps one session per phone, so every sender shares the newest connection.
        @SuppressLint("StaticFieldLeak") @Volatile var active: BleTransport? = null
            private set
        private val connecting = java.util.concurrent.atomic.AtomicInteger()
        val busy get() = connecting.get() > 0
        /** Runs after every successful handshake, on the thread that connected (the Sidecar relay starts the push channel and the job poll from it). */
        @Volatile internal var handshakeHook: ((BleTransport) -> Unit)? = null
        private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
    /** Every request of this connection goes through the lane: one in flight, strict priority, a stall closes the link and fails what is queued (BleLane.kt). */
    private val laneLink = object : LaneLink {
        override fun exchange(meta: JSONObject, body: ByteArray?, deadlineSeconds: Long) = this@BleTransport.exchange(meta, body, deadlineSeconds)
        override fun control(action: String) = this@BleTransport.control(action)
        override fun setHighPriority(on: Boolean) = this@BleTransport.setHighPriority(on)
        override fun close() = this@BleTransport.close()
    }
    internal val lane: BleLane = BleLane(laneLink)
    /** What comma's bridge announced at the handshake (`/_bridge/health`): protocol 2 and its flags, or the legacy answer. */
    @Volatile internal var features: BridgeFeatures = BridgeFeatures.LEGACY
        private set
    /** The RX characteristic accepts write without response (a bridge that has the lever): data packets of a raw-body request go without a round trip each. */
    @Volatile internal var canWriteNoResponse = false
        private set
    /** The push characteristic (number 6) is in the service. */
    @Volatile internal var hasPushCharacteristic = false
        private set
    /** Receives every notification of the push characteristic (set by the push receiver of this connection). */
    @Volatile internal var pushSink: ((ByteArray) -> Unit)? = null
    val open get() = !closed && session.isNotEmpty()
    private val events = GattEventMailbox()
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var closed = false
    @Volatile private var observedMtu: Int? = null
    private var session = ""
    private var challenge = ByteArray(0)
    private var counter = 0L
    private var packetSize = 20
    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            events.offer(GattEvent(if (state == BluetoothProfile.STATE_CONNECTED) "connect" else "disconnect", status))
        }
        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) observedMtu = mtu
            events.offer(GattEvent("mtu", status, ByteBuffer.allocate(4).putInt(mtu).array()))
        }
        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { events.offer(GattEvent("services", status)) }
        @Deprecated("Pre Android 13 callback") override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) events.offer(GattEvent("read", status, c.value ?: byteArrayOf()))
        }
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            events.offer(GattEvent("read", status, value.copyOf()))
        }
        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { events.offer(GattEvent("write", status)) }
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { events.offer(GattEvent("descriptor", status)) }
        @Deprecated("Pre Android 13 callback") override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33 && c.uuid == uuid("6")) c.value?.let { pushSink?.invoke(it.copyOf()) }
        }
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (c.uuid == uuid("6")) pushSink?.invoke(value.copyOf())
        }
    }
    private fun await(kind: String, seconds: Long = 12): ByteArray = events.await(kind, seconds) { closed }
    private fun characteristic(part: String) = gatt?.getService(uuid("1"))?.getCharacteristic(uuid(part)) ?: throw IOException("Galaxy Bluetooth service unavailable")
    private fun read(part: String): ByteArray {
        if (gatt?.readCharacteristic(characteristic(part)) != true) throw IOException("Bluetooth read unavailable")
        return await("read")
    }
    /** One data packet to RX. Without [noResponse] the stack waits for comma's response (two to three connection intervals); with it the packet goes as soon as the controller has room. */
    private fun write(value: ByteArray, noResponse: Boolean = false) {
        val g = gatt ?: throw IOException("Bluetooth disconnected")
        val c = characteristic("2")
        val type = if (noResponse) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        val sent = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, value, type) == BluetoothStatusCodes.SUCCESS
            else { c.writeType = type; c.value = value; g.writeCharacteristic(c) }
        if (!sent) throw IOException("Bluetooth write unavailable")
        await("write")
    }
    /** Returns the authenticated device address so the next connection can skip scanning. */
    fun connect(preferredAddress: String? = null): String {
        val address = connectLocked(preferredAddress)
        // After EVERY handshake, outside the monitor of the handshake: the hook uses the lane (push-enable, the first job poll).
        try { handshakeHook?.invoke(this) } catch (error: Exception) { Log.w("StarPilotSidecarBLE", "Handshake hook failed (${error.javaClass.simpleName})") }
        return address
    }
    @Synchronized private fun connectLocked(preferredAddress: String?): String {
        check(!closed)
        connecting.incrementAndGet()
        try { return connectOnce(preferredAddress) } finally { connecting.decrementAndGet() }
    }
    private fun connectOnce(preferredAddress: String?): String {
        val device = try { linkUp(preferredAddress) } catch (e: GattDenied) {
            throw PairingNotRecognized("comma has no paired phone matching this one")
        }
        try {
            // Authenticate before exposing cached controls. Do not trust the advertised device name.
            onPhase("Authenticating comma…")
            val health = JSONObject().put("method", "GET").put("path", "/_bridge/health").apply { hello?.let { put("phone", it) } }
            // Straight to the link: the lane starts after the handshake, and this thread holds the monitor the lane's thread needs.
            val response = try { exchange(health, null, 20) } catch (e: ResponseTimeout) {
                throw PairingNotRecognized("comma didn't recognize this phone's pairing")
            }
            if (response.getInt("status") != 200) throw IOException("Galaxy Bluetooth authentication failed")
            features = try { BridgeFeatures.parse(java.util.Base64.getDecoder().decode(response.optString("body", ""))) } catch (_: IllegalArgumentException) { BridgeFeatures.LEGACY }
            noteServiceShape()
            if (features.push && !hasPushCharacteristic) {
                // The bridge says it has the push characteristic and this phone's copy of the service does not: Android kept an old cached database. Ask for a fresh one, once.
                if (refreshServices()) noteServiceShape()
            }
            val previous = active
            active = this
            if (previous !== this) previous?.close()
            return device.address
        } catch (e: Exception) { close(); throw e }
    }
    /** Scans, connects and reads the session challenge; nothing is authenticated yet. */
    private fun linkUp(preferredAddress: String?): BluetoothDevice {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: throw IOException("Bluetooth is unavailable")
        if (!adapter.isEnabled) throw IOException("Enable Bluetooth on your phone and comma")
        val remembered = preferredAddress?.let { runCatching { adapter.getRemoteDevice(it) }.getOrNull() }
        val device = remembered ?: run {
            onPhase("Looking for comma over Bluetooth…")
            val scanner = adapter.bluetoothLeScanner ?: throw IOException("Bluetooth scanner unavailable")
            val found = LinkedBlockingQueue<BluetoothDevice>()
            val scan = object : ScanCallback() {
                override fun onScanResult(type: Int, result: ScanResult) { found.offer(result.device) }
                override fun onScanFailed(code: Int) { events.offer(GattEvent("scan-failed", code)) }
            }
            scanner.startScan(listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(uuid("1"))).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scan)
            try { found.poll(12, TimeUnit.SECONDS) } finally { scanner.stopScan(scan) }
                ?: throw IOException("No Galaxy Bluetooth bridge found. Enable Bluetooth and install the bridge on comma.")
        }
        if (closed) throw IOException("Connection canceled")
        events.clear()
        onPhase("Found comma. Connecting…")
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        try {
            await("connect", if (remembered != null) 4 else 12)
            // comma's controller can negotiate LE 2M even when its signal is
            // weak. Prefer the longer-range 1M PHY before route traffic starts.
            try {
                gatt?.setPreferredPhy(BluetoothDevice.PHY_LE_1M_MASK, BluetoothDevice.PHY_LE_1M_MASK,
                    BluetoothDevice.PHY_OPTION_NO_PREFERRED)
            } catch (error: Exception) {
                Log.w("StarPilotSidecarBLE", "Could not request 1M PHY (${error.javaClass.simpleName})")
            }
            onPhase("Checking comma’s Bluetooth service…")
            if (gatt?.discoverServices() != true) throw IOException("Bluetooth discovery failed")
            await("services")
            // ATT MTU 247 plus its three-byte write header fits comma's 251
            // byte HCI ACL PDU, avoiding three radio fragments per packet.
            val mtu = observedMtu ?: if (gatt?.requestMtu(247) == true) ByteBuffer.wrap(await("mtu")).int else null
            if (mtu != null) packetSize = (mtu - 3).coerceIn(20, 244)
            onPhase("Reading comma’s secure pairing challenge…")
            val info = read("4")
            require(info.size == 17 && info[0].toInt() == 1)
            challenge = info.copyOfRange(1, 17)
            session = info.drop(1).joinToString("") { "%02x".format(it.toInt() and 255) }
            return device
        } catch (e: Exception) { close(); throw e }
    }
    /** Asks the comma whose pairing window is open for a key. Returns the session challenge and comma's reply. */
    @Synchronized fun claim(body: ByteArray): Pair<ByteArray, ByteArray> {
        check(!closed)
        connecting.incrementAndGet()
        try {
            try { linkUp(null) } catch (e: GattDenied) {
                throw IOException("comma's pairing window isn't open. Open Pair phone on comma first (Settings > Bluetooth > Pair phone), then try again.")
            }
            onPhase("Pairing: sending this phone’s request to comma…")
            val frame = ByteBuffer.allocate(4 + body.size).putInt(body.size).put(body).array()
            for (packet in BleWire.packets(frame, packetSize)) write(packet)
            onPhase("Pairing: waiting for comma’s secure reply…")
            val assembler = BleWire.Assembler()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
            while (System.nanoTime() < deadline) {
                val packet = read("3")
                if (packet.contentEquals(byteArrayOf(0))) { Thread.sleep(30); continue }
                val result = assembler.add(packet)
                if (result != null) {
                    onPhase("Pairing: receiving comma’s secure reply…")
                    write(ByteBuffer.allocate(5).put(2).put(packet, 1, 4).array())
                    return challenge to result
                }
            }
            throw IOException("comma didn't answer. Open Pair phone on comma first (Settings > Bluetooth > Pair phone) and keep it open, then tap Add Bluetooth Phone Pairing again.")
        } catch (e: Exception) { close(); throw e } finally { connecting.decrementAndGet() }
    }
    /** An interactive request: the relay's calls, the guidance poll, the phone GPS, the Galaxy screens. */
    override fun request(value: JSONObject, deadlineSeconds: Long): JSONObject = lane.call(LaneClass.INTERACTIVE, deadlineSeconds, value)
    override fun request(value: JSONObject, deadlineSeconds: Long, cls: LaneClass): JSONObject = lane.call(cls, deadlineSeconds, value)

    /** One request and its answer on the link itself; only the lane's thread (and the handshake) calls it. A failure closes the connection. */
    @Synchronized internal fun exchange(meta: JSONObject, body: ByteArray?, deadlineSeconds: Long): JSONObject {
        check(!closed && session.isNotEmpty())
        val id = UUID.randomUUID().toString()
        val count = ++counter
        try {
            val io = object : GattIo {
                override fun write(packet: ByteArray, noResponse: Boolean) = this@BleTransport.write(packet, noResponse)
                override fun read(): ByteArray = this@BleTransport.read("3")
                override fun pause(millis: Long) = Thread.sleep(millis)
            }
            return BleExchange.run(io, key ?: throw IOException("Not paired"), session, count, id, meta, body, deadlineSeconds, packetSize, canWriteNoResponse)
        } catch (e: Exception) { close(); throw e }
    }

    /** Operations that take a turn on the lane but are not requests. */
    @Synchronized internal fun control(action: String): JSONObject {
        check(!closed)
        try {
            when (action) {
                "subscribe" -> subscribeToPush()
                else -> throw IOException("Unknown link operation")
            }
            return JSONObject().put("status", 200)
        } catch (e: Exception) { close(); throw e }
    }

    private fun subscribeToPush() {
        val g = gatt ?: throw IOException("Bluetooth disconnected")
        val c = characteristic("6")
        if (!g.setCharacteristicNotification(c, true)) throw IOException("Bluetooth notification unavailable")
        val descriptor = c.getDescriptor(CCCD) ?: throw IOException("Bluetooth notification descriptor unavailable")
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val sent = if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(descriptor, enable) == BluetoothStatusCodes.SUCCESS
            else { descriptor.value = enable; g.writeDescriptor(descriptor) }
        if (!sent) throw IOException("Bluetooth notification write unavailable")
        await("descriptor")
    }

    /** The shape of the service as discovered: push characteristic present, write without response on RX. */
    private fun noteServiceShape() {
        val service = gatt?.getService(uuid("1"))
        hasPushCharacteristic = service?.getCharacteristic(uuid("6")) != null
        canWriteNoResponse = service?.getCharacteristic(uuid("2"))?.let { it.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0 } == true
    }

    /** Drops Android's cached copy of comma's service and discovers it again (the cache is not refreshed on its own when the bridge gained a characteristic). */
    @SuppressLint("DiscouragedPrivateApi")
    private fun refreshServices(): Boolean {
        val g = gatt ?: return false
        return try {
            if (g.javaClass.getMethod("refresh").invoke(g) != true) return false
            events.clear()
            if (!g.discoverServices()) return false
            await("services")
            true
        } catch (_: Exception) { false }
    }

    /** What the push receiver needs of this connection. */
    internal val pushLink = object : PushLink {
        override val pushKey: ByteArray get() = key ?: throw IOException("Not paired")
        override val pushSession: String get() = session
        override fun subscribe() { lane.control(LaneClass.SMALL, 12, "subscribe") }
        override val carriesNav: Boolean get() = features.pushNav
        override fun pushEnable(): Int = lane.call(LaneClass.SMALL, 10, PushEnable.request(features.pushNav), label = "push-enable").optInt("status")
    }

    internal val jobsV2: Boolean get() = features.jobsV2 && open
    /** Write without response is not available: bytes cost more than the encoder's time, so a route goes as `xz` (the fallback of the design). */
    internal val preferXz: Boolean get() = features.xz && !canWriteNoResponse

    /** The legacy route worker asks for the high priority while it streams chunks; the lane's policy now owns the setting and gives it back after the idle time. */
    override fun routePriority(high: Boolean) { if (high) lane.wantHigh() }
    /** Not synchronized: the request for the high priority must not wait for the exchange that is running. */
    internal fun setHighPriority(high: Boolean) {
        if (closed) return
        try {
            val priority = if (high) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED
            if (gatt?.requestConnectionPriority(priority) != true)
                Log.w("StarPilotSidecarBLE", "Could not change route connection priority")
        } catch (error: Exception) {
            Log.w("StarPilotSidecarBLE", "Could not change route connection priority (${error.javaClass.simpleName})")
        }
    }
    override fun close() {
        closed = true
        pushSink = null
        lane.close()
        if (active === this) active = null
        val old = gatt; gatt = null
        old?.disconnect(); old?.close()
        events.offer(GattEvent("disconnect", -1))
    }
}
