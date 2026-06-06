// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright (c) 2026 Notaricus
// Licensed under the PolyForm Noncommercial License 1.0.0. See LICENSE.md.

package com.notaricus.voicenote

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.Wearable
import java.io.File

/**
 * Sends a finished recording from the watch to the phone over the Wear Data Layer
 * using ChannelClient (suited to large binary payloads like audio). Prototype:
 * fire-and-forget with logging; Phase 1 acceptance only requires the file to
 * arrive at a paired node.
 *
 * Connectivity reality: this needs a paired phone node. With no node (watch tested
 * alone), the send simply finds no target and logs - the recording still exists
 * locally for later transfer.
 */
object WearTransfer {

    private const val TAG = "VNC-Xfer"
    private const val CHANNEL_PATH = "/voicenote/audio"
    // A node advertising this capability is our phone companion (declared in :mobile).
    private const val PHONE_CAPABILITY = "voicenote_phone"

    fun sendToPhone(context: Context, file: File) {
        val capabilityClient = Wearable.getCapabilityClient(context)
        capabilityClient
            .getCapability(PHONE_CAPABILITY, CapabilityClient.FILTER_REACHABLE)
            .addOnSuccessListener { info ->
                val node = info.nodes.firstOrNull()
                if (node == null) {
                    Log.w(TAG, "No reachable phone node; leaving ${file.name} on watch for later")
                    return@addOnSuccessListener
                }
                openAndSend(context, node.id, file)
            }
            .addOnFailureListener { e -> Log.e(TAG, "Capability lookup failed", e) }
    }

    private fun openAndSend(context: Context, nodeId: String, file: File) {
        val channelClient = Wearable.getChannelClient(context)
        channelClient.openChannel(nodeId, "$CHANNEL_PATH/${file.name}")
            .addOnSuccessListener { channel ->
                channelClient.sendFile(channel, android.net.Uri.fromFile(file))
                    .addOnSuccessListener { Log.d(TAG, "Sent ${file.name} to $nodeId") }
                    .addOnFailureListener { e -> Log.e(TAG, "sendFile failed", e) }
            }
            .addOnFailureListener { e -> Log.e(TAG, "openChannel failed", e) }
    }
}
