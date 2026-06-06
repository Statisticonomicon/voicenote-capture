// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright (c) 2026 Konstantinos Bonikos
// Licensed under the PolyForm Noncommercial License 1.0.0. See LICENSE.md.

package com.notaricus.voicenote

/**
 * Tiny in-process bridge between [RecordingService] (writer) and
 * [WearMainActivity] (reader). The service samples the live mic amplitude and
 * stamps the session start; the activity reads both to drive the timer and
 * waveform on the recording screen.
 *
 * Volatile primitives only - cheap, lock-free, sufficient because the activity
 * polls on a UI handler and a stale-by-one-frame read is harmless.
 *
 * Both fields reset to 0 when no recording is active.
 */
object RecordingState {
    /** Last sampled MediaRecorder.getMaxAmplitude() value (0..32767), or 0 when idle. */
    @Volatile var amplitude: Int = 0

    /** System.currentTimeMillis() at which the current recording started, or 0 when idle. */
    @Volatile var startTimeMs: Long = 0L
}
