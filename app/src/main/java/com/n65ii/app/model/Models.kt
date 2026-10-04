package com.n65ii.app.model

data class AdvertisementSnapshot(
    val serviceUuids: List<String> = emptyList(),
    val connectable: Boolean? = null
)

data class DiscoveredDevice(
    val name: String?,
    val address: String,
    val rssi: Int,
    val advertisement: AdvertisementSnapshot,
    val lastSeen: Long = System.currentTimeMillis()
)

data class NaveeAutoFirmwareUi(
    val active: Boolean = false,
    val status: String = "CONNECT N65i II",
    val exactModelMatch: Boolean = false,
    val modelId: String? = null,
    val pid5: String? = null,
    val modelName: String? = null,
    val route: String = "CUSTOM_6001",
    val routeDetail: String = "N65i II 6001 / BLDC0005 only",
    val patchReady: Boolean = false,
    val flashReady: Boolean = false,
    val armed: Boolean = false,
    val armSecondsRemaining: Int = 0,
    val ecoSpeedKmh: Int = 6,
    val driveSpeedKmh: Int = 15,
    val sportSpeedKmh: Int = 20,
    val releaseSpeedKmh: Int = 40,
    val mode4Verified: Boolean = false,
    val mode4VolatileVerified: Boolean = false,
    val patchedSha256: String? = null,
    val stockSha256: String? = null,
    val lastError: String? = null
)

data class NaveeFirmwareStockUi(
    val auto: NaveeAutoFirmwareUi = NaveeAutoFirmwareUi(),
    val dfuActive: Boolean = false,
    val dfuProgressPercent: Int = 0,
    val dfuPhase: String = "IDLE",
    val dfuStatus: String = "-",
    val dfuLogTail: String = ""
)



data class NaveeMode4LogUi(
    val active: Boolean = false,
    val phase: String = "IDLE",
    val startedAtEpochMs: Long = 0L,
    val baselineMode: Int? = null,
    val mode4Mode: Int? = null,
    val mode6Mode: Int? = null,
    val mode7Mode: Int? = null,
    val selectedCandidateMode: Int? = null,
    val postRebootMode: Int? = null,
    val baselineRaw70: String? = null,
    val mode4Raw70: String? = null,
    val mode6Raw70: String? = null,
    val mode7Raw70: String? = null,
    val selectedCandidateRaw70: String? = null,
    val postRebootRaw70: String? = null,
    val conclusion: String = "NOT RUN",
    val selectedReason: String = "",
    val snapshotText: String = "",
    val notifyText: String = "",
    val logText: String = ""
)

data class NaveeSpeedResearchUi(
    val firmwareStock: NaveeFirmwareStockUi = NaveeFirmwareStockUi(),
    val mode4Log: NaveeMode4LogUi = NaveeMode4LogUi()
)

data class BleUiState(
    val bluetoothAvailable: Boolean = true,
    val scanning: Boolean = false,
    val connecting: Boolean = false,
    val connected: Boolean = false,
    val scanError: String? = null,
    val discovered: List<DiscoveredDevice> = emptyList(),
    val mtu: Int = 23,
    val naveeNotifySubscribed: Boolean = false,
    val naveePid: String? = null,
    val naveeSerial: String? = null,
    val naveeRegion: String? = null,
    val naveeDerivedSku: String? = null,
    val naveeFirmwareMeter: String? = null,
    val naveeFirmwareBldc: String? = null,
    val naveeFirmwareBms: String? = null,
    val naveeLiveBatteryPercent: Int? = null,
    val naveeLiveSpeedKmh: Double? = null,
    val naveeDrivingStatus: Int? = null,
    val naveeParamRawHex: String? = null,
    val naveeParamStartSpeed: Int? = null,
    val naveeVendorAuthState: String = "PLAIN/AUTO SESSION",
    val naveeExperimentalStatus: String = "READY",
    val naveeSpeedResearch: NaveeSpeedResearchUi = NaveeSpeedResearchUi()
)
