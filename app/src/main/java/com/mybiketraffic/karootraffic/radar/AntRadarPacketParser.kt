package com.mybiketraffic.karootraffic.radar

/**
 * Pure Kotlin port of MyBikeRawAntRadarChannel packet parsing and target timeout state from MyBikeAntRadar.mc.
 */
class AntRadarPacketParser(
    private val clockSeconds: () -> Long = { System.currentTimeMillis() / 1000L }
) {
    companion object {
        const val ANT_RADAR_DEVICE_TYPE = 40
        const val ANT_RADAR_MESSAGE_PERIOD = 4084
        const val ANT_RADAR_RADIO_FREQUENCY = 57
        const val ANT_RADAR_TRANSMISSION_TYPE = 0
        const val ANT_RADAR_SEARCH_TIMEOUT_LOW = 12

        const val ANT_RADAR_PAGE_TARGETS_A = 0x30
        const val ANT_RADAR_PAGE_TARGETS_B = 0x31
        const val ANT_RADAR_PAGE_DEVICE_STATUS = 0x01
        const val ANT_RADAR_PAGE_ERROR = 0x57

        const val ANT_RADAR_DEVICE_STATE_SHUTDOWN_REQUESTED = 1
        const val ANT_RADAR_DEVICE_STATE_SHUTDOWN_ABORTED = 2
        const val ANT_RADAR_DEVICE_STATE_SHUTDOWN_FORCED = 3

        const val ANT_RADAR_RANGE_SCALE_METERS = 3.125f
        const val ANT_RADAR_SPEED_SCALE_MPS = 3.04f

        const val ANT_RADAR_TARGET_TIMEOUT_SECONDS = 2L
        const val ANT_RADAR_TARGETS_B_TIMEOUT_SECONDS = 1L

        const val RF_EVENT_RX_SEARCH_TIMEOUT = 0x01
        const val RF_EVENT_RX_FAIL = 0x02
        const val RF_EVENT_CHANNEL_CLOSED = 0x07
        const val RF_EVENT_RX_FAIL_GO_TO_SEARCH = 0x08
    }

    private val targets: List<RadarTarget> = RadarTarget.buildEmptyTargets()
    var isSearching: Boolean = false
        private set
    var hasBroadcast: Boolean = false
        private set
    var lastPage: Int? = null
        private set
    var lastRfEvent: Int? = null
        private set
    var hasTargetPage: Boolean = false
        private set
    private var lastTargetPageTime: Long? = null
    private var lastTargetsBPageTime: Long? = null
    var rawDeviceState: Int = 0
        private set
    var sawErrorPage: Boolean = false
        private set

    fun onChannelOpened() {
        clearTargets(clearAvailability = true)
        hasBroadcast = false
        lastPage = null
        lastRfEvent = null
        isSearching = true
        rawDeviceState = 0
        sawErrorPage = false
    }

    fun onChannelClosed() {
        isSearching = false
        hasBroadcast = false
        lastPage = null
        rawDeviceState = 0
        sawErrorPage = false
        clearTargets(clearAvailability = true)
    }

    fun onBroadcastPayload(payload: ByteArray) {
        if (payload.isEmpty()) return
        val pageNumber = payload[0].toInt() and 0xFF
        lastRfEvent = null

        when (pageNumber) {
            ANT_RADAR_PAGE_TARGETS_A -> {
                lastPage = pageNumber
                markTargetPage(pageNumber)
                parseTargets(payload, 0)
            }
            ANT_RADAR_PAGE_TARGETS_B -> {
                lastPage = pageNumber
                markTargetPage(pageNumber)
                parseTargets(payload, 4)
            }
            ANT_RADAR_PAGE_DEVICE_STATUS -> {
                lastPage = pageNumber
                handleDeviceStatus(payload)
            }
            ANT_RADAR_PAGE_ERROR -> {
                lastPage = pageNumber
                hasBroadcast = true
                isSearching = false
                sawErrorPage = true
                clearTargets(clearAvailability = true)
            }
            else -> {
                hasBroadcast = true
                isSearching = false
            }
        }
    }

    fun onRfEvent(eventCode: Int) {
        val code = eventCode and 0xFF
        lastRfEvent = code
        when (code) {
            RF_EVENT_CHANNEL_CLOSED, RF_EVENT_RX_SEARCH_TIMEOUT -> {
                rawDeviceState = 0
                sawErrorPage = false
                clearTargets(clearAvailability = true)
            }
            RF_EVENT_RX_FAIL_GO_TO_SEARCH -> {
                isSearching = true
                lastPage = null
            }
        }
    }

    fun isTracking(): Boolean {
        clearIfStale()
        return hasTargetPage && !sawErrorPage
    }

    fun getRadarInfo(): List<RadarTarget>? {
        clearIfStale()
        if (!hasTargetPage || sawErrorPage) {
            return null
        }
        return targets.map { it.copySnapshot() }
    }

    private fun clearTargetRange(startIndex: Int, endIndex: Int) {
        for (i in startIndex until endIndex) {
            targets[i].clear()
        }
    }

    fun clearTargets(clearAvailability: Boolean) {
        clearTargetRange(0, targets.size)
        if (clearAvailability) {
            hasTargetPage = false
            lastTargetPageTime = null
            lastTargetsBPageTime = null
        }
    }

    private fun extract2Bits(value: Int, index: Int): Int {
        return (value ushr (index * 2)) and 0x03
    }

    private fun extractRange(rawRanges: Int, index: Int): Float {
        return ((rawRanges ushr (index * 6)) and 0x3F).toFloat() * ANT_RADAR_RANGE_SCALE_METERS
    }

    private fun extractSpeed(payload: ByteArray, index: Int): Float {
        val byteIndex = if (index < 2) 6 else 7
        if (payload.size <= byteIndex) return 0f
        val sourceByte = payload[byteIndex].toInt() and 0xFF
        val nibbleShift = (index % 2) * 4
        return ((sourceByte ushr nibbleShift) and 0x0F).toFloat() * ANT_RADAR_SPEED_SCALE_MPS
    }

    private fun parseTargets(payload: ByteArray, startIndex: Int) {
        if (payload.size < 8) return
        val b1 = payload[1].toInt() and 0xFF
        val b3 = payload[3].toInt() and 0xFF
        val b4 = payload[4].toInt() and 0xFF
        val b5 = payload[5].toInt() and 0xFF
        val rawRanges = b3 or (b4 shl 8) or (b5 shl 16)

        for (i in 0 until 4) {
            val threatLevel = extract2Bits(b1, i)
            val targetIndex = startIndex + i
            if (threatLevel == 0) {
                targets[targetIndex].clear()
                continue
            }
            targets[targetIndex].set(
                extractRange(rawRanges, i),
                extractSpeed(payload, i),
                threatLevel
            )
        }
    }

    private fun markTargetPage(pageNumber: Int) {
        val now = clockSeconds()
        hasBroadcast = true
        hasTargetPage = true
        isSearching = false
        lastRfEvent = null
        sawErrorPage = false
        rawDeviceState = 0
        lastPage = pageNumber
        lastTargetPageTime = now
        if (pageNumber == ANT_RADAR_PAGE_TARGETS_B) {
            lastTargetsBPageTime = now
        }
    }

    private fun clearIfStale() {
        val lastTargetTime = lastTargetPageTime
        if (!hasTargetPage || lastTargetTime == null) {
            return
        }
        val now = clockSeconds()
        if ((now - lastTargetTime) >= ANT_RADAR_TARGET_TIMEOUT_SECONDS) {
            clearTargets(clearAvailability = true)
            if (lastPage == ANT_RADAR_PAGE_TARGETS_A || lastPage == ANT_RADAR_PAGE_TARGETS_B) {
                lastPage = null
            }
            return
        }

        val lastBTime = lastTargetsBPageTime
        if (lastBTime != null && (now - lastBTime) >= ANT_RADAR_TARGETS_B_TIMEOUT_SECONDS) {
            clearTargetRange(4, ANT_RADAR_TARGET_SLOTS)
            lastTargetsBPageTime = null
        }
    }

    private fun handleDeviceStatus(payload: ByteArray) {
        hasBroadcast = true
        isSearching = false
        lastRfEvent = null
        lastPage = ANT_RADAR_PAGE_DEVICE_STATUS
        if (payload.size >= 2) {
            rawDeviceState = payload[1].toInt() and 0x03
        }
        val clearTargets = if (payload.size >= 8) {
            (payload[7].toInt() and 0x01) == 0
        } else {
            false
        }
        if (clearTargets ||
            rawDeviceState == ANT_RADAR_DEVICE_STATE_SHUTDOWN_REQUESTED ||
            rawDeviceState == ANT_RADAR_DEVICE_STATE_SHUTDOWN_FORCED
        ) {
            clearTargets(clearAvailability = true)
        }
    }
}
