// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright (c) 2026 Konstantinos Bonikos
// Licensed under the PolyForm Noncommercial License 1.0.0. See LICENSE.md.

package com.notaricus.voicenote

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import androidx.appcompat.app.AlertDialog
import androidx.documentfile.provider.DocumentFile
import com.google.android.gms.wearable.Wearable
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File

/**
 * Phone companion settings + manual test harness.
 *
 * Redesigned per `design_handoff_companion_app/`: pure-black single-screen
 * settings with grouped dark cards, custom red radio + switch, conditional
 * provider config card, friendly folder-path display, and a save button that
 * briefly turns green with "Saved ✓". Behaviour is unchanged from before the
 * redesign - persistence, folder picker (SAF), import test, and Wear capability
 * advert all go through the same paths.
 */
class SettingsActivity : ComponentActivity() {

    private companion object {
        const val TAG = "VNC-Settings"
        const val SAVE_CONFIRM_MS = 3000L
    }

    private lateinit var settings: Settings

    // Provider rows + radio buttons (driven together).
    private lateinit var rowSelfHosted: LinearLayout
    private lateinit var rowOpenAi: LinearLayout
    private lateinit var radioSelfHosted: RadioButton
    private lateinit var radioOpenAi: RadioButton
    private lateinit var sectionSelfHosted: LinearLayout
    private lateinit var sectionOpenAi: LinearLayout

    // Inputs.
    private lateinit var endpoint: EditText
    private lateinit var token: EditText
    private lateinit var openAiKey: EditText

    // Storage rows.
    private lateinit var rawFolderPath: TextView
    private lateinit var vaultFolderPath: TextView

    // Test card.
    private lateinit var rowRunTest: LinearLayout
    private lateinit var rowMockSwitch: LinearLayout
    private lateinit var mockSwitch: MaterialSwitch

    // Network card.
    private lateinit var rowWifiOnly: LinearLayout
    private lateinit var wifiOnlySwitch: MaterialSwitch
    private lateinit var rowSendPending: LinearLayout

    // Recordings card.
    private lateinit var rowDeleteAfterUpload: LinearLayout
    private lateinit var deleteAfterUploadSwitch: MaterialSwitch
    private lateinit var rowExportRecordings: LinearLayout
    private lateinit var rowDeleteAll: LinearLayout

    // Save button.
    private lateinit var saveButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val revertSaveButton = Runnable {
        saveButton.text = getString(R.string.save_button)
        saveButton.setBackgroundResource(R.drawable.bg_save_button)
        saveButton.setTextColor(ContextCompat.getColor(this, R.color.vnc_btn_ink))
    }

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d(TAG, "POST_NOTIFICATIONS granted=$granted")
        // Re-post pending status now that the system will let us through.
        if (granted) UploadStatusNotifier.refreshPending(applicationContext)
    }

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

        // Dynamic capability advert: more reliable than manifest meta-data, which
        // we observed being skipped by GMS Wearable on this device.
        Wearable.getCapabilityClient(this)
            .addLocalCapability("voicenote_phone")
            .addOnSuccessListener { Log.d(TAG, "voicenote_phone capability registered") }
            .addOnFailureListener { e -> Log.e(TAG, "addLocalCapability failed", e) }

        bindViews()
        loadSettingsIntoViews()
        wireProviderRows()
        wireFocusBorders(endpoint, token, openAiKey)
        wireSaveButton()
        wireStorageRows()
        wireTestRows()
        wireNetworkRow()
        wireRecordingsRows()
        ensureNotificationPermission()
    }

    /**
     * Android 13+ requires POST_NOTIFICATIONS at runtime. Without it the system
     * silently drops every notification we post (the channels still get created,
     * the notify() call still returns, but numPostedByApp stays 0 - we saw this
     * happen on the test phone). Ask for it the first time the user opens
     * settings; if denied, the upload-status notifications simply won't appear -
     * the rest of the app is unaffected.
     */
    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        // Cold-start case: queue may already hold entries from before this launch
        // (e.g. across reboot). Re-post the pending notification so the user sees
        // them whenever they open the app.
        UploadStatusNotifier.refreshPending(applicationContext)
    }

    private fun bindViews() {
        rowSelfHosted = findViewById(R.id.rowProviderSelfHosted)
        rowOpenAi = findViewById(R.id.rowProviderOpenAi)
        radioSelfHosted = findViewById(R.id.radioSelfHosted)
        radioOpenAi = findViewById(R.id.radioOpenAi)
        sectionSelfHosted = findViewById(R.id.sectionSelfHosted)
        sectionOpenAi = findViewById(R.id.sectionOpenAi)
        endpoint = findViewById(R.id.endpoint)
        token = findViewById(R.id.token)
        openAiKey = findViewById(R.id.openAiKey)
        rawFolderPath = findViewById(R.id.rawFolderPath)
        vaultFolderPath = findViewById(R.id.vaultFolderPath)
        rowRunTest = findViewById(R.id.rowRunTest)
        rowMockSwitch = findViewById(R.id.rowMockSwitch)
        mockSwitch = findViewById(R.id.mock)
        rowWifiOnly = findViewById(R.id.rowWifiOnly)
        wifiOnlySwitch = findViewById(R.id.wifiOnly)
        rowSendPending = findViewById(R.id.rowSendPending)
        rowDeleteAfterUpload = findViewById(R.id.rowDeleteAfterUpload)
        deleteAfterUploadSwitch = findViewById(R.id.deleteAfterUpload)
        rowExportRecordings = findViewById(R.id.rowExportRecordings)
        rowDeleteAll = findViewById(R.id.rowDeleteAll)
        saveButton = findViewById(R.id.save)
    }

    private fun loadSettingsIntoViews() {
        applyProviderSelection(settings.provider, animate = false)
        endpoint.setText(settings.endpointUrl)
        token.setText(settings.authToken)
        openAiKey.setText(settings.openAiApiKey)
        mockSwitch.isChecked = settings.mockMode
        wifiOnlySwitch.isChecked = settings.wifiOnly
        deleteAfterUploadSwitch.isChecked = settings.deleteAfterUpload
        refreshFolders()
    }

    // ---- Provider radio (custom rows) -------------------------------------

    private fun wireProviderRows() {
        rowSelfHosted.setOnClickListener { applyProviderSelection(Settings.PROVIDER_SELF_HOSTED) }
        rowOpenAi.setOnClickListener { applyProviderSelection(Settings.PROVIDER_OPENAI) }
    }

    /**
     * Visually + logically pick a provider:
     * - check the right radio
     * - tint the selected row's background (state_selected drives bg_provider_row_selected)
     * - swap which config section is visible (progressive disclosure)
     *
     * Persistence happens on Save, not here, so the user can preview without committing.
     */
    private fun applyProviderSelection(provider: String, animate: Boolean = true) {
        val isOpenAi = provider == Settings.PROVIDER_OPENAI
        radioSelfHosted.isChecked = !isOpenAi
        radioOpenAi.isChecked = isOpenAi
        rowSelfHosted.isSelected = !isOpenAi
        rowOpenAi.isSelected = isOpenAi
        sectionSelfHosted.visibility = if (isOpenAi) View.GONE else View.VISIBLE
        sectionOpenAi.visibility = if (isOpenAi) View.VISIBLE else View.GONE
    }

    // ---- Inputs: red focus border swap ------------------------------------

    private fun wireFocusBorders(vararg fields: EditText) {
        // The selector handles state_focused, but EditText doesn't propagate focus to
        // its background selector by default on every device / theme combo, so we
        // refreshDrawableState manually on focus change for predictable behaviour.
        for (f in fields) {
            f.onFocusChangeListener = View.OnFocusChangeListener { v, _ -> v.refreshDrawableState() }
        }
    }

    // ---- Save button: persist + transient "Saved ✓" confirmation ----------

    private fun wireSaveButton() {
        saveButton.setOnClickListener {
            settings.provider =
                if (radioOpenAi.isChecked) Settings.PROVIDER_OPENAI else Settings.PROVIDER_SELF_HOSTED
            settings.endpointUrl = endpoint.text.toString().trim()
            settings.authToken = token.text.toString().trim()
            settings.openAiApiKey = openAiKey.text.toString().trim()
            settings.mockMode = mockSwitch.isChecked

            saveButton.text = getString(R.string.save_button_confirmed)
            saveButton.setBackgroundResource(R.drawable.bg_save_button_saved)
            saveButton.setTextColor(android.graphics.Color.WHITE)
            mainHandler.removeCallbacks(revertSaveButton)
            mainHandler.postDelayed(revertSaveButton, SAVE_CONFIRM_MS)
        }
    }

    // ---- Storage rows ------------------------------------------------------

    private fun wireStorageRows() {
        findViewById<LinearLayout>(R.id.rowRawFolder).setOnClickListener { pickRaw.launch(null) }
        findViewById<LinearLayout>(R.id.rowVaultFolder).setOnClickListener { pickVault.launch(null) }
    }

    private fun persist(uri: Uri) {
        try {
            contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (t: Throwable) { Log.e(TAG, "persist permission failed", t) }
    }

    private fun refreshFolders() {
        rawFolderPath.text = friendlyPath(settings.rawFolderUri)
        vaultFolderPath.text = friendlyPath(settings.vaultFolderUri)
    }

    /**
     * Friendly SAF-tree-URI display: `…/<parent>/<leaf>`, leaf coloured red-soft.
     * Example input:
     *   content://.../tree/primary%3ADocuments%2FMyVault%2FVoiceNotes
     * Decoded path-after-colon: `Documents/MyVault/VoiceNotes`
     * Output (Spannable): `…/MyVault/VoiceNotes` with `VoiceNotes` in vnc_red_soft.
     *
     * Empty URI -> the bare placeholder string.
     */
    private fun friendlyPath(uri: String): CharSequence {
        if (uri.isEmpty()) return getString(R.string.folder_not_set)
        val decoded = Uri.decode(uri) ?: uri
        val pathPart = decoded.substringAfterLast(':', missingDelimiterValue = decoded)
        val parts = pathPart.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty()) return getString(R.string.folder_not_set)
        val leaf = parts.last()
        val parent = if (parts.size >= 2) parts[parts.size - 2] else ""
        val builder = SpannableStringBuilder()
        builder.append("…/")
        if (parent.isNotEmpty()) builder.append(parent).append("/")
        val leafStart = builder.length
        builder.append(leaf)
        val redSoft = ContextCompat.getColor(this, R.color.vnc_red_soft)
        builder.setSpan(ForegroundColorSpan(redSoft), leafStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        builder.setSpan(StyleSpan(android.graphics.Typeface.BOLD), leafStart, builder.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        return builder
    }

    // ---- Test card ---------------------------------------------------------

    private fun wireTestRows() {
        rowRunTest.setOnClickListener { pickAudio.launch("audio/*") }
        rowMockSwitch.setOnClickListener {
            mockSwitch.isChecked = !mockSwitch.isChecked
        }
        // Persist mock toggle immediately on switch state change so the user doesn't
        // have to also tap Save just to flip mock (different mental model from a
        // password field).
        mockSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.mockMode = isChecked
        }
    }

    // ---- Recordings card --------------------------------------------------

    private val pickExportFolder = registerForActivityResult(openTree()) { uri ->
        uri?.let {
            persist(it)
            exportRecordingsTo(it)
        }
    }

    private fun wireRecordingsRows() {
        rowDeleteAfterUpload.setOnClickListener {
            deleteAfterUploadSwitch.isChecked = !deleteAfterUploadSwitch.isChecked
        }
        deleteAfterUploadSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.deleteAfterUpload = isChecked
        }
        rowExportRecordings.setOnClickListener {
            val count = recordingsDir().listFiles { f -> f.isFile && f.extension == "m4a" }?.size ?: 0
            if (count == 0) {
                Toast.makeText(this, R.string.export_none, Toast.LENGTH_SHORT).show()
            } else {
                pickExportFolder.launch(null)
            }
        }
        rowDeleteAll.setOnClickListener { confirmDeleteAll() }
    }

    private fun recordingsDir(): File = File(filesDir, "incoming").apply { mkdirs() }

    /**
     * SAF-copy every .m4a in filesDir/incoming into the user-chosen tree. Does
     * NOT delete the source — Delete All is a separate action and a separate
     * choice. Runs on a background thread so a large export doesn't freeze the
     * UI; the user gets a Toast on completion.
     */
    private fun exportRecordingsTo(treeUri: Uri) {
        val files = recordingsDir().listFiles { f -> f.isFile && f.extension == "m4a" }?.toList().orEmpty()
        if (files.isEmpty()) {
            Toast.makeText(this, R.string.export_none, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, getString(R.string.export_started, files.size), Toast.LENGTH_SHORT).show()
        Thread({
            var ok = 0
            try {
                val tree = DocumentFile.fromTreeUri(this, treeUri)
                    ?: throw IllegalStateException("Cannot open chosen folder")
                for (f in files) {
                    val doc = tree.createFile("audio/mp4", f.name) ?: continue
                    contentResolver.openOutputStream(doc.uri)?.use { out ->
                        f.inputStream().use { it.copyTo(out) }
                    }
                    ok++
                }
                mainHandler.post {
                    Toast.makeText(this, getString(R.string.export_done, ok), Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "export failed", t)
                mainHandler.post {
                    Toast.makeText(this, getString(R.string.export_failed, t.message ?: "?"), Toast.LENGTH_LONG).show()
                }
            }
        }, "vnc-export").start()
    }

    private fun confirmDeleteAll() {
        val files = recordingsDir().listFiles { f -> f.isFile && f.extension == "m4a" }?.toList().orEmpty()
        if (files.isEmpty()) {
            Toast.makeText(this, R.string.delete_none, Toast.LENGTH_SHORT).show()
            return
        }
        val totalBytes = files.sumOf { it.length() }
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_confirm_title)
            .setMessage(getString(R.string.delete_confirm_body, files.size, humanBytes(totalBytes)))
            .setNegativeButton(R.string.delete_confirm_negative, null)
            .setPositiveButton(R.string.delete_confirm_positive) { _, _ ->
                var deleted = 0
                for (f in files) if (f.delete()) deleted++
                Toast.makeText(this, getString(R.string.delete_done, deleted), Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
        bytes >= 1_000 -> "%.1f KB".format(bytes / 1_000.0)
        else -> "$bytes B"
    }

    // ---- Network card -----------------------------------------------------

    private fun wireNetworkRow() {
        rowWifiOnly.setOnClickListener {
            wifiOnlySwitch.isChecked = !wifiOnlySwitch.isChecked
        }
        // Persist the toggle immediately - users expect a switch to take effect
        // without also having to hit Save. The next enqueue picks up the new
        // NetworkType constraint; in-flight work keeps the constraint it was
        // enqueued with (acceptable; they'll complete and the new flow uses the
        // new setting).
        wifiOnlySwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.wifiOnly = isChecked
        }
        rowSendPending.setOnClickListener { sendPendingUploadsNow() }
    }

    /**
     * Cancel any backed-off / waiting upload work and re-enqueue fresh requests
     * for every path the user still expects to land in the vault. The fresh
     * requests start at attempt 0 - no leftover exponential delay.
     *
     * Files that no longer exist on disk are skipped and removed from the
     * pending set (defensive; shouldn't happen but cheap to handle).
     */
    private fun sendPendingUploadsNow() {
        val pending = PendingUploads.all(applicationContext)
            .map { File(it) }
            .filter { it.exists() }
        if (pending.isEmpty()) {
            // Defensive cleanup of any stale entries that pointed at deleted files.
            for (p in PendingUploads.all(applicationContext)) {
                if (!File(p).exists()) PendingUploads.remove(applicationContext, p)
            }
            Toast.makeText(this, R.string.send_pending_none, Toast.LENGTH_SHORT).show()
            return
        }
        val wm = androidx.work.WorkManager.getInstance(applicationContext)
        wm.cancelAllWorkByTag(UploadStatusNotifier.WORK_TAG)
        val networkType = if (settings.wifiOnly)
            androidx.work.NetworkType.UNMETERED else androidx.work.NetworkType.CONNECTED
        val constraints = androidx.work.Constraints.Builder()
            .setRequiredNetworkType(networkType).build()
        for (file in pending) {
            val req = androidx.work.OneTimeWorkRequestBuilder<ProcessWorker>()
                .setInputData(androidx.work.workDataOf(ProcessWorker.KEY_FILE to file.absolutePath))
                .setConstraints(constraints)
                .setBackoffCriteria(
                    androidx.work.BackoffPolicy.LINEAR, 30, java.util.concurrent.TimeUnit.SECONDS,
                )
                .addTag(UploadStatusNotifier.WORK_TAG)
                .build()
            wm.enqueue(req)
        }
        UploadStatusNotifier.refreshPending(applicationContext)
        Toast.makeText(
            this, getString(R.string.send_pending_done, pending.size), Toast.LENGTH_SHORT,
        ).show()
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
