// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright (c) 2026 Notaricus
// Licensed under the PolyForm Noncommercial License 1.0.0. See LICENSE.md.

package com.notaricus.voicenote

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import kotlin.math.sqrt

/**
 * Live mic-amplitude waveform for the recording screen.
 *
 * Fixed-length ring of [BAR_COUNT] amplitudes (0..1); newest sample lands on
 * the right; the buffer scrolls left on each [pushAmplitude] call. Each bar is
 * vertically center-anchored, height proportional to its sample, with a per-bar
 * alpha that combines:
 *   - amplitude weight (0.55 + amp * 0.45) - quieter bars are still visible,
 *   - an edge fade (linear 0..1 over the outer 14% on each side) - matches the
 *     mask in the handoff design without needing a Paint xfermode.
 *
 * Dimensions are taken straight from the handoff (canvas px / 2 -> dp), but
 * scale with the device's actual density so larger round watches stay correct.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    companion object {
        const val BAR_COUNT = 40
        private const val BAR_WIDTH_DP = 1.5f
        private const val BAR_GAP_DP = 1.5f
        private const val MIN_HEIGHT_DP = 2f
        // Bumped from 22 (the handoff value) to 44 after on-watch testing: the
        // smaller bars felt visually inert under normal speech. Container in the
        // layout is sized to match (50dp).
        private const val MAX_HEIGHT_DP = 44f
        private const val EDGE_FADE_FRACTION = 0.14f
    }

    private val amplitudes = FloatArray(BAR_COUNT)

    private val barWidthPx: Float = dp(BAR_WIDTH_DP)
    private val barGapPx: Float = dp(BAR_GAP_DP)
    private val minHeightPx: Float = dp(MIN_HEIGHT_DP)
    private val maxHeightPx: Float = dp(MAX_HEIGHT_DP)
    private val barRadiusPx: Float = barWidthPx / 2f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFF453A.toInt() // vnc_record_red — kept literal so the View has no resource dep at construction.
        style = Paint.Style.FILL
    }

    /**
     * Append a new amplitude (0..1) and redraw.
     * Caller is responsible for normalising raw MediaRecorder.getMaxAmplitude()
     * into this range; see [normalizeAmplitude].
     */
    fun pushAmplitude(amp: Float) {
        val clamped = amp.coerceIn(0f, 1f)
        // Shift left; oldest sample drops off, newest lands at the right.
        for (i in 0 until BAR_COUNT - 1) {
            amplitudes[i] = amplitudes[i + 1]
        }
        amplitudes[BAR_COUNT - 1] = clamped
        invalidate()
    }

    /** Reset to a flat row of minimum-height bars (idle state). */
    fun clear() {
        for (i in 0 until BAR_COUNT) amplitudes[i] = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val totalBarsWidth = BAR_COUNT * barWidthPx + (BAR_COUNT - 1) * barGapPx
        val startX = (width - totalBarsWidth) / 2f
        val centerY = height / 2f

        for (i in 0 until BAR_COUNT) {
            val amp = amplitudes[i]
            val barHeight = maxOf(minHeightPx, amp * maxHeightPx)
            val left = startX + i * (barWidthPx + barGapPx)
            val top = centerY - barHeight / 2f
            val bottom = centerY + barHeight / 2f

            // Edge fade: 0..1 ramp over the outer EDGE_FADE_FRACTION on each side.
            val norm = i / (BAR_COUNT - 1f)
            val fade = when {
                norm < EDGE_FADE_FRACTION -> norm / EDGE_FADE_FRACTION
                norm > 1f - EDGE_FADE_FRACTION -> (1f - norm) / EDGE_FADE_FRACTION
                else -> 1f
            }
            val ampWeight = 0.55f + amp * 0.45f
            paint.alpha = ((ampWeight * fade) * 255f).toInt().coerceIn(0, 255)

            canvas.drawRoundRect(
                left, top, left + barWidthPx, bottom,
                barRadiusPx, barRadiusPx, paint,
            )
        }
    }

    private fun dp(value: Float): Float = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics,
    )
}

/**
 * Map a raw MediaRecorder.getMaxAmplitude() value (0..32767) into the 0..1
 * range the waveform expects.
 *
 * sqrt curve so quiet speech is still visible; divisor is 4096 (not 32767)
 * after on-watch testing — speech rarely exceeds raw values of a few thousand
 * even when loud, so dividing by full int16 range left the bars barely moving.
 * Anything above the divisor saturates to full height, which is fine for a
 * visual indicator (we want motion, not preserved dynamic range).
 */
fun normalizeAmplitude(rawMaxAmplitude: Int): Float {
    if (rawMaxAmplitude <= 0) return 0f
    val divisor = 4096f
    return sqrt((rawMaxAmplitude / divisor).coerceAtMost(1f))
}
