package com.mybiketraffic.karootraffic.karoo

import com.mybiketraffic.karootraffic.engine.TrafficEngine
import com.mybiketraffic.karootraffic.engine.TrafficSnapshot
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.DeveloperField
import io.hammerhead.karooext.models.FieldValue
import io.hammerhead.karooext.models.FitEffect
import io.hammerhead.karooext.models.WriteEventMesg
import io.hammerhead.karooext.models.WriteToRecordMesg
import io.hammerhead.karooext.models.WriteToSessionMesg
import timber.log.Timber

/**
 * Emits FIT DeveloperFields matching MyBikeTrafficFitContributions.mc and fit.xml.
 */
class FitContributionsHandler {
    companion object {
        // FIT Base Types from Garmin FIT SDK
        const val FIT_BASE_TYPE_UINT8: Short = 2
        const val FIT_BASE_TYPE_SINT16: Short = 131
        const val FIT_BASE_TYPE_UINT16: Short = 132

        private const val FIT_EVENT_LAP: Short = 9
        private const val FIT_EVENT_TYPE_MARKER: Short = 3
    }

    val rangeField = DeveloperField(
        fieldDefinitionNumber = TrafficEngine.BT_RANGE_FIELD_ID,
        fitBaseTypeId = FIT_BASE_TYPE_SINT16,
        fieldName = "radar_ranges",
        units = "m"
    )

    val speedField = DeveloperField(
        fieldDefinitionNumber = TrafficEngine.BT_SPEED_FIELD_ID,
        fitBaseTypeId = FIT_BASE_TYPE_UINT8,
        fieldName = "radar_speeds",
        units = "m/s"
    )

    val countRecordField = DeveloperField(
        fieldDefinitionNumber = TrafficEngine.BT_COUNT_FIELD_ID,
        fitBaseTypeId = FIT_BASE_TYPE_UINT16,
        fieldName = "radar_current",
        units = "cars"
    )

    val countSessionField = DeveloperField(
        fieldDefinitionNumber = TrafficEngine.BT_COUNT_SESSION_FIELD_ID,
        fitBaseTypeId = FIT_BASE_TYPE_UINT16,
        fieldName = "radar_total",
        units = "cars"
    )

    val countLapField = DeveloperField(
        fieldDefinitionNumber = TrafficEngine.BT_COUNT_LAP_FIELD_ID,
        fitBaseTypeId = FIT_BASE_TYPE_UINT16,
        fieldName = "radar_lap",
        units = "cars"
    )

    val passingSpeedRelField = DeveloperField(
        fieldDefinitionNumber = TrafficEngine.BT_PASSINGSPEEDREL_FIELD_ID,
        fitBaseTypeId = FIT_BASE_TYPE_UINT8,
        fieldName = "passing_speed",
        units = "kph/mph"
    )

    val passingSpeedAbsField = DeveloperField(
        fieldDefinitionNumber = TrafficEngine.BT_PASSINGSPEEDABS_FIELD_ID,
        fitBaseTypeId = FIT_BASE_TYPE_UINT8,
        fieldName = "passing_speedabs",
        units = "kph/mph"
    )

    private var emitter: Emitter<FitEffect>? = null
    var fitRecordingEnabled: Boolean = true

    fun start(emitter: Emitter<FitEffect>) {
        this.emitter = emitter
        emitter.setCancellable {
            this.emitter = null
        }
    }

    fun onTick(isRecording: Boolean, snapshot: TrafficSnapshot) {
        if (!isRecording || !fitRecordingEnabled) return
        val currentEmitter = emitter ?: return

        try {
            val closestRange = snapshot.rangeInfo.firstOrNull()?.toDouble() ?: -1.0
            val closestSpeedMps = snapshot.speedInfo.firstOrNull()?.toDouble() ?: 255.0
            val relSpd = if (snapshot.disabled) 0.0 else snapshot.approachspd.toDouble()
            val absSpd = if (snapshot.disabled) 0.0 else snapshot.absolutespd.toDouble()

            currentEmitter.onNext(
                WriteToRecordMesg(
                    listOf(
                        FieldValue(rangeField, closestRange),
                        FieldValue(speedField, closestSpeedMps),
                        FieldValue(countRecordField, snapshot.count.toDouble()),
                        FieldValue(countLapField, snapshot.lapcount.toDouble()),
                        FieldValue(passingSpeedRelField, relSpd),
                        FieldValue(passingSpeedAbsField, absSpd)
                    )
                )
            )

            currentEmitter.onNext(
                WriteToSessionMesg(
                    FieldValue(countSessionField, snapshot.count.toDouble())
                )
            )
        } catch (e: Exception) {
            Timber.w(e, "Error writing FIT developer fields")
        }
    }

    fun onLapCompleted(lapCountBeforeReset: Int) {
        if (!fitRecordingEnabled) return
        val currentEmitter = emitter ?: return
        try {
            currentEmitter.onNext(
                WriteEventMesg(
                    event = FIT_EVENT_LAP,
                    eventType = FIT_EVENT_TYPE_MARKER,
                    values = listOf(FieldValue(countLapField, lapCountBeforeReset.toDouble()))
                )
            )
        } catch (e: Exception) {
            Timber.w(e, "Error writing FIT lap event")
        }
    }
}
