package com.notaricus.voicenote

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester

/**
 * Watch entry point and activation controller.
 *
 * Activation model (revised in Phase 2, after on-watch testing):
 *  - **Complication tap / hardware launch -> always start recording.**
 *    Both onCreate and onNewIntent route to [ensureRecording]; if a session is
 *    already in progress the start is a no-op (RecordingService.ACTION_START is
 *    idempotent). This avoids the toggle-drift problem we saw on Pixel Watch when
 *    the activity's `recording` mirror got out of sync with the service.
 *  - **Crown press (onUserLeaveHint) -> always stop + finish.** The crown is the
 *    only "out" from the activity on a single-button Pixel Watch and the user's
 *    intent is unambiguous: "I'm done." onUserLeaveHint fires only on
 *    user-initiated navigation, so screen timeout still keeps capture going.
 *    finish() ensures the next launch is a fresh onCreate, not a stale onNewIntent.
 *  - **On-screen tap** stays as an in-app toggle fallback (guaranteed control if
 *    a watch face has no spare complication slot).
 *
 * Recording itself runs in [RecordingService] (a microphone foreground service),
 * mandatory on Android 14+ for screen-off capture. RECORD_AUDIO is a while-in-use
 * permission, so the service must be started from the foreground (here).
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
        tapToggle.setOnClickListener { onTapToggle() }

        // Force complication providers to re-render with the current app resources.
        // Wear caches the last ComplicationData; without this kick, an icon change
        // (e.g. swapped mipmap) is invisible until the user removes + re-adds the
        // complication. Idempotent and cheap.
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
     * Does NOT fire on screen timeout or transient overlays, so screen-off capture
     * continues to work as designed.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        stopAndExit("user-leave")
    }

    private fun onTapToggle() {
        if (recording) stopAndExit("tap") else ensureRecording("tap")
    }

    private fun ensureRecording(source: String) {
        if (!hasAllPermissions()) {
            ensurePermissionsThen { ensureRecording(source) }
            return
        }
        if (recording) {
            Log.d(TAG, "already recording (via $source)")
            render()
            return
        }
        startRecording()
        vibrate(start = true)
        recording = true
        render()
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
        // Always finish so the next launch is a clean onCreate -> ensureRecording.
        // Prevents the toggle-drift seen when the activity stayed alive between sessions.
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

    // ---- Haptics ----
    //
    // Predefined effects (EFFECT_HEAVY_CLICK / EFFECT_DOUBLE_CLICK) on API 29+ map
    // to the device's tuned vibrator profile and are the most reliably perceptible
    // on small watch motors. Below API 29, fall back to longer raw pulses
    // (the original 60ms pattern was inaudible on the Pixel Watch motor).
    //
    // USAGE_ASSISTANCE_SONIFICATION audio attributes mark this as an interactive
    // confirmation tone so it survives most do-not-disturb / silent-mode filters.

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
