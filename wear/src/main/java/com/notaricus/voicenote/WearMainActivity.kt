package com.notaricus.voicenote

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Button
import android.widget.TextView

/**
 * Watch entry point and activation controller (Phase 1 prototype).
 *
 * Activation model (confirmed in Phase 0.5): launchMode=singleTask.
 *  - First launch  -> onCreate    -> start recording.
 *  - Re-launch     -> onNewIntent -> toggle stop/start.
 * A hardware button assigned to this app produces these launches; an on-screen
 * button is the guaranteed fallback.
 *
 * Recording itself runs in [RecordingService] (a microphone foreground service),
 * which is mandatory on Android 14+ for capture that continues with the screen
 * off. The service must be started from here (the foreground), because
 * RECORD_AUDIO is a while-in-use permission and a mic FGS cannot be started from
 * the background.
 */
class WearMainActivity : Activity() {

    private companion object {
        const val TAG = "VNC-Wear"
        const val REQ_PERMS = 1001
    }

    private lateinit var statusView: TextView
    private lateinit var hintView: TextView
    private lateinit var tapToggle: Button

    /** Mirror of the service state for UI; the service is the source of truth. */
    private var recording = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_wear_main)
        statusView = findViewById(R.id.statusView)
        hintView = findViewById(R.id.hintView)
        tapToggle = findViewById(R.id.tapToggle)
        tapToggle.setOnClickListener { onActivationTrigger("tap") }

        // First launch is an intent to start. Ensure permissions, then begin.
        ensurePermissionsThen { onActivationTrigger("launch:onCreate") }
    }

    /** Re-launch (second+ button press) is delivered here under singleTask. */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        onActivationTrigger("relaunch:onNewIntent")
    }

    /**
     * Central toggle. Starts recording if stopped, stops if recording.
     * Distinct haptics on each transition; screen content stays minimal.
     */
    private fun onActivationTrigger(source: String) {
        if (!hasAllPermissions()) {
            // If the user hasn't granted yet, request and bail; they re-press after granting.
            ensurePermissionsThen { /* user re-triggers */ }
            return
        }
        recording = !recording
        if (recording) {
            startRecording()
            vibrate(start = true)
        } else {
            stopRecording()
            vibrate(start = false)
        }
        render()
        Log.d(TAG, "toggle -> ${if (recording) "RECORDING" else "STOPPED"} via $source")
    }

    private fun startRecording() {
        val i = Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_START)
        // startForegroundService is correct from a foreground activity; the service
        // promotes itself with startForeground() + microphone type immediately.
        startForegroundService(i)
    }

    private fun stopRecording() {
        startService(Intent(this, RecordingService::class.java).setAction(RecordingService.ACTION_STOP))
    }

    private fun render() {
        statusView.text = if (recording) getString(R.string.recording) else getString(R.string.stopped)
        statusView.setBackgroundColor(if (recording) 0xFF1B5E20.toInt() else 0xFF333333.toInt())
        hintView.text = getString(R.string.press_to_toggle)
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
            hintView.text = getString(R.string.need_mic_permission)
        }
        pendingAfterPerms = null
    }

    // ---- Haptics: start = single pulse, stop = double pulse ----

    private fun vibrate(start: Boolean) {
        val pattern = if (start) longArrayOf(0, 60) else longArrayOf(0, 40, 60, 40)
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") getSystemService(VIBRATOR_SERVICE) as? Vibrator
        } ?: return
        try { v.vibrate(VibrationEffect.createWaveform(pattern, -1)) }
        catch (t: Throwable) { Log.w(TAG, "vibrate failed: ${t.message}") }
    }
}
