// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright (c) 2026 Konstantinos Bonikos
// Licensed under the PolyForm Noncommercial License 1.0.0. See LICENSE.md.

package com.notaricus.voicenote

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService

/**
 * Watch-face complication that acts as a one-tap launcher for [WearMainActivity].
 *
 * Why this exists: the original Pixel Watch has only the rotating crown, which the
 * platform does not let a third-party app bind for arbitrary launch. So instead of
 * a hardware-button press, the user places this complication on their watch face;
 * a single tap fires the same Activity-launch path. Under launchMode=singleTask
 * (confirmed in Phase 0.5):
 *   first tap  -> onCreate    -> start recording
 *   next taps  -> onNewIntent -> toggle stop / start
 *
 * The complication itself is purely static — no periodic updates, no dynamic
 * content. We supply previews for the three slot types users most commonly have
 * available (icon-only, short text + icon, small image), so the complication
 * picker offers it wherever the watch face allows.
 */
class VoiceNoteComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? = buildFor(type)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        buildFor(request.complicationType)

    private fun buildFor(type: ComplicationType): ComplicationData? {
        val tap = launchPendingIntent()
        val label = PlainComplicationText.Builder(getString(R.string.complication_label)).build()
        val description = PlainComplicationText.Builder(getString(R.string.complication_description)).build()
        // Use the app launcher icon so the complication visually matches the app.
        // For monochrome slots the system will tint it (intentional - silhouette).
        val iconRes = Icon.createWithResource(this, R.mipmap.ic_launcher)
        val mono = MonochromaticImage.Builder(iconRes).build()

        return when (type) {
            ComplicationType.MONOCHROMATIC_IMAGE ->
                MonochromaticImageComplicationData.Builder(mono, description)
                    .setTapAction(tap)
                    .build()
            ComplicationType.SHORT_TEXT ->
                ShortTextComplicationData.Builder(label, description)
                    .setMonochromaticImage(mono)
                    .setTapAction(tap)
                    .build()
            ComplicationType.SMALL_IMAGE ->
                SmallImageComplicationData.Builder(
                    SmallImage.Builder(iconRes, SmallImageType.ICON).build(),
                    description
                ).setTapAction(tap).build()
            else -> null
        }
    }

    private fun launchPendingIntent(): PendingIntent {
        val intent = Intent().apply {
            component = ComponentName(this@VoiceNoteComplicationService, WearMainActivity::class.java)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
