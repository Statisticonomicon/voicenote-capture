// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright (c) 2026 Konstantinos Bonikos
// Licensed under the PolyForm Noncommercial License 1.0.0. See LICENSE.md.

package com.notaricus.voicenote

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester

/**
 * Watch entry point and activation controller for the (single-screen) recording app.
 *
 * Activation model (revised in Phase 2 after on-watch testing):
 *  - **Complication tap / hardware launch -> always start recording.**
 *    Both onCreate and onNewIntent route to [ensureRecording]; if a session is
 *    already in progress the start is a no-op (RecordingService.ACTION_START is
 *    idempotent). This avoids the toggle-drift problem we saw on Pixel Watch
 *    when the activity's `recording` mirror got out of sync with the service.
 *  - **Crown press (onUserLeaveHint) -> always stop + finish.** The crown is
 *    the only "out" from the activity on a single-button Pixel Watch and the
 *    user's intent is unambiguous. onUserLeaveHint fires only on user-initiated
 *    navigation, so screen timeout still keeps capture going.
 *  - **finish() after every stop** so the next launch is a clean onCreate.
 *
 * The on-screen layout is the redesigned Option A "Pure Minimal" recording
 * screen (see design_handoff_recording_screen): black background, red blinking
 * status dot + "RECORDING" label, hero m:ss timer with tabular digits, live
 * mic-amplitude waveform, and a quiet "Press crown to stop" hint. There is no
 * idle / stopped state in the UI — the screen is only shown while recording.
 */
class WearMainActivity : Activity() {

    private companion object {
        const val TAG = "VNC-Wear"
        const val REQ_PERMS = 1001
        const val TIMER_TICK_MS = 250L
        const val WAVEFORM_TICK_MS = 50L
        const val DOT_BLINK_PERIOD_MS = 1400L
    }

    private lateinit var statusRow: View
    private lateinit var recordDot: View
    private lateinit var timerView: TextView
    private lateinit var waveformView: WaveformView
    private lateinit var hintView: TextView

    /** Mirror of the service state for UI; the service is the source of truth. */
    private var recording = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var dotBlinkAnimator: ObjectAnimator? = null

    private val timerTick = object : Runnable {
        override fun run() {
            val start = RecordingState.startTimeMs
            timerView.text = formatElapsed(
                if (start > 0L) (System.currentTimeMillis() - start) / 1000L else 0L
            )
            mainHandler.postDelayed(this, TIMER_TICK_MS)
        }
    }

    private val waveformTick = object : Runnable {
        override fun run() {
            waveformView.pushAmplitude(normalizeAmplitude(RecordingState.amplitude))
            mainHandler.postDelayed(this, WAVEFORM_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wear_main)
        statusRow = findViewById(R.id.statusRow)
        recordDot = findViewById(R.id.recordDot)
        timerView = findViewById(R.id.timerView)
        waveformView = findViewById(R.id.waveformView)
        hintView = findViewById(R.id.hintView)

        // Kick connected complications so a freshly-deployed icon shows up
        // without the user having to remove and re-add the complication.
        requestComplicationRefresh()

        ensurePermissionsThen { ensureRecording("onCreate") }
    }

    /** Re-launch (complication tap while activity is alive) is delivered here under singleTask. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        ensurePermissionsThen { ensureRecording("onNewIntent") }
    }

    /**
     * User-initiated navigation away (crown press / system home gesture).
     * Does NOT fire on screen timeout or transient overlays, so screen-off
     * capture continues to work as designed.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        stopAndExit("user-leave")
    }

    override fun onResume() {
        super.onResume()
        startUiAnimations()
    }

    override fun onPause() {
        super.onPause()
        stopUiAnimations()
    }

    private fun ensureRecording(source: String) {
        if (!hasAllPermissions()) {
            ensurePermissionsThen { ensureRecording(source) }
            return
        }
        if (recording) {
            Log.d(TAG, "already recording (via $source)")
            return
        }
        startRecording()
        vibrate(start = true)
        recording = true
        Log.d(TAG, "start -> RECORDING via $source")
    }

    private fun stopAndExit(source: String) {
        if (recording) {
            stopRecording()
            vibrate(start = false)
            recording = false
            Log.d(TAG, "stop -> STOPPED via $source")
        } else {
            Log.d(TAG, "exit (not recording) via $source")
        }
        finish()
    }

    private fun startRecording() {
        val i = Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_START)
        startForegroundService(i)
    }

    private fun stopRecording() {
        startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
    }

    private fun requestComplicationRefresh() {
        try {
            ComplicationDataSourceUpdateRequester
                .create(this, ComponentName(this, VoiceNoteComplicationService::class.java))
                .requestUpdateAll()
            Log.d(TAG, "complication refresh requested")
        } catch (t: Throwable) {
            Log.w(TAG, "complication refresh failed: ${t.message}")
        }
    }

    // ---- UI animations: dot blink, timer tick, waveform tick ----

    private fun startUiAnimations() {
        // Reset waveform when we return to the screen; the service's amplitude
        // stream feeds the new history from now on.
        waveformView.clear()

        if (dotBlinkAnimator == null) {
            dotBlinkAnimator = ObjectAnimator.ofFloat(recordDot, "alpha", 1f, 0.25f, 1f).apply {
                duration = DOT_BLINK_PERIOD_MS
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
            }
        }
        dotBlinkAnimator?.start()

        mainHandler.removeCallbacks(timerTick)
        mainHandler.removeCallbacks(waveformTick)
        mainHandler.post(timerTick)
        mainHandler.post(waveformTick)
    }

    private fun stopUiAnimations() {
        dotBlinkAnimator?.cancel()
        recordDot.alpha = 1f
        mainHandler.removeCallbacks(timerTick)
        mainHandler.removeCallbacks(waveformTick)
    }

    private fun formatElapsed(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0L)
        val minutes = s / 60
        val seconds = s % 60
        // Spec: m:ss until 10 min, then mm:ss.
        return if (minutes < 10) "%d:%02d".format(minutes, seconds)
        else "%02d:%02d".format(minutes, seconds)
    }

    // ---- Permissions ----

    private fun requiredPermissions(): Array<String> {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return perms.toTypedArray()
    }

    private fun hasAllPermissions(): Boolean = requiredPermissions().all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private var pendingAfterPerms: (() -> Unit)? = null

    private fun ensurePermissionsThen(action: () -> Unit) {
        if (hasAllPermissions()) { action(); return }
        pendingAfterPerms = action
        requestPermissions(requiredPermissions(), REQ_PERMS)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMS && hasAllPermissions()) {
            pendingAfterPerms?.invoke()
        } else {
            // Permission was denied — surface the reason in the existing hint slot.
            // The crown-press path will then cleanly finish() the activity.
            hintView.text = getString(R.string.need_mic_permission)
        }
        pendingAfterPerms = null
    }

    // ---- Haptics ----
    //
    // Predefined effects (EFFECT_HEAVY_CLICK / EFFECT_DOUBLE_CLICK) on API 29+
    // map to the device's tuned vibrator profile and are the most reliably
    // perceptible on small watch motors. Below API 29, fall back to longer raw
    // pulses (the original 60ms waveform was inaudible on the Pixel Watch).
    //
    // USAGE_ASSISTANCE_SONIFICATION audio attributes mark this as an
    // interactive confirmation tone so it survives most do-not-disturb /
    // silent-mode filters.

    private fun vibrate(start: Boolean) {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as? Vibrator
        }
        if (v == null || !v.hasVibrator()) {
            Log.w(TAG, "vibrate(${if (start) "start" else "stop"}): no usable vibrator")
            return
        }
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(
                if (start) VibrationEffect.EFFECT_HEAVY_CLICK
                else VibrationEffect.EFFECT_DOUBLE_CLICK
            )
        } else {
            if (start) VibrationEffect.createOneShot(180L, VibrationEffect.DEFAULT_AMPLITUDE)
            else VibrationEffect.createWaveform(longArrayOf(0, 180, 100, 180), -1)
        }
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        try {
            v.vibrate(effect, attrs)
            Log.d(TAG, "vibrate(${if (start) "start" else "stop"}) fired")
        } catch (t: Throwable) {
            Log.w(TAG, "vibrate failed: ${t.message}")
        }
    }
}
