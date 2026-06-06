// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright (c) 2026 Notaricus
// Licensed under the PolyForm Noncommercial License 1.0.0. See LICENSE.md.

package com.notaricus.voicenote

import android.content.Context

/**
 * Tiny persistent set of audio file paths the user expects to land in the
 * vault. WorkManager's [WORK_TAG] tells us which jobs exist, but not which
 * input files they wrap (WorkInfo doesn't expose input data), so we keep our
 * own bookkeeping to support the "Send pending uploads now" action — it needs
 * to enumerate the still-to-be-uploaded paths after cancelling existing work.
 *
 * Lifecycle:
 *   - [PhoneListenerService.enqueueProcessing] -> [add] on every new upload.
 *   - [ProcessWorker] -> [remove] on every terminal outcome (success, empty
 *     transcript, exhausted retries). Retry paths keep the entry.
 *
 * A stale entry only causes a re-upload (a possible duplicate vault note) — no
 * data loss — so we're not strict about cleanup on edge cases.
 */
object PendingUploads {

    private const val PREFS_NAME = "vnc_pending_uploads"
    private const val KEY_PATHS = "paths"

    @Synchronized
    fun add(context: Context, path: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updated = (prefs.getStringSet(KEY_PATHS, emptySet()) ?: emptySet()).toMutableSet()
        updated.add(path)
        prefs.edit().putStringSet(KEY_PATHS, updated).apply()
    }

    @Synchronized
    fun remove(context: Context, path: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val updated = (prefs.getStringSet(KEY_PATHS, emptySet()) ?: emptySet()).toMutableSet()
        if (updated.remove(path)) {
            prefs.edit().putStringSet(KEY_PATHS, updated).apply()
        }
    }

    fun all(context: Context): Set<String> {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // StringSet returned by SharedPreferences must not be mutated by the caller;
        // copy so downstream code can iterate without surprises.
        return (prefs.getStringSet(KEY_PATHS, emptySet()) ?: emptySet()).toSet()
    }
}
