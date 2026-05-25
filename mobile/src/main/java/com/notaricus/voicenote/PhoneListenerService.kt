package com.notaricus.voicenote

import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.WearableListenerService
import java.io.File

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
        val req = OneTimeWorkRequestBuilder<ProcessWorker>()
            .setInputData(workDataOf(ProcessWorker.KEY_FILE to file.absolutePath))
            .build()
        WorkManager.getInstance(this).enqueue(req)
    }
}
