// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright (c) 2026 Konstantinos Bonikos
// Licensed under the PolyForm Noncommercial License 1.0.0. See LICENSE.md.

package com.notaricus.voicenote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Microphone foreground service that performs the actual capture (Phase 1 prototype).
 *
 * Declared with foregroundServiceType="microphone" and started from the foreground
 * activity. Promotes itself immediately with startForeground() so capture survives
 * the screen turning off. Records to app-internal storage, then hands the finished
 * file to [WearTransfer] for Data Layer delivery to the phone.
 *
 * Optional max-duration auto-stop is OFF by default (see [MAX_DURATION_MS] == 0).
 */
class RecordingService : Service() {

    companion object {
        const val ACTION_START = "com.notaricus.voicenote.START"
        const val ACTION_STOP = "com.notaricus.voicenote.STOP"
        private const val TAG = "VNC-RecSvc"
        private const val CHANNEL_ID = "vnc_recording"
        private const val NOTIF_ID = 42

        /** 0 = disabled (default). Set from settings later; e.g. 10 min = 600_000L. */
        private const val MAX_DURATION_MS = 0L
    }

    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var isRecording = false
    private val autoStopHandler = Handler(Looper.getMainLooper())
    private val autoStopRunnable = Runnable {
        Log.d(TAG, "Auto-stop reached")
        stopAndHandoff()
    }

    // Mic-amplitude sampling for the on-screen waveform. ~40 ms is a good
    // compromise: smooth animation without burning CPU.
    private val amplitudeHandler = Handler(Looper.getMainLooper())
    private val amplitudeIntervalMs = 40L
    private val amplitudeRunnable = object : Runnable {
        override fun run() {
            val r = recorder
            if (r != null && isRecording) {
                RecordingState.amplitude = try { r.maxAmplitude } catch (_: Throwable) { 0 }
                amplitudeHandler.postDelayed(this, amplitudeIntervalMs)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startRecording()
            ACTION_STOP -> stopAndHandoff()
            else -> Log.w(TAG, "Unknown action: ${intent?.action}")
        }
        // Not sticky: we never want the system to silently resurrect a recording.
        return START_NOT_STICKY
    }

    private fun startRecording() {
        if (isRecording) { Log.w(TAG, "Already recording"); return }
        try {
            promoteToForeground()
            val dir = File(filesDir, "recordings").apply { mkdirs() }
            val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneId.systemDefault()).format(Instant.now())
            val file = File(dir, "note-$stamp.m4a")

            val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setAudioEncodingBitRate(64_000)     // adequate for speech
            rec.setAudioSamplingRate(16_000)         // ASR-friendly
            rec.setOutputFile(file.absolutePath)
            rec.prepare()
            rec.start()

            recorder = rec
            outputFile = file
            isRecording = true
            RecordingState.startTimeMs = System.currentTimeMillis()
            RecordingState.amplitude = 0
            amplitudeHandler.post(amplitudeRunnable)
            if (MAX_DURATION_MS > 0) autoStopHandler.postDelayed(autoStopRunnable, MAX_DURATION_MS)
            Log.d(TAG, "Recording started: ${file.name}")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start recording", t)
            cleanupRecorder()
            stopSelf()
        }
    }

    private fun stopAndHandoff() {
        if (!isRecording) { stopSelf(); return }
        autoStopHandler.removeCallbacks(autoStopRunnable)
        amplitudeHandler.removeCallbacks(amplitudeRunnable)
        RecordingState.amplitude = 0
        RecordingState.startTimeMs = 0L
        val file = outputFile
        try {
            recorder?.stop()
        } catch (t: Throwable) {
            // stop() throws if stopped too quickly / no frames; treat the file as suspect.
            Log.w(TAG, "recorder.stop() threw: ${t.message}")
        } finally {
            cleanupRecorder()
            isRecording = false
        }

        if (file != null && file.exists() && file.length() > 0) {
            Log.d(TAG, "Recording stopped: ${file.name} (${file.length()} bytes)")
            // Hand off to the phone. Failure here must not crash; transfer queues/logs.
            try { WearTransfer.sendToPhone(applicationContext, file) }
            catch (t: Throwable) { Log.e(TAG, "Handoff failed", t) }
        } else {
            Log.w(TAG, "No usable recording to hand off")
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteToForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Recording", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.recording))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        // On Android 10+ specify the type explicitly to match the manifest declaration.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun cleanupRecorder() {
        try { recorder?.reset() } catch (_: Throwable) {}
        try { recorder?.release() } catch (_: Throwable) {}
        recorder = null
    }

    override fun onDestroy() {
        autoStopHandler.removeCallbacks(autoStopRunnable)
        amplitudeHandler.removeCallbacks(amplitudeRunnable)
        RecordingState.amplitude = 0
        RecordingState.startTimeMs = 0L
        cleanupRecorder()
        super.onDestroy()
    }
}
