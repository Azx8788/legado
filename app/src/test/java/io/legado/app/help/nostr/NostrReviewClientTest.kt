package io.legado.app.help.nostr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import java.util.Base64
import java.net.URLEncoder

class NostrReviewClientTest {

    @Test
    fun bookIdentifierIsStableAcrossWhitespaceAndCase() {
        val first = NostrReviewClient.bookIdentifier("  The   Book ", "Author")
        val second = NostrReviewClient.bookIdentifier("the book", " author ")

        assertEquals(first, second)
        assertTrue(first.startsWith("legado:book:"))
    }

    @Test
    fun bookIdentifierSeparatesDifferentAuthors() {
        val first = NostrReviewClient.bookIdentifier("The Book", "Author A")
        val second = NostrReviewClient.bookIdentifier("The Book", "Author B")

        assertNotEquals(first, second)
    }

    @Test
    fun paragraphMatchUsesContextWhenChapterReferenceDiffers() {
        val paragraphs = listOf(
            "A distinct preceding sentence establishes the first location.",
            "Repeated paragraph text appears in more than one chapter.",
            "An unmistakable following sentence closes the first context."
        )
        val excerpt = NostrReviewClient.NostrEvent(
            id = "event-id",
            pubkey = "pubkey",
            content = paragraphs[1],
            createdAt = 1,
            kind = 9802,
            tags = listOf(
                listOf("r", "https://other-source.example/chapter"),
                listOf("context", "sentence establishes the first location. An unmistakable following")
            )
        )

        val matches = NostrReviewClient.matchParagraphs(
            listOf(excerpt),
            paragraphs,
            "https://current-source.example/chapter"
        )

        assertTrue(matches.containsKey(2))
    }

    @Test
    fun paragraphMatchRejectsConflictingContextWithoutChapterReference() {
        val paragraphs = listOf(
            "A current chapter has neighbors that do not match this event.",
            "Repeated paragraph text appears in more than one chapter.",
            "The next paragraph in the current book is also different."
        )
        val excerpt = NostrReviewClient.NostrEvent(
            id = "event-id",
            pubkey = "pubkey",
            content = paragraphs[1],
            createdAt = 1,
            kind = 9802,
            tags = listOf(
                listOf("r", "https://other-source.example/chapter"),
                listOf("context", "unrelated words from another chapter's context")
            )
        )

        val matches = NostrReviewClient.matchParagraphs(
            listOf(excerpt),
            paragraphs,
            "https://current-source.example/chapter"
        )

        assertFalse(matches.containsKey(2))
    }

    @Test
    fun eventVerificationRejectsMismatchedEventIdBeforeSignatureCheck() {
        val validLengthHex = "00".repeat(32)
        val signatureHex = "00".repeat(64)

        val isValid = NostrReviewClient.verifyEvent(
            id = validLengthHex,
            pubkey = validLengthHex,
            createdAt = 1,
            kind = 9802,
            tags = emptyList(),
            content = "excerpt",
            signature = signatureHex
        )

        assertFalse(isValid)
    }

    @Test
    fun nip44V2MatchesOfficialConversationAndEncryptionVector() {
        val clientSecret = ("00".repeat(31) + "01").hexToBytes()
        val signerSecret = ("00".repeat(31) + "02").hexToBytes()
        val signerPublic = fr.acinq.secp256k1.Secp256k1.pubkeyCreate(signerSecret)
            .copyOfRange(1, 33)
        val expectedConversationKey =
            "c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d".hexToBytes()
        val nonce = ByteArray(32).also { it[31] = 1 }
        val expectedPayload =
            "AgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABee0G5VSK0/9YypIObAtDKfYEAjD35uVkHyB0F4DwrcNaCXlCWZKaArsGrY6M9wnuTMxWfp1RTN9Xga8no+kF5Vsb"

        val conversationKey = NostrNip44.conversationKey(clientSecret, signerPublic)

        assertEquals(expectedConversationKey.toList(), conversationKey.toList())
        assertEquals(
            expectedPayload,
            NostrNip44.encrypt("a".toByteArray(), conversationKey, nonce)
        )
        assertEquals("a", NostrNip44.decrypt(expectedPayload, conversationKey))
    }

    @Test
    fun nip44RejectsTamperedPayload() {
        val conversationKey = ByteArray(32)
        val payload = NostrNip44.encrypt("hello", conversationKey)
        val decoded = Base64.getDecoder().decode(payload)
        decoded[40] = (decoded[40].toInt() xor 1).toByte()

        val failure = runCatching {
            NostrNip44.decrypt(Base64.getEncoder().encodeToString(decoded), conversationKey)
        }

        assertTrue(failure.isFailure)
    }

    @Test
    fun bunkerUriParsesRepeatedRelaysAndSecret() {
        val signerPubkey = "ab".repeat(32)
        val firstRelay = URLEncoder.encode("wss://relay.example", Charsets.UTF_8.name())
        val secondRelay = URLEncoder.encode("wss://relay2.example", Charsets.UTF_8.name())
        val uri = "bunker://$signerPubkey?relay=$firstRelay&relay=$secondRelay&secret=single-use"

        val connection = NostrNip46.parseBunkerUri(uri)

        assertEquals(signerPubkey, connection.signerPubkey)
        assertEquals(listOf("wss://relay.example", "wss://relay2.example"), connection.relays)
        assertEquals("single-use", connection.secret)
    }

    @Test
    fun nip46PermissionsLimitSigningToParagraphReviewKinds() {
        assertEquals(
            "get_public_key,sign_event:9802,sign_event:1111,sign_event:7",
            NostrNip46.paragraphReviewPermissions
        )
    }

    @Test
    fun nostrConnectTokenRequestsOnlyParagraphReviewPermissions() {
        val clientSecret = ("00".repeat(31) + "01").hexToBytes()
        val clientPublic = fr.acinq.secp256k1.Secp256k1.pubkeyCreate(clientSecret)
            .copyOfRange(1, 33)
            .joinToString("") { "%02x".format(it) }

        val token = NostrNip46.createNostrConnectToken(
            NostrNip46.ClientKeyPair(clientSecret, clientPublic),
            listOf("wss://relay.example")
        )

        assertTrue(token.uri.startsWith("nostrconnect://$clientPublic?"))
        assertTrue(token.uri.contains("perms=get_public_key%2Csign_event%3A9802"))
        assertTrue(token.uri.contains("secret=${token.secret}"))
    }

    @Test
    fun nip46ConnectRequestIsSignedAndEncryptedForTheBunker() {
        val clientSecret = ("00".repeat(31) + "01").hexToBytes()
        val signerSecret = ("00".repeat(31) + "02").hexToBytes()
        val clientPublic = fr.acinq.secp256k1.Secp256k1.pubkeyCreate(clientSecret)
            .copyOfRange(1, 33)
        val signerPublic = fr.acinq.secp256k1.Secp256k1.pubkeyCreate(signerSecret)
            .copyOfRange(1, 33)
        val connection = NostrNip46.BunkerConnection(
            signerPubkey = signerPublic.joinToString("") { "%02x".format(it) },
            relays = listOf("wss://relay.example"),
            secret = "once"
        )

        val request = NostrNip46.buildConnectRequest(
            NostrNip46.ClientKeyPair(clientSecret, clientPublic.joinToString("") { "%02x".format(it) }),
            connection,
            createdAt = 100
        )
        val event = request.event
        val tags = listOf(listOf("p", connection.signerPubkey))
        assertTrue(
            NostrReviewClient.verifyEvent(
                id = event.getString("id"),
                pubkey = event.getString("pubkey"),
                createdAt = event.getLong("created_at"),
                kind = event.getInt("kind"),
                tags = tags,
                content = event.getString("content"),
                signature = event.getString("sig")
            )
        )

        val sharedKey = NostrNip44.conversationKey(clientSecret, signerPublic)
        val rpc = org.json.JSONObject(NostrNip44.decrypt(event.getString("content"), sharedKey))
        val params = rpc.getJSONArray("params")
        assertEquals("connect", rpc.getString("method"))
        assertEquals(connection.signerPubkey, params.getString(0))
        assertEquals("once", params.getString(1))
        assertEquals(NostrNip46.paragraphReviewPermissions, params.getString(2))
    }

    @Test
    fun nip46SignEventRequestRejectsKindsOutsideParagraphReviews() {
        val keyPair = NostrNip46.ClientKeyPair(ByteArray(32), "00".repeat(32))
        val unsignedEvent = org.json.JSONObject().put("kind", 1)

        val failure = runCatching {
            NostrNip46.buildSignEventRequest(keyPair, "11".repeat(32), unsignedEvent)
        }

        assertTrue(failure.isFailure)
    }

    @Test
    fun canonicalEventSerializationKeepsForwardSlashes() {
        val pubkey = "82341f882b6eabcd2ba7f1ef90aad961cf074af15b9ef44a09f9d2a8fbfbe6a2"
        val tagPubkey = "0000000000000000000000000000000000000000000000000000000000000001"
        val tags = listOf(listOf("p", tagPubkey))
        val content = "a/b+c="

        // 规范串里 "/" 必须原样保留（org.json 会写成 "\/"，那样算出的 id 与中继不一致）。
        assertEquals(
            "[0,\"$pubkey\",1672327989,24133,[[\"p\",\"$tagPubkey\"]],\"$content\"]",
            canonicalEventJson(pubkey, 1672327989, 24133, tags, content)
        )
        // 期望值由外部按 NIP-01 规范（保留 "/"）对上述字节算 SHA-256 得到，非本代码自洽产物。
        assertEquals(
            "07c301306673fb68f4c95e92d4e648532529d15e5f08647c2c6935577fc3903c",
            nostrEventId(pubkey, 1672327989, 24133, tags, content)
                .joinToString("") { "%02x".format(it) }
        )
    }

    private fun String.hexToBytes(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}