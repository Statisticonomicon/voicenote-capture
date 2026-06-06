// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright (c) 2026 Konstantinos Bonikos
// Licensed under the PolyForm Noncommercial License 1.0.0. See LICENSE.md.

package com.notaricus.voicenote

import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.WearableListenerService
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Receives audio files from the watch over the Data Layer (Phase 1 prototype).
 *
 * Flow on receipt:
 *   1. Pull the file off the channel into local storage.
 *   2. Persist a copy to the user's raw-audio folder (SAF), if configured.
 *   3. Enqueue [ProcessWorker] to POST it to the endpoint and write the result
 *      into the vault. WorkManager gives us the retry/queue-when-offline behaviour.
 */
class PhoneListenerService : WearableListenerService() {

    private companion object { const val TAG = "VNC-Listener" }

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        val client = com.google.android.gms.wearable.Wearable.getChannelClient(applicationContext)
        val name = channel.path.substringAfterLast('/').ifEmpty { "note-${System.currentTimeMillis()}.m4a" }
        val incoming = File(File(filesDir, "incoming").apply { mkdirs() }, name)

        client.receiveFile(channel, android.net.Uri.fromFile(incoming), false)
        client.registerChannelCallback(channel, object : ChannelClient.ChannelCallback() {
            override fun onInputClosed(ch: ChannelClient.Channel, closeReason: Int, appSpecificErrorCode: Int) {
                Log.d(TAG, "Received ${incoming.name} (${incoming.length()} bytes)")
                backupRaw(incoming)
                enqueueProcessing(incoming)
                client.unregisterChannelCallback(this)
            }
        })
    }

    /** Copy the raw audio into the user-chosen SAF folder for independent backup (out of scope downstream). */
    private fun backupRaw(file: File) {
        val uri = Settings(this).rawFolderUri
        if (uri.isEmpty()) return
        try {
            val tree = DocumentFile.fromTreeUri(this, android.net.Uri.parse(uri)) ?: return
            val doc = tree.createFile("audio/mp4", file.name) ?: return
            contentResolver.openOutputStream(doc.uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            Log.d(TAG, "Raw audio backed up to chosen folder")
        } catch (t: Throwable) {
            Log.e(TAG, "Raw backup failed (non-fatal)", t)
        }
    }

    private fun enqueueProcessing(file: File) {
        val settings = Settings(this)
        // CONNECTED by default; UNMETERED (Wi-Fi only) when the user has opted to
        // defer uploads off mobile data. Either way the worker sits in the queue
        // until the constraint is met - no failed-attempt burn while offline.
        val networkType = if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val constraints = Constraints.Builder().setRequiredNetworkType(networkType).build()
        val req = OneTimeWorkRequestBuilder<ProcessWorker>()
            .setInputData(workDataOf(ProcessWorker.KEY_FILE to file.absolutePath))
            .setConstraints(constraints)
            // Linear 30s backoff (default is exponential 30s → 60s → 120s → 240s
            // → 480s, which felt punishing when the user just reconnected). With
            // MAX_RUN_ATTEMPTS=5 the worst-case total is ~2 minutes.
            .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
            .addTag(UploadStatusNotifier.WORK_TAG)
            .build()
        PendingUploads.add(applicationContext, file.absolutePath)
        WorkManager.getInstance(this).enqueue(req)
        UploadStatusNotifier.refreshPending(applicationContext)
    }
}
