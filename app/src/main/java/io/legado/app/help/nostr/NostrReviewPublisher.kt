package io.legado.app.help.nostr

import io.legado.app.constant.AppConst
import io.legado.app.data.entities.Book
import org.json.JSONArray
import org.json.JSONObject

object NostrReviewPublisher {

    suspend fun postParagraphComment(
        book: Book,
        chapterTitle: String,
        paragraphs: List<String>,
        paragraphIndex: Int,
        comment: String
    ) {
        val text = comment.trim()
        require(text.isNotEmpty()) { "Comment cannot be empty" }
        val paragraph = paragraphs.getOrNull(paragraphIndex - 1)
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("Paragraph is unavailable")
        val session = NostrSignerStore.load()
            ?: throw IllegalStateException("Connect a Nostr remote signer before posting")
        val keyPair = NostrNip46.ClientKeyPair(session.clientPrivateKey, session.clientPublicKey)
        val createdAt = System.currentTimeMillis() / 1000
        val bookIdentifier = NostrReviewClient.bookIdentifierV2(book.name, book.author)
        val title = NostrReviewClient.normalizeForIdentity(chapterTitle)
        val previous = paragraphs.getOrNull(paragraphIndex - 2).orEmpty().takeLast(80)
        val next = paragraphs.getOrNull(paragraphIndex).orEmpty().take(80)
        val context = listOf(title, previous, next)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        val excerpt = JSONObject()
            .put("kind", 9802)
            .put("content", paragraph)
            .put("created_at", createdAt)
            .put("tags", excerptTags(bookIdentifier, context))
        val signedExcerpt = signAndPublish(session, keyPair, excerpt)
        val excerptId = signedExcerpt.getString("id")
        val commentEvent = JSONObject()
            .put("kind", 1111)
            .put("content", text)
            .put("created_at", createdAt + 1)
            .put("tags", commentTags(bookIdentifier, excerptId, session.userPubkey))
        signAndPublish(session, keyPair, commentEvent)
    }

    suspend fun replyToComment(
        book: Book,
        rootExcerptId: String,
        rootExcerptPubkey: String,
        parentCommentId: String,
        parentAuthorPubkey: String,
        content: String
    ) {
        val text = content.trim()
        require(text.isNotEmpty()) { "Comment cannot be empty" }
        val session = NostrSignerStore.load()
            ?: throw IllegalStateException("Connect a Nostr remote signer before replying")
        val createdAt = System.currentTimeMillis() / 1000
        signAndPublish(
            session,
            NostrNip46.ClientKeyPair(session.clientPrivateKey, session.clientPublicKey),
            JSONObject()
                .put("kind", 1111)
                .put("content", text)
                .put("created_at", createdAt)
                .put(
                    "tags",
                    replyTags(
                        bookIdentifier = NostrReviewClient.bookIdentifierV2(book.name, book.author),
                        rootExcerptId = rootExcerptId,
                        rootExcerptPubkey = rootExcerptPubkey,
                        parentCommentId = parentCommentId,
                        parentAuthorPubkey = parentAuthorPubkey
                    )
                )
        )
    }

    suspend fun reactToComment(commentId: String, authorPubkey: String): String {
        val session = NostrSignerStore.load()
            ?: throw IllegalStateException("Connect a Nostr remote signer before reacting")
        val signed = signAndPublish(
            session,
            NostrNip46.ClientKeyPair(session.clientPrivateKey, session.clientPublicKey),
            JSONObject()
                .put("kind", 7)
                .put("content", "+")
                .put("created_at", System.currentTimeMillis() / 1000)
                .put(
                    "tags", reactionTags(commentId, authorPubkey)
                )
        )
        return signed.getString("id")
    }

    /** 取消点赞：按 NIP-09 发 kind 5 删除自己那条 reaction。 */
    suspend fun deleteReaction(reactionEventId: String) {
        require(reactionEventId.isNotBlank()) { "Reaction event id is required" }
        val session = NostrSignerStore.load()
            ?: throw IllegalStateException("Connect a Nostr remote signer before reacting")
        signAndPublish(
            session,
            NostrNip46.ClientKeyPair(session.clientPrivateKey, session.clientPublicKey),
            JSONObject()
                .put("kind", 5)
                .put("content", "")
                .put("created_at", System.currentTimeMillis() / 1000)
                .put("tags", JSONArray().put(JSONArray().put("e").put(reactionEventId)))
        )
    }

    /** NIP-89 客户端标记；名字取项目现成常量 AppConst.APP_TAG，不写字面量。 */
    private fun clientTag(): JSONArray = JSONArray().put("client").put(AppConst.APP_TAG)

    internal fun excerptTags(
        bookIdentifier: String,
        context: String
    ): JSONArray = JSONArray()
        .put(JSONArray().put("i").put(bookIdentifier))
        .put(JSONArray().put("k").put(NostrReviewClient.BOOK_KIND))
        .put(JSONArray().put("context").put(context))
        .put(clientTag())

    internal fun commentTags(
        bookIdentifier: String,
        excerptId: String,
        userPubkey: String
    ): JSONArray = JSONArray()
        .put(JSONArray().put("I").put(bookIdentifier))
        .put(JSONArray().put("E").put(excerptId))
        .put(JSONArray().put("K").put("9802"))
        .put(JSONArray().put("P").put(userPubkey))
        .put(JSONArray().put("e").put(excerptId))
        .put(JSONArray().put("k").put("9802"))
        .put(JSONArray().put("p").put(userPubkey))
        .put(clientTag())

    internal fun replyTags(
        bookIdentifier: String,
        rootExcerptId: String,
        rootExcerptPubkey: String,
        parentCommentId: String,
        parentAuthorPubkey: String
    ): JSONArray = JSONArray()
        .put(JSONArray().put("I").put(bookIdentifier))
        .put(JSONArray().put("E").put(rootExcerptId))
        .put(JSONArray().put("K").put("9802"))
        .put(JSONArray().put("P").put(rootExcerptPubkey))
        .put(JSONArray().put("e").put(parentCommentId))
        .put(JSONArray().put("k").put("1111"))
        .put(JSONArray().put("p").put(parentAuthorPubkey))
        .put(clientTag())

    internal fun reactionTags(commentId: String, authorPubkey: String): JSONArray = JSONArray()
        .put(JSONArray().put("e").put(commentId))
        .put(JSONArray().put("p").put(authorPubkey))
        .put(clientTag())

    private suspend fun signAndPublish(
        session: NostrSignerStore.Session,
        keyPair: NostrNip46.ClientKeyPair,
        unsignedEvent: JSONObject
    ): JSONObject {
        val connection = NostrNip46.BunkerConnection(
            signerPubkey = session.signerPubkey,
            relays = session.relays,
            secret = null
        )
        val response = NostrNip46Transport.request(
            connection,
            keyPair,
            NostrNip46.buildSignEventRequest(keyPair, session.signerPubkey, unsignedEvent)
        )
        if (response.error != null) throw IllegalStateException(response.error)
        val signed = JSONObject(response.result ?: error("Signer returned an empty signed event"))
        val signedTags = signed.optJSONArray("tags") ?: JSONArray()
        val parsedTags = (0 until signedTags.length()).map { index ->
            val tag = signedTags.getJSONArray(index)
            (0 until tag.length()).map { tag.getString(it) }
        }
        val kind = unsignedEvent.getInt("kind")
        require(signed.optInt("kind", -1) == kind) { "Signer changed the requested event kind" }
        require(signed.optString("pubkey") == session.userPubkey) { "Signer returned an unexpected public key" }
        require(signed.optString("content") == unsignedEvent.getString("content")) {
            "Signer changed the requested event content"
        }
        require(parsedTags == jsonTagsToLists(unsignedEvent.getJSONArray("tags"))) {
            "Signer changed the requested event tags"
        }
        require(
            NostrReviewClient.verifyEvent(
                id = signed.getString("id"),
                pubkey = signed.getString("pubkey"),
                createdAt = signed.getLong("created_at"),
                kind = kind,
                tags = parsedTags,
                content = signed.getString("content"),
                signature = signed.getString("sig")
            )
        ) { "Signer returned an invalid Nostr signature" }
        NostrNip46Transport.publish(session.relays, signed)
        return signed
    }

    private fun jsonTagsToLists(tags: JSONArray): List<List<String>> =
        (0 until tags.length()).map { index ->
            val tag = tags.getJSONArray(index)
            (0 until tag.length()).map { tag.getString(it) }
        }
}
