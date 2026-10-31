package com.mybiketraffic.karootraffic.radar

import kotlin.math.max

/**
 * Normalizes Karoo's built-in DataType.Type.RADAR stream into 8 RadarTarget slots.
 * Since Karoo's native stream provides target ranges (meters) and threat level,
 * this class also estimates closing speed (m/s) from range deltas when explicit speeds are not present.
 */
class KarooStreamRadarNormalizer(
    private val clockMillis: () -> Long = { System.currentTimeMillis() }
) {
    private data class RangeSample(val rangeMeters: Float, val timestampMs: Long)

    private val slotHistory = Array(ANT_RADAR_TARGET_SLOTS) { ArrayDeque<RangeSample>() }
    private val lastComputedSpeeds = FloatArray(ANT_RADAR_TARGET_SLOTS)
    private val normalizedTargets = RadarTarget.buildEmptyTargets()

    var isStreamTracking: Boolean = false
        private set
    var isStreamSearching: Boolean = false
        private set

    fun onStreamSearching() {
        isStreamSearching = true
        isStreamTracking = false
        clear()
    }

    fun onStreamDisconnected() {
        isStreamSearching = false
        isStreamTracking = false
        clear()
    }

    /**
     * Updates the 8 radar target slots from Karoo's RADAR stream values.
     *
     * @param threatLevel RADAR_THREAT_LEVEL (0 = none, 1 = approaching, 2 = fast approaching)
     * @param rangesMeters Up to 8 target ranges in meters (0 or null if slot empty)
     * @param explicitSpeedsMps Optional explicit relative speeds (m/s) if available
     */
    fun onRadarStreamData(
        threatLevel: Int,
        rangesMeters: List<Float>,
        explicitSpeedsMps: List<Float>? = null
    ) {
        val nowMs = clockMillis()
        isStreamSearching = false
        isStreamTracking = true

        for (i in 0 until ANT_RADAR_TARGET_SLOTS) {
            val range = rangesMeters.getOrNull(i) ?: 0f
            if (threatLevel <= 0 || range <= 0f) {
                normalizedTargets[i].clear()
                slotHistory[i].clear()
                lastComputedSpeeds[i] = 0f
                continue
            }

            val speedMps = if (explicitSpeedsMps != null && i < explicitSpeedsMps.size) {
                max(0f, explicitSpeedsMps[i])
            } else {
                estimateClosingSpeedMps(i, range, nowMs)
            }

            normalizedTargets[i].set(range, speedMps, threatLevel)
        }
    }

    private fun estimateClosingSpeedMps(slotIndex: Int, currentRange: Float, nowMs: Long): Float {
        val history = slotHistory[slotIndex]

        // Prune samples older than 1500ms
        while (history.isNotEmpty() && (nowMs - history.first().timestampMs) > 1500L) {
            history.removeFirst()
        }

        // If the target jumped significantly farther away (> 8m), a new target entered this slot
        val lastSample = history.lastOrNull()
        if (lastSample != null && (currentRange - lastSample.rangeMeters) > 8.0f) {
            history.clear()
            lastComputedSpeeds[slotIndex] = 0f
        }

        history.addLast(RangeSample(currentRange, nowMs))

        val oldest = history.first()
        val dtSec = (nowMs - oldest.timestampMs) / 1000.0f
        if (dtSec >= 0.25f) {
            val deltaRange = oldest.rangeMeters - currentRange
            if (deltaRange > 0f) {
                val rawSpeed = (deltaRange / dtSec).coerceIn(0f, 65f)
                lastComputedSpeeds[slotIndex] = rawSpeed
            } else if (dtSec >= 1.2f && deltaRange <= 0f) {
                lastComputedSpeeds[slotIndex] = 0f
            }
        }
        return lastComputedSpeeds[slotIndex]
    }

    fun getTargetsSnapshot(): List<RadarTarget>? {
        if (!isStreamTracking) return null
        return normalizedTargets.map { it.copySnapshot() }
    }

    fun clear() {
        for (i in 0 until ANT_RADAR_TARGET_SLOTS) {
            normalizedTargets[i].clear()
            slotHistory[i].clear()
            lastComputedSpeeds[i] = 0f
        }
    }
}
