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

    /**
     * Which transcription backend [ProcessWorker] talks to.
     * - [PROVIDER_SELF_HOSTED] = the async upload / poll / download protocol
     *   ([endpointUrl] + optional [authToken]). The original path.
     * - [PROVIDER_OPENAI] = sync POST to https://api.openai.com/v1/audio/transcriptions
     *   ([openAiApiKey] required; the user's own OpenAI account pays for it).
     */
    var provider: String
        get() = prefs.getString(KEY_PROVIDER, PROVIDER_SELF_HOSTED) ?: PROVIDER_SELF_HOSTED
        set(v) = prefs.edit().putString(KEY_PROVIDER, v).apply()

    var endpointUrl: String
        get() = prefs.getString(KEY_ENDPOINT, "") ?: ""
        set(v) = prefs.edit().putString(KEY_ENDPOINT, v).apply()

    var authToken: String
        get() = prefs.getString(KEY_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_TOKEN, v).apply()

    /** User's own OpenAI API key when [provider] = [PROVIDER_OPENAI]. */
    var openAiApiKey: String
        get() = prefs.getString(KEY_OPENAI_KEY, "") ?: ""
        set(v) = prefs.edit().putString(KEY_OPENAI_KEY, v).apply()

    /**
     * Mock mode short-circuits the network call and returns canned text -
     * still useful as a zero-cost end-to-end check (watch -> phone -> vault)
     * for users who haven't configured a transcription provider yet.
     *
     * Default flipped from ON to OFF in Phase 2 (was ON during Phase 1
     * emulator-testing days, when there was a single user — the author — who
     * deliberately stayed in mock to validate the chain without a server).
     * For users in the wild, defaulting to ON would silently produce fake
     * notes after a normal Configure-and-go setup; so new installs now
     * default to OFF and the user opts in deliberately.
     */
    var mockMode: Boolean
        get() = prefs.getBoolean(KEY_MOCK, false)
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
        const val PROVIDER_SELF_HOSTED = "self_hosted"
        const val PROVIDER_OPENAI = "openai"

        private const val KEY_PROVIDER = "provider"
        private const val KEY_ENDPOINT = "endpoint_url"
        private const val KEY_TOKEN = "auth_token"
        private const val KEY_OPENAI_KEY = "openai_api_key"
        private const val KEY_MOCK = "mock_mode"
        private const val KEY_RAW = "raw_folder_uri"
        private const val KEY_VAULT = "vault_folder_uri"
    }
}
