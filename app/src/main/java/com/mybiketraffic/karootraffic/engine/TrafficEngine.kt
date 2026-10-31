package com.mybiketraffic.karootraffic.engine

import com.mybiketraffic.karootraffic.radar.RadarConnectionManager
import kotlin.math.roundToInt

data class TrafficSnapshot(
    val count: Int,
    val lapcount: Int,
    val approachspd: Int,
    val absolutespd: Int,
    val lastspd: Int,
    val dist: Int,
    val disabled: Boolean,
    val metric: Boolean,
    val statusText: String,
    val rangeInfo: IntArray,
    val speedInfo: IntArray
)

/**
 * Exact Kotlin port of MyBikeTrafficFitContributions.mc.
 * Computes vehicle counts, lap counts, relative/absolute/last speeds, closest distance,
 * and the FIT developer field arrays at 1Hz.
 */
class TrafficEngine(
    var metric: Boolean = true,
    val bikeRadar: RadarConnectionManager
) {
    companion object {
        const val THRESH = 10
        const val RANGETARGETS = 8
        const val SPEEDTARGETS = 8

        const val BT_RANGE_FIELD_ID: Short = 0
        const val BT_SPEED_FIELD_ID: Short = 1
        const val BT_COUNT_FIELD_ID: Short = 2
        const val BT_COUNT_SESSION_FIELD_ID: Short = 3
        const val BT_COUNT_LAP_FIELD_ID: Short = 4
        const val BT_PASSINGSPEEDREL_FIELD_ID: Short = 5
        const val BT_PASSINGSPEEDABS_FIELD_ID: Short = 6
    }

    var lapcount: Int = 0
        private set
    var count: Int = 0
        private set
    var approachspd: Int = 0
        private set
    var absolutespd: Int = 0
        private set
    var lastspd: Int = 0
        private set
    var dist: Int = 0
        private set
    var disabled: Boolean = true
        private set

    private var lasttrackcnt: Int = 0
    private var lastspdCandidate: Int = 0
    private var crossedthresh: Boolean = false

    val rangeInfo: IntArray = IntArray(RANGETARGETS) { -1 }
    val speedInfo: IntArray = IntArray(SPEEDTARGETS) { 255 }

    /**
     * Called once per second (1Hz).
     *
     * @param isRecording True when the ride is actively recording (equivalent to Garmin timerState == 3).
     * @param currentSpeedMps Rider's current GPS/sensor speed in m/s (or null if unavailable).
     */
    fun compute(isRecording: Boolean, currentSpeedMps: Float?): TrafficSnapshot {
        bikeRadar.tick()

        if (!isRecording) {
            disabled = true
            return snapshot()
        }

        computeAnt(currentSpeedMps)
        return snapshot()
    }

    private fun computeAnt(currentSpeedMps: Float?) {
        val radarInfo = bikeRadar.getRadarInfo()
        if (radarInfo != null && radarInfo.size >= RANGETARGETS) {
            disabled = false
            for (i in 0 until RANGETARGETS) {
                rangeInfo[i] = radarInfo[i].range.toInt()
            }
            for (i in 0 until SPEEDTARGETS) {
                speedInfo[i] = radarInfo[i].speed.toInt()
            }

            approachspd = if (metric) {
                (speedInfo[0] * 3.6).roundToInt()
            } else {
                (speedInfo[0] * 2.23694).roundToInt()
            }

            val currentSpeed = currentSpeedMps ?: 0f
            val riderSpeedConverted = if (metric) {
                (currentSpeed * 3.6).roundToInt()
            } else {
                (currentSpeed * 2.23694).roundToInt()
            }
            absolutespd = if (approachspd > 0) (approachspd + riderSpeedConverted) else 0

            var trackcnt = 0
            for (i in radarInfo.indices) {
                if (radarInfo[i].threat != 0) {
                    trackcnt++
                }
            }

            if (trackcnt < lasttrackcnt) {
                if (crossedthresh) {
                    count += (lasttrackcnt - trackcnt)
                    lapcount += (lasttrackcnt - trackcnt)
                    lastspd = lastspdCandidate
                }
            }

            if (trackcnt > 0) {
                lastspdCandidate = absolutespd
            } else if (trackcnt == 0) {
                lastspdCandidate = 0
            }

            dist = if (metric) {
                rangeInfo[0]
            } else {
                (rangeInfo[0] * 3.28084).toInt()
            }
            crossedthresh = rangeInfo[0] < THRESH
            lasttrackcnt = trackcnt
        } else {
            disabled = true
            for (i in 0 until RANGETARGETS) {
                rangeInfo[i] = -1
                speedInfo[i] = 255
            }
            approachspd = 0
        }
    }

    fun onTimerReset() {
        count = 0
        lapcount = 0
        lasttrackcnt = 0
        lastspdCandidate = 0
        crossedthresh = false
    }

    fun onTimerLap() {
        lapcount = 0
    }

    fun snapshot(): TrafficSnapshot {
        return TrafficSnapshot(
            count = count,
            lapcount = lapcount,
            approachspd = approachspd,
            absolutespd = absolutespd,
            lastspd = lastspd,
            dist = dist,
            disabled = disabled,
            metric = metric,
            statusText = bikeRadar.getDisplayStatus(),
            rangeInfo = rangeInfo.copyOf(),
            speedInfo = speedInfo.copyOf()
        )
    }
}
