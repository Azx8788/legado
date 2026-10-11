package io.legado.app.help.nostr

import io.legado.app.constant.AppLog
import io.legado.app.data.entities.Book
import fr.acinq.secp256k1.Secp256k1
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

object NostrReviewClient {

    private fun relays(): List<String> = NostrRelays.forReading()

    data class Summary(val counts: Map<Int, Int>)

    data class Comment(
        val id: String,
        val name: String,
        val authorPubkey: String,
        val rootExcerptId: String,
        val rootExcerptPubkey: String,
        val content: String,
        val time: String,
        val likeCount: Int,
        val replies: List<Comment>
    )

    data class Details(
        val items: List<Comment>,
        val hasMore: Boolean
    )

    suspend fun loadSummary(
        book: Book,
        chapterUrl: String,
        paragraphs: List<String>,
        allowCreatingComments: Boolean = false
    ): Summary {
        if (paragraphs.isEmpty()) return Summary(emptyMap())
        val bookTag = bookIdentifier(book.name, book.author)
        // 中继全部不可用时降级为「暂无摘录」:已登录用户仍能看到可首评的占位图标,
        // 而不是因为查询异常整章都没有段评入口(发布时的中继错误会单独提示)。
        val excerpts = runCatching {
            queryEvents(
                JSONObject()
                    .put("kinds", JSONArray().put(9802))
                    .put("#i", JSONArray().put(bookTag))
                    .put("limit", 1000)
            )
        }.onFailure {
            if (it is CancellationException) throw it
            AppLog.put("查询 Nostr 段落摘录失败\n${it.localizedMessage}", it, tag = "Nostr")
        }.getOrDefault(emptyList())
        val matched = matchParagraphs(excerpts, paragraphs, chapterUrl)
        val excerptIds = matched.values.flatten().map { it.id }.distinct()
        if (excerptIds.isEmpty()) {
            val writableParagraphs = if (allowCreatingComments) {
                paragraphs.mapIndexedNotNull { index, paragraph ->
                    if (paragraph.isNotBlank()) index + 1 to -1 else null
                }.toMap()
            } else {
                emptyMap()
            }
            return Summary(writableParagraphs)
        }

        val comments = queryComments(excerptIds)
        val commentCounts = comments.groupingBy { it.rootId }.eachCount()
        val counts = matched.mapNotNull { (paragraphIndex, events) ->
            val count = events.sumOf { commentCounts[it.id] ?: 0 }
            when {
                count > 0 -> paragraphIndex to count
                allowCreatingComments -> paragraphIndex to -1
                else -> null
            }
        }.toMap()
        if (!allowCreatingComments) return Summary(counts)
        val visibleCounts = counts.toMutableMap()
        paragraphs.forEachIndexed { index, paragraph ->
            if (paragraph.isNotBlank()) visibleCounts.putIfAbsent(index + 1, -1)
        }
        return Summary(visibleCounts)
    }

    suspend fun loadDetails(
        book: Book,
        chapterUrl: String,
        paragraphs: List<String>,
        paragraphIndex: Int,
        page: Int
    ): Details {
        if (paragraphs.getOrNull(paragraphIndex - 1).isNullOrBlank()) {
            return Details(emptyList(), false)
        }
        val excerpts = queryEvents(
            JSONObject()
                .put("kinds", JSONArray().put(9802))
                .put("#i", JSONArray().put(bookIdentifier(book.name, book.author)))
                .put("limit", 1000)
        )
        val matching = matchParagraphs(excerpts, paragraphs, chapterUrl)[paragraphIndex].orEmpty()
        if (matching.isEmpty()) return Details(emptyList(), false)
        val excerptIds = matching.map { it.id }.distinct()
        val comments = queryComments(excerptIds)
        val reactions = queryReactions(comments.map { it.id })
        val likeCounts = reactions.filter { it.content == "+" }
            .groupBy { it.tagValues("e").firstOrNull().orEmpty() }
            .mapValues { (_, events) -> events.distinctBy { it.pubkey }.size }
        val commentsByExcerpt = comments.groupBy { it.rootId }
        val excerptAuthors = matching.associate { it.id to it.pubkey }
        val roots = excerptIds.flatMap { excerptId ->
            buildThread(
                commentsByExcerpt[excerptId].orEmpty(),
                excerptId,
                excerptAuthors[excerptId].orEmpty()
            )
        }.sortedByDescending { it.createdAt }
        val pageSize = 20
        val pageItems = roots.drop((page - 1).coerceAtLeast(0) * pageSize).take(pageSize)
        return Details(
            items = pageItems.map { it.toComment(likeCounts) },
            hasMore = roots.size > page.coerceAtLeast(1) * pageSize
        )
    }

    fun bookIdentifier(name: String, author: String): String {
        val normalizedName = normalize(name).lowercase(Locale.ROOT)
        val normalizedAuthor = normalize(author).lowercase(Locale.ROOT)
        return "legado:book:${sha256("v1|$normalizedName|$normalizedAuthor")}" 
    }

    internal fun matchParagraphs(
        excerpts: List<NostrEvent>,
        paragraphs: List<String>,
        chapterUrl: String
    ): Map<Int, List<NostrEvent>> {
        val normalizedParagraphs = paragraphs.map(::normalize)
        val indexesByText = normalizedParagraphs.mapIndexedNotNull { index, paragraph ->
            paragraph.takeIf { it.isNotEmpty() }?.let { it to index }
        }.groupBy({ it.first }, { it.second })
        val indexesByHash = normalizedParagraphs.mapIndexedNotNull { index, paragraph ->
            paragraph.takeIf { it.isNotEmpty() }?.let { sha256(it) to index }
        }.groupBy({ it.first }, { it.second })
        val matches = mutableMapOf<Int, MutableList<NostrEvent>>()
        excerpts.forEach { event ->
            val content = normalize(event.content)
            val candidateIndexes = linkedSetOf<Int>()
            candidateIndexes.addAll(indexesByText[content].orEmpty())
            val contentHash = content.removePrefix("sha256:")
            candidateIndexes.addAll(indexesByHash[contentHash].orEmpty())
            event.tagValues("t").forEach { tag ->
                val paragraphHash = tag.removePrefix("legado:paragraph:")
                candidateIndexes.addAll(indexesByHash[paragraphHash].orEmpty())
            }
            val candidates = candidateIndexes.toList()
            val context = normalize(event.tagValues("context").firstOrNull().orEmpty())
            val referenceScore = if (chapterUrl in event.tagValues("r")) 4 else 0
            val contextScore: (Int) -> Int = { index ->
                var score = 0
                normalizedParagraphs.getOrNull(index - 1)?.takeLast(24)
                    ?.takeIf { it.length >= 8 && context.contains(it) }?.let { score++ }
                normalizedParagraphs.getOrNull(index + 1)?.take(24)
                    ?.takeIf { it.length >= 8 && context.contains(it) }?.let { score++ }
                score
            }
            val selected = if (candidates.size > 1) {
                val scores = candidates.associateWith { index ->
                    referenceScore + contextScore(index)
                }
                val bestScore = scores.values.maxOrNull() ?: 0
                val best = scores.filterValues { it == bestScore }.keys
                if (bestScore > 0 && best.size == 1) best else candidates
            } else if (candidates.size == 1 && context.isNotEmpty() &&
                referenceScore == 0 && contextScore(candidates.single()) == 0
            ) {
                emptyList()
            } else {
                candidates
            }
            selected.forEach { index ->
                matches.getOrPut(index + 1) { mutableListOf() }.add(event)
            }
        }
        return matches
    }

    private suspend fun queryComments(excerptIds: List<String>): List<NostrEvent> {
        return excerptIds.distinct().chunked(50).flatMap { batch ->
            queryEvents(
                JSONObject()
                    .put("kinds", JSONArray().put(1111))
                    .put("#E", JSONArray(batch))
                    .put("limit", 1000)
            )
        }.distinctBy { it.id }
    }

    private suspend fun queryReactions(commentIds: List<String>): List<NostrEvent> {
        return commentIds.distinct().chunked(50).flatMap { batch ->
            queryEvents(
                JSONObject()
                    .put("kinds", JSONArray().put(7))
                    .put("#e", JSONArray(batch))
                    .put("limit", 1000)
            )
        }.distinctBy { it.id }
    }

    private suspend fun queryEvents(filter: JSONObject): List<NostrEvent> = coroutineScope {
        val activeRelays = relays()
        val expectedKinds = filter.optJSONArray("kinds")?.let { kinds ->
            (0 until kinds.length()).map { kinds.optInt(it) }.toSet()
        }.orEmpty()
        val results = activeRelays.map { relay ->
            async(Dispatchers.IO) {
                runCatching { queryRelay(relay, filter) }
            }
        }.awaitAll()
        val successful = results.filter { it.isSuccess }
        if (successful.isEmpty()) {
            throw results.firstOrNull()?.exceptionOrNull() ?: IllegalStateException("Nostr relay unavailable")
        }
        successful.flatMap { it.getOrThrow() }
            .filter { expectedKinds.isEmpty() || it.kind in expectedKinds }
            .distinctBy { it.id }
    }

    private suspend fun queryRelay(relay: String, filter: JSONObject): List<NostrEvent> =
        withTimeout(10_000) {
            suspendCancellableCoroutine { continuation ->
                val subscriptionId = UUID.randomUUID().toString().replace("-", "")
                val events = mutableListOf<NostrEvent>()
                val finished = AtomicBoolean(false)
                lateinit var socket: WebSocket
                socket = nostrWebSocketClient.newWebSocket(
                    Request.Builder().url(relay).build(),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(
                                JSONArray().put("REQ").put(subscriptionId).put(filter).toString()
                            )
                        }

                        override fun onMessage(webSocket: WebSocket, text: String) {
                            runCatching {
                                val message = JSONArray(text)
                                when (message.optString(0)) {
                                    "EVENT" -> if (message.optString(1) == subscriptionId) {
                                        if (!finished.get()) {
                                            message.optJSONObject(2)?.let(NostrEvent::fromJson)?.let(events::add)
                                        }
                                    }

                                    "EOSE" -> if (message.optString(1) == subscriptionId &&
                                        finished.compareAndSet(false, true)
                                    ) {
                                        webSocket.send(JSONArray().put("CLOSE").put(subscriptionId).toString())
                                        webSocket.close(1000, "query complete")
                                        continuation.resume(events.toList())
                                    }

                                    "NOTICE" -> if (message.optString(1).contains("error", true) &&
                                        finished.compareAndSet(false, true)
                                    ) {
                                        webSocket.close(1000, "relay notice")
                                        continuation.resumeWithException(
                                            IllegalStateException(message.optString(1))
                                        )
                                    }
                                }
                            }
                        }

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            if (finished.compareAndSet(false, true)) {
                                continuation.resumeWithException(t)
                            }
                        }
                    }
                )
                continuation.invokeOnCancellation {
                    if (finished.compareAndSet(false, true)) socket.cancel()
                }
            }
        }

    private fun buildThread(
        events: List<NostrEvent>,
        excerptId: String,
        excerptAuthor: String
    ): List<NostrEvent> {
        val byParent = events.groupBy { event ->
            event.tagValues("e").lastOrNull() ?: event.rootId
        }
        val eventIds = events.mapTo(HashSet()) { it.id }
        fun children(parentId: String, depth: Int): List<NostrEvent> {
            if (depth >= 5) return emptyList()
            return byParent[parentId].orEmpty()
                .sortedBy { it.createdAt }
                .map {
                    it.copy(
                        children = children(it.id, depth + 1),
                        rootExcerptPubkey = excerptAuthor
                    )
                }
        }
        return children(excerptId, 0).filter { it.id in eventIds }
    }

    private fun NostrEvent.toComment(likeCounts: Map<String, Int>): Comment = Comment(
        id = id,
        name = pubkey.take(12).ifBlank { "Nostr" },
        authorPubkey = pubkey,
        rootExcerptId = rootId,
        rootExcerptPubkey = rootExcerptPubkey ?: tagValues("P").firstOrNull().orEmpty(),
        content = content,
        time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(createdAt * 1000)),
        likeCount = likeCounts[id] ?: 0,
        replies = children.map { it.toComment(likeCounts) }
    )

    private fun normalize(value: String): String = value
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    internal fun verifyEvent(
        id: String,
        pubkey: String,
        createdAt: Long,
        kind: Int,
        tags: List<List<String>>,
        content: String,
        signature: String
    ): Boolean = runCatching {
        val canonicalTags = JSONArray().apply {
            tags.forEach { tag -> put(JSONArray(tag)) }
        }
        val serialized = JSONArray()
            .put(0)
            .put(pubkey)
            .put(createdAt)
            .put(kind)
            .put(canonicalTags)
            .put(content)
            .toString()
        val calculatedId = MessageDigest.getInstance("SHA-256")
            .digest(serialized.toByteArray(Charsets.UTF_8))
        val suppliedId = id.hexToByteArray() ?: return false
        if (!MessageDigest.isEqual(calculatedId, suppliedId)) return false
        val signatureBytes = signature.hexToByteArray() ?: return false
        val publicKeyBytes = pubkey.hexToByteArray() ?: return false
        if (signatureBytes.size != 64 || publicKeyBytes.size != 32) return false
        Secp256k1.verifySchnorr(
            signatureBytes,
            calculatedId,
            publicKeyBytes
        )
    }.getOrDefault(false)

    private fun String.hexToByteArray(): ByteArray? {
        if (length % 2 != 0 || any { it.digitToIntOrNull(16) == null }) return null
        return ByteArray(length / 2) { index ->
            substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    internal data class NostrEvent(
        val id: String,
        val pubkey: String,
        val content: String,
        val createdAt: Long,
        val kind: Int,
        val tags: List<List<String>>,
        val children: List<NostrEvent> = emptyList(),
        val rootExcerptPubkey: String? = null
    ) {
        val rootId: String get() = tagValues("E").firstOrNull().orEmpty()

        fun tagValues(name: String): List<String> = tags.mapNotNull { tag ->
            tag.getOrNull(1)?.takeIf { tag.firstOrNull() == name }
        }

        companion object {
            fun fromJson(json: JSONObject): NostrEvent? {
                val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
                val pubkey = json.optString("pubkey").takeIf { it.isNotBlank() } ?: return null
                val createdAt = json.optLong("created_at", -1).takeIf { it >= 0 } ?: return null
                val kind = json.optInt("kind", -1)
                    .takeIf { it == 7 || it == 9802 || it == 1111 || it == 24133 } ?: return null
                val content = json.optString("content")
                val signature = json.optString("sig").takeIf { it.isNotBlank() } ?: return null
                val tags = json.optJSONArray("tags") ?: JSONArray()
                val parsedTags = (0 until tags.length()).mapNotNull { index ->
                    val tag = tags.optJSONArray(index) ?: return@mapNotNull null
                    (0 until tag.length()).map { tag.optString(it) }
                }
                if (id.hexToByteArray()?.size != 32 || pubkey.hexToByteArray()?.size != 32 ||
                    signature.hexToByteArray()?.size != 64
                ) return null
                if (!verifyEvent(id, pubkey, createdAt, kind, parsedTags, content, signature)) return null
                return NostrEvent(id, pubkey, content, createdAt, kind, parsedTags)
            }
        }
    }
}