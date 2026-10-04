package com.n65ii.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.n65ii.app.ble.BleViewModel
import com.n65ii.app.model.BleUiState
import com.n65ii.app.model.DiscoveredDevice
import java.util.Locale
import kotlin.math.roundToInt

enum class N65Tab(val title: String) {
    Flash("FLASH"), Tools("TOOLS"), Connect("CONNECT")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScooterBleApp(vm: BleViewModel) {
    val state by vm.state.collectAsState()
    var tab by remember { mutableStateOf(N65Tab.Connect) }
    var permissionRefresh by remember { mutableStateOf(0) }
    var autoScanAfterPermission by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        permissionRefresh++
        if (autoScanAfterPermission) {
            autoScanAfterPermission = false
            if (vm.manager.hasPermissions()) vm.manager.startScan(false)
        }
    }

    LaunchedEffect(Unit) {
        if (!vm.manager.hasPermissions()) permissionLauncher.launch(vm.manager.recommendedPermissions())
    }

    val exact6001 = isExact6001(state)
    val status = when {
        state.connected && exact6001 -> "N65i II 6001 • READY"
        state.connected -> "Connected • verifying N65i II…"
        state.connecting -> "Connecting…"
        state.scanning -> "Scanning…"
        else -> "Disconnected"
    }

    Scaffold(
        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
        topBar = {
            TopAppBar(title = {
                Column {
                    Text("n65II", fontWeight = FontWeight.Bold)
                    Text(status, style = MaterialTheme.typography.labelMedium)
                }
            })
        }
    ) { inner ->
        Column(Modifier.padding(inner).fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                N65Tab.entries.forEach { item ->
                    FilterChip(
                        selected = tab == item,
                        onClick = { tab = item },
                        label = { Text(item.title) }
                    )
                }
            }
            HorizontalDivider()
            Box(Modifier.fillMaxSize()) {
                when (tab) {
                    N65Tab.Flash -> N65FlashScreen(state, vm)
                    N65Tab.Tools -> N65ToolsScreen(state, vm)
                    N65Tab.Connect -> N65ConnectScreen(
                        state = state,
                        vm = vm,
                        permissionRefresh = permissionRefresh,
                        requestPermissions = { startAfterGrant ->
                            autoScanAfterPermission = startAfterGrant
                            permissionLauncher.launch(vm.manager.recommendedPermissions())
                        }
                    )
                }
            }
        }
    }
}

private fun compactFw(v: String?): String? = v?.filter { it.isDigit() }?.takeIf { it.isNotBlank() }

private fun isExact6001(state: BleUiState): Boolean {
    if (!state.connected) return false
    val firmwareExact = compactFw(state.naveeFirmwareMeter) == "2058" && compactFw(state.naveeFirmwareBldc) == "0005"
    return firmwareExact && state.naveePid == "2509" && state.naveeSpeedResearch.firmwareStock.auto.exactModelMatch
}

@Composable
private fun N65ConnectScreen(
    state: BleUiState,
    vm: BleViewModel,
    permissionRefresh: Int,
    requestPermissions: (Boolean) -> Unit
) {
    @Suppress("UNUSED_VARIABLE") val permissionGeneration = permissionRefresh
    val context = LocalContext.current
    val permissionsOk = vm.manager.hasPermissions()
    val exact = isExact6001(state)

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("CONNECT", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when {
                        state.connected && exact -> "N65i II 6001 connected"
                        state.connected -> "Connected — waiting for exact N65i II 6001 identity"
                        state.connecting -> "Connecting…"
                        state.scanning -> "Scanning for NAVEE…"
                        else -> "Not connected"
                    },
                    fontWeight = FontWeight.SemiBold
                )
                if (state.connected) {
                    Text("PID: ${state.naveePid ?: "-"}")
                    Text("Meter: ${state.naveeFirmwareMeter ?: "-"}")
                    Text("BLDC: ${state.naveeFirmwareBldc ?: "-"}")
                    Text("Battery: ${state.naveeLiveBatteryPercent ?: -1}%")
                    Text("Auth: ${state.naveeVendorAuthState}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (state.scanning) vm.manager.stopScan()
                else if (!permissionsOk) requestPermissions(true)
                else vm.manager.startScan(false)
            }) { Text(if (state.scanning) "STOP" else "SCAN") }
            if (state.connected) OutlinedButton(onClick = { vm.manager.disconnect() }) { Text("DISCONNECT") }
        }

        if (!permissionsOk) {
            OutlinedButton(onClick = { requestPermissions(false) }) { Text("BLUETOOTH PERMISSIONS") }
        }
        OutlinedButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }) { Text("ANDROID APP SETTINGS") }

        state.scanError?.let { Notice(it) }

        val visible = state.discovered.filter { d ->
            val n = d.name?.uppercase(Locale.US).orEmpty()
            n.startsWith("NAVEE") || d.advertisement.serviceUuids.any { "d0ff" in it.lowercase(Locale.US) }
        }.sortedByDescending { it.rssi }

        visible.forEach { d -> N65DeviceCard(d) { vm.connect(d) } }
        if (!state.scanning && visible.isEmpty() && !state.connected) {
            Text("Tryck SCAN, välj din N65i II och anslut.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun N65DeviceCard(d: DiscoveredDevice, onConnect: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(d.name ?: "NAVEE", fontWeight = FontWeight.Bold)
                Text("${d.rssi} dBm", style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = onConnect) { Text("CONNECT") }
        }
    }
}

@Composable
private fun N65FlashScreen(state: BleUiState, vm: BleViewModel) {
    val stock = state.naveeSpeedResearch.firmwareStock
    val auto = stock.auto
    val exact = isExact6001(state)
    val canFlash = exact && auto.patchReady && auto.flashReady && auto.armed && !stock.dfuActive

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("N65i II SPEED", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        if (!exact) {
            Notice("FLASH är låst tills exakt N65i II 6001 / Meter 2058 / BLDC 0005 har identifierats.")
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("4 SPEED PROFILES", fontWeight = FontWeight.Bold)
                Text("ECO, DRIVE och normal SPORT byggs som vanliga caps. SPEED RELEASE är en separat dold mode-4-cap som växlas live från TOOLS.", style = MaterialTheme.typography.bodySmall)
                ModeSpeedSlider("ECO", auto.ecoSpeedKmh, exact && !stock.dfuActive) { vm.manager.naveeSetModeSpeedPreset("ECO", it) }
                ModeSpeedSlider("DRIVE", auto.driveSpeedKmh, exact && !stock.dfuActive) { vm.manager.naveeSetModeSpeedPreset("DRIVE", it) }
                ModeSpeedSlider("SPORT NORMAL", auto.sportSpeedKmh, exact && !stock.dfuActive) { vm.manager.naveeSetModeSpeedPreset("SPORT", it) }
                ModeSpeedSlider("SPEED RELEASE", auto.releaseSpeedKmh, exact && !stock.dfuActive) { vm.manager.naveeSetModeSpeedPreset("RELEASE", it) }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("IDENTITY", fontWeight = FontWeight.Bold)
                Text("PID ${state.naveePid ?: "-"} • Meter ${state.naveeFirmwareMeter ?: "-"} • BLDC ${state.naveeFirmwareBldc ?: "-"}")
                Text("Battery ${state.naveeLiveBatteryPercent ?: -1}% • Speed ${"%.1f".format(state.naveeLiveSpeedKmh ?: 0.0)} km/h", style = MaterialTheme.typography.bodySmall)
                Text("Route: ${auto.route} • ${auto.routeDetail}", style = MaterialTheme.typography.bodySmall)
                Text("Meter 2058 mode-4 passthrough: ${if (auto.mode4Verified) "VERIFIED" else "NOT VERIFIED"}", style = MaterialTheme.typography.bodySmall)
                Text("Mode-4 power-cycle reset: ${if (auto.mode4VolatileVerified) "VOLATILE VERIFIED" else "NOT VERIFIED"}", style = MaterialTheme.typography.bodySmall)
            }
        }

        Button(
            onClick = {
                vm.manager.naveeSetModeSpeedPresetsEnabled(true)
                vm.manager.naveeAutoPrepareAndArmSpeed()
            },
            enabled = exact && !stock.dfuActive && !auto.active,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (auto.active) "BUILDING / VERIFYING…" else "BUILD + VERIFY PATCH") }

        if (auto.patchReady) {
            OutlinedButton(
                onClick = { vm.manager.naveeAutoRunConnectionPreflight() },
                enabled = exact && state.connected && !stock.dfuActive && !auto.armed,
                modifier = Modifier.fillMaxWidth()
            ) { Text("CONNECTION PREFLIGHT + ARM") }
        }

        Button(
            onClick = { vm.manager.naveeAutoExecuteSpeedAction() },
            enabled = canFlash,
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (auto.armed) "FLASH E${auto.ecoSpeedKmh} / D${auto.driveSpeedKmh} / S${auto.sportSpeedKmh} / R${auto.releaseSpeedKmh}" else "FLASH — NOT ARMED") }

        if (stock.dfuActive) {
            Text("DFU ${stock.dfuProgressPercent}% • ${stock.dfuPhase}", fontWeight = FontWeight.SemiBold)
        }
        Text(auto.status, style = MaterialTheme.typography.bodySmall)
        Text("PATCH: ${if (auto.patchReady) "READY" else "NOT READY"} • FLASH: ${if (auto.flashReady && auto.armed) "ARMED ${auto.armSecondsRemaining}s" else "NOT ARMED"}", style = MaterialTheme.typography.bodySmall)
        auto.lastError?.let { Notice(it) }

        HorizontalDivider()
        Text("STOCK RESTORE", fontWeight = FontWeight.Bold)
        OutlinedButton(
            onClick = { vm.manager.naveeAutoPrepareRestoreAndArm() },
            enabled = exact && auto.patchReady && state.connected && !stock.dfuActive,
            modifier = Modifier.fillMaxWidth()
        ) { Text("PREPARE + ARM STOCK RESTORE") }
        OutlinedButton(
            onClick = { vm.manager.naveeAutoStartRestore() },
            enabled = exact && auto.armed && state.connected && !stock.dfuActive,
            modifier = Modifier.fillMaxWidth()
        ) { Text("FLASH ORIGINAL STOCK") }
    }
}

@Composable
private fun ModeSpeedSlider(label: String, value: Int, enabled: Boolean, onCommit: (Int) -> Unit) {
    var local by remember(value) { mutableStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontWeight = FontWeight.SemiBold)
            Text("${local.roundToInt()} km/h", fontWeight = FontWeight.Bold)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            valueRange = 1f..60f,
            steps = 58,
            enabled = enabled,
            onValueChangeFinished = { onCommit(local.roundToInt().coerceIn(1, 60)) }
        )
    }
}

@Composable
private fun N65ToolsScreen(state: BleUiState, vm: BleViewModel) {
    val exact = isExact6001(state)
    val canWrite = state.connected && exact && !state.naveeSpeedResearch.firmwareStock.dfuActive
    val raw70 = state.naveeParamRawHex
    val lock = raw70Byte(raw70, 2)
    var startSpeed by remember(state.naveeParamStartSpeed) { mutableStateOf((state.naveeParamStartSpeed ?: 3).coerceIn(0, 5).toFloat()) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("TOOLS", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Normal NAVEE controls only. Firmware flashing stays exclusively in the FLASH tab.", style = MaterialTheme.typography.bodySmall)
        if (!exact) Notice("Tools är låsta tills exakt N65i II 6001 / 2058 / 0005 är identifierad.")

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("LOCK", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.manager.naveeExperimentalSetLock(true) }, enabled = canWrite) { Text("LOCK") }
                    OutlinedButton(onClick = { vm.manager.naveeExperimentalSetLock(false) }, enabled = canWrite) { Text("UNLOCK") }
                }
                Text("Current lock byte: ${lock ?: -1}", style = MaterialTheme.typography.bodySmall)
            }
        }

        ToolOnOff("Tail light", canWrite, raw70Byte(raw70, 4) == 1, { vm.manager.naveeExperimentalSetLight(true) }, { vm.manager.naveeExperimentalSetLight(false) })
        ToolOnOff("Cruise control", canWrite, raw70Byte(raw70, 3)?.let { it != 0 }, { vm.manager.naveeExperimentalSetCruise(true) }, { vm.manager.naveeExperimentalSetCruise(false) })
        ToolOnOff("Traction control (TCS)", canWrite, raw70Byte(raw70, 11) == 1, { vm.manager.naveeExperimentalSetTcs(true) }, { vm.manager.naveeExperimentalSetTcs(false) })
        ToolOnOff("Auto light", canWrite, raw70Byte(raw70, 8) == 1, { vm.manager.naveeExperimentalSetAutoLight(true) }, { vm.manager.naveeExperimentalSetAutoLight(false) })
        ToolOnOff("Daytime running light (DRL)", canWrite, raw70Byte(raw70, 24) == 1, { vm.manager.naveeExperimentalSetDrl(true) }, { vm.manager.naveeExperimentalSetDrl(false) })
        ToolOnOff("Turn signal sound", canWrite, null, { vm.manager.naveeExperimentalSetTurnSignalSound(true) }, { vm.manager.naveeExperimentalSetTurnSignalSound(false) })
        ToolOnOff("Proximity key", canWrite, null, { vm.manager.naveeExperimentalSetProximityKey(true) }, { vm.manager.naveeExperimentalSetProximityKey(false) })
        ToolOnOff("Eco low-power mode", canWrite, raw70Byte(raw70, 32) == 1, { vm.manager.naveeExperimentalSetEco(true) }, { vm.manager.naveeExperimentalSetEco(false) })

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("DRIVE MODE", fontWeight = FontWeight.Bold)
                Text("Live: ${raw70Byte(raw70, 26) ?: -1}", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.manager.naveeExperimentalSetDriveMode(3) }, enabled = canWrite) { Text("NORMAL") }
                    OutlinedButton(onClick = { vm.manager.naveeExperimentalSetDriveMode(5) }, enabled = canWrite) { Text("TURBO") }
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("START SPEED", fontWeight = FontWeight.Bold)
                Text("${startSpeed.roundToInt()}", fontWeight = FontWeight.SemiBold)
                Slider(
                    value = startSpeed,
                    onValueChange = { startSpeed = it },
                    valueRange = 0f..5f,
                    steps = 4,
                    enabled = canWrite,
                    onValueChangeFinished = { vm.manager.naveeExperimentalSetStartSpeed(startSpeed.roundToInt()) }
                )
                Text("Firmware/region kan blockera 0–2 på icke-USA-varianter; appen behåller den befintliga skyddskontrollen.", style = MaterialTheme.typography.bodySmall)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val liveMode = raw70Byte(raw70, 26)
                val mode4Log = state.naveeSpeedResearch.mode4Log
                Text("4/6/7 TELEMETRY + REBOOT LOGGER", fontWeight = FontWeight.Bold)
                Text(
                    "Stock-firmware sweep: baseline → 4 → restore → 6 → restore → 7 → restore. It captures raw B003 + parsed 0x70/0x90/0x91/0x92 and ALWAYS asks for one physical reboot, even when 0x70 driveMode stays 0. No DFU is started.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text("Phase: ${mode4Log.phase} • Live drive mode: ${liveMode ?: -1}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "Baseline ${mode4Log.baselineMode ?: -1} • 4→${mode4Log.mode4Mode ?: -1} • 6→${mode4Log.mode6Mode ?: -1} • 7→${mode4Log.mode7Mode ?: -1}",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    "Selected ${mode4Log.selectedCandidateMode ?: -1} → After reboot ${mode4Log.postRebootMode ?: -1}",
                    style = MaterialTheme.typography.bodySmall
                )
                if (mode4Log.selectedReason.isNotBlank()) {
                    Text("Selection reason: ${mode4Log.selectedReason}", style = MaterialTheme.typography.bodySmall)
                }
                Text("Result: ${mode4Log.conclusion}", fontWeight = FontWeight.SemiBold)
                Text(
                    "This logger is diagnostic only: no passthrough/volatile verdict is accepted from driveMode[26] alone.",
                    style = MaterialTheme.typography.bodySmall
                )

                when (mode4Log.phase) {
                    "WAIT_POWER_CYCLE" -> Notice("STARTA OM NU: stäng av scootern helt, starta den igen och anslut appen. Tryck sedan CHECK AFTER REBOOT. En vanlig BLE-reconnect räknas inte som fysisk power cycle.")
                    "CAPTURE_BASELINE", "CAPTURE_MODE4", "RESTORE4", "CAPTURE_MODE6", "RESTORE6", "CAPTURE_MODE7", "RESTORE7", "CAPTURE_SELECTED", "CAPTURE_POST_REBOOT" -> Notice("Testet arbetar. Låt scootern stå helt stilla. Appen loggar rå B003 och alla parsade NAVEE-frames under 4/6/7-sweep.")
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.manager.naveeStartMode4RebootLog() },
                        enabled = canWrite && !mode4Log.active && mode4Log.phase != "WAIT_POWER_CYCLE"
                    ) { Text("START LOG") }
                    Button(
                        onClick = { vm.manager.naveeMode4LogCheckAfterReboot() },
                        enabled = canWrite && mode4Log.phase == "WAIT_POWER_CYCLE"
                    ) { Text("CHECK AFTER REBOOT") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.manager.naveeCancelMode4RebootLog() },
                        enabled = canWrite && mode4Log.phase !in setOf("IDLE", "COMPLETE", "CANCELLED", "FAILED")
                    ) { Text("CANCEL / RESTORE") }
                    OutlinedButton(
                        onClick = { vm.manager.naveeShareMode4RebootLog() },
                        enabled = mode4Log.logText.isNotBlank()
                    ) { Text("SHARE LOG") }
                    OutlinedButton(
                        onClick = { vm.manager.naveeResetMode4RebootLog() },
                        enabled = !mode4Log.active && mode4Log.logText.isNotBlank()
                    ) { Text("CLEAR") }
                }

                if (mode4Log.logText.isNotBlank()) {
                    val tail = mode4Log.logText.lines().takeLast(12).joinToString("\n")
                    Text(tail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("ENERGY RECOVERY", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.manager.naveeExperimentalSetEnergyRecovery(30) }, enabled = canWrite) { Text("30") }
                    OutlinedButton(onClick = { vm.manager.naveeExperimentalSetEnergyRecovery(60) }, enabled = canWrite) { Text("60") }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.manager.naveeExperimentalRefreshObservedControls() }, enabled = canWrite) { Text("REFRESH") }
            OutlinedButton(onClick = { vm.manager.naveeExperimentalResetBleSession() }, enabled = state.connected) { Text("RECONNECT BLE") }
        }
        Text(state.naveeExperimentalStatus, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun ToolOnOff(title: String, enabled: Boolean, current: Boolean?, on: () -> Unit, off: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                current?.let { Text("Live: ${if (it) "ON" else "OFF"}", style = MaterialTheme.typography.bodySmall) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = on, enabled = enabled) { Text("ON") }
                OutlinedButton(onClick = off, enabled = enabled) { Text("OFF") }
            }
        }
    }
}

private fun raw70Byte(raw: String?, index: Int): Int? {
    if (raw.isNullOrBlank()) return null
    val bytes = Regex("[0-9A-Fa-f]{2}").findAll(raw).map { it.value.toInt(16) }.toList()
    return bytes.getOrNull(index)
}

private fun autoMode4Verified(state: BleUiState): Boolean =
    state.naveeSpeedResearch.firmwareStock.auto.mode4Verified

private fun autoMode4VolatileVerified(state: BleUiState): Boolean =
    state.naveeSpeedResearch.firmwareStock.auto.mode4VolatileVerified

@Composable
private fun Notice(text: String) {
    Card(Modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
    }
}
