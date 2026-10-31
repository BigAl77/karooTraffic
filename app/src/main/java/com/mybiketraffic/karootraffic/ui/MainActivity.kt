package com.mybiketraffic.karootraffic.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mybiketraffic.karootraffic.engine.TrafficSnapshot
import com.mybiketraffic.karootraffic.karoo.KarooTrafficManager
import com.mybiketraffic.karootraffic.karoo.MyBikeTrafficCanvasRenderer
import com.mybiketraffic.karootraffic.preferences.TrafficDisplaySettings
import com.mybiketraffic.karootraffic.upload.UploadPhase
import com.mybiketraffic.karootraffic.upload.UploadStatusSnapshot
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Companion Activity providing:
 * 1. Live Karoo DataField Preview & Interactive Traffic/Ride Simulator (for local testing on PC/Emulator)
 * 2. MyBikeTraffic.com Cloud Auto-Upload (Sign-In + Automatic Post-Ride Wi-Fi Upload)
 * 3. Full Garmin MyBikeTraffic settings configuration
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQ_READ_STORAGE = 1001
    }

    private lateinit var manager: KarooTrafficManager
    private lateinit var previewView: DataFieldPreviewView
    private lateinit var fitInspectorText: TextView
    private lateinit var liveSummaryText: TextView
    private lateinit var uploadStatusText: TextView

    private var previewDarkTheme: Boolean = false
    private var previewTileWidthDp: Int = 240
    private var previewTileHeightDp: Int = 110
    private var updatingSettingsUi: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = KarooTrafficManager.getInstance(this)
        ensureStoragePermission()

        val rootScroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#12161F"))
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
        }
        rootScroll.addView(content)

        // Header
        content.addView(TextView(this).apply {
            text = "KarooTraffic (MyBikeTraffic for Karoo)"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        })

        content.addView(TextView(this).apply {
            text = "Tap the live data field below twice within 1s to test double-tap radar reset."
            setTextColor(Color.parseColor("#9AA5B4"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setPadding(0, 0, 0, dp(10))
        })

        // Live DataField Preview Card
        val previewContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = roundedBg("#1E2433", "#38435A")
            val pad = dp(10)
            setPadding(pad, pad, pad, pad)
        }

        previewView = DataFieldPreviewView(this, manager).apply {
            layoutParams = LinearLayout.LayoutParams(dp(previewTileWidthDp), dp(previewTileHeightDp))
        }
        previewContainer.addView(previewView)

        // Preview size & theme toggles
        val previewControlsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        previewControlsRow.addView(smallButton("Wide (Full)") {
            setPreviewSize(340, 105)
        })
        previewControlsRow.addView(smallButton("Half Tile") {
            setPreviewSize(175, 110)
        })
        previewControlsRow.addView(smallButton("Tall Stack") {
            setPreviewSize(165, 210)
        })
        previewControlsRow.addView(smallButton("Light/Dark") {
            previewDarkTheme = !previewDarkTheme
            previewView.darkTheme = previewDarkTheme
            previewView.invalidate()
        })
        previewContainer.addView(previewControlsRow)
        content.addView(previewContainer, marginBottom(12))

        // MyBikeTraffic.com Auto-Upload Section Card
        val uploadCard = createSectionCard("MyBikeTraffic.com Cloud Auto-Upload (Wi-Fi)")
        buildAutoUploadControls(uploadCard)
        content.addView(uploadCard, marginBottom(12))

        // Simulator Section Card
        val simCard = createSectionCard("Local Ride & Traffic Simulator")

        val simToggleRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val simEnabledCheck = CheckBox(this).apply {
            text = "Sim Radar On"
            setTextColor(Color.WHITE)
            isChecked = manager.simEnabled
            setOnCheckedChangeListener { _, checked ->
                manager.setSimulatorEnabled(checked)
            }
        }
        val simRecCheck = CheckBox(this).apply {
            text = "Ride Recording"
            setTextColor(Color.WHITE)
            isChecked = manager.simRecording
            setOnCheckedChangeListener { _, checked ->
                manager.setSimulatorRecording(checked)
            }
        }
        val simMetricCheck = CheckBox(this).apply {
            text = "Metric"
            setTextColor(Color.WHITE)
            isChecked = manager.trafficEngine.metric
            setOnCheckedChangeListener { _, checked ->
                manager.setSimulatorMetric(checked)
            }
        }
        simToggleRow.addView(simEnabledCheck)
        simToggleRow.addView(simRecCheck)
        simToggleRow.addView(simMetricCheck)
        simCard.addView(simToggleRow)

        val autoTrafficCheck = CheckBox(this).apply {
            text = "Auto-Traffic (periodically spawns approaching vehicles)"
            setTextColor(Color.WHITE)
            isChecked = manager.simAutoTraffic
            setOnCheckedChangeListener { _, checked ->
                manager.setSimulatorAutoTraffic(checked)
                if (checked && !simEnabledCheck.isChecked) {
                    simEnabledCheck.isChecked = true
                }
            }
        }
        simCard.addView(autoTrafficCheck)

        // Rider speed slider
        val speedLabel = TextView(this).apply {
            text = "Simulated Rider Speed: 30 kph (18.6 mph)"
            setTextColor(Color.parseColor("#D0D7E2"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setPadding(0, dp(6), 0, dp(2))
        }
        simCard.addView(speedLabel)

        val speedSeek = SeekBar(this).apply {
            max = 55
            progress = 30
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    val kph = value.toFloat()
                    val mph = kph * 0.621371f
                    speedLabel.text = String.format(
                        Locale.US,
                        "Simulated Rider Speed: %d kph (%.1f mph)",
                        value,
                        mph
                    )
                    manager.setSimulatorRiderSpeedKph(kph)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        simCard.addView(speedSeek)

        // Spawn & Lap Action Buttons
        val simActionRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        simActionRow1.addView(actionButton("+1 Car (Normal)", "#2563EB") {
            simEnabledCheck.isChecked = true
            manager.spawnSimulatedCar(rangeMeters = 75f, closingSpeedMps = 15.2f, threat = 1)
        })
        simActionRow1.addView(actionButton("+1 Fast Car", "#DC2626") {
            simEnabledCheck.isChecked = true
            manager.spawnSimulatedCar(rangeMeters = 90f, closingSpeedMps = 24.32f, threat = 2)
        })
        simCard.addView(simActionRow1)

        val simActionRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        simActionRow2.addView(actionButton("+3 Car Platoon", "#7C3AED") {
            simEnabledCheck.isChecked = true
            manager.spawnSimulatedPlatoon(carCount = 3, baseSpeedMps = 15.2f)
        })
        simActionRow2.addView(actionButton("New Lap", "#059669") {
            manager.triggerSimulatedLap()
        })
        simActionRow2.addView(actionButton("Reset Ride", "#4B5563") {
            manager.resetSimulatedRide()
        })
        simCard.addView(simActionRow2)

        liveSummaryText = TextView(this).apply {
            setTextColor(Color.parseColor("#93C5FD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, 0)
        }
        simCard.addView(liveSummaryText)

        fitInspectorText = TextView(this).apply {
            setTextColor(Color.parseColor("#A7F3D0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(6), 0, 0)
        }
        simCard.addView(fitInspectorText)

        content.addView(simCard, marginBottom(12))

        // Settings Section Card (Exact parity with Garmin MyBikeTraffic settings.xml)
        val settingsCard = createSectionCard("Display & Radar Settings (Garmin Parity)")
        buildSettingsControls(settingsCard)
        content.addView(settingsCard, marginBottom(24))

        setContentView(rootScroll)

        observeState()
    }

    override fun onResume() {
        super.onResume()
        manager.uploader.refreshStatus()
    }

    private fun ensureStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                REQ_READ_STORAGE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_READ_STORAGE) {
            manager.uploader.refreshStatus()
        }
    }

    private fun buildAutoUploadControls(container: LinearLayout) {
        val current = manager.preferences.getSettings()

        val autoUploadCheck = CheckBox(this).apply {
            text = "Auto-upload ride .FIT to MyBikeTraffic.com when ride finishes (over Wi-Fi)"
            setTextColor(Color.WHITE)
            isChecked = current.autoUploadEnabled
            setOnCheckedChangeListener { _, checked ->
                if (!updatingSettingsUi) {
                    val updated = manager.preferences.getSettings().copy(autoUploadEnabled = checked)
                    manager.preferences.saveSettings(updated)
                }
            }
        }
        container.addView(autoUploadCheck)

        uploadStatusText = TextView(this).apply {
            setTextColor(Color.parseColor("#A7F3D0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            typeface = Typeface.MONOSPACE
            setPadding(0, dp(6), 0, dp(8))
        }
        container.addView(uploadStatusText)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        btnRow.addView(actionButton("Sign In / Open MyBikeTraffic", "#2563EB") {
            startActivity(Intent(this, MyBikeTrafficWebActivity::class.java))
        })
        btnRow.addView(actionButton("Upload Latest .FIT Now", "#059669") {
            ensureStoragePermission()
            manager.uploader.uploadLatestFitFile(isAutoTrigger = false)
        })
        container.addView(btnRow)
    }

    private fun setPreviewSize(widthDp: Int, heightDp: Int) {
        previewTileWidthDp = widthDp
        previewTileHeightDp = heightDp
        previewView.layoutParams = LinearLayout.LayoutParams(dp(widthDp), dp(heightDp))
        previewView.requestLayout()
        previewView.invalidate()
    }

    private fun buildSettingsControls(container: LinearLayout) {
        val current = manager.preferences.getSettings()
        val positionEntries = listOf("Off (0)", "1", "2", "3", "4", "5", "6")
        val positionValues = listOf(0, 1, 2, 3, 4, 5, 6)

        fun updatePref(block: (TrafficDisplaySettings) -> TrafficDisplaySettings) {
            if (updatingSettingsUi) return
            val updated = block(manager.preferences.getSettings())
            manager.preferences.saveSettings(updated)
        }

        addSpinnerSetting(
            container,
            "Display vehicle total",
            positionEntries,
            positionValues.indexOf(current.displayTotalPosition).coerceAtLeast(0)
        ) { idx -> updatePref { it.copy(displayTotalPosition = positionValues[idx]) } }

        addSpinnerSetting(
            container,
            "Display lap total",
            positionEntries,
            positionValues.indexOf(current.displayLapPosition).coerceAtLeast(0)
        ) { idx -> updatePref { it.copy(displayLapPosition = positionValues[idx]) } }

        addSpinnerSetting(
            container,
            "Display approach speed (relative)",
            positionEntries,
            positionValues.indexOf(current.displaySpeedRelativePosition).coerceAtLeast(0)
        ) { idx -> updatePref { it.copy(displaySpeedRelativePosition = positionValues[idx]) } }

        addSpinnerSetting(
            container,
            "Display vehicle speed (absolute)",
            positionEntries,
            positionValues.indexOf(current.displaySpeedAbsolutePosition).coerceAtLeast(0)
        ) { idx -> updatePref { it.copy(displaySpeedAbsolutePosition = positionValues[idx]) } }

        addSpinnerSetting(
            container,
            "Display last vehicle speed (absolute)",
            positionEntries,
            positionValues.indexOf(current.displaySpeedLastPosition).coerceAtLeast(0)
        ) { idx -> updatePref { it.copy(displaySpeedLastPosition = positionValues[idx]) } }

        addSpinnerSetting(
            container,
            "Display closest vehicle distance",
            positionEntries,
            positionValues.indexOf(current.displayClosestDistPosition).coerceAtLeast(0)
        ) { idx -> updatePref { it.copy(displayClosestDistPosition = positionValues[idx]) } }

        val orientationLabels = listOf("Auto (-1)", "Horizontal (0)", "Vertical (1)")
        val orientationValues = listOf(-1, 0, 1)
        addSpinnerSetting(
            container,
            "Orientation",
            orientationLabels,
            orientationValues.indexOf(current.autoOrientation).coerceAtLeast(0)
        ) { idx -> updatePref { it.copy(autoOrientation = orientationValues[idx]) } }

        val labelSizeLabels = listOf(
            "Auto (-2)", "Off (-1)", "Extra tiny (0)", "Tiny (1)", "Small (2)",
            "Medium (3)", "Medium+ (4)", "Large (5)", "Large+ (6)", "Extra Large (7)", "Huge (8)"
        )
        val labelSizeValues = listOf(-2, -1, 0, 1, 2, 3, 4, 5, 6, 7, 8)
        addSpinnerSetting(
            container,
            "Label size",
            labelSizeLabels,
            labelSizeValues.indexOf(current.autoLabelSize).coerceAtLeast(0)
        ) { idx -> updatePref { it.copy(autoLabelSize = labelSizeValues[idx]) } }

        val valueSizeLabels = listOf(
            "Auto (-1)", "Extra tiny (0)", "Tiny (1)", "Small (2)", "Medium (3)",
            "Medium+ (4)", "Large (5)", "Large+ (6)", "Extra Large (7)", "Huge (8)"
        )
        val valueSizeValues = listOf(-1, 0, 1, 2, 3, 4, 5, 6, 7, 8)
        addSpinnerSetting(
            container,
            "Value size",
            valueSizeLabels,
            valueSizeValues.indexOf(current.autoValueSize).coerceAtLeast(0)
        ) { idx -> updatePref { it.copy(autoValueSize = valueSizeValues[idx]) } }

        val connModeLabels = listOf(
            "Built-in Karoo Radar Stream (Default)",
            "Switch between Built-in & Direct ANT+",
            "Direct ANT+ / RearVue 820 only"
        )
        val connModeValues = listOf(1, 2, 0)
        addSpinnerSetting(
            container,
            "Radar connection mode",
            connModeLabels,
            connModeValues.indexOf(current.connectionBehaviorMode).coerceAtLeast(0)
        ) { idx -> updatePref { it.copy(connectionBehaviorMode = connModeValues[idx]) } }

        val debugStatusCheck = CheckBox(this).apply {
            text = "Display connection status bar"
            setTextColor(Color.WHITE)
            isChecked = current.displayDebugStatus
            setOnCheckedChangeListener { _, checked ->
                updatePref { it.copy(displayDebugStatus = checked) }
            }
        }
        container.addView(debugStatusCheck)

        val fitRecordCheck = CheckBox(this).apply {
            text = "Record vehicle count & speeds to FIT file"
            setTextColor(Color.WHITE)
            isChecked = current.fitRecordingEnabled
            setOnCheckedChangeListener { _, checked ->
                updatePref { it.copy(fitRecordingEnabled = checked) }
            }
        }
        container.addView(fitRecordCheck)
    }

    private fun addSpinnerSetting(
        container: LinearLayout,
        title: String,
        options: List<String>,
        initialIndex: Int,
        onSelected: (Int) -> Unit
    ) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
        }
        val label = TextView(this).apply {
            text = title
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.2f)
        }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                options
            )
            setSelection(initialIndex, false)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                    (view as? TextView)?.setTextColor(Color.WHITE)
                    onSelected(pos)
                }
                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
        }
        row.addView(label)
        row.addView(spinner)
        container.addView(row)
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    manager.snapshotFlow,
                    manager.settingsFlow,
                    manager.uploader.statusFlow
                ) { snapshot, settings, uploadStatus ->
                    Triple(snapshot, settings, uploadStatus)
                }.collect { (snapshot, settings, uploadStatus) ->
                    previewView.updateState(snapshot, settings)
                    updateInspectorText(snapshot)
                    updateUploadStatusText(uploadStatus)
                }
            }
        }
    }

    private fun updateUploadStatusText(status: UploadStatusSnapshot) {
        val colorHex = when (status.phase) {
            UploadPhase.SUCCESS -> "#6EE7B7"
            UploadPhase.UPLOADING, UploadPhase.WAITING_FOR_WIFI -> "#FCD34D"
            UploadPhase.NEEDS_LOGIN, UploadPhase.ERROR -> "#FCA5A5"
            UploadPhase.IDLE -> "#CBD5E1"
        }
        uploadStatusText.setTextColor(Color.parseColor(colorHex))

        val sessionStr = if (status.hasSessionCookie) {
            "Signed in (Session cookie ready)"
        } else {
            "Not signed in yet — tap 'Sign In / Open MyBikeTraffic' once"
        }
        val localFitStr = status.latestLocalFitFile ?: "None found in /sdcard/FitFiles"
        val lastUploadedStr = status.lastUploadedFitFile ?: "None"

        uploadStatusText.text = buildString {
            appendLine("Account : $sessionStr")
            appendLine("Newest  : $localFitStr (Last uploaded: $lastUploadedStr)")
            append("Status  : ${status.message}")
        }
    }

    private fun updateInspectorText(snapshot: TrafficSnapshot?) {
        if (snapshot == null) return
        val speedUnit = if (snapshot.metric) "kph" else "mph"
        val distUnit = if (snapshot.metric) "m" else "ft"

        liveSummaryText.text = String.format(
            Locale.US,
            "State: %s | Total: %d | Lap: %d | Rel: %d %s | Abs: %d %s | Last: %d %s | Dist: %d %s",
            if (snapshot.disabled) "DISABLED" else "ACTIVE",
            snapshot.count,
            snapshot.lapcount,
            snapshot.approachspd,
            speedUnit,
            snapshot.absolutespd,
            speedUnit,
            snapshot.lastspd,
            speedUnit,
            snapshot.dist,
            distUnit
        )

        val rangesStr = snapshot.rangeInfo.joinToString(", ")
        val speedsStr = snapshot.speedInfo.joinToString(", ")
        val relFit = if (snapshot.disabled) 0 else snapshot.approachspd
        val absFit = if (snapshot.disabled) 0 else snapshot.absolutespd

        fitInspectorText.text = buildString {
            appendLine("FIT Developer Fields (1Hz Record / Lap / Session):")
            appendLine("  radar_ranges[8]  : [$rangesStr]")
            appendLine("  radar_speeds[8]  : [$speedsStr]")
            appendLine("  radar_current    : ${snapshot.count}  | radar_lap: ${snapshot.lapcount}  | radar_total: ${snapshot.count}")
            append("  passing_speed    : $relFit $speedUnit | passing_speedabs: $absFit $speedUnit")
        }
    }

    private fun createSectionCard(title: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundedBg("#1B2230", "#2E3A4F")
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
            addView(TextView(this@MainActivity).apply {
                text = title
                setTextColor(Color.parseColor("#60A5FA"))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 0, 0, dp(6))
            })
        }
    }

    private fun smallButton(label: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setOnClickListener { onClick() }
        }
    }

    private fun actionButton(label: String, hexColor: String, onClick: () -> Unit): Button {
        return Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            background = roundedBg(hexColor, hexColor)
            val lp = LinearLayout.LayoutParams(0, dp(38), 1f)
            lp.setMargins(dp(3), 0, dp(3), 0)
            layoutParams = lp
            setOnClickListener { onClick() }
        }
    }

    private fun roundedBg(fillHex: String, strokeHex: String): GradientDrawable {
        return GradientDrawable().apply {
            setColor(Color.parseColor(fillHex))
            cornerRadius = dp(8).toFloat()
            setStroke(dp(1), Color.parseColor(strokeHex))
        }
    }

    private fun marginBottom(dpVal: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            bottomMargin = dp(dpVal)
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private class DataFieldPreviewView(
        context: Context,
        private val manager: KarooTrafficManager
    ) : View(context) {
        private val renderer = MyBikeTrafficCanvasRenderer(manager.preferences.getSettings())
        private var currentSnapshot: TrafficSnapshot? = manager.snapshotFlow.value
        var darkTheme: Boolean = false

        init {
            setOnClickListener {
                renderer.handleResetTap {
                    manager.radarManager.requestRawWaitReset()
                }
                invalidate()
            }
        }

        fun updateState(snapshot: TrafficSnapshot?, settings: TrafficDisplaySettings) {
            currentSnapshot = snapshot
            renderer.applySettings(settings)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            renderer.render(canvas, width, height, currentSnapshot, darkTheme)
        }
    }
}
