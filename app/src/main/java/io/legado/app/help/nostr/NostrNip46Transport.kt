package io.legado.app.help.nostr

import io.legado.app.help.http.okHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object NostrNip46Transport {

    suspend fun publish(relays: List<String>, event: JSONObject, timeoutMillis: Long = 15_000) {
        val validRelays = relays.distinct().filter { relay -> relay.startsWith("wss://", true) }
        require(validRelays.isNotEmpty()) { "No valid Relay available for publishing" }
        val eventId = event.optString("id").takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("Cannot publish an unsigned event")
        val results = coroutineScope {
            validRelays.map { relay ->
                async(Dispatchers.IO) {
                    runCatching { publishToRelay(relay, event, eventId, timeoutMillis) }
                }
            }.awaitAll()
        }
        if (results.none { result -> result.getOrDefault(false) }) {
            throw results.firstNotNullOfOrNull { result -> result.exceptionOrNull() }
                ?: IllegalStateException("No Relay accepted the Nostr event")
        }
    }

    private suspend fun publishToRelay(
        relay: String,
        event: JSONObject,
        eventId: String,
        timeoutMillis: Long
    ): Boolean = withTimeout(timeoutMillis) {
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            lateinit var socket: WebSocket
            socket = okHttpClient.newWebSocket(
                Request.Builder().url(relay).build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (!webSocket.send(JSONArray().put("EVENT").put(event).toString()) &&
                            completed.compareAndSet(false, true)
                        ) {
                            continuation.resumeWithException(
                                IllegalStateException("Unable to publish to $relay")
                            )
                        }
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        runCatching {
                            val message = JSONArray(text)
                            if (message.optString(0) != "OK" || message.optString(1) != eventId) return
                            if (completed.compareAndSet(false, true)) {
                                val accepted = message.optBoolean(2)
                                webSocket.close(1000, "publish acknowledged")
                                if (accepted) continuation.resume(true)
                                else continuation.resumeWithException(
                                    IllegalStateException(message.optString(3, "Relay rejected event"))
                                )
                            }
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (completed.compareAndSet(false, true)) continuation.resumeWithException(t)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (completed.compareAndSet(false, true)) {
                            continuation.resumeWithException(
                                IllegalStateException("Relay closed before acknowledging event: $relay")
                            )
                        }
                    }
                }
            )
            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) socket.cancel()
            }
        }
    }

    suspend fun awaitNostrConnect(
        relays: List<String>,
        keyPair: NostrNip46.ClientKeyPair,
        secret: String,
        timeoutMillis: Long = 120_000
    ): NostrNip46.NostrConnectApproval = withTimeout(timeoutMillis) {
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            val failedRelays = AtomicInteger(0)
            val sockets = CopyOnWriteArrayList<WebSocket>()
            val validRelays = relays.distinct().filter { it.startsWith("wss://", true) }
            require(validRelays.isNotEmpty()) { "No valid Relay available for nostrconnect" }

            fun closeSockets() {
                sockets.forEach { it.close(1000, "connection established") }
                sockets.clear()
            }

            validRelays.forEach { relay ->
                if (completed.get()) return@forEach
                val subscriptionId = UUID.randomUUID().toString().replace("-", "")
                val socket = okHttpClient.newWebSocket(
                    Request.Builder().url(relay).build(),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            val filter = JSONObject()
                                .put("kinds", JSONArray().put(24133))
                                .put("#p", JSONArray().put(keyPair.publicKey))
                                .put("since", System.currentTimeMillis() / 1000 - 300)
                            if (!webSocket.send(
                                    JSONArray().put("REQ").put(subscriptionId).put(filter).toString()
                                ) && failedRelays.incrementAndGet() == validRelays.size &&
                                completed.compareAndSet(false, true)
                            ) {
                                continuation.resumeWithException(
                                    IllegalStateException("Unable to subscribe to nostrconnect Relay")
                                )
                            }
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (completed.get()) return
                            runCatching {
                                val message = JSONArray(text)
                                if (message.optString(0) != "EVENT" ||
                                    message.optString(1) != subscriptionId
                                ) return
                                val event = message.optJSONObject(2) ?: return
                                val approval = NostrNip46.validateNostrConnectApproval(
                                    eventJson = event,
                                    keyPair = keyPair,
                                    expectedSecret = secret
                                )
                                if (completed.compareAndSet(false, true)) {
                                    closeSockets()
                                    continuation.resume(approval)
                                }
                            }
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            if (failedRelays.incrementAndGet() == validRelays.size &&
                                completed.compareAndSet(false, true)
                            ) continuation.resumeWithException(t)
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            if (!completed.get() && failedRelays.incrementAndGet() == validRelays.size &&
                                completed.compareAndSet(false, true)
                            ) continuation.resumeWithException(
                                IllegalStateException("All nostrconnect Relays closed before approval")
                            )
                        }
                    }
                )
                sockets.add(socket)
                if (completed.get()) socket.close(1000, "connection already established")
            }

            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) closeSockets()
            }
        }
    }

    suspend fun request(
        connection: NostrNip46.BunkerConnection,
        keyPair: NostrNip46.ClientKeyPair,
        request: NostrNip46.RpcRequest,
        timeoutMillis: Long = 90_000
    ): NostrNip46.RpcResponse = withTimeout(timeoutMillis) {
        suspendCancellableCoroutine { continuation ->
            val completed = AtomicBoolean(false)
            val failedRelays = AtomicInteger(0)
            val sockets = CopyOnWriteArrayList<WebSocket>()
            val expectedAuthor = connection.signerPubkey
            val eventJson = request.event.toString()

            fun closeSockets(code: Int, reason: String) {
                sockets.forEach { it.close(code, reason) }
                sockets.clear()
            }

            fun failRelay(error: Throwable) {
                if (failedRelays.incrementAndGet() == connection.relays.size &&
                    completed.compareAndSet(false, true)
                ) {
                    continuation.resumeWithException(error)
                }
            }

            connection.relays.forEach { relay ->
                if (completed.get()) return@forEach
                val subscriptionId = UUID.randomUUID().toString().replace("-", "")
                val socket = okHttpClient.newWebSocket(
                    Request.Builder().url(relay).build(),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            val filter = JSONObject()
                                .put("kinds", JSONArray().put(24133))
                                .put("authors", JSONArray().put(expectedAuthor))
                                .put("#p", JSONArray().put(keyPair.publicKey))
                            if (!webSocket.send(
                                    JSONArray().put("REQ").put(subscriptionId).put(filter).toString()
                                ) || !webSocket.send(JSONArray().put("EVENT").put(request.event).toString())
                            ) {
                                failRelay(IllegalStateException("Unable to send NIP-46 request to $relay"))
                            }
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            if (completed.get()) return
                            runCatching {
                                val message = JSONArray(text)
                                if (message.optString(0) != "EVENT" ||
                                    message.optString(1) != subscriptionId
                                ) return
                                val event = message.optJSONObject(2) ?: return
                                val result = NostrNip46.decryptResponse(
                                    eventJson = event,
                                    keyPair = keyPair,
                                    signerPubkey = expectedAuthor,
                                    expectedRequestId = request.id
                                )
                                if (completed.compareAndSet(false, true)) {
                                    closeSockets(1000, "response received")
                                    continuation.resume(result)
                                }
                            }
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            if (!completed.get()) failRelay(t)
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            if (!completed.get()) {
                                failRelay(IllegalStateException("NIP-46 relay closed before responding: $relay"))
                            }
                        }
                    }
                )
                sockets.add(socket)
                if (completed.get()) socket.close(1000, "response already received")
            }

            continuation.invokeOnCancellation {
                if (completed.compareAndSet(false, true)) closeSockets(1000, "request cancelled")
            }
        }
    }
}
