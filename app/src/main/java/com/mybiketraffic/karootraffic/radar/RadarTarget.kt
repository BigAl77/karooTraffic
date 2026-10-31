package com.mybiketraffic.karootraffic.radar

const val ANT_RADAR_TARGET_SLOTS = 8

data class RadarTarget(
    var range: Float = 0f,
    var speed: Float = 0f,
    var threat: Int = 0
) {
    fun set(rangeMeters: Float, speedMetersPerSecond: Float, threatLevel: Int) {
        range = rangeMeters
        speed = speedMetersPerSecond
        threat = threatLevel
    }

    fun clear() {
        set(0f, 0.0f, 0)
    }

    fun copySnapshot(): RadarTarget = RadarTarget(range, speed, threat)

    companion object {
        fun buildEmptyTargets(slots: Int = ANT_RADAR_TARGET_SLOTS): List<RadarTarget> {
            return List(slots) { RadarTarget(0f, 0.0f, 0) }
        }
    }
}
