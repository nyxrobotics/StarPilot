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
private class ResponseTimeout : IOException("Bluetooth response timed out. Check the result before retrying a change.")

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
    }
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
    }
    private fun await(kind: String, seconds: Long = 12): ByteArray = events.await(kind, seconds) { closed }
    private fun characteristic(part: String) = gatt?.getService(uuid("1"))?.getCharacteristic(uuid(part)) ?: throw IOException("Galaxy Bluetooth service unavailable")
    private fun read(part: String): ByteArray {
        if (gatt?.readCharacteristic(characteristic(part)) != true) throw IOException("Bluetooth read unavailable")
        return await("read")
    }
    private fun write(value: ByteArray) {
        val g = gatt ?: throw IOException("Bluetooth disconnected")
        val c = characteristic("2")
        val sent = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            else { c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT; c.value = value; g.writeCharacteristic(c) }
        if (!sent) throw IOException("Bluetooth write unavailable")
        await("write")
    }
    /** Returns the authenticated device address so the next connection can skip scanning. */
    @Synchronized fun connect(preferredAddress: String? = null): String {
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
            val response = try { request(health, 20) } catch (e: ResponseTimeout) {
                throw PairingNotRecognized("comma didn't recognize this phone's pairing")
            }
            if (response.getInt("status") != 200) throw IOException("Galaxy Bluetooth authentication failed")
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
    @Synchronized override fun request(value: JSONObject, deadlineSeconds: Long): JSONObject {
        check(!closed && session.isNotEmpty())
        val id = UUID.randomUUID().toString()
        val count = ++counter
        val request = JSONObject(value.toString()).put("id", id).put("session", session).put("counter", count).put("responseCodec", 2).put("responseFlow", "read-stream")
        // Upstream read-stream flow acknowledges once after authenticated reassembly.
        try {
            for (packet in BleWire.packets(BleWire.seal(request.toString().toByteArray(), key ?: throw IOException("Not paired")), packetSize)) write(packet)
            val assembler = BleWire.Assembler()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(deadlineSeconds)
            while (System.nanoTime() < deadline) {
                val packet = read("3")
                if (packet.contentEquals(byteArrayOf(0))) { Thread.sleep(30); continue }
                val result = assembler.add(packet)
                if (result != null) {
                    val decoded = BleWire.decode(result, key ?: throw IOException("Not paired"))
                    val response = JSONObject(String(decoded.json, Charsets.UTF_8))
                    decoded.body?.let { require(!response.has("body")); response.put("body", java.util.Base64.getEncoder().encodeToString(it)) }
                    require(response.getString("id") == id && response.getString("session") == session && response.getLong("counter") == count)
                    write(ByteBuffer.allocate(5).put(2).put(packet, 1, 4).array())
                    return response
                }
            }
            throw ResponseTimeout()
        } catch (e: Exception) { close(); throw e }
    }
    /** Favor a short connection interval while streaming a route, then restore Android's default. */
    @Synchronized override fun routePriority(high: Boolean) {
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
        if (active === this) active = null
        val old = gatt; gatt = null
        old?.disconnect(); old?.close()
        events.offer(GattEvent("disconnect", -1))
    }
}
