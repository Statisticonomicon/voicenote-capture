package com.notaricus.voicenote

import androidx.activity.ComponentActivity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File

/**
 * Phone companion settings + manual test harness (Phase 1 prototype).
 *
 * Lets you set the endpoint URL, auth token, mock mode, and pick the raw-audio
 * and vault folders via SAF. Includes an "import audio file" action so the
 * phone-side chain (save -> process -> vault) can be tested on a real phone
 * WITHOUT a paired watch, by feeding it any audio file.
 */
class SettingsActivity : ComponentActivity() {

    private companion object { const val TAG = "VNC-Settings" }

    private lateinit var settings: Settings
    private lateinit var endpoint: EditText
    private lateinit var token: EditText
    private lateinit var mock: CheckBox
    private lateinit var folders: TextView

    private val pickRaw = registerForActivityResult(openTree()) { uri ->
        uri?.let { persist(it); settings.rawFolderUri = it.toString(); refreshFolders() }
    }
    private val pickVault = registerForActivityResult(openTree()) { uri ->
        uri?.let { persist(it); settings.vaultFolderUri = it.toString(); refreshFolders() }
    }
    private val pickAudio = registerForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { importAudioForTest(it) } }

    private fun openTree() =
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        setContentView(R.layout.activity_settings)

        endpoint = findViewById(R.id.endpoint)
        token = findViewById(R.id.token)
        mock = findViewById(R.id.mock)
        folders = findViewById(R.id.folders)

        endpoint.setText(settings.endpointUrl)
        token.setText(settings.authToken)
        mock.isChecked = settings.mockMode
        refreshFolders()

        findViewById<Button>(R.id.save).setOnClickListener {
            settings.endpointUrl = endpoint.text.toString().trim()
            settings.authToken = token.text.toString().trim()
            settings.mockMode = mock.isChecked
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }
        findViewById<Button>(R.id.pickRaw).setOnClickListener { pickRaw.launch(null) }
        findViewById<Button>(R.id.pickVault).setOnClickListener { pickVault.launch(null) }
        findViewById<Button>(R.id.importTest).setOnClickListener { pickAudio.launch("audio/*") }
    }

    private fun persist(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (t: Throwable) { Log.e(TAG, "persist permission failed", t) }
    }

    private fun refreshFolders() {
        val raw = if (settings.rawFolderUri.isEmpty()) "(not set)" else settings.rawFolderUri
        val vault = if (settings.vaultFolderUri.isEmpty()) "(not set)" else settings.vaultFolderUri
        folders.text = getString(R.string.folders_fmt, raw, vault)
    }

    /** Copy a chosen audio file into local storage and run it through the same worker the watch path uses. */
    private fun importAudioForTest(uri: Uri) {
        try {
            val dir = File(filesDir, "incoming").apply { mkdirs() }
            val dest = File(dir, "imported-${System.currentTimeMillis()}.m4a")
            contentResolver.openInputStream(uri)?.use { input -> dest.outputStream().use { input.copyTo(it) } }
            WorkManager.getInstance(this).enqueue(
                OneTimeWorkRequestBuilder<ProcessWorker>()
                    .setInputData(workDataOf(ProcessWorker.KEY_FILE to dest.absolutePath))
                    .build()
            )
            Toast.makeText(this, R.string.test_enqueued, Toast.LENGTH_SHORT).show()
        } catch (t: Throwable) {
            Log.e(TAG, "import test failed", t)
            Toast.makeText(this, R.string.test_failed, Toast.LENGTH_SHORT).show()
        }
    }
}
