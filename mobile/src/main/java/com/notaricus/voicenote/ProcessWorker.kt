package com.notaricus.voicenote

import android.content.Context
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Uploads one audio file to the configured endpoint and writes the returned text
 * into the Obsidian vault folder (Phase 1 prototype).
 *
 * WorkManager handles retry/backoff and running when constraints (network) are met.
 * The endpoint is treated as a black box returning text (per the agreed contract):
 * on your home server it transcribes + summarises; the app just stores the result.
 *
 * Mock mode (default) skips the network entirely and writes canned text, so the
 * whole watch -> phone -> vault chain is testable on an emulator with no server.
 */
class ProcessWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {

    companion object {
        const val KEY_FILE = "file_path"
        private const val TAG = "VNC-Worker"
    }

    override fun doWork(): Result {
        val path = inputData.getString(KEY_FILE) ?: return Result.failure()
        val file = File(path)
        if (!file.exists()) { Log.e(TAG, "Missing file: $path"); return Result.failure() }

        val settings = Settings(applicationContext)
        return try {
            val text = if (settings.mockMode) {
                mockTranscript(file)
            } else {
                postToEndpoint(settings.endpointUrl, settings.authToken, file)
            }
            writeToVault(settings.vaultFolderUri, file.nameWithoutExtension, text)
            Log.d(TAG, "Processed ${file.name} -> vault")
            Result.success()
        } catch (t: Throwable) {
            // Transient failures (server down, offline) -> retry with backoff.
            Log.w(TAG, "Processing failed, will retry: ${t.message}")
            Result.retry()
        }
    }

    private fun mockTranscript(file: File): String =
        "# Voice note (MOCK)\n\nSource: ${file.name}\n\n" +
        "This is mock transcript text generated locally because mock mode is on. " +
        "Turn mock mode off in settings to POST to your endpoint instead.\n"

    /**
     * Minimal multipart POST of the audio file. The endpoint returns text (plain
     * or JSON); for the prototype we store whatever comes back as-is. A real
     * client would parse {text} / poll {job_id}; left as a clear extension point.
     */
    private fun postToEndpoint(url: String, token: String, file: File): String {
        if (url.isEmpty()) throw IllegalStateException("No endpoint configured")
        val boundary = "----vnc${System.currentTimeMillis()}"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 15_000
            readTimeout = 120_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
        }
        DataOutputStream(conn.outputStream).use { out ->
            out.writeBytes("--$boundary\r\n")
            out.writeBytes("Content-Disposition: form-data; name=\"audio\"; filename=\"${file.name}\"\r\n")
            out.writeBytes("Content-Type: audio/mp4\r\n\r\n")
            file.inputStream().use { it.copyTo(out) }
            out.writeBytes("\r\n--$boundary--\r\n")
        }
        val code = conn.responseCode
        if (code !in 200..299) throw RuntimeException("Endpoint HTTP $code")
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    /** Write the returned text as a Markdown note into the user-chosen vault folder (SAF). */
    private fun writeToVault(vaultUri: String, baseName: String, text: String) {
        if (vaultUri.isEmpty()) { Log.w(TAG, "No vault folder set; skipping write"); return }
        val tree = DocumentFile.fromTreeUri(applicationContext, android.net.Uri.parse(vaultUri))
            ?: throw IllegalStateException("Vault folder not accessible")
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault()).format(Instant.now())
        val doc = tree.createFile("text/markdown", "$baseName-$stamp.md")
            ?: throw IllegalStateException("Could not create note in vault")
        applicationContext.contentResolver.openOutputStream(doc.uri)?.use { it.write(text.toByteArray()) }
    }
}
