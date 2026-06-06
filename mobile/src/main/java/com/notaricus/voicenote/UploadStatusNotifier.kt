// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright (c) 2026 Notaricus
// Licensed under the PolyForm Noncommercial License 1.0.0. See LICENSE.md.

package com.notaricus.voicenote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.UUID

/**
 * Surfaces upload state to the user as phone notifications.
 *
 * Two channels (separately mutable in system settings):
 *   - [CHANNEL_PENDING] IMPORTANCE_LOW: an ongoing "N waiting to upload"
 *     status row in the shade. Replaces itself as the queue changes; auto-
 *     cancels when the queue drains.
 *   - [CHANNEL_ALERTS] IMPORTANCE_DEFAULT: one per problematic note (empty
 *     transcript, terminal upload failure). Auto-cancel on tap.
 *
 * All notifications are setLocalOnly(true) so they stay on the phone and
 * don't bridge to the paired watch (which would defeat the wrist-driven UX).
 *
 * Source of truth for the pending count is WorkManager itself, queried by
 * the tag we attach to every [ProcessWorker] request in
 * [PhoneListenerService.enqueueProcessing]. The worker passes its own id to
 * [refreshPending] so it's not double-counted while it's still running.
 */
object UploadStatusNotifier {

    private const val TAG = "VNC-Notif"

    const val WORK_TAG = "vnc-upload"

    private const val CHANNEL_PENDING = "vnc_upload_pending"

    // Alerts moved to v2 channel ID because Android locks importance after a
    // channel's first creation. v1 was created with IMPORTANCE_DEFAULT (sound +
    // peek), which felt noisy for upload failures; v2 is IMPORTANCE_LOW so the
    // alert lands silently in the shade. The old channel is deleted on first
    // run after upgrade.
    private const val CHANNEL_ALERTS_V1 = "vnc_upload_alerts"
    private const val CHANNEL_ALERTS = "vnc_upload_alerts_v2"

    private const val NOTIF_ID_PENDING = 100
    private const val NOTIF_ID_ALERT_BASE = 1000

    /**
     * Refresh the ongoing "N waiting to upload" status row. Counts every
     * [WORK_TAG]-tagged work item that's not in a terminal state, optionally
     * excluding the caller (so a worker that's just finishing doesn't see
     * itself in the count).
     */
    fun refreshPending(context: Context, excludeWorkerId: UUID? = null) {
        ensureChannels(context)
        val infos = try {
            WorkManager.getInstance(context).getWorkInfosByTag(WORK_TAG).get()
        } catch (t: Throwable) {
            Log.w(TAG, "refreshPending: failed to read WorkManager state", t)
            return
        }
        val unfinished = infos.filter { !it.state.isFinished && it.id != excludeWorkerId }
        val running = unfinished.count { it.state == WorkInfo.State.RUNNING }
        val waiting = unfinished.size - running
        val total = unfinished.size

        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (total == 0) {
            nm.cancel(NOTIF_ID_PENDING)
            return
        }

        // Title names the dominant state so the user sees what's actually happening:
        //   any running -> "Uploading N voice note(s)"
        //   only waiting -> "N voice note(s) waiting"
        // Body adds detail only when the secondary count is non-zero.
        val title = when {
            running > 0 -> context.resources.getQuantityString(
                R.plurals.notif_pending_uploading_title, total, total,
            )
            else -> context.resources.getQuantityString(
                R.plurals.notif_pending_waiting_title, total, total,
            )
        }
        val body = when {
            running > 0 && waiting > 0 -> context.getString(R.string.notif_pending_body_mixed, waiting)
            running > 0 -> context.getString(R.string.notif_pending_body_uploading)
            else -> context.getString(R.string.notif_pending_body_waiting)
        }
        val notif = baseBuilder(context, CHANNEL_PENDING)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(R.drawable.ic_notif_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openSettingsPendingIntent(context))
            .build()
        nm.notify(NOTIF_ID_PENDING, notif)
    }

    /** Post a per-file alert that whisper returned an empty transcript. */
    fun notifyEmptyTranscript(context: Context, fileName: String) {
        ensureChannels(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val notif = baseBuilder(context, CHANNEL_ALERTS)
            .setContentTitle(context.getString(R.string.notif_empty_title))
            .setContentText(context.getString(R.string.notif_empty_body, fileName))
            .setStyle(Notification.BigTextStyle().bigText(
                context.getString(R.string.notif_empty_body, fileName)
            ))
            .setSmallIcon(R.drawable.ic_notif_warning)
            .setAutoCancel(true)
            .setContentIntent(openSettingsPendingIntent(context))
            .build()
        nm.notify(alertIdFor(fileName), notif)
    }

    /** Post a per-file alert that the upload failed terminally after retries. */
    fun notifyTerminalFailure(context: Context, fileName: String) {
        ensureChannels(context)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val notif = baseBuilder(context, CHANNEL_ALERTS)
            .setContentTitle(context.getString(R.string.notif_failed_title))
            .setContentText(context.getString(R.string.notif_failed_body, fileName))
            .setStyle(Notification.BigTextStyle().bigText(
                context.getString(R.string.notif_failed_body, fileName)
            ))
            .setSmallIcon(R.drawable.ic_notif_warning)
            .setAutoCancel(true)
            .setContentIntent(openSettingsPendingIntent(context))
            .build()
        nm.notify(alertIdFor(fileName), notif)
    }

    // ---- helpers ----------------------------------------------------------

    private fun baseBuilder(context: Context, channel: String): Notification.Builder =
        Notification.Builder(context, channel)
            .setLocalOnly(true) // never bridge to the watch
            .setShowWhen(true)

    private fun openSettingsPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, SettingsActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /** Stable-ish id per file so a repeated failure replaces (not stacks) its own notification. */
    private fun alertIdFor(fileName: String): Int =
        NOTIF_ID_ALERT_BASE + (fileName.hashCode() and 0x7FFFFFFF) % 100_000

    private fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL_PENDING) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_PENDING,
                    context.getString(R.string.notif_channel_pending),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notif_channel_pending_desc)
                    setShowBadge(false)
                }
            )
        }
        // Drop the v1 alerts channel if it's still around (was IMPORTANCE_DEFAULT
        // and noisy). It only ever existed for users who installed the build
        // shipped between the notifications-arrive-MR and now.
        if (nm.getNotificationChannel(CHANNEL_ALERTS_V1) != null) {
            nm.deleteNotificationChannel(CHANNEL_ALERTS_V1)
        }
        if (nm.getNotificationChannel(CHANNEL_ALERTS) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERTS,
                    context.getString(R.string.notif_channel_alerts),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.notif_channel_alerts_desc)
                    setSound(null, null)
                    enableVibration(false)
                    setShowBadge(false)
                }
            )
        }
    }
}
