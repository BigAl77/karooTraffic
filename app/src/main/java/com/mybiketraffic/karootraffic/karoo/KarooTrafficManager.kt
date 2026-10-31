package com.mybiketraffic.karootraffic.karoo

import android.content.Context
import com.mybiketraffic.karootraffic.engine.TrafficEngine
import com.mybiketraffic.karootraffic.engine.TrafficSnapshot
import com.mybiketraffic.karootraffic.preferences.TrafficDisplaySettings
import com.mybiketraffic.karootraffic.preferences.TrafficPreferences
import com.mybiketraffic.karootraffic.radar.ANT_RADAR_TARGET_SLOTS
import com.mybiketraffic.karootraffic.radar.KarooStreamRadarSource
import com.mybiketraffic.karootraffic.radar.RadarConnectionManager
import com.mybiketraffic.karootraffic.radar.RadarTarget
import com.mybiketraffic.karootraffic.radar.RawAntRadarChannel
import com.mybiketraffic.karootraffic.upload.MyBikeTrafficUploader
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnStreamState
import io.hammerhead.karooext.models.RequestAnt
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import kotlin.random.Random

/**
 * Coordinates KarooSystemService streams, RadarConnectionManager, TrafficEngine (1Hz tick),
 * FIT file contributions, automatic post-ride MyBikeTraffic.com upload, and the built-in Simulator.
 */
class KarooTrafficManager private constructor(context: Context) {

    companion object {
        const val EXTENSION_ID = "karootraffic"

        @Volatile
        private var instance: KarooTrafficManager? = null

        fun getInstance(context: Context): KarooTrafficManager {
            return instance ?: synchronized(this) {
                instance ?: KarooTrafficManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val preferences = TrafficPreferences(appContext)
    val karooSystem = KarooSystemService(appContext)
    val fitHandler = FitContributionsHandler()
    val uploader = MyBikeTrafficUploader(appContext, preferences)

    private val karooRadarSource = KarooStreamRadarSource(karooSystem)
    val radarManager: RadarConnectionManager
    val trafficEngine: TrafficEngine

    private var karooConnected: Boolean = false
    private var rideState: RideState = RideState.Idle
    private var currentSpeedMps: Float? = null
    private var lastLapNumber: Int? = null

    private var rideStateConsumerId: String? = null
    private var userProfileConsumerId: String? = null
    private var speedConsumerId: String? = null
    private var lapConsumerId: String? = null

    // Local Simulator state (for testing on Emulator / PC when KarooSystem is not present)
    var simEnabled: Boolean = false
        private set
    var simRecording: Boolean = true
        private set
    var simRiderSpeedMps: Float = 8.33f // ~30 kph default
        private set
    var simAutoTraffic: Boolean = false
        private set

    private data class SimVehicle(var rangeMeters: Float, val closingSpeedMps: Float, val threat: Int)
    private val simVehicles = mutableListOf<SimVehicle>()

    private val _snapshotFlow = MutableStateFlow<TrafficSnapshot?>(null)
    val snapshotFlow: StateFlow<TrafficSnapshot?> = _snapshotFlow.asStateFlow()

    private val _settingsFlow = MutableStateFlow(preferences.getSettings())
    val settingsFlow: StateFlow<TrafficDisplaySettings> = _settingsFlow.asStateFlow()

    private val _tapCountFlow = MutableStateFlow(0L)
    val tapEventFlow: StateFlow<Long> = _tapCountFlow.asStateFlow()

    init {
        val initialSettings = preferences.getSettings()
        fitHandler.fitRecordingEnabled = initialSettings.fitRecordingEnabled

        radarManager = RadarConnectionManager(
            connectionBehaviorMode = initialSettings.connectionBehaviorMode,
            nativeRadar = karooRadarSource,
            rawRadarFactory = { RawAntRadarChannel(appContext) }
        )

        trafficEngine = TrafficEngine(
            metric = true,
            bikeRadar = radarManager
        )

        _snapshotFlow.value = trafficEngine.snapshot()

        scope.launch {
            preferences.settingsFlow().collect { settings ->
                _settingsFlow.value = settings
                fitHandler.fitRecordingEnabled = settings.fitRecordingEnabled
                radarManager.updateConnectionBehaviorMode(settings.connectionBehaviorMode)
            }
        }

        connectKarooSystem()
        startOneHertzLoop()
    }

    private fun connectKarooSystem() {
        try {
            karooSystem.connect { connected ->
                Timber.i("KarooTraffic connected to KarooSystem: %s", connected)
                karooConnected = connected
                if (connected) {
                    karooSystem.dispatch(RequestAnt(EXTENSION_ID))
                    karooRadarSource.start(true)
                    registerKarooConsumers()
                } else {
                    karooRadarSource.stop()
                    unregisterKarooConsumers()
                }
            }
        } catch (e: Exception) {
            Timber.w(e, "KarooSystemService not available on this device (running in standalone/emulator mode)")
        }
    }

    private fun registerKarooConsumers() {
        unregisterKarooConsumers()

        rideStateConsumerId = karooSystem.addConsumer { newState: RideState ->
            val oldState = rideState
            rideState = newState
            if (newState is RideState.Idle && (oldState is RideState.Recording || oldState is RideState.Paused)) {
                trafficEngine.onTimerReset()
                lastLapNumber = null
                uploader.onRideCompleted()
            }
        }

        userProfileConsumerId = karooSystem.addConsumer { profile: UserProfile ->
            val isMetric = profile.preferredUnit.distance != UserProfile.PreferredUnit.UnitType.IMPERIAL
            if (!simEnabled) {
                trafficEngine.metric = isMetric
            }
        }

        speedConsumerId = karooSystem.addConsumer(
            OnStreamState.StartStreaming(DataType.Type.SPEED)
        ) { event: OnStreamState ->
            val state = event.state
            currentSpeedMps = if (state is StreamState.Streaming) {
                state.dataPoint.singleValue?.toFloat()
            } else {
                null
            }
        }

        lapConsumerId = karooSystem.addConsumer(
            OnStreamState.StartStreaming(DataType.Type.LAP_NUMBER)
        ) { event: OnStreamState ->
            val state = event.state
            if (state is StreamState.Streaming) {
                val lap = state.dataPoint.singleValue?.toInt() ?: return@addConsumer
                val prev = lastLapNumber
                if (prev != null && lap > prev) {
                    fitHandler.onLapCompleted(trafficEngine.lapcount)
                    trafficEngine.onTimerLap()
                }
                lastLapNumber = lap
            }
        }
    }

    private fun unregisterKarooConsumers() {
        rideStateConsumerId?.let { runCatching { karooSystem.removeConsumer(it) } }
        userProfileConsumerId?.let { runCatching { karooSystem.removeConsumer(it) } }
        speedConsumerId?.let { runCatching { karooSystem.removeConsumer(it) } }
        lapConsumerId?.let { runCatching { karooSystem.removeConsumer(it) } }
        rideStateConsumerId = null
        userProfileConsumerId = null
        speedConsumerId = null
        lapConsumerId = null
    }

    private fun startOneHertzLoop() {
        scope.launch {
            while (isActive) {
                tickOnce()
                delay(1_000L)
            }
        }
    }

    @Synchronized
    fun tickOnce(): TrafficSnapshot {
        if (simEnabled) {
            stepSimulation()
        }

        val recording = if (simEnabled) {
            simRecording
        } else {
            rideState is RideState.Recording
        }

        val speedMps = if (simEnabled) {
            simRiderSpeedMps
        } else {
            currentSpeedMps
        }

        val snap = trafficEngine.compute(recording, speedMps)
        fitHandler.onTick(recording, snap)
        _snapshotFlow.value = snap
        return snap
    }

    fun onDataFieldTapped() {
        _tapCountFlow.value = System.currentTimeMillis()
    }

    // --- Local Simulator Controls ---

    @Synchronized
    fun setSimulatorEnabled(enabled: Boolean) {
        simEnabled = enabled
        if (!enabled) {
            simVehicles.clear()
            radarManager.setSimulatorTargets(false, null)
        } else {
            updateSimulatorRadarTargets()
        }
        tickOnce()
    }

    @Synchronized
    fun setSimulatorRecording(recording: Boolean) {
        simRecording = recording
        tickOnce()
    }

    @Synchronized
    fun setSimulatorMetric(metric: Boolean) {
        trafficEngine.metric = metric
        tickOnce()
    }

    @Synchronized
    fun setSimulatorRiderSpeedKph(speedKph: Float) {
        simRiderSpeedMps = (speedKph / 3.6f).coerceAtLeast(0f)
    }

    @Synchronized
    fun setSimulatorAutoTraffic(enabled: Boolean) {
        simAutoTraffic = enabled
        if (enabled && !simEnabled) {
            simEnabled = true
        }
    }

    @Synchronized
    fun spawnSimulatedCar(rangeMeters: Float = 90f, closingSpeedMps: Float = 15.2f, threat: Int = 1) {
        if (!simEnabled) {
            simEnabled = true
        }
        if (simVehicles.size < ANT_RADAR_TARGET_SLOTS) {
            simVehicles.add(SimVehicle(rangeMeters, closingSpeedMps, threat))
            simVehicles.sortBy { it.rangeMeters }
            updateSimulatorRadarTargets()
        }
    }

    @Synchronized
    fun spawnSimulatedPlatoon(carCount: Int = 3, baseSpeedMps: Float = 15.2f) {
        if (!simEnabled) {
            simEnabled = true
        }
        val countToAdd = carCount.coerceIn(1, ANT_RADAR_TARGET_SLOTS)
        for (i in 0 until countToAdd) {
            if (simVehicles.size < ANT_RADAR_TARGET_SLOTS) {
                val startRange = 65f + (i * 18f)
                val threat = if (baseSpeedMps >= 18f) 2 else 1
                simVehicles.add(SimVehicle(startRange, baseSpeedMps, threat))
            }
        }
        simVehicles.sortBy { it.rangeMeters }
        updateSimulatorRadarTargets()
    }

    @Synchronized
    fun triggerSimulatedLap() {
        fitHandler.onLapCompleted(trafficEngine.lapcount)
        trafficEngine.onTimerLap()
        _snapshotFlow.value = trafficEngine.snapshot()
    }

    @Synchronized
    fun resetSimulatedRide() {
        simVehicles.clear()
        trafficEngine.onTimerReset()
        if (simEnabled) {
            updateSimulatorRadarTargets()
        }
        _snapshotFlow.value = trafficEngine.snapshot()
    }

    private fun stepSimulation() {
        val iterator = simVehicles.iterator()
        while (iterator.hasNext()) {
            val car = iterator.next()
            car.rangeMeters -= car.closingSpeedMps
            if (car.rangeMeters <= 0f) {
                iterator.remove()
            }
        }

        if (simAutoTraffic && simVehicles.isEmpty() && Random.nextFloat() < 0.35f) {
            val speedSteps = listOf(12.16f, 15.20f, 18.24f, 21.28f)
            val chosenSpeed = speedSteps.random()
            val threat = if (chosenSpeed >= 18f) 2 else 1
            simVehicles.add(SimVehicle(rangeMeters = 85f, closingSpeedMps = chosenSpeed, threat = threat))
        }

        simVehicles.sortBy { it.rangeMeters }
        updateSimulatorRadarTargets()
    }

    private fun updateSimulatorRadarTargets() {
        val targets = RadarTarget.buildEmptyTargets()
        for (i in 0 until minOf(simVehicles.size, ANT_RADAR_TARGET_SLOTS)) {
            val v = simVehicles[i]
            targets[i].set(v.rangeMeters, v.closingSpeedMps, v.threat)
        }
        radarManager.setSimulatorTargets(true, targets)
    }
}
