package io.legado.app.help.nostr

import fr.acinq.secp256k1.Secp256k1
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

object NostrNip46 {

    val paragraphReviewKinds = setOf(9802, 1111, 7)
    private val secureRandom = SecureRandom()

    data class BunkerConnection(
        val signerPubkey: String,
        val relays: List<String>,
        val secret: String?
    )

    data class NostrConnectToken(
        val uri: String,
        val secret: String,
        val relays: List<String>
    )

    data class ClientKeyPair(
        val privateKey: ByteArray,
        val publicKey: String
    )

    data class RpcRequest(
        val id: String,
        val event: JSONObject
    )

    data class RpcResponse(
        val id: String,
        val result: String?,
        val error: String?
    )

    data class NostrConnectApproval(
        val signerPubkey: String,
        val requestId: String
    )

    fun parseBunkerUri(value: String): BunkerConnection {
        val uri = URI(value.trim())
        require(uri.scheme.equals("bunker", ignoreCase = true)) { "Expected a bunker:// URI" }
        val signerPubkey = uri.host.orEmpty().lowercase(Locale.ROOT)
        require(signerPubkey.isHexKey()) { "Invalid remote signer public key" }
        val queryParameters = uri.rawQuery.orEmpty()
            .split('&')
            .filter { it.isNotBlank() }
            .mapNotNull { parameter ->
                val separator = parameter.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                URLDecoder.decode(parameter.substring(0, separator), Charsets.UTF_8.name()) to
                    URLDecoder.decode(parameter.substring(separator + 1), Charsets.UTF_8.name())
            }
        val relays = queryParameters.asSequence()
            .filter { it.first == "relay" }
            .map { it.second }
            .map(String::trim)
            .filter { it.startsWith("wss://", ignoreCase = true) }
            .distinct()
            .toList()
        require(relays.isNotEmpty()) { "Bunker URI must include at least one wss:// relay" }
        return BunkerConnection(
            signerPubkey = signerPubkey,
            relays = relays,
            secret = queryParameters.firstOrNull { it.first == "secret" }?.second
                ?.takeIf { it.isNotBlank() }
        )
    }

    fun newClientKeyPair(): ClientKeyPair {
        val privateKey = ByteArray(32)
        do {
            secureRandom.nextBytes(privateKey)
        } while (!Secp256k1.secKeyVerify(privateKey))
        val publicKey = Secp256k1.pubkeyCreate(privateKey).copyOfRange(1, 33).toHex()
        return ClientKeyPair(privateKey, publicKey)
    }

    fun createNostrConnectToken(keyPair: ClientKeyPair, relays: List<String>): NostrConnectToken {
        val validRelays = relays.map(String::trim)
            .filter { it.startsWith("wss://", ignoreCase = true) }
            .distinct()
        require(validRelays.isNotEmpty()) { "At least one wss:// relay is required" }
        val secret = ByteArray(16).also(secureRandom::nextBytes).toHex()
        val query = buildList {
            validRelays.forEach { add("relay=${it.urlEncoded()}") }
            add("secret=${secret.urlEncoded()}")
            add("perms=${paragraphReviewPermissions.urlEncoded()}")
            add("name=${"Legado".urlEncoded()}")
        }.joinToString("&")
        return NostrConnectToken(
            uri = "nostrconnect://${keyPair.publicKey}?$query",
            secret = secret,
            relays = validRelays
        )
    }

    fun buildConnectRequest(
        keyPair: ClientKeyPair,
        connection: BunkerConnection,
        createdAt: Long = System.currentTimeMillis() / 1000
    ): RpcRequest = buildRequest(
        keyPair = keyPair,
        signerPubkey = connection.signerPubkey,
        method = "connect",
        params = listOf(
            connection.signerPubkey,
            connection.secret.orEmpty(),
            paragraphReviewPermissions,
            JSONObject().put("name", "Legado").toString()
        ),
        createdAt = createdAt
    )

    fun buildGetPublicKeyRequest(
        keyPair: ClientKeyPair,
        signerPubkey: String,
        createdAt: Long = System.currentTimeMillis() / 1000
    ): RpcRequest = buildRequest(keyPair, signerPubkey, "get_public_key", emptyList(), createdAt)

    fun buildSwitchRelaysRequest(
        keyPair: ClientKeyPair,
        signerPubkey: String,
        createdAt: Long = System.currentTimeMillis() / 1000
    ): RpcRequest = buildRequest(keyPair, signerPubkey, "switch_relays", emptyList(), createdAt)

    fun buildSignEventRequest(
        keyPair: ClientKeyPair,
        signerPubkey: String,
        unsignedEvent: JSONObject,
        createdAt: Long = System.currentTimeMillis() / 1000
    ): RpcRequest {
        val kind = unsignedEvent.optInt("kind", -1)
        require(kind in paragraphReviewKinds) { "Signing kind $kind is not permitted for paragraph review" }
        require(unsignedEvent.optString("pubkey").isBlank()) {
            "The signer must supply the user public key"
        }
        return buildRequest(
            keyPair,
            signerPubkey,
            "sign_event",
            listOf(unsignedEvent.toString()),
            createdAt
        )
    }

    fun decryptResponse(
        eventJson: JSONObject,
        keyPair: ClientKeyPair,
        signerPubkey: String,
        expectedRequestId: String
    ): RpcResponse {
        val event = NostrReviewClient.NostrEvent.fromJson(eventJson)
            ?: throw IllegalArgumentException("Invalid signed NIP-46 response event")
        require(event.kind == 24133) { "Unexpected NIP-46 response kind" }
        require(event.pubkey == signerPubkey) { "NIP-46 response is from an unknown signer" }
        require(keyPair.publicKey in event.tagValues("p")) { "NIP-46 response is not addressed to this client" }
        val conversationKey = NostrNip44.conversationKey(keyPair.privateKey, signerPubkey.hexToByteArray())
        val payload = NostrNip44.decrypt(event.content, conversationKey)
        val response = JSONObject(payload)
        require(response.optString("id") == expectedRequestId) { "NIP-46 response ID does not match" }
        val error = response.optString("error").takeIf { it.isNotBlank() }
        return RpcResponse(
            id = expectedRequestId,
            result = response.optString("result").takeIf { it.isNotBlank() },
            error = error
        )
    }

    fun validateNostrConnectApproval(
        eventJson: JSONObject,
        keyPair: ClientKeyPair,
        expectedSecret: String
    ): NostrConnectApproval {
        val event = NostrReviewClient.NostrEvent.fromJson(eventJson)
            ?: throw IllegalArgumentException("Invalid signed nostrconnect response")
        require(event.kind == 24133) { "Unexpected nostrconnect response kind" }
        require(keyPair.publicKey in event.tagValues("p")) { "Response is not addressed to this client" }
        val conversationKey = NostrNip44.conversationKey(keyPair.privateKey, event.pubkey.hexToByteArray())
        val response = JSONObject(NostrNip44.decrypt(event.content, conversationKey))
        require(response.optString("result") == expectedSecret) { "Nostr connect secret did not match" }
        require(response.optString("error").isBlank()) { response.optString("error") }
        return NostrConnectApproval(event.pubkey, response.optString("id"))
    }

    private fun buildRequest(
        keyPair: ClientKeyPair,
        signerPubkey: String,
        method: String,
        params: List<String>,
        createdAt: Long
    ): RpcRequest {
        require(signerPubkey.isHexKey()) { "Invalid remote signer public key" }
        val requestId = ByteArray(16).also(secureRandom::nextBytes).toHex()
        val plaintext = JSONObject()
            .put("id", requestId)
            .put("method", method)
            .put("params", JSONArray(params))
            .toString()
        val conversationKey = NostrNip44.conversationKey(
            keyPair.privateKey,
            signerPubkey.hexToByteArray()
        )
        val content = NostrNip44.encrypt(plaintext, conversationKey)
        val tags = listOf(listOf("p", signerPubkey))
        val id = eventId(keyPair.publicKey, createdAt, 24133, tags, content)
        val signature = Secp256k1.signSchnorr(id, keyPair.privateKey, null).toHex()
        val event = JSONObject()
            .put("id", id.toHex())
            .put("pubkey", keyPair.publicKey)
            .put("created_at", createdAt)
            .put("kind", 24133)
            .put("tags", JSONArray().put(JSONArray().put("p").put(signerPubkey)))
            .put("content", content)
            .put("sig", signature)
        return RpcRequest(requestId, event)
    }

    private fun eventId(
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String
    ): ByteArray = nostrEventId(pubkey, createdAt, kind, tags, content)

    internal val paragraphReviewPermissions: String = buildList {
        add("get_public_key")
        paragraphReviewKinds.forEach { add("sign_event:$it") }
    }.joinToString(",")

    private fun String.isHexKey(): Boolean = length == 64 && all { it.digitToIntOrNull(16) != null }

    private fun String.hexToByteArray(): ByteArray {
        require(isHexKey()) { "Expected 32-byte hex public key" }
        return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun String.urlEncoded(): String = URLEncoder.encode(this, Charsets.UTF_8.name())
}