package com.mybiketraffic.karootraffic.radar

import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.StreamState
import timber.log.Timber

/**
 * Connects to Karoo's built-in DataType.Type.RADAR stream and normalizes target ranges and threat levels.
 */
class KarooStreamRadarSource(
    private val karooSystem: KarooSystemService,
    private val normalizer: KarooStreamRadarNormalizer = KarooStreamRadarNormalizer()
) : NativeRadarProvider {

    companion object {
        private val RANGE_FIELDS = listOf(
            DataType.Field.RADAR_TARGET_1_RANGE,
            DataType.Field.RADAR_TARGET_2_RANGE,
            DataType.Field.RADAR_TARGET_3_RANGE,
            DataType.Field.RADAR_TARGET_4_RANGE,
            DataType.Field.RADAR_TARGET_5_RANGE,
            DataType.Field.RADAR_TARGET_6_RANGE,
            DataType.Field.RADAR_TARGET_7_RANGE,
            DataType.Field.RADAR_TARGET_8_RANGE
        )
    }

    private var consumerId: String? = null
    private var karooConnected: Boolean = false

    override val isAvailable: Boolean
        get() = karooConnected

    override val isTracking: Boolean
        get() = normalizer.isStreamTracking

    override val isSearching: Boolean
        get() = normalizer.isStreamSearching

    fun start(connected: Boolean) {
        karooConnected = connected
        stop()
        if (!connected) return

        try {
            consumerId = karooSystem.addConsumer(
                OnStreamState.StartStreaming(DataType.Type.RADAR)
            ) { event: OnStreamState ->
                when (val state = event.state) {
                    is StreamState.Streaming -> {
                        val values = state.dataPoint.values
                        val threat = (values[DataType.Field.RADAR_THREAT_LEVEL] ?: 0.0).toInt()
                        val ranges = RANGE_FIELDS.map { key ->
                            (values[key] ?: 0.0).toFloat()
                        }
                        normalizer.onRadarStreamData(threat, ranges)
                    }
                    is StreamState.Searching -> {
                        normalizer.onStreamSearching()
                    }
                    is StreamState.Idle, is StreamState.NotAvailable -> {
                        normalizer.onStreamDisconnected()
                    }
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "Failed to subscribe to Karoo RADAR stream")
        }
    }

    fun stop() {
        consumerId?.let {
            try {
                karooSystem.removeConsumer(it)
            } catch (_: Exception) {}
        }
        consumerId = null
        normalizer.onStreamDisconnected()
    }

    override fun resetNativeRadar() {
        if (karooConnected) {
            start(true)
        }
    }

    override fun getRadarInfo(): List<RadarTarget>? {
        return normalizer.getTargetsSnapshot()
    }
}
