package com.n65ii.app.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.ParcelUuid
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.n65ii.app.model.AdvertisementSnapshot
import com.n65ii.app.model.BleUiState
import com.n65ii.app.model.DiscoveredDevice
import com.n65ii.app.model.NaveeAutoFirmwareUi
import com.n65ii.app.model.NaveeFirmwareStockUi
import com.n65ii.app.model.NaveeMode4LogUi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * Dedicated NAVEE N65i II 6001 manager.
 *
 * Scope is intentionally narrow:
 * - exact N65i II 6001 / Meter 2058 / BLDC 0005 identification
 * - OEM-compatible NAVEE session bring-up / anonymous auth fallback
 * - normal NAVEE settings controls used by TOOLS
 * - the preserved N65i II mode-cap BLDC patch + hidden mode-4 release + target-2 DFU/stock restore
 *
 * No multi-model firmware catalogue or cross-flash route is present in this app.
 */
@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {
    companion object {
        private const val N65_METER = "2058"
        private const val N65_BLDC = "0005"
        private const val N65_MODEL = "6001"
        private const val N65_PID = "2509"
        private const val MIN_DFU_BATTERY = 50
        private const val MIN_DFU_MTU = 136
        private const val SESSION_MTU = 148
        private const val WRITE_GAP_MS = 200L
        private const val ARM_WINDOW_MS = 30_000L
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    private val btManager = context.getSystemService(BluetoothManager::class.java)
    private val adapter = btManager?.adapter
    private val scanner get() = adapter?.bluetoothLeScanner
    private val main = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("n65ii_speed_modes", Context.MODE_PRIVATE)

    private val initialAuto = NaveeAutoFirmwareUi(
        ecoSpeedKmh = prefs.getInt("eco", 6).coerceIn(1, 60),
        driveSpeedKmh = prefs.getInt("drive", 15).coerceIn(1, 60),
        sportSpeedKmh = prefs.getInt("sport", 20).coerceIn(1, 60),
        releaseSpeedKmh = prefs.getInt("release", 40).coerceIn(1, 60),
        mode4Verified = prefs.getBoolean("mode4_verified", false),
        mode4VolatileVerified = prefs.getBoolean("mode4_volatile_verified", false)
    )
    private val initialMode4Log = NaveeMode4LogUi(
        active = prefs.getBoolean("mode4_log_active", false),
        phase = prefs.getString("mode4_log_phase", "IDLE") ?: "IDLE",
        startedAtEpochMs = prefs.getLong("mode4_log_started", 0L),
        baselineMode = prefs.getInt("mode4_log_baseline_mode", -1).takeIf { it >= 0 },
        mode4Mode = prefs.getInt("mode4_log_mode4_mode", -1).takeIf { it >= 0 },
        mode6Mode = prefs.getInt("mode4_log_mode6_mode", -1).takeIf { it >= 0 },
        mode7Mode = prefs.getInt("mode4_log_mode7_mode", -1).takeIf { it >= 0 },
        selectedCandidateMode = prefs.getInt("mode4_log_selected_mode", -1).takeIf { it >= 0 },
        postRebootMode = prefs.getInt("mode4_log_post_mode", -1).takeIf { it >= 0 },
        baselineRaw70 = prefs.getString("mode4_log_baseline_raw70", null),
        mode4Raw70 = prefs.getString("mode4_log_mode4_raw70", null),
        mode6Raw70 = prefs.getString("mode4_log_mode6_raw70", null),
        mode7Raw70 = prefs.getString("mode4_log_mode7_raw70", null),
        selectedCandidateRaw70 = prefs.getString("mode4_log_selected_raw70", null),
        postRebootRaw70 = prefs.getString("mode4_log_post_raw70", null),
        conclusion = prefs.getString("mode4_log_conclusion", "NOT RUN") ?: "NOT RUN",
        selectedReason = prefs.getString("mode4_log_selected_reason", "") ?: "",
        snapshotText = prefs.getString("mode4_log_snapshots", "") ?: "",
        notifyText = prefs.getString("mode4_log_notifies", "") ?: "",
        logText = prefs.getString("mode4_log_text", "") ?: ""
    )
    private val _state = MutableStateFlow(
        BleUiState(
            bluetoothAvailable = adapter != null,
            naveeSpeedResearch = com.n65ii.app.model.NaveeSpeedResearchUi(
                firmwareStock = NaveeFirmwareStockUi(auto = initialAuto),
                mode4Log = initialMode4Log
            )
        )
    )
    val state: StateFlow<BleUiState> = _state.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var rx: BluetoothGattCharacteristic? = null
    private var selectedAddress: String? = null
    private var connectedAtElapsed = 0L
    private var manualDisconnect = false
    private var forceAuthNextConnect = false
    private var lastSessionUsedAuth = false
    private var notifyReady = false
    private var mtuFallbackGeneration = 0L

    private val normalQueue = ArrayDeque<ByteArray>()
    private var normalWriting = false
    private val rxBuffer = ArrayDeque<Byte>()
    private var modeSweepRunId = 0L

    private var authPending = false
    private var authRounds = 0
    private var authWireId = 1L
    private var authTimeout: Runnable? = null

    private data class SpeedPlan(
        val stock: ByteArray,
        val patched: ByteArray,
        val patchedSha: String,
        val eco: Int,
        val drive: Int,
        val sport: Int,
        val release: Int
    )
    private var speedPlan: SpeedPlan? = null
    private var armDeadline = 0L
    private var armRestore = false

    private var dfu: NaveeDfuEngine? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val dfuLog = ArrayDeque<String>()

    private val armTick = object : Runnable {
        override fun run() {
            val remaining = ((armDeadline - SystemClock.elapsedRealtime() + 999L) / 1000L).toInt()
            if (armDeadline <= 0L || remaining <= 0) {
                armDeadline = 0L
                updateAuto { it.copy(armed = false, flashReady = false, armSecondsRemaining = 0, status = "ARM EXPIRED — RUN PREFLIGHT AGAIN") }
                return
            }
            updateAuto { it.copy(armed = true, flashReady = true, armSecondsRemaining = remaining) }
            main.postDelayed(this, 500L)
        }
    }

    private val pollTask = object : Runnable {
        private var tick = 0
        override fun run() {
            if (!_state.value.connected) return
            if (notifyReady && dfu?.isRunning() != true) {
                enqueueFrame(NaveeProtocol.buildRead(0x92))
                tick++
                if (tick % 2 == 0) enqueueFrame(NaveeProtocol.buildRead(0x70))
                if (tick % 5 == 0) enqueueFrame(NaveeProtocol.buildRead(0x72))
            }
            main.postDelayed(this, 1_000L)
        }
    }

    fun recommendedPermissions(): Array<String> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun hasPermissions(): Boolean = recommendedPermissions().all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    fun startScan(compatibilityMode: Boolean = false) {
        if (!hasPermissions()) {
            _state.update { it.copy(scanning = false, scanError = "Bluetooth permissions missing") }
            return
        }
        if (adapter?.isEnabled != true || scanner == null) {
            _state.update { it.copy(scanning = false, scanError = "Bluetooth scanner unavailable") }
            return
        }
        stopScan()
        _state.update { it.copy(scanning = true, scanError = null, discovered = emptyList()) }
        runCatching {
            if (compatibilityMode) scanner?.startScan(scanCallback)
            else scanner?.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).setReportDelay(0L).build(),
                scanCallback
            )
        }.onFailure { err ->
            _state.update { it.copy(scanning = false, scanError = err.message ?: err.javaClass.simpleName) }
        }
    }

    fun stopScan() {
        runCatching { scanner?.stopScan(scanCallback) }
        _state.update { it.copy(scanning = false) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = addScanResult(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::addScanResult)
        override fun onScanFailed(errorCode: Int) {
            _state.update { it.copy(scanning = false, scanError = "BLE scan failed ($errorCode)") }
        }
    }

    private fun addScanResult(result: ScanResult) {
        val record = result.scanRecord
        val uuids = record?.serviceUuids?.map(ParcelUuid::toString).orEmpty()
        val device = DiscoveredDevice(
            name = record?.deviceName ?: runCatching { result.device.name }.getOrNull(),
            address = result.device.address,
            rssi = result.rssi,
            advertisement = AdvertisementSnapshot(serviceUuids = uuids, connectable = result.isConnectable)
        )
        _state.update { s ->
            val next = s.discovered.filterNot { it.address == device.address } + device
            s.copy(discovered = next.sortedByDescending { it.rssi })
        }
    }

    fun connect(device: DiscoveredDevice) {
        selectedAddress = device.address
        stopScan()
        connectAddress(device.address)
    }

    private fun connectAddress(address: String) {
        if (!hasPermissions()) return
        val remote = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return
        manualDisconnect = false
        notifyReady = false
        tx = null
        rx = null
        normalQueue.clear()
        normalWriting = false
        rxBuffer.clear()
        gatt?.close()
        gatt = null
        _state.update {
            it.copy(
                connecting = true,
                connected = false,
                naveeNotifySubscribed = false,
                naveeVendorAuthState = if (forceAuthNextConnect) "AUTH RETRY PENDING" else "PLAIN/AUTO SESSION",
                scanError = null
            )
        }
        gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            remote.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            remote.connectGatt(context, false, gattCallback)
        }
    }

    fun disconnect() {
        manualDisconnect = true
        forceAuthNextConnect = false
        main.removeCallbacks(pollTask)
        runCatching { gatt?.disconnect() }
    }

    fun close() {
        main.removeCallbacksAndMessages(null)
        runCatching { scanner?.stopScan(scanCallback) }
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt = g
                connectedAtElapsed = SystemClock.elapsedRealtime()
                _state.update { it.copy(connecting = false, connected = true, scanError = null) }
                if (_state.value.naveeSpeedResearch.mode4Log.phase == "WAIT_POWER_CYCLE") {
                    appendMode4Log("BLE CONNECTED — after physical power-on, wait for exact identity then press CHECK AFTER REBOOT")
                }
                main.postDelayed({ runCatching { g.discoverServices() } }, 1_200L)
                return
            }
            if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val probePhase = _state.value.naveeSpeedResearch.mode4Log.phase
                if (probePhase == "WAIT_POWER_CYCLE") {
                    appendMode4Log("BLE DISCONNECTED while waiting for physical power cycle")
                } else if (diagnosticStage(probePhase) != null) {
                    appendMode4Log("UNEXPECTED BLE DISCONNECT during $probePhase — preserving telemetry; sweep stopped")
                    modeSweepRunId++
                    updateMode4Log { it.copy(active = false, phase = "FAILED", conclusion = "FAILED — BLE disconnected during $probePhase") }
                }
                val lived = (SystemClock.elapsedRealtime() - connectedAtElapsed).coerceAtLeast(0L)
                val wasDfu = dfu?.isRunning() == true
                if (wasDfu) dfu?.onLinkLost()
                notifyReady = false
                tx = null
                rx = null
                main.removeCallbacks(pollTask)
                _state.update { it.copy(connecting = false, connected = false, naveeNotifySubscribed = false, mtu = 23) }

                val shouldAuthRetry = !manualDisconnect && !wasDfu && !lastSessionUsedAuth && lived in 1L..15_000L
                runCatching { g.close() }
                if (shouldAuthRetry && selectedAddress != null) {
                    forceAuthNextConnect = true
                    _state.update { it.copy(naveeVendorAuthState = "PLAIN SESSION DROPPED — RETRYING OEM AUTH") }
                    main.postDelayed({ selectedAddress?.let(::connectAddress) }, 1_000L)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.update { it.copy(scanError = "Service discovery failed ($status)") }
                return
            }
            val service = g.getService(UUID.fromString(NaveeProtocol.SERVICE_UUID))
            tx = service?.getCharacteristic(UUID.fromString(NaveeProtocol.TX_UUID))
            rx = service?.getCharacteristic(UUID.fromString(NaveeProtocol.RX_UUID))
            if (service == null || tx == null || rx == null) {
                _state.update { it.copy(scanError = "Connected device is not a NAVEE D0FF scooter") }
                return
            }
            val generation = ++mtuFallbackGeneration
            val started = runCatching { g.requestMtu(SESSION_MTU) }.getOrDefault(false)
            if (!started) main.postDelayed({ if (generation == mtuFallbackGeneration) enableNotifications(g) }, 100L)
            else main.postDelayed({ if (generation == mtuFallbackGeneration && !notifyReady) enableNotifications(g) }, 1_500L)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) _state.update { it.copy(mtu = mtu) }
            val generation = ++mtuFallbackGeneration
            main.postDelayed({ if (generation == mtuFallbackGeneration && !notifyReady) enableNotifications(g) }, 100L)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && descriptor.uuid == CCCD_UUID) markNotifyReady()
            else if (descriptor.uuid == CCCD_UUID) _state.update { it.copy(scanError = "B003 notification subscribe failed ($status)") }
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            onNotify(characteristic, characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            onNotify(characteristic, value)
        }
    }

    private fun enableNotifications(g: BluetoothGatt) {
        val c = rx ?: return
        if (!runCatching { g.setCharacteristicNotification(c, true) }.getOrDefault(false)) {
            _state.update { it.copy(scanError = "Could not enable B003 notifications") }
            return
        }
        val d = c.getDescriptor(CCCD_UUID)
        if (d == null) {
            markNotifyReady()
            return
        }
        val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                d.value = value
                g.writeDescriptor(d)
            }
        }
        if (!started) _state.update { it.copy(scanError = "Could not write B003 CCCD") }
    }

    private fun markNotifyReady() {
        if (notifyReady) return
        notifyReady = true
        _state.update { it.copy(naveeNotifySubscribed = true) }
        main.postDelayed({ startSession() }, 100L)
        main.removeCallbacks(pollTask)
        main.postDelayed(pollTask, 1_000L)
    }

    private fun startSession() {
        if (!notifyReady || !_state.value.connected) return
        if (forceAuthNextConnect) {
            forceAuthNextConnect = false
            startAuth("EARLY-DISCONNECT RETRY")
        } else {
            plainSession("PLAIN NAVEE SESSION")
        }
    }

    private fun plainSession(reason: String) {
        lastSessionUsedAuth = false
        _state.update { it.copy(naveeVendorAuthState = reason) }
        enqueueFrame(NaveeProtocol.buildTimeSync())
        main.postDelayed({ refreshStatus() }, 500L)
    }

    private fun startAuth(reason: String) {
        lastSessionUsedAuth = true
        authPending = true
        authRounds = 0
        authWireId = NaveeSessionAuth.newAnonymousSessionId()
        _state.update { it.copy(naveeVendorAuthState = "OEM AUTH — $reason") }
        enqueueFrame(NaveeProtocol.buildWrite(0x30, NaveeSessionAuth.authInitPayload(authWireId, false)))
        armAuthTimeout()
    }

    private fun armAuthTimeout() {
        authTimeout?.let(main::removeCallbacks)
        val r = Runnable {
            if (!authPending || !_state.value.connected) return@Runnable
            authPending = false
            _state.update { it.copy(naveeVendorAuthState = "AUTH TIMEOUT — PLAIN FALLBACK") }
            plainSession("AUTH TIMEOUT FALLBACK")
        }
        authTimeout = r
        main.postDelayed(r, 2_000L)
    }

    private fun afterAuth(reason: String) {
        authTimeout?.let(main::removeCallbacks)
        authTimeout = null
        authPending = false
        _state.update { it.copy(naveeVendorAuthState = "SESSION AUTH COMPLETE — $reason") }
        enqueueFrame(NaveeProtocol.buildTimeSync())
        enqueueFrame(NaveeProtocol.buildRead(0x7B))
        main.postDelayed({ refreshStatus() }, 500L)
    }

    private fun refreshStatus() {
        if (!notifyReady || dfu?.isRunning() == true) return
        enqueueFrame(NaveeProtocol.buildRead(0x74))
        enqueueFrame(NaveeProtocol.buildRead(0x70))
        enqueueFrame(NaveeProtocol.buildRead(0x72))
        enqueueFrame(NaveeProtocol.buildRead(0x73))
        enqueueFrame(NaveeProtocol.buildRead(0x92))
    }

    private fun onNotify(characteristic: BluetoothGattCharacteristic, value: ByteArray) {
        if (!characteristic.uuid.toString().equals(NaveeProtocol.RX_UUID, ignoreCase = true)) return
        captureDiagnosticNotify(value)
        val engine = dfu
        if (engine?.isRunning() == true) {
            engine.onNotify(value)
            return
        }
        value.forEach { rxBuffer.addLast(it) }
        parseBufferedFrames()
    }

    private fun parseBufferedFrames() {
        while (true) {
            while (rxBuffer.size >= 2 && !((rxBuffer.elementAt(0).toInt() and 0xFF) == 0x55 && (rxBuffer.elementAt(1).toInt() and 0xFF) == 0xAA)) {
                rxBuffer.removeFirst()
            }
            if (rxBuffer.size < 5) return
            val payloadLen = rxBuffer.elementAt(4).toInt() and 0xFF
            val expected = 8 + payloadLen
            if (rxBuffer.size < expected) return
            val frameBytes = ByteArray(expected) { rxBuffer.removeFirst() }
            val frame = NaveeProtocol.parse(frameBytes) ?: continue
            if (!frame.checksumOk) continue
            handleFrame(frame)
        }
    }

    private fun handleFrame(frame: NaveeFrame) {
        captureDiagnosticFrame(frame)
        if (authPending && frame.command == 0x30) {
            val auth = NaveeSessionAuth.parse30(frame)
            when (auth.kind) {
                NaveeSessionAuth.Kind.CHALLENGE -> auth.response?.let {
                    enqueueFrame(NaveeProtocol.buildWrite(0x31, it))
                    _state.update { s -> s.copy(naveeVendorAuthState = "SESSION AUTH CHALLENGE → 0x31") }
                    armAuthTimeout()
                }
                NaveeSessionAuth.Kind.COMPLETE -> afterAuth("0x30 COMPLETE")
                NaveeSessionAuth.Kind.REJECTED -> {
                    authPending = false
                    _state.update { it.copy(naveeVendorAuthState = "SESSION AUTH REJECTED — PLAIN FALLBACK") }
                    plainSession("AUTH REJECTED FALLBACK")
                }
                NaveeSessionAuth.Kind.NONE -> Unit
            }
            return
        }
        if (authPending && frame.command == 0x31) {
            val ok = NaveeSessionAuth.parse31(frame)
            if (ok != null) {
                authRounds++
                if (ok && authRounds < 4) {
                    enqueueFrame(NaveeProtocol.buildWrite(0x30, NaveeSessionAuth.authInitPayload(authWireId, false)))
                    _state.update { it.copy(naveeVendorAuthState = "SESSION AUTH ROUND ${authRounds + 1}") }
                    armAuthTimeout()
                } else {
                    afterAuth(if (ok) "ROUND CAP" else "0x31 REJECTED")
                }
            }
            return
        }


        when (frame.command) {
            0x74 -> NaveeProtocol.vehicleIdentity(frame)?.let { id ->
                _state.update { it.copy(naveeSerial = id.serial, naveePid = id.pid, naveeRegion = id.region, naveeDerivedSku = id.derivedSku) }
                refreshExactIdentity()
            }
            0x72 -> NaveeProtocol.batteryInfo(frame)?.let { b ->
                _state.update { it.copy(naveeLiveBatteryPercent = b.chargePercent) }
            }
            0x73 -> NaveeProtocol.firmwareVersions(frame)?.let { fw ->
                _state.update { it.copy(naveeFirmwareMeter = fw.meter, naveeFirmwareBldc = fw.bldc, naveeFirmwareBms = fw.bms) }
                refreshExactIdentity()
            }
            0x70 -> handleSettings(frame)
            0x92 -> NaveeProtocol.drivingTelemetryV1(frame)?.let { telem ->
                _state.update { it.copy(naveeLiveBatteryPercent = telem.batteryPercent, naveeLiveSpeedKmh = telem.realTimeSpeedKmh, naveeDrivingStatus = telem.drivingStatus) }
            }
        }
    }

    private fun handleSettings(frame: NaveeFrame) {
        if (NaveeProtocol.statusByte(frame) != 0) return
        val data = frame.payload.drop(1)
        val start = data.getOrNull(19)?.toInt()?.and(0xFF)
        val driveMode = data.getOrNull(26)?.toInt()?.and(0xFF)
        val dataHex = data.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        _state.update { it.copy(naveeParamRawHex = dataHex, naveeParamStartSpeed = start) }
        handleMode4DiagnosticSettings(driveMode, dataHex)
    }

    private fun currentParamByte(index: Int): Int? {
        val raw = _state.value.naveeParamRawHex ?: return null
        val bytes = raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return bytes.getOrNull(index)?.toIntOrNull(16)
    }

    private fun compact(v: String?): String = v.orEmpty().filter(Char::isDigit)

    private fun isExact6001(): Boolean {
        val s = _state.value
        return s.naveePid == N65_PID &&
            compact(s.naveeFirmwareMeter) == N65_METER &&
            compact(s.naveeFirmwareBldc) == N65_BLDC
    }

    private fun refreshExactIdentity() {
        val exact = isExact6001()
        updateAuto { auto ->
            if (exact) auto.copy(
                exactModelMatch = true,
                modelId = N65_MODEL,
                pid5 = "25092",
                modelName = "N65i II",
                route = "CUSTOM_6001",
                routeDetail = "Exact PID 2509 / Meter 2058 / BLDC 0005",
                status = if (auto.patchReady) auto.status else "N65i II 6001 VERIFIED — READY"
            ) else auto.copy(
                exactModelMatch = false,
                modelId = null,
                pid5 = null,
                modelName = null,
                patchReady = false,
                flashReady = false,
                armed = false,
                status = "NOT EXACT PID 2509 / Meter 2058 / BLDC 0005 — FLASH LOCKED"
            )
        }
        if (!exact) speedPlan = null
    }

    private fun enqueueFrame(frame: ByteArray) {
        if (!notifyReady || dfu?.isRunning() == true) return
        normalQueue.addLast(frame)
        drainQueue()
    }

    private fun drainQueue() {
        if (normalWriting || !notifyReady || dfu?.isRunning() == true) return
        val frame = normalQueue.pollFirst() ?: return
        normalWriting = true
        val ok = writeRaw(frame)
        main.postDelayed({
            normalWriting = false
            if (!ok) _state.update { it.copy(naveeExperimentalStatus = "BLE WRITE FAILED") }
            drainQueue()
        }, WRITE_GAP_MS)
    }

    private fun writeRaw(frame: ByteArray): Boolean {
        val g = gatt ?: return false
        val c = tx ?: return false
        val writeType = if ((c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, frame, writeType) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                c.writeType = writeType
                c.value = frame
                g.writeCharacteristic(c)
            }
        }
    }

    private fun directDfuWrite(frame: ByteArray): Boolean = writeRaw(frame)

    private fun updateAuto(transform: (NaveeAutoFirmwareUi) -> NaveeAutoFirmwareUi) {
        _state.update { s ->
            val stock = s.naveeSpeedResearch.firmwareStock
            s.copy(naveeSpeedResearch = s.naveeSpeedResearch.copy(firmwareStock = stock.copy(auto = transform(stock.auto))))
        }
    }

    private fun updateStock(transform: (NaveeFirmwareStockUi) -> NaveeFirmwareStockUi) {
        _state.update { s -> s.copy(naveeSpeedResearch = s.naveeSpeedResearch.copy(firmwareStock = transform(s.naveeSpeedResearch.firmwareStock))) }
    }

    private fun persistMode4Log(log: NaveeMode4LogUi) {
        prefs.edit()
            .putBoolean("mode4_log_active", log.active)
            .putString("mode4_log_phase", log.phase)
            .putLong("mode4_log_started", log.startedAtEpochMs)
            .putInt("mode4_log_baseline_mode", log.baselineMode ?: -1)
            .putInt("mode4_log_mode4_mode", log.mode4Mode ?: -1)
            .putInt("mode4_log_mode6_mode", log.mode6Mode ?: -1)
            .putInt("mode4_log_mode7_mode", log.mode7Mode ?: -1)
            .putInt("mode4_log_selected_mode", log.selectedCandidateMode ?: -1)
            .putInt("mode4_log_post_mode", log.postRebootMode ?: -1)
            .putString("mode4_log_baseline_raw70", log.baselineRaw70)
            .putString("mode4_log_mode4_raw70", log.mode4Raw70)
            .putString("mode4_log_mode6_raw70", log.mode6Raw70)
            .putString("mode4_log_mode7_raw70", log.mode7Raw70)
            .putString("mode4_log_selected_raw70", log.selectedCandidateRaw70)
            .putString("mode4_log_post_raw70", log.postRebootRaw70)
            .putString("mode4_log_conclusion", log.conclusion)
            .putString("mode4_log_selected_reason", log.selectedReason)
            .putString("mode4_log_snapshots", log.snapshotText)
            .putString("mode4_log_notifies", log.notifyText)
            .putString("mode4_log_text", log.logText)
            .apply()
    }

    private fun updateMode4Log(transform: (NaveeMode4LogUi) -> NaveeMode4LogUi) {
        var next: NaveeMode4LogUi? = null
        _state.update { state ->
            val changed = transform(state.naveeSpeedResearch.mode4Log)
            next = changed
            state.copy(naveeSpeedResearch = state.naveeSpeedResearch.copy(mode4Log = changed))
        }
        next?.let(::persistMode4Log)
    }

    private fun appendMode4Log(message: String) {
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        updateMode4Log { old ->
            val suffix = if (old.logText.isBlank()) "[$stamp] $message" else "\n[$stamp] $message"
            old.copy(logText = (old.logText + suffix).takeLast(48000))
        }
    }

    private fun diagnosticStage(phase: String): String? = when (phase) {
        "CAPTURE_BASELINE" -> "BASELINE"
        "CAPTURE_MODE4" -> "MODE4"
        "RESTORE4" -> "RESTORE4"
        "CAPTURE_MODE6" -> "MODE6"
        "RESTORE6" -> "RESTORE6"
        "CAPTURE_MODE7" -> "MODE7"
        "RESTORE7" -> "RESTORE7"
        "CAPTURE_SELECTED" -> "SELECTED"
        "CAPTURE_POST_REBOOT" -> "POST_REBOOT"
        else -> null
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun captureDiagnosticNotify(value: ByteArray) {
        val log = _state.value.naveeSpeedResearch.mode4Log
        val stage = diagnosticStage(log.phase) ?: return
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$stamp|$stage|${hex(value)}"
        updateMode4Log { old ->
            val next = if (old.notifyText.isBlank()) line else old.notifyText + "\n" + line
            old.copy(notifyText = next.takeLast(48000))
        }
    }

    private fun captureDiagnosticFrame(frame: NaveeFrame) {
        val log = _state.value.naveeSpeedResearch.mode4Log
        val stage = diagnosticStage(log.phase) ?: return
        val cmd = "%02X".format(frame.command and 0xFF)
        val stamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$stamp|$stage|$cmd|${hex(frame.raw)}"
        updateMode4Log { old ->
            val next = if (old.snapshotText.isBlank()) line else old.snapshotText + "\n" + line
            old.copy(snapshotText = next.takeLast(64000))
        }
    }

    private fun latestFramesForStage(log: NaveeMode4LogUi, stage: String): Map<Int, String> {
        val out = linkedMapOf<Int, String>()
        log.snapshotText.lineSequence().forEach { line ->
            val p = line.split('|', limit = 4)
            if (p.size == 4 && p[1] == stage) {
                p[2].toIntOrNull(16)?.let { out[it] = p[3] }
            }
        }
        return out
    }

    private fun telemetryDiffSummary(log: NaveeMode4LogUi, fromStage: String, toStage: String): String {
        val a = latestFramesForStage(log, fromStage)
        val b = latestFramesForStage(log, toStage)
        val cmds = (a.keys + b.keys).filter { it in setOf(0x70, 0x90, 0x91, 0x92) }.distinct().sorted()
        if (cmds.isEmpty()) return "no comparable 0x70/90/91/92 frames"
        return cmds.joinToString("; ") { cmd ->
            val av = a[cmd]
            val bv = b[cmd]
            when {
                av == null -> "0x%02X:new".format(cmd)
                bv == null -> "0x%02X:missing".format(cmd)
                av == bv -> "0x%02X:same".format(cmd)
                else -> "0x%02X:changed".format(cmd)
            }
        }
    }

    private fun candidateScore(log: NaveeMode4LogUi, stage: String): Int {
        val base = latestFramesForStage(log, "BASELINE")
        val cand = latestFramesForStage(log, stage)
        var score = 0
        for (cmd in listOf(0x70, 0x90, 0x91, 0x92)) {
            val a = base[cmd]
            val b = cand[cmd]
            if (a == null && b != null) score += 1
            else if (a != null && b != null && a != b) score += 2
        }
        return score
    }

    private fun mode4RawDiff(before: String?, after: String): String {
        if (before.isNullOrBlank()) return "baseline unavailable"
        val a = before.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.mapNotNull { it.toIntOrNull(16) }
        val b = after.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.mapNotNull { it.toIntOrNull(16) }
        val max = maxOf(a.size, b.size)
        val changes = (0 until max).mapNotNull { i ->
            val av = a.getOrNull(i)
            val bv = b.getOrNull(i)
            if (av == bv) null else "[$i] ${av?.let { "%02X".format(it) } ?: "--"}->${bv?.let { "%02X".format(it) } ?: "--"}"
        }
        return if (changes.isEmpty()) "no byte changes" else changes.joinToString(", ")
    }

    private fun scheduleModeSweep(runId: Long, delayMs: Long, block: () -> Unit) {
        main.postDelayed({
            if (runId == modeSweepRunId && _state.value.naveeSpeedResearch.mode4Log.active) block()
        }, delayMs)
    }

    private fun requestDiagnosticReads(label: String) {
        if (!_state.value.connected) return
        appendMode4Log("READ SNAPSHOT $label: requesting 0x70 / 0x90 / 0x91 / 0x92; all spontaneous parsed frames are captured too")
        listOf(0x70, 0x90, 0x91, 0x92).forEach { enqueueFrame(NaveeProtocol.buildRead(it)) }
    }

    private fun sendDiagnosticMode(mode: Int, phase: String, label: String) {
        if ((_state.value.naveeLiveSpeedKmh ?: 0.0) > 0.5) {
            appendMode4Log("ABORT: scooter moved before $label")
            updateMode4Log { it.copy(active = false, phase = "FAILED", conclusion = "FAILED — scooter moved during telemetry sweep") }
            modeSweepRunId++
            main.postDelayed(pollTask, 1_000L)
            return
        }
        updateMode4Log { it.copy(phase = phase) }
        val frame = NaveeProtocol.buildWrite(0x58, byteArrayOf(mode.toByte()))
        appendMode4Log("TX 0x58=$mode ($label): ${hex(frame)}")
        enqueueFrame(frame)
    }

    private fun runModeCandidate(runId: Long, mode: Int, next: (() -> Unit)?) {
        val phase = "CAPTURE_MODE$mode"
        sendDiagnosticMode(mode, phase, "candidate $mode")
        scheduleModeSweep(runId, 350L) { requestDiagnosticReads("MODE $mode") }
        scheduleModeSweep(runId, 2_300L) {
            val restorePhase = "RESTORE$mode"
            sendDiagnosticMode(5, restorePhase, "restore SPORT after candidate $mode")
            scheduleModeSweep(runId, 350L) { requestDiagnosticReads("RESTORE AFTER $mode") }
            scheduleModeSweep(runId, 2_150L) { next?.invoke() }
        }
    }

    private fun chooseDiagnosticCandidate(): Pair<Int, String> {
        val log = _state.value.naveeSpeedResearch.mode4Log
        val scored = listOf(4 to candidateScore(log, "MODE4"), 6 to candidateScore(log, "MODE6"), 7 to candidateScore(log, "MODE7"))
        val bestScore = scored.maxOf { it.second }
        val mode = scored.first { it.second == bestScore }.first
        val reason = if (bestScore > 0) {
            "telemetry evidence score=$bestScore; scores ${scored.joinToString { "${it.first}:${it.second}" }}"
        } else {
            "no observable 0x70/90/91/92 difference; fallback to MODE 4 because BLDC0005 has a dedicated mode-4 branch"
        }
        return mode to reason
    }

    private fun activateSelectedAndPromptReboot(runId: Long) {
        val (candidate, reason) = chooseDiagnosticCandidate()
        updateMode4Log { it.copy(selectedCandidateMode = candidate, selectedReason = reason, conclusion = "SELECTED $candidate — final pre-reboot capture") }
        appendMode4Log("SELECTED candidate $candidate: $reason")
        sendDiagnosticMode(candidate, "CAPTURE_SELECTED", "selected candidate before reboot")
        scheduleModeSweep(runId, 350L) { requestDiagnosticReads("SELECTED MODE $candidate") }
        scheduleModeSweep(runId, 2_500L) {
            val fresh = _state.value.naveeSpeedResearch.mode4Log
            appendMode4Log("PRE-REBOOT DIFF baseline→selected: ${telemetryDiffSummary(fresh, "BASELINE", "SELECTED")}")
            updateMode4Log {
                it.copy(
                    active = true,
                    phase = "WAIT_POWER_CYCLE",
                    conclusion = "REBOOT REQUIRED NOW — candidate $candidate left active for physical power-cycle test"
                )
            }
            appendMode4Log("NEXT: POWER SCOOTER OFF COMPLETELY, POWER IT ON, reconnect, then press CHECK AFTER REBOOT")
        }
    }

    private fun handleMode4DiagnosticSettings(driveMode: Int?, raw70: String) {
        val phase = _state.value.naveeSpeedResearch.mode4Log.phase
        when (phase) {
            "CAPTURE_BASELINE" -> updateMode4Log { it.copy(baselineMode = driveMode, baselineRaw70 = raw70) }
            "CAPTURE_MODE4" -> updateMode4Log { it.copy(mode4Mode = driveMode, mode4Raw70 = raw70) }
            "CAPTURE_MODE6" -> updateMode4Log { it.copy(mode6Mode = driveMode, mode6Raw70 = raw70) }
            "CAPTURE_MODE7" -> updateMode4Log { it.copy(mode7Mode = driveMode, mode7Raw70 = raw70) }
            "CAPTURE_SELECTED" -> updateMode4Log { it.copy(selectedCandidateRaw70 = raw70) }
            "CAPTURE_POST_REBOOT" -> updateMode4Log { it.copy(postRebootMode = driveMode, postRebootRaw70 = raw70) }
        }
    }

    private fun finishPostRebootCapture(runId: Long) {
        if (runId != modeSweepRunId) return
        val log = _state.value.naveeSpeedResearch.mode4Log
        val baseline = latestFramesForStage(log, "BASELINE")
        val selected = latestFramesForStage(log, "SELECTED")
        val post = latestFramesForStage(log, "POST_REBOOT")
        val changed = listOf(0x70, 0x90, 0x91, 0x92).filter { cmd ->
            baseline[cmd] != null && selected[cmd] != null && baseline[cmd] != selected[cmd]
        }
        val returned = changed.filter { cmd -> post[cmd] != null && post[cmd] == baseline[cmd] }
        val persisted = changed.filter { cmd -> post[cmd] != null && post[cmd] == selected[cmd] }
        val conclusion = when {
            changed.isEmpty() -> "REBOOT CAPTURED — TELEMETRY INCONCLUSIVE: selected mode produced no stable 0x70/90/91/92 difference"
            returned.size == changed.size -> "VOLATILE EVIDENCE — all observable selected-mode telemetry returned to baseline after reboot"
            persisted.size == changed.size -> "PERSISTENCE EVIDENCE — observable selected-mode telemetry survived reboot"
            else -> "MIXED REBOOT RESULT — some telemetry returned to baseline and some did not"
        }
        appendMode4Log("POST-REBOOT DIFF baseline→post: ${telemetryDiffSummary(log, "BASELINE", "POST_REBOOT")}")
        appendMode4Log("POST-REBOOT comparison: changedBefore=${changed.joinToString { "0x%02X".format(it) }.ifBlank { "none" }} returned=${returned.size} persisted=${persisted.size}")
        appendMode4Log("RESULT: $conclusion")
        prefs.edit().putBoolean("mode4_verified", false).putBoolean("mode4_volatile_verified", false).apply()
        updateAuto {
            it.copy(
                mode4Verified = false,
                mode4VolatileVerified = false,
                flashReady = false,
                armed = false,
                status = "TELEMETRY REBOOT LOG COMPLETE — REVIEW LOG BEFORE ANY RELEASE PATCH",
                lastError = null
            )
        }
        updateMode4Log { it.copy(active = false, phase = "COMPLETE", conclusion = conclusion) }
        main.postDelayed(pollTask, 1_000L)
    }

    fun naveeStartMode4RebootLog(): Boolean {
        if (!isExact6001() || !_state.value.connected || dfu?.isRunning() == true) return false
        if ((_state.value.naveeLiveSpeedKmh ?: 0.0) > 0.5) {
            _state.update { it.copy(naveeExperimentalStatus = "TELEMETRY SWEEP MUST START WHILE SCOOTER IS STATIONARY") }
            return false
        }
        main.removeCallbacks(pollTask)
        modeSweepRunId++
        val runId = modeSweepRunId
        prefs.edit().putBoolean("mode4_verified", false).putBoolean("mode4_volatile_verified", false).apply()
        updateAuto { it.copy(mode4Verified = false, mode4VolatileVerified = false, flashReady = false, armed = false, status = "TELEMETRY 4/6/7 + REBOOT LOG RUNNING — NO FLASH", lastError = null) }
        updateMode4Log {
            NaveeMode4LogUi(
                active = true,
                phase = "CAPTURE_BASELINE",
                startedAtEpochMs = System.currentTimeMillis(),
                conclusion = "RUNNING — broad telemetry baseline then 4 / 6 / 7"
            )
        }
        appendMode4Log("START N65II TELEMETRY 4/6/7 REBOOT LOG — exact 6001 / Meter 2058 / BLDC0005")
        appendMode4Log("Sequence: baseline → mode4 → restore → mode6 → restore → mode7 → restore → evidence-selected candidate → ALWAYS one physical reboot")
        appendMode4Log("Capture includes raw B003 notification chunks plus every parsed NAVEE frame; explicit reads request 0x70/0x90/0x91/0x92")
        appendMode4Log("No DFU or firmware write is performed by this diagnostic")
        requestDiagnosticReads("BASELINE")
        scheduleModeSweep(runId, 2_500L) {
            runModeCandidate(runId, 4) {
                runModeCandidate(runId, 6) {
                    runModeCandidate(runId, 7) {
                        activateSelectedAndPromptReboot(runId)
                    }
                }
            }
        }
        return true
    }

    fun naveeMode4LogCheckAfterReboot(): Boolean {
        val log = _state.value.naveeSpeedResearch.mode4Log
        if (log.phase != "WAIT_POWER_CYCLE" || !isExact6001() || !_state.value.connected || dfu?.isRunning() == true) return false
        modeSweepRunId++
        val runId = modeSweepRunId
        updateMode4Log { it.copy(active = true, phase = "CAPTURE_POST_REBOOT", conclusion = "CAPTURING all telemetry after user-confirmed physical reboot") }
        appendMode4Log("USER CONFIRMED physical power cycle; capturing broad post-reboot telemetry")
        requestDiagnosticReads("POST REBOOT")
        scheduleModeSweep(runId, 3_000L) { finishPostRebootCapture(runId) }
        return true
    }

    fun naveeCancelMode4RebootLog(): Boolean {
        val log = _state.value.naveeSpeedResearch.mode4Log
        if (log.phase == "IDLE") return true
        modeSweepRunId++
        if (_state.value.connected) {
            appendMode4Log("CANCEL: sending SPORT mode 5 restore")
            enqueueFrame(NaveeProtocol.buildWrite(0x58, byteArrayOf(5)))
        }
        updateMode4Log { it.copy(active = false, phase = "CANCELLED", conclusion = "CANCELLED — SPORT restore requested") }
        main.postDelayed(pollTask, 1_000L)
        return true
    }

    fun naveeResetMode4RebootLog(): Boolean {
        if (_state.value.naveeSpeedResearch.mode4Log.active) return false
        modeSweepRunId++
        updateMode4Log { NaveeMode4LogUi() }
        return true
    }

    fun naveeShareMode4RebootLog(): Boolean {
        val state = _state.value
        val log = state.naveeSpeedResearch.mode4Log
        if (log.logText.isBlank()) return false
        return runCatching {
            val dir = File(context.cacheDir, "mode4_logs").apply { mkdirs() }
            val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val file = File(dir, "N65II_TELEMETRY_REBOOT_LOG_$stamp.txt")
            file.writeText(buildString {
                appendLine("N65i II 6001 — BROAD TELEMETRY MODE 4/6/7 + REBOOT DIAGNOSTIC")
                appendLine("PID=${state.naveePid ?: "-"} Meter=${state.naveeFirmwareMeter ?: "-"} BLDC=${state.naveeFirmwareBldc ?: "-"}")
                appendLine("Baseline=${log.baselineMode ?: -1} Mode4=${log.mode4Mode ?: -1} Mode6=${log.mode6Mode ?: -1} Mode7=${log.mode7Mode ?: -1}")
                appendLine("Selected=${log.selectedCandidateMode ?: -1} reason=${log.selectedReason.ifBlank { "-" }} PostReboot=${log.postRebootMode ?: -1}")
                appendLine("Conclusion=${log.conclusion}")
                appendLine("============================================================")
                appendLine(log.logText)
                appendLine("============================================================")
                appendLine("RAW 0x70 baseline: ${log.baselineRaw70 ?: "-"}")
                appendLine("RAW 0x70 mode4: ${log.mode4Raw70 ?: "-"}")
                appendLine("RAW 0x70 mode6: ${log.mode6Raw70 ?: "-"}")
                appendLine("RAW 0x70 mode7: ${log.mode7Raw70 ?: "-"}")
                appendLine("RAW 0x70 selected: ${log.selectedCandidateRaw70 ?: "-"}")
                appendLine("RAW 0x70 post-reboot: ${log.postRebootRaw70 ?: "-"}")
                appendLine("============================================================")
                appendLine("PARSED NAVEE FRAME SNAPSHOTS")
                appendLine(log.snapshotText.ifBlank { "-" })
                appendLine("============================================================")
                appendLine("RAW B003 NOTIFICATION CHUNKS")
                appendLine(log.notifyText.ifBlank { "-" })
            })
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "N65i II telemetry reboot log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(share, "Share telemetry reboot log").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(chooser)
            true
        }.getOrElse { err ->
            _state.update { it.copy(naveeExperimentalStatus = "LOG SHARE FAILED — ${err.message ?: err.javaClass.simpleName}") }
            false
        }
    }

    fun naveeSetModeSpeedPreset(slot: String, speedKmh: Int): Boolean {
        if (speedKmh !in 1..60 || dfu?.isRunning() == true) return false
        val key = slot.trim().uppercase(Locale.US)
        if (key !in setOf("ECO", "DRIVE", "SPORT", "RELEASE")) return false
        updateAuto { auto ->
            val changed = when (key) {
                "ECO" -> auto.copy(ecoSpeedKmh = speedKmh)
                "DRIVE" -> auto.copy(driveSpeedKmh = speedKmh)
                "SPORT" -> auto.copy(sportSpeedKmh = speedKmh)
                else -> auto.copy(releaseSpeedKmh = speedKmh)
            }
            changed.copy(
                patchReady = false,
                flashReady = false,
                armed = false,
                armSecondsRemaining = 0,
                patchedSha256 = null,
                status = "$key = $speedKmh km/h — BUILD PATCH AGAIN",
                lastError = null
            )
        }
        val a = _state.value.naveeSpeedResearch.firmwareStock.auto
        prefs.edit().putInt("eco", a.ecoSpeedKmh).putInt("drive", a.driveSpeedKmh).putInt("sport", a.sportSpeedKmh).putInt("release", a.releaseSpeedKmh).apply()
        speedPlan = null
        disarm()
        return true
    }

    fun naveeSetModeSpeedPresetsEnabled(enabled: Boolean): Boolean = enabled

    fun naveeAutoPrepareAndArmSpeed(): Boolean {
        if (!isExact6001()) {
            updateAuto { it.copy(status = "BLOCKED — EXACT N65i II 6001 / 2058 / 0005 REQUIRED", lastError = "Firmware mismatch") }
            return false
        }
        if (dfu?.isRunning() == true) return false
        val auto = _state.value.naveeSpeedResearch.firmwareStock.auto
        updateAuto { it.copy(active = true, patchReady = false, flashReady = false, armed = false, status = "BUILDING + VERIFYING N65i II MODE PATCH…", lastError = null) }
        Thread {
            runCatching {
                val stock = context.assets.open("firmware/n65ii_6001_bldc0005_stock.bin").use { it.readBytes() }
                val built = Navee6001SpeedProfiles.buildModes(stock, auto.ecoSpeedKmh, auto.driveSpeedKmh, auto.sportSpeedKmh, auto.releaseSpeedKmh)
                SpeedPlan(stock, built.bytes, built.sha256, auto.ecoSpeedKmh, auto.driveSpeedKmh, auto.sportSpeedKmh, auto.releaseSpeedKmh)
            }.onSuccess { plan ->
                main.post {
                    speedPlan = plan
                    updateAuto {
                        it.copy(
                            active = false,
                            exactModelMatch = true,
                            modelId = N65_MODEL,
                            pid5 = "25092",
                            modelName = "N65i II",
                            route = "CUSTOM_6001",
                            routeDetail = "TRUE MODE CAPS: ECO ${plan.eco} / DRIVE ${plan.drive} / SPORT ${plan.sport} / RELEASE ${plan.release} km/h",
                            patchReady = true,
                            flashReady = false,
                            armed = false,
                            stockSha256 = Navee6001SpeedProfiles.STOCK_SHA256,
                            patchedSha256 = plan.patchedSha,
                            status = "PATCH READY — VERIFIED + RESEALED — RUN CONNECTION PREFLIGHT",
                            lastError = null
                        )
                    }
                }
            }.onFailure { err ->
                main.post {
                    speedPlan = null
                    updateAuto { it.copy(active = false, patchReady = false, status = "PATCH BUILD FAILED", lastError = err.message ?: err.javaClass.simpleName) }
                }
            }
        }.start()
        return true
    }

    fun naveeAutoRunConnectionPreflight(): Boolean {
        val plan = speedPlan ?: run {
            updateAuto { it.copy(status = "BUILD PATCH FIRST") }
            return false
        }
        val auto = _state.value.naveeSpeedResearch.firmwareStock.auto
        if (!auto.mode4Verified || !auto.mode4VolatileVerified) {
            updateAuto { it.copy(flashReady = false, armed = false, status = "PREFLIGHT BLOCKED — COMPLETE MODE 4 REBOOT LOG FIRST", lastError = "Need both mode-4 passthrough and volatile reboot reset verification") }
            return false
        }
        val result = preflight(plan)
        if (!result.first) {
            updateAuto { it.copy(flashReady = false, armed = false, status = "PREFLIGHT BLOCKED — ${result.second}", lastError = result.second) }
            return false
        }
        armRestore = false
        armDeadline = SystemClock.elapsedRealtime() + ARM_WINDOW_MS
        updateAuto { it.copy(flashReady = true, armed = true, armSecondsRemaining = 30, status = "FLASH READY — ARMED 30s", lastError = null) }
        main.removeCallbacks(armTick)
        main.post(armTick)
        return true
    }

    private fun preflight(plan: SpeedPlan): Pair<Boolean, String> {
        val s = _state.value
        if (!s.connected || !notifyReady) return false to "NAVEE BLE / B003 NOT READY"
        if (!isExact6001()) return false to "LIVE FIRMWARE IS NOT 2058 / 0005"
        if (dfu?.isRunning() == true) return false to "DFU ALREADY ACTIVE"
        if (s.mtu < MIN_DFU_MTU) return false to "MTU ${s.mtu} < $MIN_DFU_MTU"
        val battery = s.naveeLiveBatteryPercent ?: return false to "BATTERY SOC UNKNOWN"
        if (battery < MIN_DFU_BATTERY) return false to "BATTERY $battery% < $MIN_DFU_BATTERY%"
        if ((s.naveeLiveSpeedKmh ?: 0.0) > 0.5) return false to "SCOOTER IS MOVING"
        if ((s.naveeDrivingStatus ?: 0) != 0) return false to "DRIVING STATUS ${s.naveeDrivingStatus}"
        if (currentParamByte(26) == 4) return false to "SPEED RELEASE IS ACTIVE — PRESS LOCK BEFORE DFU"
        if (plan.stock.size != Navee6001SpeedProfiles.IMAGE_SIZE || sha256(plan.stock) != Navee6001SpeedProfiles.STOCK_SHA256) return false to "STOCK IMAGE HASH/SIZE CHANGED"
        return true to "PASS"
    }

    private fun disarm() {
        main.removeCallbacks(armTick)
        armDeadline = 0L
        updateAuto { it.copy(flashReady = false, armed = false, armSecondsRemaining = 0) }
    }

    fun naveeAutoExecuteSpeedAction(): Boolean = startDfu(restore = false)

    fun naveeAutoPrepareRestoreAndArm(): Boolean {
        val plan = speedPlan ?: run {
            updateAuto { it.copy(status = "BUILD A VERIFIED PATCH FIRST — STOCK RESTORE IMAGE IS THEN PINNED") }
            return false
        }
        val result = preflight(plan)
        if (!result.first) {
            updateAuto { it.copy(status = "RESTORE PREFLIGHT BLOCKED — ${result.second}", lastError = result.second) }
            return false
        }
        armRestore = true
        armDeadline = SystemClock.elapsedRealtime() + ARM_WINDOW_MS
        updateAuto { it.copy(flashReady = true, armed = true, armSecondsRemaining = 30, status = "STOCK RESTORE READY — ARMED 30s", lastError = null) }
        main.removeCallbacks(armTick)
        main.post(armTick)
        return true
    }

    fun naveeAutoStartRestore(): Boolean = startDfu(restore = true)

    private fun startDfu(restore: Boolean): Boolean {
        val plan = speedPlan ?: return false
        if (restore != armRestore || armDeadline <= SystemClock.elapsedRealtime() || !_state.value.naveeSpeedResearch.firmwareStock.auto.armed) {
            disarm()
            updateAuto { it.copy(status = "ARM WINDOW INVALID — RUN PREFLIGHT AGAIN") }
            return false
        }
        val auto = _state.value.naveeSpeedResearch.firmwareStock.auto
        if (!restore && (!auto.mode4Verified || !auto.mode4VolatileVerified)) {
            disarm()
            updateAuto { it.copy(status = "FINAL PREFLIGHT BLOCKED — MODE 4 REBOOT LOG NOT VERIFIED", lastError = "Passthrough + volatile reboot reset required") }
            return false
        }
        val pre = preflight(plan)
        if (!pre.first) {
            disarm()
            updateAuto { it.copy(status = "FINAL PREFLIGHT BLOCKED — ${pre.second}", lastError = pre.second) }
            return false
        }
        val image = if (restore) plan.stock else plan.patched
        disarm()
        normalQueue.clear()
        normalWriting = false
        dfuLog.clear()
        appendDfuLog("n65II target-2 DFU — ${if (restore) "STOCK RESTORE" else "4-PROFILE MODE4 RELEASE PATCH"}")
        appendDfuLog("ECO ${plan.eco} / DRIVE ${plan.drive} / SPORT ${plan.sport} / RELEASE ${plan.release}")
        appendDfuLog("Image SHA-256 ${sha256(image)}")

        val host = object : NaveeDfuEngine.Host {
            override fun write(frame: ByteArray): Boolean = directDfuWrite(frame)
            override fun setHighPriority(high: Boolean) {
                runCatching { gatt?.requestConnectionPriority(if (high) BluetoothGatt.CONNECTION_PRIORITY_HIGH else BluetoothGatt.CONNECTION_PRIORITY_BALANCED) }
            }
            override fun progress(percent: Int, block: Int, count: Int, phase: String) {
                updateStock { it.copy(dfuActive = true, dfuProgressPercent = percent.coerceIn(0, 100), dfuPhase = phase.uppercase(Locale.US), dfuStatus = "$phase — $percent% ($block/$count)") }
                updateAuto { it.copy(active = true, flashReady = false, armed = false, status = "DFU ${phase.uppercase(Locale.US)} — $percent%") }
            }
            override fun log(line: String) = appendDfuLog(line)
            override fun state(state: String, message: String?) {
                updateStock { it.copy(dfuActive = true, dfuPhase = state.uppercase(Locale.US), dfuStatus = message ?: state) }
            }
            override fun finished(success: Boolean) {
                val result = when {
                    success && restore -> "SUCCESS — STOCK RESTORE dfu_ok RECEIVED"
                    success -> "SUCCESS — MODE4 RELEASE PATCH dfu_ok RECEIVED"
                    else -> "DFU FAILED / INTERRUPTED — RECONNECT BEFORE ANY FURTHER ACTION"
                }
                appendDfuLog(result)
                updateStock { it.copy(dfuActive = false, dfuProgressPercent = if (success) 100 else it.dfuProgressPercent, dfuPhase = if (success) "DONE" else "FAILED", dfuStatus = result, dfuLogTail = dfuLog.joinToString("\n")) }
                updateAuto { it.copy(active = false, flashReady = false, armed = false, armSecondsRemaining = 0, status = result, lastError = if (success) null else result) }
                runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
                wakeLock = null
                dfu = null
                if (success) main.postDelayed({ if (_state.value.connected) refreshStatus() }, 2_500L)
            }
        }

        val engine = NaveeDfuEngine(host)
        engine.setResumable(false)
        dfu = engine
        runCatching {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "n65II:DFU")?.apply { acquire(10 * 60 * 1000L) }
        }
        updateStock { it.copy(dfuActive = true, dfuProgressPercent = 0, dfuPhase = "STARTING", dfuStatus = "STARTING BLDC TARGET 2 — DO NOT POWER OFF") }
        updateAuto { it.copy(active = true, status = "STARTING BLDC TARGET 2 DFU") }
        engine.start(image, targetComponent = 2)
        return true
    }

    private fun appendDfuLog(line: String) {
        val stamped = "%tT  %s".format(System.currentTimeMillis(), line)
        dfuLog.addLast(stamped)
        while (dfuLog.size > 30) dfuLog.removeFirst()
        updateStock { it.copy(dfuLogTail = dfuLog.joinToString("\n")) }
    }

    private fun toolsAllowed(): Boolean = _state.value.connected && notifyReady && isExact6001() && dfu?.isRunning() != true

    private fun sendTool(command: Int, payload: ByteArray, label: String): Boolean {
        if (!toolsAllowed()) {
            _state.update { it.copy(naveeExperimentalStatus = "BLOCKED — CONNECT EXACT N65i II 6001 FIRST") }
            return false
        }
        enqueueFrame(NaveeProtocol.buildWrite(command, payload))
        _state.update { it.copy(naveeExperimentalStatus = "$label SENT — VERIFYING") }
        main.postDelayed({ if (_state.value.connected) enqueueFrame(NaveeProtocol.buildRead(0x70)) }, 350L)
        return true
    }

    fun naveeExperimentalSetLight(enabled: Boolean): Boolean = sendTool(0x54, byteArrayOf((if (enabled) 1 else 0).toByte()), if (enabled) "LIGHT ON" else "LIGHT OFF")
    fun naveeExperimentalSetTurnSignalSound(enabled: Boolean): Boolean = sendTool(0x60, byteArrayOf((if (enabled) 1 else 0).toByte()), if (enabled) "TURN SOUND ON" else "TURN SOUND OFF")
    fun naveeExperimentalSetTcs(enabled: Boolean): Boolean = sendTool(0x5F, byteArrayOf((if (enabled) 1 else 0).toByte()), if (enabled) "TCS ON" else "TCS OFF")
    fun naveeExperimentalSetCruise(enabled: Boolean): Boolean = sendTool(0x52, byteArrayOf((if (enabled) 1 else 0).toByte()), if (enabled) "CRUISE ON" else "CRUISE OFF")
    fun naveeExperimentalSetAutoLight(enabled: Boolean): Boolean = sendTool(0x57, byteArrayOf((if (enabled) 1 else 0).toByte()), if (enabled) "AUTO LIGHT ON" else "AUTO LIGHT OFF")
    fun naveeExperimentalSetLock(locked: Boolean): Boolean = sendTool(0x51, byteArrayOf((if (locked) 1 else 0).toByte()), if (locked) "LOCK" else "UNLOCK")
    fun naveeExperimentalSetUnitMph(mph: Boolean): Boolean = sendTool(0x55, byteArrayOf((if (mph) 1 else 0).toByte()), if (mph) "DISPLAY MPH" else "DISPLAY KM/H")
    fun naveeExperimentalSetEco(enabled: Boolean): Boolean = sendTool(0x6F, byteArrayOf(0x05, (if (enabled) 1 else 0).toByte()), if (enabled) "ECO LOW POWER ON" else "ECO LOW POWER OFF")
    fun naveeExperimentalSetDrl(enabled: Boolean): Boolean = sendTool(0x6D, byteArrayOf(0x03, (if (enabled) 1 else 0).toByte()), if (enabled) "DRL ON" else "DRL OFF")
    fun naveeExperimentalSetProximityKey(enabled: Boolean): Boolean = sendTool(0x61, byteArrayOf((if (enabled) 1 else 0).toByte()), if (enabled) "PROXIMITY KEY ON" else "PROXIMITY KEY OFF")

    fun naveeExperimentalSetEnergyRecovery(value: Int): Boolean {
        if (value !in setOf(30, 60)) return false
        return sendTool(0x53, byteArrayOf(value.toByte()), "ENERGY RECOVERY $value")
    }

    fun naveeExperimentalSetDriveMode(mode: Int): Boolean {
        if (mode !in setOf(3, 4, 5)) return false
        val label = when (mode) {
            3 -> "DRIVE NORMAL"
            4 -> "HIDDEN RELEASE MODE 4"
            else -> "DRIVE TURBO"
        }
        return sendTool(0x58, byteArrayOf(mode.toByte()), label)
    }

    /**
     * Runtime speed profile toggle for the dedicated N65i II 6001 / BLDC0005 patch.
     * Mode 4 is repurposed by the patched controller as the independent RELEASE cap.
     * OFF returns to stock Turbo/Sport mode 5. The following 0x70 refresh is queued by sendTool(),
     * so the UI only shows active when the scooter actually reports driveMode == 4.
     */
    fun naveeSetSpeedRelease(enabled: Boolean): Boolean {
        val auto = _state.value.naveeSpeedResearch.firmwareStock.auto
        if (enabled && !auto.mode4Verified && (_state.value.naveeLiveSpeedKmh ?: 0.0) > 0.5) {
            _state.update { it.copy(naveeExperimentalStatus = "FIRST MODE 4 PASSTHROUGH TEST MUST BE DONE STATIONARY") }
            return false
        }
        return sendTool(
            0x58,
            byteArrayOf((if (enabled) 4 else 5).toByte()),
            if (enabled) "SPEED RELEASE / MODE 4 REQUEST" else "SPEED RELEASE OFF — SPORT"
        )
    }

    fun naveeExperimentalSetStartSpeed(value: Int): Boolean {
        if (value !in 0..5) return false
        val sku = _state.value.naveeDerivedSku
        if (sku != "USA" && value < 3) {
            _state.update { it.copy(naveeExperimentalStatus = "START SPEED $value BLOCKED ON ${sku ?: "NON-USA"}: STOCK METER CLAMPS 0–2") }
            return false
        }
        return sendTool(0x6A, byteArrayOf(value.toByte()), "START SPEED $value")
    }

    fun naveeExperimentalRefreshObservedControls(): Boolean {
        if (!toolsAllowed()) return false
        refreshStatus()
        _state.update { it.copy(naveeExperimentalStatus = "STATUS REFRESH QUEUED") }
        return true
    }

    fun naveeExperimentalResetBleSession(): Boolean {
        if (dfu?.isRunning() == true) return false
        val address = selectedAddress ?: return false
        manualDisconnect = true
        runCatching { gatt?.disconnect() }
        main.postDelayed({
            manualDisconnect = false
            connectAddress(address)
        }, 1_000L)
        return true
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
