package com.mybiketraffic.karootraffic.radar

/**
 * Abstraction over the raw ANT+ channel lifecycle so RadarConnectionManager can be tested on JVM.
 */
interface RawAntRadarController {
    val isOpen: Boolean
    val isSearching: Boolean
    val isTracking: Boolean
    val lastPage: Int?
    val lastRfEvent: Int?
    val rawDeviceState: Int
    val sawErrorPage: Boolean
    fun openChannel(): Boolean
    fun closeChannel()
    fun getRadarInfo(): List<RadarTarget>?
}

/**
 * Abstraction over Karoo's built-in radar stream state.
 */
interface NativeRadarProvider {
    val isAvailable: Boolean
    val isTracking: Boolean
    val isSearching: Boolean
    fun resetNativeRadar()
    fun getRadarInfo(): List<RadarTarget>?
}

/**
 * Exact Kotlin port of MyBikeAntRadar state machine from MyBikeAntRadar.mc.
 * Prioritizes the built-in Karoo radar stream by default, with full support for direct ANT+ fallback/override.
 */
class RadarConnectionManager(
    connectionBehaviorMode: Int = CONNECTION_MODE_NEVER_820,
    private val nativeRadar: NativeRadarProvider,
    private val rawRadarFactory: () -> RawAntRadarController
) {
    companion object {
        const val CONNECTION_MODE_ALWAYS_820 = 0
        const val CONNECTION_MODE_NEVER_820 = 1
        const val CONNECTION_MODE_SOMETIMES_820 = 2

        const val ANT_RADAR_FALLBACK_DELAY_SOMETIMES_TICKS = 120
        const val ANT_RADAR_RAW_RECOVERY_DELAY_TICKS = 2
        const val ANT_RADAR_RAW_RESTART_COOLDOWN_TICKS = 8
        const val ANT_RADAR_RAW_RESET_STATUS_TICKS = 3
    }

    var connectionBehaviorMode: Int = connectionBehaviorMode
        private set

    private var statusCode: String = if (nativeRadar.isAvailable) "init" else "fallback"
    private var skipWaitForNew820SecureBluetooth: Boolean = connectionBehaviorMode == CONNECTION_MODE_ALWAYS_820
    private var useNativeOnly: Boolean = connectionBehaviorMode == CONNECTION_MODE_NEVER_820
    private var manualRawResetPending: Boolean = false
    private var rawRadar: RawAntRadarController? = null
    private var nativeSessionLocked: Boolean = false
    private var rawInvalidTicks: Int = 0
    private var rawRestartCooldownTicks: Int = 0
    private var rawResetStatusTicks: Int = 0
    private var ticksWithoutNative: Int = 0
    private var skipWaitOnce: Boolean = false

    // Optional local simulator override for testing on emulator/PC
    private var simulatorTargets: List<RadarTarget>? = null
    var isSimulatorActive: Boolean = false
        private set

    fun setSimulatorTargets(active: Boolean, targets: List<RadarTarget>?) {
        isSimulatorActive = active
        simulatorTargets = targets?.map { it.copySnapshot() }
    }

    fun updateConnectionBehaviorMode(mode: Int) {
        if (connectionBehaviorMode == mode) return
        connectionBehaviorMode = mode
        skipWaitForNew820SecureBluetooth = mode == CONNECTION_MODE_ALWAYS_820
        useNativeOnly = mode == CONNECTION_MODE_NEVER_820
        requestRawWaitReset()
    }

    private fun clearRawRecoveryState() {
        rawInvalidTicks = 0
        rawRestartCooldownTicks = 0
        rawResetStatusTicks = 0
        manualRawResetPending = false
    }

    private fun stopRawFallback() {
        val currentRaw = rawRadar
        if (currentRaw == null) {
            clearRawRecoveryState()
            return
        }
        currentRaw.closeChannel()
        rawRadar = null
        clearRawRecoveryState()
    }

    private fun startRawFallback() {
        if (rawRadar != null) return
        skipWaitOnce = false
        val channel = rawRadarFactory()
        rawRadar = channel
        if (channel.openChannel()) {
            statusCode = "scan"
        } else {
            statusCode = "raw_fail"
            manualRawResetPending = false
        }
    }

    private fun restartRawFallback() {
        stopRawFallback()
        rawInvalidTicks = 0
        rawRestartCooldownTicks = ANT_RADAR_RAW_RESTART_COOLDOWN_TICKS
        startRawFallback()
        if (rawRadar?.isOpen == true) {
            rawResetStatusTicks = ANT_RADAR_RAW_RESET_STATUS_TICKS
        }
    }

    private fun tickRawRecoveryState() {
        if (rawRestartCooldownTicks > 0) {
            rawRestartCooldownTicks--
        }
        if (rawResetStatusTicks > 0) {
            rawResetStatusTicks--
        }
    }

    private fun shouldRestartRawFallback(
        rawTracking: Boolean,
        rawSearching: Boolean,
        rawLastPage: Int?,
        rawLastRfEvent: Int?
    ): Boolean {
        val currentRaw = rawRadar ?: return false
        if (rawTracking) return false
        if (currentRaw.sawErrorPage) return true

        if (rawLastRfEvent == AntRadarPacketParser.RF_EVENT_CHANNEL_CLOSED ||
            rawLastRfEvent == AntRadarPacketParser.RF_EVENT_RX_SEARCH_TIMEOUT ||
            rawLastRfEvent == AntRadarPacketParser.RF_EVENT_RX_FAIL_GO_TO_SEARCH
        ) {
            return true
        }

        if (rawSearching) return false
        return rawLastPage != AntRadarPacketParser.ANT_RADAR_PAGE_DEVICE_STATUS
    }

    private fun updateRawStatus(): Boolean {
        val currentRaw = rawRadar ?: return false
        val lastPage = currentRaw.lastPage
        when {
            currentRaw.isTracking -> {
                manualRawResetPending = false
                statusCode = "raw"
            }
            lastPage != null -> {
                manualRawResetPending = false
                statusCode = String.format("page_%02X", lastPage)
            }
            currentRaw.isSearching -> {
                manualRawResetPending = false
                statusCode = "scan"
            }
        }
        return true
    }

    private fun getRawScanDisplayStatus(): String? {
        val currentRaw = rawRadar ?: return null
        if (!currentRaw.isSearching) return null
        val lastRfEvent = currentRaw.lastRfEvent ?: return null
        return when (lastRfEvent) {
            AntRadarPacketParser.RF_EVENT_RX_SEARCH_TIMEOUT -> "TIMEOUT"
            AntRadarPacketParser.RF_EVENT_RX_FAIL_GO_TO_SEARCH -> "SCANFAIL"
            AntRadarPacketParser.RF_EVENT_CHANNEL_CLOSED -> "CLOSED"
            else -> "SCANNING"
        }
    }

    private fun getRawPageDisplayStatus(): String? {
        val currentRaw = rawRadar ?: return null
        if (currentRaw.sawErrorPage) return "ERROR"
        val lastPage = currentRaw.lastPage
        if (lastPage != AntRadarPacketParser.ANT_RADAR_PAGE_DEVICE_STATUS) return null
        return when (currentRaw.rawDeviceState) {
            AntRadarPacketParser.ANT_RADAR_DEVICE_STATE_SHUTDOWN_REQUESTED -> "SHUTREQ"
            AntRadarPacketParser.ANT_RADAR_DEVICE_STATE_SHUTDOWN_ABORTED -> "SHUTABRT"
            AntRadarPacketParser.ANT_RADAR_DEVICE_STATE_SHUTDOWN_FORCED -> "SHUTFORCE"
            else -> "STATUS"
        }
    }

    fun tick() {
        if (isSimulatorActive) {
            statusCode = "sim"
            return
        }

        val nativeTracking = !skipWaitForNew820SecureBluetooth && nativeRadar.isAvailable && nativeRadar.isTracking
        val nativeSearching = !skipWaitForNew820SecureBluetooth && nativeRadar.isAvailable && nativeRadar.isSearching
        val rawTracking = rawRadar?.isTracking == true
        val rawSearching = rawRadar?.isSearching == true
        val rawLastPage = rawRadar?.lastPage
        val rawLastRfEvent = rawRadar?.lastRfEvent

        if (nativeTracking) {
            manualRawResetPending = false
            nativeSessionLocked = true
        }

        if (!skipWaitForNew820SecureBluetooth && nativeRadar.isAvailable && (nativeSessionLocked || nativeSearching)) {
            rawInvalidTicks = 0
            stopRawFallback()
            statusCode = when {
                nativeTracking -> {
                    ticksWithoutNative = 0
                    "native"
                }
                nativeSearching -> {
                    manualRawResetPending = false
                    ticksWithoutNative = 0
                    "pair"
                }
                else -> "wait"
            }
            return
        }

        if (useNativeOnly) {
            stopRawFallback()
            statusCode = if (nativeRadar.isAvailable) "wait" else "fallback"
            return
        }

        tickRawRecoveryState()

        if (!skipWaitForNew820SecureBluetooth && nativeRadar.isAvailable) {
            if (rawRadar != null) {
                if (shouldRestartRawFallback(rawTracking, rawSearching, rawLastPage, rawLastRfEvent)) {
                    rawInvalidTicks++
                    if (rawRestartCooldownTicks == 0 && rawInvalidTicks >= ANT_RADAR_RAW_RECOVERY_DELAY_TICKS) {
                        restartRawFallback()
                        updateRawStatus()
                        return
                    }
                } else {
                    rawInvalidTicks = 0
                }
                updateRawStatus()
                return
            }

            if (!rawTracking) {
                ticksWithoutNative++
                if (connectionBehaviorMode == CONNECTION_MODE_SOMETIMES_820 &&
                    !skipWaitOnce &&
                    ticksWithoutNative < ANT_RADAR_FALLBACK_DELAY_SOMETIMES_TICKS
                ) {
                    statusCode = "wait"
                    return
                }
            }
        }

        startRawFallback()
        updateRawStatus()
    }

    fun getRadarInfo(): List<RadarTarget>? {
        if (isSimulatorActive) {
            return simulatorTargets
        }

        if (!skipWaitForNew820SecureBluetooth && nativeRadar.isAvailable) {
            val nativeTracking = nativeRadar.isTracking
            if (nativeSessionLocked || nativeTracking) {
                if (!nativeTracking) return null
                return nativeRadar.getRadarInfo()
            }
        }

        return rawRadar?.getRadarInfo()
    }

    fun isTracking(): Boolean {
        if (isSimulatorActive) {
            return simulatorTargets != null
        }

        if (!skipWaitForNew820SecureBluetooth && nativeRadar.isAvailable) {
            val nativeTracking = nativeRadar.isTracking
            if (nativeSessionLocked || nativeTracking) {
                return nativeTracking
            }
        }

        return rawRadar?.isTracking == true
    }

    fun requestRawWaitReset() {
        stopRawFallback()
        nativeSessionLocked = false
        if (useNativeOnly) {
            manualRawResetPending = false
            rawResetStatusTicks = 0
            nativeRadar.resetNativeRadar()
            statusCode = if (nativeRadar.isAvailable) "pair" else "fallback"
        } else {
            manualRawResetPending = true
            rawResetStatusTicks = ANT_RADAR_RAW_RESET_STATUS_TICKS
            if (connectionBehaviorMode == CONNECTION_MODE_SOMETIMES_820) {
                skipWaitOnce = true
            }
        }
        ticksWithoutNative = 0
        statusCode = "init"

        if (!skipWaitForNew820SecureBluetooth &&
            nativeRadar.isAvailable &&
            connectionBehaviorMode == CONNECTION_MODE_SOMETIMES_820 &&
            !skipWaitOnce
        ) {
            manualRawResetPending = false
            rawResetStatusTicks = 0
            statusCode = "wait"
        }
    }

    private fun getLiveDisplayFallback(): String {
        if (!skipWaitForNew820SecureBluetooth && nativeRadar.isAvailable) {
            if (nativeRadar.isTracking) return "PAIRED"
            if (nativeRadar.isSearching) return "PAIR"
        }
        rawRadar?.let { raw ->
            if (raw.isTracking) return "820 OK"
            if (raw.isSearching) return "SCAN"
        }
        if (nativeRadar.isAvailable) return "WAIT"
        return "RADAR"
    }

    fun getDisplayStatus(): String {
        if (isSimulatorActive) return "SIM OK"
        if (manualRawResetPending || rawResetStatusTicks > 0) return "RESET"
        if (statusCode == "wait") return "WAIT"

        getRawScanDisplayStatus()?.let { return it }
        getRawPageDisplayStatus()?.let { return it }

        return when {
            statusCode == "init" -> "INIT"
            statusCode == "pair" -> "PAIR"
            statusCode == "none" -> "NONE"
            statusCode == "native" -> "PAIRED"
            statusCode == "scan" -> "SCAN"
            statusCode == "raw" -> "820 OK"
            statusCode == "raw_fail" -> "RAWFAIL"
            statusCode == "fallback" -> "FALLBACK"
            statusCode == "page_01" -> "STATUS"
            statusCode == "page_30" || statusCode == "page_31" -> "TARGETS"
            statusCode == "page_57" -> "ERROR"
            statusCode.startsWith("page_") -> "PAGE"
            else -> getLiveDisplayFallback()
        }
    }

    fun dispose() {
        stopRawFallback()
    }
}
