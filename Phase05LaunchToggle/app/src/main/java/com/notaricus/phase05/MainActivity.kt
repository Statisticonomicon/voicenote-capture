package com.notaricus.phase05

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 0.5 launch-toggle spike.
 *
 * Phase 0 proved a foreground Wear app does NOT receive hardware-button key
 * events. This spike tests the alternative: drive the app through the LAUNCH
 * path instead.
 *
 * A hardware button assigned to an app fires a launcher Intent. With
 * launchMode=singleTask, the FIRST launch hits onCreate() and the app starts
 * "recording" (state ACTIVE). A SECOND launch (button pressed again) should be
 * delivered to the SAME instance via onNewIntent(), which we treat as STOP.
 *
 * The gating question for Phase 0.5: does onNewIntent() fire on re-launch?
 * Test without needing real button assignment by simulating the button's
 * launch with: adb shell am start -n com.notaricus.phase05/.MainActivity
 *
 * An on-screen tap toggle is kept as the guaranteed fallback control.
 * This is a prototype: it proves a mechanism, not production capture code.
 */
class MainActivity : Activity() {

    private companion object {
        const val TAG = "Phase05Toggle"
    }

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var tapToggle: Button

    private var recording = false
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.UK)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.statusView)
        logView = findViewById(R.id.logView)
        logScroll = findViewById(R.id.logScroll)
        tapToggle = findViewById(R.id.tapToggle)

        tapToggle.setOnClickListener { toggle(source = "TAP (fallback)") }

        // First launch = START recording.
        recording = true
        renderStatus()
        vibrate(recording)
        appendLog("onCreate -> START (first launch)")
        Log.d(TAG, "onCreate -> START")
    }

    /**
     * The actual test. With singleTask, a re-launch (e.g. a second hardware
     * button press, simulated here via `am start`) is delivered HERE rather
     * than creating a new activity. We toggle on each such delivery.
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        appendLog("onNewIntent received -> treating as toggle")
        Log.d(TAG, "onNewIntent received")
        toggle(source = "RE-LAUNCH (onNewIntent)")
    }

    private fun toggle(source: String) {
        recording = !recording
        renderStatus()
        vibrate(recording)
        appendLog("TOGGLE -> ${if (recording) "RECORDING" else "STOPPED"}   via $source")
        Log.d(TAG, "TOGGLE -> ${if (recording) "RECORDING" else "STOPPED"} via $source")
    }

    private fun renderStatus() {
        statusView.text = if (recording) "RECORDING" else "STOPPED"
        statusView.setBackgroundColor(if (recording) 0xFF1B5E20.toInt() else 0xFF333333.toInt())
    }

    /** start = single short pulse, stop = double pulse. */
    private fun vibrate(active: Boolean) {
        val pattern = if (active) longArrayOf(0, 60) else longArrayOf(0, 40, 60, 40)
        val vibrator = obtainVibrator() ?: return
        try {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } catch (t: Throwable) {
            Log.w(TAG, "Vibration failed (expected on emulators without a vibrator): ${t.message}")
        }
    }

    private fun obtainVibrator(): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
    }

    private fun appendLog(line: String) {
        logView.append("${timeFmt.format(Date())}  $line\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
