package com.notaricus.voicenote

import android.content.Context

/**
 * Phone-side settings (Phase 1 prototype). Folder locations are stored as SAF
 * tree URIs (persisted permission), which is the correct modern way to write to
 * a user-chosen location like an Obsidian vault folder without broad storage
 * permissions.
 */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("vnc_settings", Context.MODE_PRIVATE)

    var endpointUrl: String
        get() = prefs.getString(KEY_ENDPOINT, "") ?: ""
        set(v) = prefs.edit().putString(KEY_ENDPOINT, v).apply()

    var authToken: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TOKEN, v).apply()

    /** Mock mode short-circuits the network call and returns canned text. Default ON for first-run emulator testing. */
    var mockMode: Boolean
        get() = prefs.getBoolean(KEY_MOCK, true)
        set(v) = prefs.edit().putBoolean(KEY_MOCK, v).apply()

    /** SAF tree URI (string) for raw audio backup folder; empty = use app-internal fallback. */
    var rawFolderUri: String
        get() = prefs.getString(KEY_RAW, "") ?: ""
        set(v) = prefs.edit().putString(KEY_RAW, v).apply()

    /** SAF tree URI (string) for the Obsidian vault output folder. */
    var vaultFolderUri: String
        get() = prefs.getString(KEY_VAULT, "") ?: ""
        set(v) = prefs.edit().putString(KEY_VAULT, v).apply()

    companion object {
        private const val KEY_ENDPOINT = "endpoint_url"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_MOCK = "mock_mode"
        private const val KEY_RAW = "raw_folder_uri"
        private const val KEY_VAULT = "vault_folder_uri"
    }
}
