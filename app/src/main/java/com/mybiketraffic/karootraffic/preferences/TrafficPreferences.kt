package com.mybiketraffic.karootraffic.preferences

import android.content.Context
import android.content.SharedPreferences
import com.mybiketraffic.karootraffic.radar.RadarConnectionManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class TrafficDisplaySettings(
    val displayTotalPosition: Int = 1,
    val displayLapPosition: Int = 0,
    val displaySpeedRelativePosition: Int = 0,
    val displaySpeedAbsolutePosition: Int = 2,
    val displaySpeedLastPosition: Int = 0,
    val displayClosestDistPosition: Int = 0,
    val displayDebugStatus: Boolean = true,
    val connectionBehaviorMode: Int = RadarConnectionManager.CONNECTION_MODE_NEVER_820,
    val autoOrientation: Int = -1,
    val autoLabelSize: Int = -2,
    val autoValueSize: Int = -1,
    val fitRecordingEnabled: Boolean = true,
    val autoUploadEnabled: Boolean = true
) {
    val displayPositions: List<Int>
        get() = listOf(
            displayTotalPosition,
            displayLapPosition,
            displaySpeedRelativePosition,
            displaySpeedAbsolutePosition,
            displaySpeedLastPosition,
            displayClosestDistPosition
        )
}

class TrafficPreferences(context: Context) {
    companion object {
        private const val PREFS_NAME = "karoo_traffic_prefs"

        const val KEY_DISPLAY_TOTAL_POS = "displayTotalPosition"
        const val KEY_DISPLAY_LAP_POS = "displayLapPosition"
        const val KEY_DISPLAY_SPEED_REL_POS = "displaySpeedRelativePosition"
        const val KEY_DISPLAY_SPEED_ABS_POS = "displaySpeedAbsolutePosition"
        const val KEY_DISPLAY_SPEED_LAST_POS = "displaySpeedLastPosition"
        const val KEY_DISPLAY_CLOSEST_DIST_POS = "displayClosestDistPosition"
        const val KEY_DISPLAY_DEBUG_STATUS = "displayDebugStatus"
        const val KEY_CONNECTION_BEHAVIOR_MODE = "connectionBehaviorMode"
        const val KEY_AUTO_ORIENTATION = "autoOrientation"
        const val KEY_AUTO_LABEL_SIZE = "autoLabelSize"
        const val KEY_AUTO_VALUE_SIZE = "autoValueSize"
        const val KEY_FIT_RECORDING_ENABLED = "fitRecordingEnabled"
        const val KEY_AUTO_UPLOAD_ENABLED = "autoUploadEnabled"
        const val KEY_LAST_UPLOADED_FIT_NAME = "lastUploadedFitName"
        const val KEY_LAST_UPLOADED_FIT_MTIME = "lastUploadedFitMtime"
        const val KEY_LAST_UPLOAD_STATUS = "lastUploadStatus"
    }

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getSettings(): TrafficDisplaySettings {
        return TrafficDisplaySettings(
            displayTotalPosition = prefs.getInt(KEY_DISPLAY_TOTAL_POS, 1),
            displayLapPosition = prefs.getInt(KEY_DISPLAY_LAP_POS, 0),
            displaySpeedRelativePosition = prefs.getInt(KEY_DISPLAY_SPEED_REL_POS, 0),
            displaySpeedAbsolutePosition = prefs.getInt(KEY_DISPLAY_SPEED_ABS_POS, 2),
            displaySpeedLastPosition = prefs.getInt(KEY_DISPLAY_SPEED_LAST_POS, 0),
            displayClosestDistPosition = prefs.getInt(KEY_DISPLAY_CLOSEST_DIST_POS, 0),
            displayDebugStatus = prefs.getBoolean(KEY_DISPLAY_DEBUG_STATUS, true),
            connectionBehaviorMode = prefs.getInt(
                KEY_CONNECTION_BEHAVIOR_MODE,
                RadarConnectionManager.CONNECTION_MODE_NEVER_820
            ),
            autoOrientation = prefs.getInt(KEY_AUTO_ORIENTATION, -1),
            autoLabelSize = prefs.getInt(KEY_AUTO_LABEL_SIZE, -2),
            autoValueSize = prefs.getInt(KEY_AUTO_VALUE_SIZE, -1),
            fitRecordingEnabled = prefs.getBoolean(KEY_FIT_RECORDING_ENABLED, true),
            autoUploadEnabled = prefs.getBoolean(KEY_AUTO_UPLOAD_ENABLED, true)
        )
    }

    fun saveSettings(settings: TrafficDisplaySettings) {
        prefs.edit()
            .putInt(KEY_DISPLAY_TOTAL_POS, settings.displayTotalPosition)
            .putInt(KEY_DISPLAY_LAP_POS, settings.displayLapPosition)
            .putInt(KEY_DISPLAY_SPEED_REL_POS, settings.displaySpeedRelativePosition)
            .putInt(KEY_DISPLAY_SPEED_ABS_POS, settings.displaySpeedAbsolutePosition)
            .putInt(KEY_DISPLAY_SPEED_LAST_POS, settings.displaySpeedLastPosition)
            .putInt(KEY_DISPLAY_CLOSEST_DIST_POS, settings.displayClosestDistPosition)
            .putBoolean(KEY_DISPLAY_DEBUG_STATUS, settings.displayDebugStatus)
            .putInt(KEY_CONNECTION_BEHAVIOR_MODE, settings.connectionBehaviorMode)
            .putInt(KEY_AUTO_ORIENTATION, settings.autoOrientation)
            .putInt(KEY_AUTO_LABEL_SIZE, settings.autoLabelSize)
            .putInt(KEY_AUTO_VALUE_SIZE, settings.autoValueSize)
            .putBoolean(KEY_FIT_RECORDING_ENABLED, settings.fitRecordingEnabled)
            .putBoolean(KEY_AUTO_UPLOAD_ENABLED, settings.autoUploadEnabled)
            .apply()
    }

    fun getLastUploadedFitName(): String? = prefs.getString(KEY_LAST_UPLOADED_FIT_NAME, null)

    fun getLastUploadedFitMtime(): Long = prefs.getLong(KEY_LAST_UPLOADED_FIT_MTIME, 0L)

    fun setLastUploadedFit(fileName: String, lastModified: Long) {
        prefs.edit()
            .putString(KEY_LAST_UPLOADED_FIT_NAME, fileName)
            .putLong(KEY_LAST_UPLOADED_FIT_MTIME, lastModified)
            .apply()
    }

    fun getLastUploadStatus(): String =
        prefs.getString(KEY_LAST_UPLOAD_STATUS, "No rides uploaded yet") ?: "No rides uploaded yet"

    fun setLastUploadStatus(status: String) {
        prefs.edit().putString(KEY_LAST_UPLOAD_STATUS, status).apply()
    }

    fun settingsFlow(): Flow<TrafficDisplaySettings> = callbackFlow {
        trySend(getSettings())
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            trySend(getSettings())
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
}
