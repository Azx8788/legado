package io.legado.app.ui.book.read

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.appcompat.widget.AppCompatImageButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.flexbox.FlexboxLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.base.adapter.RecyclerAdapter
import io.legado.app.data.appDb
import io.legado.app.data.entities.rule.ReviewRule
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.exoplayer.ExoPlayerHelper
import io.legado.app.help.glide.ImageLoader
import io.legado.app.help.config.AppConfig
import io.legado.app.help.nostr.NostrProfileStore
import io.legado.app.help.nostr.NostrReviewClient
import io.legado.app.help.nostr.NostrReviewDetailCache
import io.legado.app.help.nostr.NostrReviewPublisher
import io.legado.app.help.nostr.NostrSigners
import io.legado.app.help.nostr.ReviewDetailItem
import io.legado.app.model.ReadBook
import io.legado.app.model.analyzeRule.AnalyzeRule
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setChapter
import io.legado.app.model.analyzeRule.AnalyzeRule.Companion.setCoroutineContext
import io.legado.app.model.analyzeRule.AnalyzeUrl
import io.legado.app.model.jsSource.JsSourceReview
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.ACache
import io.legado.app.utils.dpToPx
import io.legado.app.utils.gone
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.isDataUrl
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.postEvent
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.visible
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.legado.app.utils.windowSize
import io.legado.app.databinding.DialogRecyclerViewBinding
import io.legado.app.databinding.ItemReviewCommentBinding
import io.legado.app.ui.book.read.page.provider.ChapterProvider
import io.legado.app.ui.nostr.NostrSignerDialogController
import io.legado.app.ui.widget.dialog.PhotoDialog
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Dispatchers.Main
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.roundToInt
import splitties.systemservices.windowManager

class ReviewDetailDialog() : BaseDialogFragment(R.layout.dialog_recycler_view) {

    /** 贴底弹窗自设背景(bg_dialog_round_top/bg_review_toolbar),豁免统一圆角模板 */
    override val dialogForm = DialogForm.SELF_MANAGED

    constructor(paragraphNum: Int, totalCount: Int) : this() {
        arguments = Bundle().apply {
            putInt("paragraphNum", paragraphNum)
            putInt("totalCount", totalCount)
        }
    }

    private val binding by viewBinding(DialogRecyclerViewBinding::bind)
    private val adapter by lazy { ReviewAdapter(requireContext()) }
    private var paragraphNum: Int = 0
    private var totalCount: Int = 0
    private var isLoading = false
    private var hasMore = true
    private var currentPage = 1
    private var nextPageUrl: String? = null
    private val mainItemIndexByKey = LinkedHashMap<String, Int>()
    private val detailItems = ArrayList<ReviewDetailItem>()
    /** 本地占位项的发布参数，用于草稿态直接重发；成功移除占位时清理。 */
    private val localPendingPublish = HashMap<String, PublishArgs>()

    /** NIP-55：本机签名器用 get_public_key 回包。段评弹窗内也能直接发起本机签名器连接。 */
    private val nostrExternalSignerConnect =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            nostrSignerDialog.onNip55Result(result.resultCode, result.data)
        }

    private val nostrSignerDialog by lazy {
        NostrSignerDialogController(this, nostrExternalSignerConnect)
    }

    /** 未登录时点击评论/回复/点赞暂存的待执行动作，连接成功后接续执行一次。 */
    private var pendingSignerAction: (() -> Unit)? = null

    private val expandedReplyParentKeys = HashSet<String>()
    private var reviewAudioPlayer: ExoPlayer? = null
    private var currentAudioUrl: String? = null
    private var isAudioPreparing = false
    private var dragStartRawY = 0f
    private var dragStartHeightPx = 0
    private var isDraggingHeight = false
    private val audioPlayerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            val previousAudioUrl = currentAudioUrl
            when (playbackState) {
                Player.STATE_BUFFERING -> isAudioPreparing = true
                Player.STATE_READY -> isAudioPreparing = false
                Player.STATE_ENDED -> {
                    isAudioPreparing = false
                    currentAudioUrl = null
                }
            }
            refreshAudioPlaybackUi(previousAudioUrl, currentAudioUrl)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            refreshAudioPlaybackUi(currentAudioUrl, currentAudioUrl)
        }

        override fun onPlayerError(error: PlaybackException) {
            val previousAudioUrl = currentAudioUrl
            isAudioPreparing = false
            currentAudioUrl = null
            AppLog.put("段评语音播放失败\n${error.localizedMessage}", error)
            context?.toastOnUi(error.localizedMessage ?: getString(R.string.load_over_time))
            refreshAudioPlaybackUi(previousAudioUrl, currentAudioUrl)
        }
    }
    private val uiItemDiffCallback = object : DiffUtil.ItemCallback<ReviewUiItem>() {
        override fun areItemsTheSame(oldItem: ReviewUiItem, newItem: ReviewUiItem): Boolean {
            if (oldItem.itemType != newItem.itemType) return false
            if (oldItem.itemType == TYPE_MORE) {
                return oldItem.parentKey == newItem.parentKey
            }
            val oldId = oldItem.id?.takeIf { it.isNotBlank() }
            val newId = newItem.id?.takeIf { it.isNotBlank() }
            if (oldId != null || newId != null) {
                return oldId == newId && oldItem.isReply == newItem.isReply
            }
            return oldItem.isReply == newItem.isReply &&
                    oldItem.parentKey == newItem.parentKey &&
                    oldItem.name == newItem.name &&
                    oldItem.content == newItem.content &&
                    oldItem.time == newItem.time &&
                    oldItem.avatar == newItem.avatar &&
                    oldItem.imageUrl == newItem.imageUrl &&
                    oldItem.audioUrl == newItem.audioUrl
        }

        override fun areContentsTheSame(oldItem: ReviewUiItem, newItem: ReviewUiItem): Boolean {
            return oldItem == newItem
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.run {
            setBackgroundDrawableResource(R.color.transparent)
            decorView.setPadding(0, 0, 0, 0)
            val attr = attributes
            attr.dimAmount = 0.16f
            attr.gravity = Gravity.BOTTOM
            attributes = attr
        }
        applyDialogHeightRatio(lastHeightRatio)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        paragraphNum = arguments?.getInt("paragraphNum") ?: 0
        totalCount = arguments?.getInt("totalCount") ?: 0
        binding.root.setBackgroundResource(R.drawable.bg_dialog_round_top)
        binding.dragHandle.visible()
        binding.toolBar.setBackgroundResource(R.drawable.bg_review_toolbar)
        binding.toolBar.updateLayoutParams<ViewGroup.LayoutParams> {
            height = 42.dpToPx()
        }
        binding.toolBar.minimumHeight = 0
        binding.toolBar.setPadding(0, 0, 0, 0)
        binding.toolBar.setContentInsetsRelative(6.dpToPx(), 6.dpToPx())
        binding.toolBar.title = ""
        binding.toolBar.subtitle = null
        val oldCountView = binding.toolBar.findViewWithTag<View>("review_count_tag")
        if (oldCountView != null) {
            binding.toolBar.removeView(oldCountView)
        }
        if (totalCount > 0) {
            val countView = TextView(requireContext()).apply {
                tag = "review_count_tag"
                text = getString(R.string.review_total_count, totalCount)
                setTextColor(getCompatColor(R.color.secondaryText))
                textSize = 14f
                includeFontPadding = false
                gravity = Gravity.CENTER_VERTICAL
                maxLines = 1
            }
            val lp = androidx.appcompat.widget.Toolbar.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL
            ).apply {
                marginEnd = 8.dpToPx()
            }
            binding.toolBar.addView(countView, lp)
        }
        addNostrCommentAction()
        binding.toolBar.setNavigationIcon(R.drawable.ic_baseline_close)
        binding.toolBar.navigationIcon?.setTint(getCompatColor(R.color.secondaryText))
        binding.toolBar.setNavigationOnClickListener { dismiss() }
        setupDragHandle()
        val layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.layoutManager = layoutManager
        binding.recyclerView.adapter = adapter
        binding.recyclerView.itemAnimator = null
        binding.recyclerView.setFastScrollEnabled(false)
        binding.recyclerView.setTrackVisible(false)
        binding.recyclerView.setBubbleVisible(false)
        binding.recyclerView.addOnScrollListener(object : androidx.recyclerview.widget.RecyclerView.OnScrollListener() {
            override fun onScrolled(
                recyclerView: androidx.recyclerview.widget.RecyclerView,
                dx: Int,
                dy: Int
            ) {
                if (dy <= 0) return
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (hasMore && !isLoading && lastVisible >= adapter.itemCount - 3) {
                    loadDetailPage(paragraphNum, currentPage + 1, append = true)
                }
            }
        })
        loadDetailPage(paragraphNum, 1, append = false)
    }

    private fun addNostrCommentAction() {
        if (!AppConfig.nostrReviewEnabled) return
        binding.toolBar.findViewWithTag<View>("nostr_comment_action")?.let(binding.toolBar::removeView)
        binding.toolBar.findViewWithTag<View>("review_count_tag")?.let {
            (it.layoutParams as? androidx.appcompat.widget.Toolbar.LayoutParams)?.marginEnd = 48.dpToPx()
        }
        val action = AppCompatImageButton(requireContext()).apply {
            tag = "nostr_comment_action"
            setImageResource(R.drawable.ic_edit)
            imageTintList = android.content.res.ColorStateList.valueOf(
                requireContext().getCompatColor(R.color.secondaryText)
            )
            contentDescription = getString(R.string.nostr_comment_write)
            setBackgroundResource(android.R.drawable.list_selector_background)
            setOnClickListener { onWriteCommentAction() }
        }
        val layoutParams = androidx.appcompat.widget.Toolbar.LayoutParams(
            42.dpToPx(),
            42.dpToPx(),
            Gravity.END or Gravity.CENTER_VERTICAL
        )
        binding.toolBar.addView(action, layoutParams)
    }

    /** 发布失败的草稿：按 书|章节|段落|(回复父评论) 存，成功后清除。 */
    private val reviewDraftCache by lazy { ACache.get("nostr_review_drafts") }

    private fun reviewDraftKey(kind: String, extra: String = ""): String? {
        val book = ReadBook.book ?: return null
        return "$kind|${book.bookUrl}|${ReadBook.durChapterIndex}|$paragraphNum|$extra"
    }

    private fun readReviewDraft(key: String?): String? =
        key?.let { reviewDraftCache.getAsString(it) }?.takeIf { it.isNotBlank() }

    private fun saveReviewDraft(key: String?, text: String) {
        if (key == null || text.isBlank()) return
        reviewDraftCache.put(key, text, REVIEW_DRAFT_TTL_SECONDS)
    }

    private fun clearReviewDraft(key: String?) {
        if (key == null) return
        reviewDraftCache.remove(key)
    }

    /**
     * 发布在应用级作用域完成后回调，可能晚于弹窗关闭/视图销毁：此时 binding、detailItems、
     * renderUiItems、toast 都不可安全触碰。统一切回主线程并复检存活状态后再执行 UI 更新。
     */
    private fun runOnUiIfAlive(block: () -> Unit) {
        val root = view ?: return
        root.post {
            if (isAdded && view != null) block()
        }
    }

    /**
     * 乐观插入一条本地占位评论：首评加到列表顶部，回复挂到父评论 replies 末尾。
     * 返回占位 id，发布成功后据此移除、失败后据此转草稿态。
     */
    private fun insertLocalPendingItem(text: String, replyTo: ReviewDetailItem?): String {
        val pubkey = NostrSigners.currentPubkey()?.takeIf { it.isNotBlank() }
        val profile = pubkey?.let { NostrProfileStore.get(it) }
        val localId = "local:" + UUID.randomUUID()
        val pending = ReviewDetailItem(
            id = localId,
            avatar = profile?.picture,
            name = profile?.bestName ?: pubkey?.take(12) ?: getString(R.string.nostr_comment_me),
            nostrAuthorPubkey = pubkey,
            badges = emptyList(),
            content = text,
            imageUrl = null,
            audioUrl = null,
            time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date()),
            likeCount = null,
            replyCount = null,
            replies = emptyList(),
            pendingState = PENDING_SENDING
        )
        val parentId = replyTo?.id?.takeIf { it.isNotBlank() }
        if (parentId != null && appendReplyToItem(parentId, pending)) {
            findTopLevelParentKey(parentId)?.let { expandedReplyParentKeys.add(it) }
        } else {
            detailItems.add(0, pending)
            reindexMainItems()
        }
        binding.tvMsg.gone()
        renderUiItems()
        return localId
    }

    /** 把占位回复挂到 targetId 评论的 replies 末尾(递归查找，含嵌套)；返回是否找到父项。 */
    private fun appendReplyToItem(targetId: String, reply: ReviewDetailItem): Boolean {
        for (index in detailItems.indices) {
            val item = detailItems[index]
            if (item.id == targetId) {
                detailItems[index] = item.copy(replies = item.replies + reply)
                return true
            }
            val updated = appendReplyToReplies(item.replies, targetId, reply)
            if (updated != null) {
                detailItems[index] = item.copy(replies = updated)
                return true
            }
        }
        return false
    }

    private fun appendReplyToReplies(
        replies: List<ReviewDetailItem>,
        targetId: String,
        reply: ReviewDetailItem
    ): List<ReviewDetailItem>? {
        var found = false
        val result = replies.map { existing ->
            when {
                existing.id == targetId -> {
                    found = true
                    existing.copy(replies = existing.replies + reply)
                }
                else -> {
                    val nested = appendReplyToReplies(existing.replies, targetId, reply)
                    if (nested != null) {
                        found = true
                        existing.copy(replies = nested)
                    } else {
                        existing
                    }
                }
            }
        }
        return if (found) result else null
    }

    /** 找到 targetId 所在的主评论 key，用于展开其回复区。 */
    private fun findTopLevelParentKey(targetId: String): String? {
        detailItems.forEach { item ->
            if (item.id == targetId || containsReplyId(item.replies, targetId)) {
                return buildDetailItemKey(item, isReply = false)
            }
        }
        return null
    }

    private fun containsReplyId(replies: List<ReviewDetailItem>, targetId: String): Boolean =
        replies.any { it.id == targetId || containsReplyId(it.replies, targetId) }

    private fun findDetailItemById(id: String): ReviewDetailItem? {
        detailItems.forEach { item ->
            if (item.id == id) return item
            findDetailItemInReplies(item.replies, id)?.let { return it }
        }
        return null
    }

    private fun findDetailItemInReplies(replies: List<ReviewDetailItem>, id: String): ReviewDetailItem? {
        replies.forEach { reply ->
            if (reply.id == id) return reply
            findDetailItemInReplies(reply.replies, id)?.let { return it }
        }
        return null
    }

    /** 顶层索引表用于合并网络结果，列表顶部插删会错位，必须重建。 */
    private fun reindexMainItems() {
        mainItemIndexByKey.clear()
        detailItems.forEachIndexed { index, item ->
            mainItemIndexByKey[buildDetailItemKey(item, isReply = false)] = index
        }
    }

    /**
     * 把已签名事件拼成本地「已确认」条目（pendingState=null，带真实事件 id）：
     * 可安全入缓存与上屏，不会像占位项那样被缓存剔除，也不会与联网结果重复。
     */
    private fun confirmPublishedComment(
        published: NostrReviewPublisher.PublishedComment,
        text: String
    ): ReviewDetailItem {
        val event = published.event
        val pubkey = event.optString("pubkey").takeIf { it.isNotBlank() }
            ?: NostrSigners.currentPubkey()?.takeIf { it.isNotBlank() }
        val profile = pubkey?.let { NostrProfileStore.get(it) }
        return ReviewDetailItem(
            id = event.optString("id").takeIf { it.isNotBlank() },
            avatar = profile?.picture,
            name = profile?.bestName ?: pubkey?.take(12) ?: getString(R.string.nostr_comment_me),
            nostrAuthorPubkey = pubkey,
            nostrRootExcerptId = published.rootExcerptId,
            nostrRootExcerptPubkey = published.rootExcerptPubkey,
            badges = emptyList(),
            content = text,
            imageUrl = null,
            audioUrl = null,
            time = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(Date(event.optLong("created_at", System.currentTimeMillis() / 1000) * 1000)),
            likeCount = 0,
            likedByMe = false,
            myReactionId = null,
            replyCount = 0,
            replies = emptyList(),
            pendingState = null
        )
    }

    /** 发布成功后把已确认评论并入详情缓存：重开该段时先显它，不必等联网。 */
    private fun cachePublishedComment(parentId: String?, item: ReviewDetailItem) {
        val book = ReadBook.book ?: return
        val cacheKey = NostrReviewDetailCache.key(
            book.bookUrl, ReadBook.durChapterIndex, paragraphNum
        )
        val cached = NostrReviewDetailCache.get(cacheKey).orEmpty()
        val merged = if (parentId == null) {
            listOf(item) + cached.filterNot { it.id != null && it.id == item.id }
        } else {
            attachReplyTo(cached, parentId, item) ?: return
        }
        NostrReviewDetailCache.put(cacheKey, merged)
    }

    /** 在（可能嵌套的）评论列表里找到 parentId 并把回复挂上去；找不到返回 null。 */
    private fun attachReplyTo(
        items: List<ReviewDetailItem>,
        parentId: String,
        reply: ReviewDetailItem
    ): List<ReviewDetailItem>? {
        var found = false
        val updated = items.map { item ->
            if (item.id == parentId) {
                found = true
                item.copy(
                    replies = item.replies.filterNot { it.id != null && it.id == reply.id } + reply,
                    replyCount = (item.replyCount ?: item.replies.size) + 1
                )
            } else {
                val replies = attachReplyTo(item.replies, parentId, reply)
                if (replies != null) {
                    found = true
                    item.copy(replies = replies)
                } else {
                    item
                }
            }
        }
        return if (found) updated else null
    }

    /** 按 id 递归移除本地占位项。 */
    private fun removeLocalPendingItem(localId: String) {
        localPendingPublish.remove(localId)
        val topIndex = detailItems.indexOfFirst { it.id == localId }
        if (topIndex >= 0) {
            detailItems.removeAt(topIndex)
            reindexMainItems()
            renderUiItems()
            return
        }
        for (index in detailItems.indices) {
            val updated = removeReplyById(detailItems[index].replies, localId)
            if (updated != null) {
                detailItems[index] = detailItems[index].copy(replies = updated)
                renderUiItems()
                return
            }
        }
    }

    private fun removeReplyById(
        replies: List<ReviewDetailItem>,
        targetId: String
    ): List<ReviewDetailItem>? {
        if (replies.isEmpty()) return null
        if (replies.any { it.id == targetId }) {
            return replies.filterNot { it.id == targetId }
        }
        var changed = false
        val result = replies.map { reply ->
            val updated = removeReplyById(reply.replies, targetId)
            if (updated != null) {
                changed = true
                reply.copy(replies = updated)
            } else {
                reply
            }
        }
        return if (changed) result else null
    }

    /** 改变本地占位项的发布态(sending/failed)，触发重绑以更新 chip。 */
    private fun setPendingState(localId: String, state: String?) {
        for (index in detailItems.indices) {
            val updated = detailItems[index].withPendingState(localId, state)
            if (updated !== detailItems[index]) {
                detailItems[index] = updated
                renderUiItems()
                return
            }
        }
    }

    private fun ReviewDetailItem.withPendingState(
        targetId: String,
        state: String?
    ): ReviewDetailItem {
        if (id == targetId) return copy(pendingState = state)
        if (replies.isEmpty()) return this
        var changed = false
        val newReplies = replies.map { reply ->
            val updated = reply.withPendingState(targetId, state)
            if (updated !== reply) changed = true
            updated
        }
        return if (changed) copy(replies = newReplies) else this
    }

    /** 点击草稿态占位：直接用原参数重发(sending 态忽略，防重复点击)。 */
    private fun republishLocalPending(item: ReviewUiItem) {
        val localId = item.id?.takeIf { it.isNotBlank() } ?: return
        if (item.pendingState != PENDING_FAILED) return
        val args = localPendingPublish[localId] ?: return
        setPendingState(localId, PENDING_SENDING)
        val draftKey = if (args.parentId != null) {
            reviewDraftKey("reply", args.parentId)
        } else {
            reviewDraftKey("comment")
        }
        // 乐观上屏：重发同样先本地 +1，失败再回退（首次失败时已回退过一次）。
        postEvent(EventBus.REFRESH_REVIEW_SUMMARY, paragraphNum.toString())
        NostrReviewPublisher.launchPublish(
            paragraphNum = paragraphNum,
            block = {
                AppLog.put("段评重发开始 p=$paragraphNum id=$localId", tag = "Nostr")
                val book = ReadBook.book ?: error("No book is open")
                withContext(IO) {
                    val parentId = args.parentId
                    if (parentId != null) {
                        NostrReviewPublisher.replyToComment(
                            book = book,
                            rootExcerptId = args.rootId.orEmpty(),
                            rootExcerptPubkey = args.rootAuthor.orEmpty(),
                            parentCommentId = parentId,
                            parentAuthorPubkey = args.parentAuthor.orEmpty(),
                            content = args.text
                        )
                    } else {
                        val chapterIndex = ReadBook.durChapterIndex
                        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
                            ?: error("Current chapter is unavailable")
                        val textChapter = ReadBook.curTextChapter?.takeIf {
                            it.chapter.index == chapterIndex
                        } ?: error("Current chapter text is unavailable")
                        NostrReviewPublisher.postParagraphComment(
                            book = book,
                            chapterTitle = chapter.title,
                            paragraphs = textChapter.paragraphTexts,
                            paragraphIndex = paragraphNum,
                            comment = args.text
                        )
                    }
                }
            },
            onSuccess = { published ->
                AppLog.put("段评发布成功 id=$localId", tag = "Nostr")
                // 清草稿与弹窗无关，弹窗已关也要执行；统计已在点击时乐观 +1，成功后不再重复计入。
                clearReviewDraft(draftKey)
                // 重发成功立刻入缓存/上屏，不必等联网回读。
                val confirmed = confirmPublishedComment(published, args.text)
                cachePublishedComment(parentId = args.parentId, item = confirmed)
                runOnUiIfAlive {
                    removeLocalPendingItem(localId)
                    val parentId = args.parentId
                    if (parentId != null) {
                        appendReplyToItem(parentId, confirmed)
                    } else {
                        detailItems.add(0, confirmed)
                        reindexMainItems()
                    }
                    renderUiItems()
                    loadDetailPage(paragraphNum, 1, append = false)
                }
            },
            onFailure = { cause ->
                AppLog.put("段评发布失败 id=$localId：${cause.localizedMessage}", cause, tag = "Nostr")
                // 失败必落草稿态：草稿落盘与弹窗无关，先存；占位转草稿态仅在弹窗存活时做
                saveReviewDraft(draftKey, args.text)
                // 失败后把气泡回退到真实值。
                postEvent(EventBus.REFRESH_REVIEW_SUMMARY_ONLY, paragraphNum.toString())
                runOnUiIfAlive {
                    setPendingState(localId, PENDING_FAILED)
                    context?.toastOnUi(
                        cause.localizedMessage ?: getString(R.string.nostr_comment_failed)
                    )
                }
            }
        )
    }

    /**
     * 需要签名器的动作：已连接直接执行，未连接先弹「去连接」确认框，
     * 连接成功后接续执行原动作。
     */
    private fun requireNostrSigner(action: () -> Unit) {
        if (NostrSigners.current() != null) {
            action()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.nostr_signer)
            .setMessage(R.string.nostr_comment_need_connect)
            .setPositiveButton(R.string.nostr_signer_connect_nostr) { _, _ ->
                pendingSignerAction = action
                nostrSignerDialog.show(onConnected = ::resumePendingSignerAction)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onWriteCommentAction() = requireNostrSigner { showNostrCommentEditor() }

    private fun resumePendingSignerAction() {
        if (NostrSigners.current() == null) return
        val action = pendingSignerAction ?: return
        pendingSignerAction = null
        runOnUiIfAlive { action() }
    }

    private fun showNostrCommentEditor() {
        val draftKey = reviewDraftKey("comment")
        val editBinding = io.legado.app.databinding.DialogEditTextBinding.inflate(layoutInflater).apply {
            editView.hint = getString(R.string.nostr_comment_hint)
            editView.minLines = 3
            editView.maxLines = 8
            editView.setSingleLine(false)
            // 上次发布失败留下的草稿优先回填
            readReviewDraft(draftKey)?.let { editView.setText(it) }
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.nostr_comment_write))
            .setView(editBinding.root)
            .setPositiveButton(getString(R.string.nostr_comment_publish), null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.setOnShowListener {
            val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            positive.setOnClickListener {
                val comment = editBinding.editView.text?.toString().orEmpty().trim()
                if (comment.isBlank()) return@setOnClickListener
                positive.isEnabled = false
                val localId = insertLocalPendingItem(comment, null)
                localPendingPublish[localId] = PublishArgs(text = comment)
                dialog.dismiss()
                // 乐观上屏：点击瞬间就让正文气泡 +1，不必等中继接收事件；失败时再回退。
                postEvent(EventBus.REFRESH_REVIEW_SUMMARY, paragraphNum.toString())
                NostrReviewPublisher.launchPublish(
                    paragraphNum = paragraphNum,
                    block = {
                        AppLog.put("段评发布开始 p=$paragraphNum id=$localId", tag = "Nostr")
                        val book = ReadBook.book ?: error("No book is open")
                        val chapterIndex = ReadBook.durChapterIndex
                        val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex)
                            ?: error("Current chapter is unavailable")
                        val textChapter = ReadBook.curTextChapter?.takeIf {
                            it.chapter.index == chapterIndex
                        } ?: error("Current chapter text is unavailable")
                        withContext(IO) {
                            NostrReviewPublisher.postParagraphComment(
                                book = book,
                                chapterTitle = chapter.title,
                                paragraphs = textChapter.paragraphTexts,
                                paragraphIndex = paragraphNum,
                                comment = comment
                            )
                        }
                    },
                    onSuccess = { published ->
                        AppLog.put("段评发布成功 id=$localId", tag = "Nostr")
                        // 清草稿与弹窗无关，弹窗已关也要执行；统计已在点击时乐观 +1，成功后不再重复计入。
                        clearReviewDraft(draftKey)
                        // 已确认评论立刻入缓存/上屏：弹窗已关也写缓存，重开该段不必等联网。
                        val confirmed = confirmPublishedComment(published, comment)
                        cachePublishedComment(parentId = null, item = confirmed)
                        runOnUiIfAlive {
                            removeLocalPendingItem(localId)
                            detailItems.add(0, confirmed)
                            reindexMainItems()
                            renderUiItems()
                            loadDetailPage(paragraphNum, 1, append = false)
                        }
                    },
                    onFailure = { cause ->
                        AppLog.put("段评发布失败 id=$localId：${cause.localizedMessage}", cause, tag = "Nostr")
                        // 失败必落草稿态：弹窗已关闭也要把草稿存下，下次打开发布框自动回填；
                        // 占位转草稿态仅在弹窗存活时做
                        saveReviewDraft(draftKey, comment)
                        // 失败后把气泡回退到真实值（点击时已乐观 +1，未落地就不该计）。
                        postEvent(EventBus.REFRESH_REVIEW_SUMMARY_ONLY, paragraphNum.toString())
                        runOnUiIfAlive {
                            setPendingState(localId, PENDING_FAILED)
                            context?.toastOnUi(
                                cause.localizedMessage ?: getString(R.string.nostr_comment_failed)
                            )
                        }
                    }
                )
            }
        }

        dialog.show()
    }

    private fun setupDragHandle() {
        binding.toolBar.setOnTouchListener { _, event ->
            val windowSize = requireContext().windowManager.windowSize
            val minHeight = (windowSize.heightPixels * MIN_HEIGHT_RATIO).roundToInt()
            val maxHeight = (windowSize.heightPixels * MAX_HEIGHT_RATIO).roundToInt()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (!isInToolbarDragZone(event.x)) {
                        isDraggingHeight = false
                        return@setOnTouchListener false
                    }
                    isDraggingHeight = true
                    dragStartRawY = event.rawY
                    dragStartHeightPx = dialog?.window?.attributes?.height
                        ?.takeIf { it > 0 }
                        ?: (windowSize.heightPixels * lastHeightRatio).roundToInt()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (!isDraggingHeight) return@setOnTouchListener false
                    val deltaY = event.rawY - dragStartRawY
                    val newHeight = (dragStartHeightPx - deltaY).roundToInt()
                        .coerceIn(minHeight, maxHeight)
                    applyDialogHeightPx(newHeight)
                    true
                }

                MotionEvent.ACTION_UP,
                MotionEvent.ACTION_CANCEL -> {
                    if (!isDraggingHeight) return@setOnTouchListener false
                    isDraggingHeight = false
                    binding.dragHandle.performClick()
                    true
                }

                else -> false
            }
        }
    }

    private fun isInToolbarDragZone(x: Float): Boolean {
        val toolbarWidth = binding.toolBar.width
        if (toolbarWidth <= 0) return false
        val leftBoundary = 56.dpToPx().toFloat()
        val rightBoundary = binding.toolBar.findViewWithTag<View>("review_count_tag")
            ?.let { (it.left - 12.dpToPx()).toFloat() }
            ?: (toolbarWidth - 56.dpToPx()).toFloat()
        if (rightBoundary <= leftBoundary) {
            val fallbackStart = toolbarWidth * 0.25f
            val fallbackEnd = toolbarWidth * 0.75f
            return x in fallbackStart..fallbackEnd
        }
        return x in leftBoundary..rightBoundary
    }

    private fun applyDialogHeightRatio(heightRatio: Float) {
        val windowSize = requireContext().windowManager.windowSize
        val minHeight = (windowSize.heightPixels * MIN_HEIGHT_RATIO).roundToInt()
        val maxHeight = (windowSize.heightPixels * MAX_HEIGHT_RATIO).roundToInt()
        val targetHeight = (windowSize.heightPixels * heightRatio).roundToInt()
            .coerceIn(minHeight, maxHeight)
        applyDialogHeightPx(targetHeight)
    }

    private fun applyDialogHeightPx(heightPx: Int) {
        val windowSize = requireContext().windowManager.windowSize
        dialog?.window?.setLayout(MATCH_PARENT, heightPx)
        lastHeightRatio = heightPx.toFloat() / windowSize.heightPixels.toFloat()
    }

    override fun onDestroyView() {
        releaseAudioPlayer()
        super.onDestroyView()
    }

    private fun ensureAudioPlayer(): ExoPlayer {
        reviewAudioPlayer?.let { return it }
        return ExoPlayerHelper.createHttpExoPlayer(requireContext()).also { player ->
            player.addListener(audioPlayerListener)
            reviewAudioPlayer = player
        }
    }

    private fun toggleAudioPlayback(audioUrl: String) {
        val previousAudioUrl = currentAudioUrl
        val player = ensureAudioPlayer()
        if (currentAudioUrl == audioUrl) {
            if (isAudioPreparing) return
            if (player.isPlaying) {
                player.pause()
            } else {
                player.play()
            }
            refreshAudioPlaybackUi(previousAudioUrl, currentAudioUrl)
            return
        }
        currentAudioUrl = audioUrl
        isAudioPreparing = true
        runCatching {
            player.setMediaItem(MediaItem.fromUri(audioUrl), true)
            player.prepare()
            player.playWhenReady = true
        }.onFailure {
            isAudioPreparing = false
            currentAudioUrl = null
            AppLog.put("段评语音播放失败\n${it.localizedMessage}", it)
            context?.toastOnUi(it.localizedMessage ?: getString(R.string.load_over_time))
        }
        refreshAudioPlaybackUi(previousAudioUrl, currentAudioUrl)
    }

    private fun releaseAudioPlayer() {
        val previousAudioUrl = currentAudioUrl
        isAudioPreparing = false
        currentAudioUrl = null
        reviewAudioPlayer?.runCatching {
            removeListener(audioPlayerListener)
            stop()
            clearMediaItems()
            release()
        }
        reviewAudioPlayer = null
        refreshAudioPlaybackUi(previousAudioUrl, currentAudioUrl)
    }

    private fun refreshAudioPlaybackUi(oldAudioUrl: String?, newAudioUrl: String?) {
        if (view == null) return
        val affectedUrls = linkedSetOf<String>()
        oldAudioUrl?.takeIf { it.isNotBlank() }?.let { affectedUrls.add(it) }
        newAudioUrl?.takeIf { it.isNotBlank() }?.let { affectedUrls.add(it) }
        if (affectedUrls.isEmpty()) return
        binding.recyclerView.post {
            if (view == null) return@post
            adapter.getItems().forEachIndexed { index, item ->
                val itemAudioUrl = item.audioUrl
                if (item.itemType == TYPE_NORMAL &&
                    !itemAudioUrl.isNullOrBlank() &&
                    affectedUrls.contains(itemAudioUrl)
                ) {
                    adapter.updateItem(index, PAYLOAD_AUDIO_STATE)
                }
            }
        }
    }

    private fun loadDetailPage(paragraphNum: Int, page: Int, append: Boolean) {
        if (isLoading) return
        if (!append) {
            // 初始加载先亮加载指示：未命中缓存时它要一直亮到联网结果回来，避免列表空白无反馈。
            binding.rotateLoading.visible()
            binding.tvMsg.gone()
            adapter.setItems(emptyList())
            currentPage = 1
            hasMore = true
            nextPageUrl = null
            mainItemIndexByKey.clear()
            detailItems.clear()
            expandedReplyParentKeys.clear()
            // 先渲染上次的首页内容（内存→落盘），联网结果回来后再合并刷新
            val cached = ReadBook.book?.let { book ->
                NostrReviewDetailCache.get(
                    NostrReviewDetailCache.key(book.bookUrl, ReadBook.durChapterIndex, paragraphNum)
                )
            }
            if (!cached.isNullOrEmpty()) {
                // 命中缓存：先渲染缓存并收起加载指示
                binding.rotateLoading.gone()
                mergeDetailItems(cached)
                renderUiItems()
            }
        }
        if (!hasMore) return
        isLoading = true
        Coroutine.async(lifecycleScope, IO) {
            val source = ReadBook.bookSource
            val book = ReadBook.book ?: return@async null
            val chapterIndex = ReadBook.durChapterIndex
            val chapter = appDb.bookChapterDao.getChapter(book.bookUrl, chapterIndex) ?: return@async null
            val rawKey = ChapterProvider.getReviewKeyById(paragraphNum)
            val paraData = rawKey?.takeIf { it.isNotBlank() } ?: ""
            val sourceResult = if (source == null) null else try {
                if (source.isJsSource()) {
                    JsSourceReview.getReviewDetailAwait(
                        source, book, chapter, paragraphNum, paraData, page
                    )?.let { result ->
                        ReviewResult(
                            items = result.first.map { convertJsReviewItemToUi(it) },
                            nextPageUrl = result.second,
                            hasNextPageRule = result.second != null
                        )
                    }
                } else {
                    val rule = source.ruleReview?.takeIf { it.enabled }
                    val firstPageUrlRule = rule?.reviewDetailUrl?.takeIf { it.isNotBlank() }
                    val nextPageUrlRule = rule?.reviewDetailNextPageUrl?.takeIf { it.isNotBlank() }
                    val effectiveNextUrl = nextPageUrl?.takeIf {
                        it.isNotBlank() && it != NOSTR_MORE_PAGE
                    }
                    if (rule == null || firstPageUrlRule == null ||
                        page > 1 && effectiveNextUrl == null && nextPageUrlRule == null ||
                        rule.detailListRule.isNullOrBlank() || rule.detailContentRule.isNullOrBlank()
                    ) {
                        null
                    } else {
                        val detailUrlRule = when {
                            page > 1 && !effectiveNextUrl.isNullOrBlank() -> effectiveNextUrl
                            page > 1 -> nextPageUrlRule ?: firstPageUrlRule
                            else -> firstPageUrlRule
                        }
                        val paraIndex = paragraphNum.toString()
                        val analyzeUrl = AnalyzeUrl(
                            detailUrlRule,
                            page = page,
                            extraParams = mapOf(
                                "paraIndex" to paraIndex,
                                "paraData" to paraData,
                                "page" to page.toString()
                            ),
                            baseUrl = chapter.url,
                            source = source,
                            ruleData = book,
                            chapter = chapter,
                            coroutineContext = coroutineContext
                        )
                        val body = analyzeUrl.getStrResponseAwait().body ?: ""
                        val result = parseReviewDetailList(
                            body = body,
                            rule = rule,
                            nextPageRule = nextPageUrlRule,
                            baseUrl = analyzeUrl.url,
                            source = source,
                            book = book,
                            chapter = chapter,
                            context = coroutineContext,
                            paraIndex = paraIndex,
                            paraData = paraData,
                            page = page.toString()
                        )
                        ReviewResult(result.items, result.nextPageUrl, nextPageUrlRule != null)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.put("加载站点段评详情失败\n${e.localizedMessage}", e)
                null
            }
            val textChapter = ReadBook.curTextChapter?.takeIf {
                it.chapter.index == chapterIndex
            }
            val paragraphTexts = textChapter?.paragraphTexts.orEmpty()
            val hasParagraph = !paragraphTexts.getOrNull(paragraphNum - 1).isNullOrBlank()
            val nostrResult = if (AppConfig.nostrReviewEnabled && hasParagraph) {
                runCatching {
                    NostrReviewClient.loadDetails(book, chapter.title, paragraphTexts, paragraphNum, page)
                }.onFailure {
                    if (it is CancellationException) throw it
                    AppLog.put("加载 Nostr 段评详情失败\n${it.localizedMessage}", it)
                }.getOrNull()?.let { details ->
                    ReviewResult(
                        items = NostrReviewDetailCache.toItems(details.items),
                        nextPageUrl = if (details.hasMore) "more" else null,
                        hasNextPageRule = true
                    )
                }
            } else {
                null
            }
            if (sourceResult == null && nostrResult == null) return@async null
            val sourceHasMore = sourceResult?.hasNextPageRule == true &&
                !sourceResult.nextPageUrl.isNullOrBlank()
            val nostrHasMore = nostrResult?.hasNextPageRule == true &&
                !nostrResult.nextPageUrl.isNullOrBlank()
            ReviewResult(
                items = sourceResult?.items.orEmpty() + nostrResult?.items.orEmpty(),
                nextPageUrl = when {
                    sourceHasMore -> sourceResult?.nextPageUrl
                    nostrHasMore -> NOSTR_MORE_PAGE
                    else -> null
                },
                hasNextPageRule = sourceResult?.hasNextPageRule == true || nostrResult?.hasNextPageRule == true
            )
        }.onSuccess(Main) { result ->
            if (!append) {
                binding.rotateLoading.gone()
            }
            val items = result?.items.orEmpty()
            val nextUrlFromRule = result?.nextPageUrl
            if (result?.hasNextPageRule == true) {
                nextPageUrl = nextUrlFromRule
                if (nextUrlFromRule.isNullOrBlank()) {
                    hasMore = false
                }
            }
            if (items.isEmpty() && !append) {
                hasMore = false
                binding.tvMsg.text = getString(R.string.content_empty)
                binding.tvMsg.visible()
                isLoading = false
                return@onSuccess
            }
            if (items.isEmpty()) {
                hasMore = false
                isLoading = false
                return@onSuccess
            }
            val mergedCount = mergeDetailItems(items)
            if (mergedCount == 0 && append) {
                hasMore = false
                isLoading = false
                return@onSuccess
            }
            currentPage = page
            renderUiItems()
            refreshProfilesForDisplay()
            if (!append && page == 1) {
                val book = ReadBook.book
                if (book != null) {
                    val cacheKey = NostrReviewDetailCache.key(
                        book.bookUrl, ReadBook.durChapterIndex, paragraphNum
                    )
                    NostrReviewDetailCache.put(cacheKey, ArrayList(detailItems))
                }
            }
            isLoading = false
        }.onError {
            isLoading = false
            if (!append) {
                binding.rotateLoading.gone()
                binding.tvMsg.text = it.localizedMessage ?: getString(R.string.content_empty)
                binding.tvMsg.visible()
            }
        }
    }

    private fun buildDetailItemKey(item: ReviewDetailItem, isReply: Boolean): String {
        val id = item.id?.trim().orEmpty()
        if (id.isNotEmpty()) {
            return (if (isReply) "r|" else "m|") + id
        }
        return buildString {
            append(if (isReply) "r" else "m")
            append('|')
            append(item.name.orEmpty())
            append('|')
            append(item.content.orEmpty())
            append('|')
            append(item.time.orEmpty())
            append('|')
            append(item.avatar.orEmpty())
            append('|')
            append(item.imageUrl.orEmpty())
            append('|')
            append(item.audioUrl.orEmpty())
        }
    }

    private fun mergeDetailItems(newItems: List<ReviewDetailItem>): Int {
        var changed = 0
        newItems.forEach { incoming ->
            val mainKey = buildDetailItemKey(incoming, isReply = false)
            val normalizedReplies = dedupeReplies(incoming.replies)
            val normalized = incoming.copy(replies = normalizedReplies)
            val index = mainItemIndexByKey[mainKey]
            if (index == null) {
                detailItems.add(normalized)
                mainItemIndexByKey[mainKey] = detailItems.lastIndex
                changed++
            } else {
                val old = detailItems[index]
                val mergedReplies = mergeReplies(old.replies, normalized.replies)
                val mergedReplyCount = max(old.replyCount ?: 0, normalized.replyCount ?: 0)
                    .takeIf { it > 0 }
                // 用本次下发的字段覆盖旧条目（点赞/时间/内容可能变），仅回复取并集
                val refreshed = normalized.copy(
                    replies = mergedReplies,
                    replyCount = mergedReplyCount
                )
                if (old != refreshed) {
                    detailItems[index] = refreshed
                    changed++
                }
            }
        }
        return changed
    }

    private fun dedupeReplies(replies: List<ReviewDetailItem>): List<ReviewDetailItem> {
        if (replies.isEmpty()) return replies
        val seen = HashSet<String>(replies.size)
        val result = ArrayList<ReviewDetailItem>(replies.size)
        replies.forEach { reply ->
            val key = buildDetailItemKey(reply, isReply = true)
            if (seen.add(key)) result.add(reply)
        }
        return result
    }

    private fun mergeReplies(
        oldReplies: List<ReviewDetailItem>,
        newReplies: List<ReviewDetailItem>
    ): List<ReviewDetailItem> {
        if (oldReplies.isEmpty()) return newReplies
        if (newReplies.isEmpty()) return oldReplies
        val result = ArrayList<ReviewDetailItem>(oldReplies.size + newReplies.size)
        result.addAll(oldReplies)
        val seen = HashSet<String>(oldReplies.size + newReplies.size)
        oldReplies.forEach { seen.add(buildDetailItemKey(it, isReply = true)) }
        newReplies.forEach { reply ->
            val key = buildDetailItemKey(reply, isReply = true)
            if (seen.add(key)) result.add(reply)
        }
        return result
    }

    private data class ReviewResult(
        val items: List<ReviewDetailItem>,
        val nextPageUrl: String?,
        val hasNextPageRule: Boolean
    )

    /** 本地占位的发布参数：parentId 为空即首评，否则为回复。 */
    private data class PublishArgs(
        val text: String,
        val parentId: String? = null,
        val parentAuthor: String? = null,
        val rootId: String? = null,
        val rootAuthor: String? = null
    )

    /**
     * 引擎层 JsSourceReview.ReviewDetailItem 转 UI 层 ReviewDetailItem。
     * badge(单值)映射为 badges 列表;JS 契约无 img/audio/time/likeCount 字段,对应 UI 字段取 null。
     * 嵌套 replies 原样保留,由 flattenItems 按层级渲染。
     */
    private fun convertJsReviewItemToUi(item: JsSourceReview.ReviewDetailItem): ReviewDetailItem {
        val replies = item.replies.map { convertJsReviewItemToUi(it) }
        return jsReviewItemToUi(item, replies)
    }

    /** 本地乐观更新某条评论的点赞态与计数（失败时反向调用以回滚）。 */
    private fun applyLikeState(commentId: String, liked: Boolean, reactionId: String? = null) {
        for (index in detailItems.indices) {
            val updated = detailItems[index].withLikeState(commentId, liked, reactionId)
            if (updated !== detailItems[index]) {
                detailItems[index] = updated
                break
            }
        }
        renderUiItems()
    }

    private fun ReviewDetailItem.withLikeState(
        commentId: String,
        liked: Boolean,
        reactionId: String?
    ): ReviewDetailItem {
        if (id == commentId) {
            // 仅在点赞态真正变化时动计数（发布成功后回填 reaction id 时不应再加一）
            val delta = when {
                liked == likedByMe -> 0
                liked -> 1
                else -> -1
            }
            val newCount = ((likeCount ?: 0) + delta).coerceAtLeast(0)
            return copy(
                likedByMe = liked,
                likeCount = newCount,
                myReactionId = if (liked) (reactionId ?: myReactionId) else null
            )
        }
        if (replies.isEmpty()) return this
        var changed = false
        val newReplies = replies.map { reply ->
            val updated = reply.withLikeState(commentId, liked, reactionId)
            if (updated !== reply) changed = true
            updated
        }
        return if (changed) copy(replies = newReplies) else this
    }

    /** 列表涉及到的 Nostr 作者（含回复），用于批量拉资料。 */
    private fun collectNostrAuthors(items: List<ReviewDetailItem>): List<String> {
        val result = LinkedHashSet<String>()
        fun walk(list: List<ReviewDetailItem>) {
            list.forEach { item ->
                item.nostrAuthorPubkey?.takeIf { it.isNotBlank() }?.let { result.add(it) }
                walk(item.replies)
            }
        }
        walk(items)
        return result.toList()
    }

    /** 把本地缓存的资料（头像/昵称）套到条目上，递归到回复。 */
    private fun ReviewDetailItem.withCachedProfile(): ReviewDetailItem {
        val newReplies = if (replies.isEmpty()) replies else replies.map { it.withCachedProfile() }
        val profile = nostrAuthorPubkey?.takeIf { it.isNotBlank() }?.let { NostrProfileStore.get(it) }
        if (profile == null) {
            return if (newReplies === replies) this else copy(replies = newReplies)
        }
        return copy(
            avatar = profile.picture ?: avatar,
            name = profile.bestName ?: name,
            replies = newReplies
        )
    }

    /** 缓存命中的先渲染；缺资料或过期的作者拉一次，拿到后刷新列表。 */
    private fun refreshProfilesForDisplay() {
        val authors = collectNostrAuthors(detailItems)
        if (authors.isEmpty()) return
        lifecycleScope.launch {
            val fetched = NostrProfileStore.sync(authors)
            if (fetched <= 0) return@launch
            for (index in detailItems.indices) {
                detailItems[index] = detailItems[index].withCachedProfile()
            }
            renderUiItems()
        }
    }

    private fun jsReviewItemToUi(
        item: JsSourceReview.ReviewDetailItem,
        replies: List<ReviewDetailItem>
    ): ReviewDetailItem {
        return ReviewDetailItem(
            id = item.id,
            avatar = item.avatar,
            name = item.name,
            badges = item.badge?.takeIf { it.isNotBlank() }?.let { listOf(it) } ?: emptyList(),
            content = item.content,
            imageUrl = null,
            audioUrl = null,
            time = null,
            likeCount = null,
            replyCount = null,
            replies = replies
        )
    }

    private data class ReviewUiItem(
        val id: String?,
        val avatar: String?,
        val name: String?,
        val nostrAuthorPubkey: String? = null,
        val nostrRootExcerptId: String? = null,
        val nostrRootExcerptPubkey: String? = null,
        val badges: List<String>,
        val content: String?,
        val imageUrl: String?,
        val audioUrl: String?,
        val time: String?,
        val likeCount: Int?,
        val likedByMe: Boolean = false,
        val myReactionId: String? = null,
        val isReply: Boolean,
        /** 层级：1=主评论，2 起为回复。 */
        val level: Int = 1,
        /** 回复对象昵称（仅回复项），用于昵称行「X 回复 Y」。 */
        val replyToName: String? = null,
        val itemType: Int = TYPE_NORMAL,
        val parentKey: String? = null,
        val pendingState: String? = null
    )

    private fun flattenItems(items: List<ReviewDetailItem>): List<ReviewUiItem> {
        val list = ArrayList<ReviewUiItem>()
        items.forEach { item ->
            val parentKey = buildDetailItemKey(item, isReply = false)
            list.add(item.toUiItem(level = 1, parentKey = parentKey))

            val loadedReplyCount = item.replies.size
            if (loadedReplyCount <= 0) return@forEach

            if (!expandedReplyParentKeys.contains(parentKey)) {
                list.add(
                    ReviewUiItem(
                        id = null,
                        avatar = null,
                        name = null,
                        badges = emptyList(),
                        content = null,
                        imageUrl = null,
                        audioUrl = null,
                        time = null,
                        likeCount = null,
                        isReply = false,
                        itemType = TYPE_MORE,
                        parentKey = parentKey
                    )
                )
                return@forEach
            }

            appendReplyItems(
                result = list,
                replies = item.replies,
                level = 2,
                parentKey = parentKey,
                replyToName = item.name
            )
        }
        return list
    }

    /**
     * 递归展开回复（含更深嵌套），最深到 [MAX_REPLY_LEVEL] 级；同级回复统一缩进。
     * replyToName 为该级回复的对象昵称，随层级向下传递。
     */
    private fun appendReplyItems(
        result: MutableList<ReviewUiItem>,
        replies: List<ReviewDetailItem>,
        level: Int,
        parentKey: String?,
        replyToName: String?
    ) {
        if (level > MAX_REPLY_LEVEL) return
        replies.forEach { reply ->
            result.add(reply.toUiItem(level = level, parentKey = parentKey, replyToName = replyToName))
            if (reply.replies.isNotEmpty()) {
                appendReplyItems(
                    result = result,
                    replies = reply.replies,
                    level = level + 1,
                    parentKey = parentKey,
                    replyToName = reply.name ?: replyToName
                )
            }
        }
    }

    private fun renderUiItems() {
        val uiItems = flattenItems(detailItems)
        adapter.setItems(uiItems, uiItemDiffCallback, skipDiff = true)
    }

    private fun ReviewDetailItem.toUiItem(
        level: Int,
        parentKey: String? = null,
        replyToName: String? = null
    ) = ReviewUiItem(
        id = id,
        avatar = avatar,
        name = name,
        nostrAuthorPubkey = nostrAuthorPubkey,
        nostrRootExcerptId = nostrRootExcerptId,
        nostrRootExcerptPubkey = nostrRootExcerptPubkey,
        badges = badges,
        content = content,
        imageUrl = imageUrl,
        audioUrl = audioUrl,
        time = time,
        likeCount = likeCount,
        likedByMe = likedByMe,
        myReactionId = myReactionId,
        isReply = level > 1,
        level = level,
        replyToName = replyToName,
        parentKey = parentKey,
        pendingState = pendingState
    )

    private companion object {
        private const val DEFAULT_HEIGHT_RATIO = 0.68f
        private const val MIN_HEIGHT_RATIO = 0.35f
        private const val MAX_HEIGHT_RATIO = 0.92f
        const val TYPE_NORMAL = 0
        const val TYPE_MORE = 1
        private const val NOSTR_MORE_PAGE = "nostr-more"
        /** 段评最多展示的嵌套层级（1=主评论）；第 5 级不再显示回复按钮。 */
        private const val MAX_REPLY_LEVEL = 5
        private const val REVIEW_DRAFT_TTL_SECONDS = 30 * 24 * 60 * 60
        const val PAYLOAD_AUDIO_STATE = "review_audio_state"
        private const val PENDING_SENDING = "sending"
        private const val PENDING_FAILED = "failed"
        private var lastHeightRatio = DEFAULT_HEIGHT_RATIO
    }

    private data class ReviewParseResult(
        val items: List<ReviewDetailItem>,
        val nextPageUrl: String?
    )

    private data class ReviewContentProtocol(
        val text: String?,
        val imageUrl: String?,
        val audioUrl: String?,
        val time: String?,
        val likeCount: Int?,
        val replyCount: Int?
    )

    private fun parseReviewDetailList(
        body: String,
        rule: ReviewRule,
        nextPageRule: String?,
        baseUrl: String,
        source: io.legado.app.data.entities.BaseSource,
        book: io.legado.app.data.entities.Book,
        chapter: io.legado.app.data.entities.BookChapter,
        context: kotlin.coroutines.CoroutineContext,
        paraIndex: String,
        paraData: String,
        page: String
    ): ReviewParseResult {
        val listRule = rule.detailListRule?.trim().orEmpty()
        if (listRule.isEmpty()) return ReviewParseResult(emptyList(), null)
        val analyzeRule = AnalyzeRule(book, source)
            .setChapter(chapter)
            .setCoroutineContext(context)
            .setContent(body, baseUrl)
            .setLocal("paraIndex", paraIndex)
            .setLocal("paraData", paraData)
            .setLocal("page", page)
        val list = runCatching { analyzeRule.getElements(listRule) }.getOrElse {
            AppLog.put("段评详情列表规则执行出错\n${it.localizedMessage}", it)
            emptyList()
        }
        val nextPageUrl = if (!nextPageRule.isNullOrBlank()) {
            val raw = safeRuleString(analyzeRule, nextPageRule)?.trim().orEmpty()
            when {
                raw.isEmpty() -> null
                AnalyzeUrl.paramPattern.matcher(raw).find() -> raw
                else -> NetworkUtils.getAbsoluteURL(baseUrl, raw)
            }
        } else {
            null
        }
        if (list.isEmpty()) return ReviewParseResult(emptyList(), nextPageUrl)
        val items = list.mapNotNull {
            parseReviewDetailItem(
                analyzeRule = analyzeRule,
                item = it,
                rule = rule,
                baseUrl = baseUrl,
                isReply = false
            )
        }
        return ReviewParseResult(items, nextPageUrl)
    }

    private fun parseReviewDetailItem(
        analyzeRule: AnalyzeRule,
        item: Any,
        rule: ReviewRule,
        baseUrl: String,
        isReply: Boolean
    ): ReviewDetailItem? {
        analyzeRule.setContent(item, baseUrl)
        val avatarRule = if (isReply) rule.replyAvatarRule else rule.detailAvatarRule
        val nameRule = if (isReply) rule.replyNameRule else rule.detailNameRule
        val badgeRule = if (isReply) rule.replyBadgeRule else rule.detailBadgeRule
        val contentRule = if (isReply) rule.replyContentRule else rule.detailContentRule
        val idRule = if (isReply) rule.replyIdRule else rule.detailIdRule

        val avatar = safeRuleString(analyzeRule, avatarRule)
            ?.let { NetworkUtils.getAbsoluteURL(baseUrl, it) }
        val name = safeRuleString(analyzeRule, nameRule)
        val rawContent = safeRuleString(analyzeRule, contentRule)
        val contentProtocol = parseReviewContentProtocol(rawContent, baseUrl)
        val content = if (contentProtocol != null) {
            contentProtocol.text ?: ""
        } else {
            rawContent
        }
        val imageUrl = contentProtocol?.imageUrl
        val audioUrl = contentProtocol?.audioUrl
        val time = contentProtocol?.time
        val id = safeRuleString(analyzeRule, idRule)
        val badges = safeRuleList(analyzeRule, badgeRule)
        val likeCount = if (isReply) null else contentProtocol?.likeCount
        val replyCount = if (isReply) null else contentProtocol?.replyCount

        val replies = if (!isReply && !rule.replyListRule.isNullOrBlank()) {
            val replyList = runCatching {
                analyzeRule.getElements(rule.replyListRule!!.trim())
            }.getOrElse { emptyList() }
            replyList.mapNotNull {
                parseReviewDetailItem(
                    analyzeRule = analyzeRule,
                    item = it,
                    rule = rule,
                    baseUrl = baseUrl,
                    isReply = true
                )
            }
        } else {
            emptyList()
        }

        if (name.isNullOrBlank() && content.isNullOrBlank() && imageUrl.isNullOrBlank() && audioUrl.isNullOrBlank()) {
            return null
        }
        return ReviewDetailItem(
            id = id,
            avatar = avatar,
            name = name,
            badges = badges,
            content = content,
            imageUrl = imageUrl,
            audioUrl = audioUrl,
            time = time,
            likeCount = likeCount,
            replyCount = replyCount,
            replies = replies
        )
    }

    private fun parseReviewContentProtocol(raw: String?, baseUrl: String): ReviewContentProtocol? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || !text.startsWith("{") || !text.endsWith("}")) return null
        val obj = runCatching { JSONObject(text) }.getOrNull() ?: return null
        val t = obj.optString("text").trim().ifEmpty { null }
        val imgRaw = obj.optString("img").trim().ifEmpty { null }
        val audioRaw = obj.optString("audio").trim().ifEmpty { null }
        val timeRaw = obj.optString("time").trim().ifEmpty { null }
        val likeCountRaw = obj.opt("likeCount")
        val replyCountRaw = obj.opt("replyCount")
        val likeCount = parseProtocolInt(likeCountRaw)
        val replyCount = parseProtocolInt(replyCountRaw)
        if (t == null && imgRaw == null && audioRaw == null &&
            timeRaw == null && likeCount == null && replyCount == null
        ) return null
        return ReviewContentProtocol(
            text = t,
            imageUrl = imgRaw?.let { NetworkUtils.getAbsoluteURL(baseUrl, it) },
            audioUrl = audioRaw?.let { NetworkUtils.getAbsoluteURL(baseUrl, it) },
            time = timeRaw,
            likeCount = likeCount,
            replyCount = replyCount
        )
    }

    private fun parseProtocolInt(value: Any?): Int? {
        return when (value) {
            null, JSONObject.NULL -> null
            is Number -> value.toInt()
            is String -> value.trim().toIntOrNull() ?: value.trim().toDoubleOrNull()?.toInt()
            else -> value.toString().trim().toIntOrNull() ?: value.toString().trim().toDoubleOrNull()?.toInt()
        }
    }

    private fun safeRuleString(analyzeRule: AnalyzeRule, rule: String?): String? {
        val r = rule?.trim().orEmpty()
        if (r.isEmpty()) return null
        return runCatching { analyzeRule.getString(r) }
            .onFailure { AppLog.put("段评规则执行出错: $r\n${it.localizedMessage}", it) }
            .getOrDefault("")
            .takeIf { it.isNotBlank() }
    }

    private fun safeRuleList(analyzeRule: AnalyzeRule, rule: String?): List<String> {
        val r = rule?.trim().orEmpty()
        if (r.isEmpty()) return emptyList()
        val fromList = runCatching {
            analyzeRule.getStringList(r).orEmpty()
        }.onFailure {
            AppLog.put("段评规则执行出错: $r\n${it.localizedMessage}", it)
        }.getOrDefault(emptyList())
        if (fromList.isNotEmpty()) {
            return fromList.flatMap { splitBadgeValue(it) }.distinct()
        }
        val fromString = runCatching { analyzeRule.getString(r) }
            .onFailure { AppLog.put("段评规则执行出错: $r\n${it.localizedMessage}", it) }
            .getOrDefault("")
        return splitBadgeValue(fromString).distinct()
    }

    private fun splitBadgeValue(value: String?): List<String> {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        if (raw.isDataUrl()) return listOf(raw)

        if (raw.startsWith("[") && raw.endsWith("]")) {
            val jsonArray = runCatching { JSONArray(raw) }.getOrNull()
            if (jsonArray != null) {
                val list = ArrayList<String>(jsonArray.length())
                for (i in 0 until jsonArray.length()) {
                    val item = jsonArray.optString(i).trim()
                    if (item.isNotEmpty()) list.add(item)
                }
                if (list.isNotEmpty()) return list
            }
        }

        val separator = when {
            raw.contains('\n') -> '\n'
            raw.contains('|') -> '|'
            raw.contains(',') && shouldSplitByComma(raw) -> ','
            else -> null
        }
        if (separator == null) return listOf(raw)

        return raw.split(separator)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun shouldSplitByComma(raw: String): Boolean {
        if (raw.startsWith("data:")) return false
        if (!raw.contains("://")) return true
        return raw.contains(",http://") || raw.contains(",https://")
    }

    private inner class ReviewAdapter(context: Context) :
        RecyclerAdapter<ReviewUiItem, ItemReviewCommentBinding>(context) {

        override fun getViewBinding(parent: ViewGroup): ItemReviewCommentBinding {
            return ItemReviewCommentBinding.inflate(inflater, parent, false)
        }

        override fun convert(
            holder: ItemViewHolder,
            binding: ItemReviewCommentBinding,
            item: ReviewUiItem,
            payloads: MutableList<Any>
        ) {
            if (payloads.any { it == PAYLOAD_AUDIO_STATE }) {
                bindAudioState(binding, item)
                return
            }
            val tvContentLp = binding.tvContent.layoutParams as ViewGroup.MarginLayoutParams
            val basePadding = 8.dpToPx()
            val mainAvatarSize = 36.dpToPx()
            if (item.itemType == TYPE_MORE) {
                binding.root.alpha = 1f
                binding.root.updatePaddingRelative(
                    start = basePadding,
                    top = 2.dpToPx(),
                    end = basePadding,
                    bottom = 2.dpToPx()
                )
                binding.ivAvatar.updateLayoutParams<ViewGroup.LayoutParams> {
                    width = mainAvatarSize
                    height = mainAvatarSize
                }
                binding.ivAvatar.visibility = View.INVISIBLE
                binding.llLikeArea.gone()
                binding.tvName.gone()
                binding.llBadges.gone()
                binding.tvTime.gone()
                binding.ivMedia.gone()
                binding.tvAudio.gone()
                binding.tvReplyBottom.gone()
                binding.tvReplyTo.gone()
                binding.llFooter.gone()
                binding.tvContent.visible()
                binding.tvContent.text = context.getString(R.string.review_more_replies)
                binding.tvContent.textSize = 14f
                binding.tvContent.setTextColor(context.getCompatColor(R.color.accent))
                binding.tvContent.setPadding(0, 0, 0, 0)
                tvContentLp.topMargin = 0
                binding.tvContent.layoutParams = tvContentLp
                binding.llContentCard.background = null
                return
            }

            binding.root.alpha = if (item.pendingState == PENDING_SENDING) 0.6f else 1f

            // 子评论缩进：使用主评论头像宽度作为占位，避免写死 magic number
            val replyIndent = mainAvatarSize
            val startPadding = if (item.isReply) basePadding + replyIndent else basePadding
            binding.root.updatePaddingRelative(
                start = startPadding,
                top = basePadding,
                end = basePadding,
                bottom = basePadding
            )

            binding.llContentCard.background = null
            binding.llFooter.visible()

            val avatarSize = if (item.isReply) 28.dpToPx() else 36.dpToPx()
            binding.ivAvatar.updateLayoutParams<ViewGroup.LayoutParams> {
                width = avatarSize
                height = avatarSize
            }

            if (item.avatar.isNullOrBlank()) {
                binding.ivAvatar.gone()
            } else {
                binding.ivAvatar.visible()
                ImageLoader.load(context, item.avatar).circleCrop().into(binding.ivAvatar)
            }

            val primaryColor = context.getCompatColor(R.color.primaryText)
            val secondaryColor = context.getCompatColor(R.color.secondaryText)
            val contentColor = context.getCompatColor(R.color.reviewContentText)
            binding.tvName.text = item.name.orEmpty()
            binding.tvName.visibility = if (item.name.isNullOrBlank()) View.GONE else View.VISIBLE
            binding.tvName.setTextColor(primaryColor)
            // 回复项在昵称行补充回复对象：X 回复 Y
            val replyTo = if (item.isReply) item.replyToName?.takeIf { it.isNotBlank() } else null
            binding.tvReplyTo.visibility = if (replyTo != null) View.VISIBLE else View.GONE
            binding.tvReplyTo.text = replyTo?.let {
                context.getString(R.string.nostr_comment_reply_to, it)
            } ?: ""
            binding.tvContent.text = item.content.orEmpty()
            val canReply = item.pendingState == null &&
                    !item.nostrAuthorPubkey.isNullOrBlank() &&
                    item.level < MAX_REPLY_LEVEL
            // 回复按钮统一放在时间行，主评论与回复项保持一致
            binding.tvReplyBottom.visibility = if (canReply) View.VISIBLE else View.GONE
            val hasText = binding.tvContent.text?.isNotBlank() == true
            binding.tvContent.visibility = if (hasText) View.VISIBLE else View.GONE
            binding.tvContent.textSize = if (item.isReply) 14f else 16f
            binding.tvContent.setTextColor(contentColor)
            binding.tvContent.setPadding(0, 0, 0, 0)
            tvContentLp.topMargin = if (item.isReply) 0 else 4.dpToPx()
            binding.tvContent.layoutParams = tvContentLp

            if (item.imageUrl.isNullOrBlank()) {
                binding.ivMedia.gone()
            } else {
                binding.ivMedia.visible()
                ImageLoader.load(context, item.imageUrl).into(binding.ivMedia)
            }

            bindAudioState(binding, item)

            binding.tvTime.text = item.time.orEmpty()
            binding.tvTime.visibility = if (item.time.isNullOrBlank()) View.GONE else View.VISIBLE
            if (item.pendingState == null) {
                binding.ivLike.visible()
                binding.llLikeArea.visible()
                // 已赞（含本地乐观态）用强调色
                binding.ivLike.setColorFilter(
                    if (item.likedByMe) context.getCompatColor(R.color.accent) else secondaryColor
                )
                val likeCount = item.likeCount
                if (likeCount != null && likeCount > 0) {
                    binding.tvLikeCount.text = likeCount.toString()
                    binding.tvLikeCount.visible()
                } else {
                    binding.tvLikeCount.gone()
                }
            } else {
                binding.ivLike.gone()
                binding.tvLikeCount.gone()
                binding.llLikeArea.gone()
            }

            if (item.isReply && item.pendingState == null) {
                binding.llBadges.gone()
            } else {
                bindBadges(binding.llBadges, item.badges, item.pendingState)
            }
        }

        override fun registerListener(holder: ItemViewHolder, binding: ItemReviewCommentBinding) {
            binding.root.setOnClickListener {
                val item = getItemByLayoutPosition(holder.layoutPosition) ?: return@setOnClickListener
                if (item.itemType == TYPE_MORE) {
                    val parentKey = item.parentKey ?: return@setOnClickListener
                    val canExpand = detailItems.any {
                        buildDetailItemKey(it, isReply = false) == parentKey && it.replies.isNotEmpty()
                    }
                    if (canExpand && expandedReplyParentKeys.add(parentKey)) {
                        renderUiItems()
                    }
                    return@setOnClickListener
                }
                if (item.pendingState == PENDING_FAILED) {
                    republishLocalPending(item)
                }
            }
            binding.ivMedia.setOnClickListener {
                val item = getItemByLayoutPosition(holder.layoutPosition) ?: return@setOnClickListener
                val imageUrl = item.imageUrl ?: return@setOnClickListener
                PhotoDialog(imageUrl).show(this@ReviewDetailDialog.childFragmentManager, "reviewPhoto")
            }
            binding.tvAudio.setOnClickListener {
                val item = getItemByLayoutPosition(holder.layoutPosition) ?: return@setOnClickListener
                val audioUrl = item.audioUrl ?: return@setOnClickListener
                toggleAudioPlayback(audioUrl)
            }
            val onReplyClick = View.OnClickListener onReply@{
                val item = getItemByLayoutPosition(holder.layoutPosition) ?: return@onReply
                if (item.itemType != TYPE_MORE && item.pendingState == null) {
                    requireNostrSigner { showNostrReplyEditor(item) }
                }
            }
            binding.tvReplyBottom.setOnClickListener(onReplyClick)
            binding.llLikeArea.setOnClickListener {
                val item = getItemByLayoutPosition(holder.layoutPosition) ?: return@setOnClickListener
                if (item.itemType == TYPE_MORE || item.pendingState != null) return@setOnClickListener
                val commentId = item.id?.takeIf { it.isNotBlank() } ?: return@setOnClickListener
                val authorPubkey = item.nostrAuthorPubkey?.takeIf { it.isNotBlank() }
                    ?: return@setOnClickListener
                requireNostrSigner {
                    if (item.likedByMe) {
                        // 取消点赞：先本地回退，再删掉自己那条 reaction，失败则恢复
                        val reactionId = item.myReactionId
                        applyLikeState(commentId, liked = false)
                        deleteNostrReaction(commentId, reactionId)
                    } else {
                        // 乐观更新：先本地显示为已赞、计数+1；同步失败再回滚
                        applyLikeState(commentId, liked = true)
                        publishNostrReaction(commentId, authorPubkey)
                    }
                }
            }
        }

        private fun showNostrReplyEditor(item: ReviewUiItem) {
            val parentId = item.id?.takeIf { it.isNotBlank() } ?: return
            val parentAuthor = item.nostrAuthorPubkey?.takeIf { it.isNotBlank() } ?: return
            val rootId = item.nostrRootExcerptId?.takeIf { it.isNotBlank() } ?: return
            val rootAuthor = item.nostrRootExcerptPubkey?.takeIf { it.isNotBlank() } ?: return
            val draftKey = reviewDraftKey("reply", parentId)
            val editBinding = io.legado.app.databinding.DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = getString(R.string.nostr_comment_hint)
                editView.minLines = 3
                editView.maxLines = 8
                editView.setSingleLine(false)
                readReviewDraft(draftKey)?.let { editView.setText(it) }
            }
            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.nostr_comment_reply))
                .setView(editBinding.root)
                .setPositiveButton(getString(R.string.nostr_comment_publish), null)
                .setNegativeButton(android.R.string.cancel, null)
                .create()

            dialog.setOnShowListener {
                val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                positive.setOnClickListener {
                    val content = editBinding.editView.text?.toString().orEmpty().trim()
                    if (content.isBlank()) return@setOnClickListener
                    positive.isEnabled = false
                    val localId = insertLocalPendingItem(content, findDetailItemById(parentId))
                    localPendingPublish[localId] = PublishArgs(
                        text = content,
                        parentId = parentId,
                        parentAuthor = parentAuthor,
                        rootId = rootId,
                        rootAuthor = rootAuthor
                    )
                    dialog.dismiss()
                    // 乐观上屏：回复也挂在段落下的根摘录，气泡同样 +1。
                    postEvent(EventBus.REFRESH_REVIEW_SUMMARY, paragraphNum.toString())
                    NostrReviewPublisher.launchPublish(
                        paragraphNum = paragraphNum,
                        block = {
                            AppLog.put("段评发布开始 p=$paragraphNum id=$localId", tag = "Nostr")
                            val book = ReadBook.book ?: error("No book is open")
                            withContext(IO) {
                                NostrReviewPublisher.replyToComment(
                                    book = book,
                                    rootExcerptId = rootId,
                                    rootExcerptPubkey = rootAuthor,
                                    parentCommentId = parentId,
                                    parentAuthorPubkey = parentAuthor,
                                    content = content
                                )
                            }
                        },
                        onSuccess = { published ->
                            AppLog.put("段评发布成功 id=$localId", tag = "Nostr")
                            // 清草稿与弹窗无关，弹窗已关也要执行；统计已在点击时乐观 +1，成功后不再重复计入。
                            clearReviewDraft(draftKey)
                            // 已确认回复立刻入缓存/上屏（挂在父评论下），重开该段不必等联网。
                            val confirmed = confirmPublishedComment(published, content)
                            cachePublishedComment(parentId = parentId, item = confirmed)
                            runOnUiIfAlive {
                                removeLocalPendingItem(localId)
                                appendReplyToItem(parentId, confirmed)
                                renderUiItems()
                                loadDetailPage(paragraphNum, 1, append = false)
                            }
                        },
                        onFailure = { cause ->
                            AppLog.put("段评发布失败 id=$localId：${cause.localizedMessage}", cause, tag = "Nostr")
                            // 失败必落草稿态：弹窗已关闭也要把草稿存下，下次打开发布框自动回填；
                            // 占位转草稿态仅在弹窗存活时做
                            saveReviewDraft(draftKey, content)
                            // 失败后把气泡回退到真实值。
                            postEvent(EventBus.REFRESH_REVIEW_SUMMARY_ONLY, paragraphNum.toString())
                            runOnUiIfAlive {
                                setPendingState(localId, PENDING_FAILED)
                                context?.toastOnUi(
                                    cause.localizedMessage ?: getString(R.string.nostr_comment_failed)
                                )
                            }
                        }
                    )
                }
            }

            dialog.show()
        }

        private fun publishNostrReaction(commentId: String, authorPubkey: String) {
            lifecycleScope.launch {
                val result = runCatching {
                    withContext(IO) {
                        NostrReviewPublisher.reactToComment(commentId, authorPubkey)
                    }
                }.onFailure { if (it is CancellationException) throw it }
                result.onSuccess { reactionId ->
                    // 记下自己那条 reaction 的 id，再次点击时用它删除
                    applyLikeState(commentId, liked = true, reactionId = reactionId)
                }.onFailure {
                    // 远端同步失败：回滚本地点赞态与计数
                    applyLikeState(commentId, liked = false)
                    context?.toastOnUi(it.localizedMessage ?: getString(R.string.nostr_comment_failed))
                }
            }
        }

        /** 取消点赞：NIP-09 删除自己那条 reaction；失败则恢复点赞态。 */
        private fun deleteNostrReaction(commentId: String, reactionId: String?) {
            lifecycleScope.launch {
                val result = runCatching {
                    withContext(IO) {
                        NostrReviewPublisher.deleteReaction(reactionId.orEmpty())
                    }
                }.onFailure { if (it is CancellationException) throw it }
                result.onFailure {
                    applyLikeState(commentId, liked = true, reactionId = reactionId)
                    context?.toastOnUi(it.localizedMessage ?: getString(R.string.nostr_comment_failed))
                }
            }
        }

        private fun bindAudioState(binding: ItemReviewCommentBinding, item: ReviewUiItem) {
            if (item.itemType == TYPE_MORE || item.audioUrl.isNullOrBlank()) {
                binding.tvAudio.gone()
                return
            }
            val isCurrentAudio = item.audioUrl == currentAudioUrl
            val audioTextRes = when {
                isCurrentAudio && isAudioPreparing -> R.string.loading
                isCurrentAudio && reviewAudioPlayer?.isPlaying == true -> R.string.review_pause_audio
                else -> R.string.review_play_audio
            }
            val audioIconRes = if (isCurrentAudio && reviewAudioPlayer?.isPlaying == true) {
                R.drawable.ic_pause_24dp
            } else {
                R.drawable.ic_play_24dp
            }
            binding.tvAudio.visible()
            binding.tvAudio.text = context.getString(audioTextRes)
            binding.tvAudio.setCompoundDrawablesRelativeWithIntrinsicBounds(
                audioIconRes,
                0,
                0,
                0
            )
        }

        private fun bindBadges(
            container: FlexboxLayout,
            badges: List<String>,
            pendingState: String? = null
        ) {
            container.removeAllViews()
            if (badges.isEmpty() && pendingState == null) {
                container.gone()
                return
            }
            container.visible()
            badges.forEach { badge ->
                if (badge.isAbsUrl() || badge.isDataUrl()) {
                    val badgeHeight = 20.dpToPx()
                    val iv = ImageView(context).apply {
                        layoutParams = FlexboxLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            badgeHeight
                        ).apply { setMargins(0, 0, 6.dpToPx(), 0) }
                        scaleType = ImageView.ScaleType.FIT_CENTER
                        adjustViewBounds = true
                    }
                    ImageLoader.load(context, badge).into(iv)
                    container.addView(iv)
                } else {
                    val tv = TextView(context).apply {
                        layoutParams = FlexboxLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { setMargins(0, 0, 6.dpToPx(), 0) }
                        setPadding(6.dpToPx(), 2.dpToPx(), 6.dpToPx(), 2.dpToPx())
                        setTextColor(context.getCompatColor(R.color.secondaryText))
                        textSize = 11f
                        text = badge
                        setBackgroundResource(R.drawable.bg_review_badge_chip)
                    }
                    container.addView(tv)
                }
            }
            if (pendingState != null) {
                container.addView(createPendingChip(pendingState))
            }
        }

        private fun createPendingChip(pendingState: String): TextView {
            val sending = pendingState == PENDING_SENDING
            return TextView(context).apply {
                layoutParams = FlexboxLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 0, 6.dpToPx(), 0) }
                setPadding(6.dpToPx(), 2.dpToPx(), 6.dpToPx(), 2.dpToPx())
                setTextColor(context.getCompatColor(if (sending) R.color.secondaryText else R.color.accent))
                textSize = 11f
                text = context.getString(
                    if (sending) R.string.nostr_comment_sending else R.string.nostr_comment_draft_unsent
                )
                setBackgroundResource(R.drawable.bg_review_badge_chip)
            }
        }
    }
}
