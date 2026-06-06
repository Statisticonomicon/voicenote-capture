// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright (c) 2026 Notaricus
// Licensed under the PolyForm Noncommercial License 1.0.0. See LICENSE.md.

package com.notaricus.voicenote

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Processes one recording end-to-end and writes the transcript into the Obsidian
 * vault folder (Phase 1 PROTOTYPE).
 *
 * The real (non-mock) flow uses an asynchronous transcription protocol against the
 * endpoint *base* URL configured in Settings (e.g. http://host:8457):
 *
 *   1. POST the audio file (multipart field "audio") to {base}/upload
 *      -> JSON {"job_id": "..."}.
 *   2. Poll GET {base}/status/{job_id} until "status" is "done" or "error"
 *      (intermediate: queued, loading_model, transcribing).
 *   3. On "done": GET {base}/download/{job_id}?format=plain -> plain transcript.
 *   4. Write the transcript to the vault folder (unchanged from before).
 *   5. On "error": log the "error" field and retry so WorkManager re-attempts.
 *
 * Long jobs: a single WorkManager execution is capped (~10 min by the framework),
 * so polling is bounded by [MAX_POLL_MILLIS]. The upload's job_id is persisted
 * keyed by the audio path; if this execution exhausts its budget (or hits a
 * transient network error) it returns Result.retry(), and the NEXT execution
 * RESUMES polling the same job_id rather than re-uploading the file. This handles
 * long-running transcription within WorkManager's constraints and retry/backoff
 * without a foreground service.
 *
 * Runaway protection: retries (including resume cycles) are capped at
 * [MAX_RUN_ATTEMPTS] via WorkManager's runAttemptCount, so a permanently-failing
 * job eventually fails terminally instead of looping forever.
 *
 * Mock mode (default ON) skips the network entirely and writes canned text, so the
 * watch -> phone -> vault chain is testable on an emulator with no server.
 */
class ProcessWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    companion object {
        const val KEY_FILE = "file_path"
        private const val TAG = "VNC-Worker"

        /** Per-file job_id store, so a resumed execution polls instead of re-uploading. */
        private const val JOBS_PREFS = "vnc_jobs"

        /**
         * Max processing attempts before giving up. runAttemptCount is 0-based
         * (first run = 0), so the guard fires once this many attempts have run.
         * NOTE: resume-on-retry cycles for long jobs also consume attempts, so this
         * doubles as an upper bound on total job duration (~MAX_RUN_ATTEMPTS poll
         * windows). Raise it if single jobs are expected to take longer.
         */
        private const val MAX_RUN_ATTEMPTS = 5

        // Per-HTTP-call timeouts (milliseconds).
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val UPLOAD_READ_TIMEOUT_MS = 120_000
        private const val STATUS_READ_TIMEOUT_MS = 30_000
        private const val DOWNLOAD_READ_TIMEOUT_MS = 120_000

        // Polling cadence and per-execution budget (kept under WorkManager's ~10 min cap).
        private const val POLL_INTERVAL_MS = 3_000L
        private const val MAX_POLL_MILLIS = 8L * 60L * 1000L

        // OpenAI Whisper (BYOK) — sync POST, response_format=text.
        private const val OPENAI_TRANSCRIPTIONS_URL = "https://api.openai.com/v1/audio/transcriptions"
        private const val OPENAI_MODEL = "whisper-1"
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val path = inputData.getString(KEY_FILE)
        if (path.isNullOrEmpty()) {
            Log.e(TAG, "No file path in input data")
            return@withContext Result.failure()
        }

        // Stop runaway retries (e.g. a permanently-failing job) before doing more work.
        if (runAttemptCount >= MAX_RUN_ATTEMPTS) {
            Log.e(TAG, "Giving up on $path after $runAttemptCount attempts (cap $MAX_RUN_ATTEMPTS)")
            clearJobId(path)
            PendingUploads.remove(applicationContext, path)
            UploadStatusNotifier.notifyTerminalFailure(applicationContext, File(path).name)
            UploadStatusNotifier.refreshPending(applicationContext, id)
            return@withContext Result.failure()
        }

        val file = File(path)
        val settings = Settings(applicationContext)

        try {
            val text = when {
                settings.mockMode -> {
                    if (!file.exists()) {
                        Log.e(TAG, "Missing file: $path")
                        return@withContext Result.failure()
                    }
                    mockTranscript(file)
                }
                settings.provider == Settings.PROVIDER_OPENAI -> {
                    if (!file.exists()) {
                        Log.e(TAG, "Missing file: $path")
                        return@withContext Result.failure()
                    }
                    transcribeViaOpenAi(settings, file)
                }
                else -> {
                    // null => job still running and this execution's budget is spent; resume on retry.
                    transcribeViaEndpoint(settings, file, path)
                        ?: return@withContext Result.retry()
                }
            }
            // Empty body from the provider (e.g. whisper transcribed silence): don't
            // write a 0-byte note. Surface it as a soft failure so the user knows the
            // recording was processed but had nothing to say, and stop retrying.
            if (text.isBlank()) {
                Log.w(TAG, "Empty transcript for ${file.name}; skipping vault write")
                UploadStatusNotifier.notifyEmptyTranscript(applicationContext, file.name)
                clearJobId(path)
                PendingUploads.remove(applicationContext, path)
                UploadStatusNotifier.refreshPending(applicationContext, id)
                return@withContext Result.success()
            }
            writeToVault(settings.vaultFolderUri, file.nameWithoutExtension, text)
            Log.d(TAG, "Processed ${file.name} -> vault")
            if (settings.deleteAfterUpload && file.exists()) {
                val deleted = file.delete()
                Log.d(TAG, "deleteAfterUpload: ${file.name} deleted=$deleted")
            }
            PendingUploads.remove(applicationContext, path)
            UploadStatusNotifier.refreshPending(applicationContext, id)
            Result.success()
        } catch (e: EndpointErrorException) {
            // Server reported a processing error for this job. Drop the job_id so the
            // retry starts a fresh upload, and reschedule via WorkManager backoff.
            Log.e(TAG, "Endpoint error for ${file.name}: ${e.message}")
            clearJobId(path)
            Result.retry()
        } catch (e: IOException) {
            // Transient: server down, offline, timeout. Keep the job_id and retry w/ backoff.
            Log.w(TAG, "Transient failure for ${file.name}, will retry: ${e.message}")
            Result.retry()
        } catch (t: Throwable) {
            // Misconfiguration / unexpected (e.g. no endpoint set). Fail terminally to
            // avoid a hot retry loop; clear any stale job_id.
            Log.e(TAG, "Unrecoverable failure for ${file.name}", t)
            clearJobId(path)
            PendingUploads.remove(applicationContext, path)
            UploadStatusNotifier.notifyTerminalFailure(applicationContext, file.name)
            UploadStatusNotifier.refreshPending(applicationContext, id)
            Result.failure()
        }
    }

    // ---- Mock path ---------------------------------------------------------

    /** Canned transcript used when mock mode is on (no network), unchanged from before. */
    private fun mockTranscript(file: File): String =
        "# Voice note (MOCK)\n\nSource: ${file.name}\n\n" +
        "This is mock transcript text generated locally because mock mode is on. " +
        "Turn mock mode off in settings to use your endpoint instead.\n"

    // ---- OpenAI Whisper (synchronous; BYOK) -------------------------------

    /**
     * POST the audio to https://api.openai.com/v1/audio/transcriptions and return
     * the transcript as plain text.
     *
     * Synchronous: no job_id / polling. response_format=text means the response
     * body is the transcript itself (no JSON parsing). The user's own API key
     * pays for the call (BYOK), so we don't validate or rate-limit it here —
     * OpenAI's own error response is forwarded via the existing retry path.
     *
     * Whisper file-size limit is 25 MB; our recordings are typically <1 MB so
     * we don't pre-check.
     */
    private fun transcribeViaOpenAi(settings: Settings, file: File): String {
        val apiKey = settings.openAiApiKey.trim()
        if (apiKey.isEmpty()) {
            throw IllegalStateException("OpenAI API key is not set in settings")
        }
        val boundary = "----vnc${System.currentTimeMillis()}"
        val conn = (URL(OPENAI_TRANSCRIPTIONS_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = UPLOAD_READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        try {
            DataOutputStream(conn.outputStream).use { out ->
                writeFormField(out, boundary, "model", OPENAI_MODEL)
                writeFormField(out, boundary, "response_format", "text")
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"${file.name}\"\r\n")
                out.writeBytes("Content-Type: audio/mp4\r\n\r\n")
                file.inputStream().use { it.copyTo(out) }
                out.writeBytes("\r\n--$boundary--\r\n")
                out.flush()
            }
            val text = readBody(conn).trim()
            Log.d(TAG, "OpenAI transcribed ${file.name} (${text.length} chars)")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun writeFormField(out: DataOutputStream, boundary: String, name: String, value: String) {
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        out.writeBytes(value)
        out.writeBytes("\r\n")
    }

    // ---- Asynchronous endpoint protocol -----------------------------------

    /**
     * Runs upload -> poll -> download against the endpoint base URL.
     *
     * @return the transcript text once the job reaches "done"; or null if this
     *   execution exhausted its polling budget while the job was still running
     *   (the caller should Result.retry() to resume the same job_id later).
     * @throws EndpointErrorException if the server reports status "error".
     * @throws IOException on any transient network/HTTP/parse failure (caller retries).
     * @throws IllegalStateException if the endpoint base URL is unset or the file
     *   to upload is missing (caller fails terminally).
     */
    private suspend fun transcribeViaEndpoint(settings: Settings, file: File, path: String): String? {
        val base = settings.endpointUrl.trim().trimEnd('/')
        if (base.isEmpty()) throw IllegalStateException("No endpoint base URL configured")
        val token = settings.authToken

        // Resume an in-flight job if we have one; otherwise upload now.
        var jobId = loadJobId(path)
        if (jobId == null) {
            if (!file.exists()) throw IllegalStateException("Missing file to upload: $path")
            jobId = uploadAudio(base, token, file)
            saveJobId(path, jobId)
            Log.d(TAG, "Uploaded ${file.name}, job_id=$jobId")
        } else {
            Log.d(TAG, "Resuming poll for ${file.name}, job_id=$jobId")
        }

        val deadline = System.currentTimeMillis() + MAX_POLL_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val status = fetchStatus(base, token, jobId)
            when (status.optString("status")) {
                "done" -> {
                    val text = downloadTranscript(base, token, jobId)
                    clearJobId(path)
                    return text
                }
                "error" -> throw EndpointErrorException(status.optString("error", "(no error message)"))
                "queued", "loading_model", "transcribing" -> {
                    Log.d(TAG, "job $jobId status=${status.optString("status")}")
                    delay(POLL_INTERVAL_MS)
                }
                else -> {
                    // Unknown/missing status: keep polling rather than fail hard.
                    Log.d(TAG, "job $jobId unrecognised status=${status.optString("status")}; continuing")
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
        Log.d(TAG, "Poll budget reached for job $jobId; will resume on retry")
        return null
    }

    /** POST the audio as multipart field "audio" to {base}/upload; returns the job_id. */
    private fun uploadAudio(base: String, token: String, file: File): String {
        val boundary = "----vnc${System.currentTimeMillis()}"
        val conn = openConnection("$base/upload", "POST", token, UPLOAD_READ_TIMEOUT_MS).apply {
            doOutput = true
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        }
        val body = try {
            DataOutputStream(conn.outputStream).use { out ->
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"audio\"; filename=\"${file.name}\"\r\n")
                out.writeBytes("Content-Type: audio/mp4\r\n\r\n")
                file.inputStream().use { it.copyTo(out) }
                out.writeBytes("\r\n--$boundary--\r\n")
                out.flush()
            }
            readBody(conn)
        } finally {
            conn.disconnect()
        }
        val jobId = parseJson(body, "upload").optString("job_id")
        if (jobId.isEmpty()) throw IOException("Upload response missing job_id: ${body.take(200)}")
        return jobId
    }

    /** GET {base}/status/{job_id}; returns the parsed status JSON. */
    private fun fetchStatus(base: String, token: String, jobId: String): JSONObject {
        val conn = openConnection("$base/status/$jobId", "GET", token, STATUS_READ_TIMEOUT_MS)
        val body = try { readBody(conn) } finally { conn.disconnect() }
        return parseJson(body, "status")
    }

    /** GET {base}/download/{job_id}?format=plain; returns the plain transcript text. */
    private fun downloadTranscript(base: String, token: String, jobId: String): String {
        val conn = openConnection("$base/download/$jobId?format=plain", "GET", token, DOWNLOAD_READ_TIMEOUT_MS)
        return try { readBody(conn) } finally { conn.disconnect() }
    }

    // ---- HTTP helpers ------------------------------------------------------

    /** Open an HttpURLConnection with shared timeouts and optional bearer auth. */
    private fun openConnection(urlStr: String, method: String, token: String, readTimeoutMs: Int): HttpURLConnection =
        (URL(urlStr).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = readTimeoutMs
            if (token.isNotEmpty()) setRequestProperty("Authorization", "Bearer $token")
        }

    /** Read a 2xx body as text; throw IOException for non-2xx so the caller retries. */
    private fun readBody(conn: HttpURLConnection): String {
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull()
            throw IOException("HTTP $code from ${conn.url}: ${err?.take(200) ?: "(no body)"}")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }

    /** Parse a JSON object body; malformed JSON is treated as transient (IOException). */
    private fun parseJson(body: String, what: String): JSONObject =
        try { JSONObject(body) }
        catch (e: JSONException) { throw IOException("Malformed $what JSON: ${body.take(200)}", e) }

    // ---- job_id persistence (resume across retries) ------------------------

    private fun jobsPrefs() = applicationContext.getSharedPreferences(JOBS_PREFS, Context.MODE_PRIVATE)
    private fun loadJobId(path: String): String? = jobsPrefs().getString(path, null)
    private fun saveJobId(path: String, jobId: String) = jobsPrefs().edit().putString(path, jobId).apply()
    private fun clearJobId(path: String) = jobsPrefs().edit().remove(path).apply()

    // ---- Vault write (unchanged behaviour) ---------------------------------

    /** Write the transcript as a Markdown note into the user-chosen vault folder (SAF). */
    private fun writeToVault(vaultUri: String, baseName: String, text: String) {
        if (vaultUri.isEmpty()) { Log.w(TAG, "No vault folder set; skipping write"); return }
        val tree = DocumentFile.fromTreeUri(applicationContext, Uri.parse(vaultUri))
            ?: throw IllegalStateException("Vault folder not accessible")
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.systemDefault()).format(Instant.now())
        val doc = tree.createFile("text/markdown", "$baseName-$stamp.md")
            ?: throw IllegalStateException("Could not create note in vault")
        applicationContext.contentResolver.openOutputStream(doc.uri)?.use { it.write(text.toByteArray()) }
    }
}

/** Server reported a terminal "error" status for a job (carries the server's message). */
private class EndpointErrorException(message: String) : Exception(message)
