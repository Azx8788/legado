package io.legado.app.help.nostr

/**
 * 应用级待发布段评/回复：发布本身绑进程生命周期，弹窗关闭/退回正文后仍要继续在途或遗留草稿，
 * 故占位状态不挂在弹窗实例上。作用域为进程内存；失败项另有磁盘草稿兜底。
 */
internal object NostrPendingReviews {

    const val STATE_SENDING = "sending"
    const val STATE_DRAFT = "draft"

    data class Entry(
        val localId: String,
        val key: String,
        val text: String,
        val parentId: String? = null,
        val parentAuthor: String? = null,
        val rootId: String? = null,
        val rootAuthor: String? = null,
        val state: String = STATE_SENDING,
        val createdAtMs: Long = System.currentTimeMillis()
    )

    private val entries = LinkedHashMap<String, Entry>()

    fun keyOf(bookUrl: String, chapterIndex: Int, paragraphNum: Int): String =
        "$bookUrl|$chapterIndex|$paragraphNum"

    @Synchronized
    fun add(entry: Entry) {
        entries[entry.localId] = entry
    }

    @Synchronized
    fun get(localId: String): Entry? = entries[localId]

    @Synchronized
    fun list(key: String): List<Entry> =
        entries.values.filter { it.key == key }.sortedBy { it.createdAtMs }

    @Synchronized
    fun remove(localId: String) {
        entries.remove(localId)
    }

    @Synchronized
    fun markDraft(localId: String) {
        val entry = entries[localId] ?: return
        entries[localId] = entry.copy(state = STATE_DRAFT)
    }

    @Synchronized
    fun markSending(localId: String) {
        val entry = entries[localId] ?: return
        entries[localId] = entry.copy(state = STATE_SENDING)
    }
}
