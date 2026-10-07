package com.geoffchan.glucosewidget.wear

import android.content.Context
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

class PhoneUnreachable(message: String) : Exception(message)

/**
 * Request/reply over MessageClient: send on [path] to the paired phone, wait
 * for the phone's message on [replyPath]. Only one request is in flight at a
 * time (the UI is a single linear flow).
 */
class PhoneLink(private val context: Context) {
    suspend fun request(path: String, body: String, replyPath: String, timeoutMs: Long = 25_000): String {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        val phone = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
            ?: throw PhoneUnreachable("Can't reach your phone. Is Bluetooth on and the phone nearby?")
        val client = Wearable.getMessageClient(context)
        val reply = CompletableDeferred<String>()
        val listener = MessageClient.OnMessageReceivedListener { e ->
            if (e.path == replyPath && e.sourceNodeId == phone.id) reply.complete(String(e.data, Charsets.UTF_8))
        }
        client.addListener(listener).await()
        try {
            client.sendMessage(phone.id, path, body.toByteArray(Charsets.UTF_8)).await()
            return withTimeoutOrNull(timeoutMs) { reply.await() }
                ?: throw PhoneUnreachable("Your phone didn't answer in time.")
        } finally {
            client.removeListener(listener)
        }
    }
}
