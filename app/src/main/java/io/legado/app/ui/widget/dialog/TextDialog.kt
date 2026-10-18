package io.legado.app.ui.widget.dialog

import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Spannable
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.view.textclassifier.TextClassifier
import androidx.core.view.GravityCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.drawerlayout.widget.DrawerLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.base.BaseDialogFragment
import io.legado.app.databinding.DialogTextViewBinding
import io.legado.app.databinding.ItemHelpTocBinding
import io.legado.app.help.HelpSections
import io.legado.app.help.IntentData
import io.legado.app.help.findTextRanges
import io.legado.app.lib.theme.AppColorScheme
import io.legado.app.utils.ColorUtils
import io.legado.app.utils.applyTint
import io.legado.app.utils.setHtml
import io.legado.app.utils.setLayout
import io.legado.app.utils.viewbindingdelegate.viewBinding
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.glide.GlideImagesPlugin
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class TextDialog() : BaseDialogFragment(R.layout.dialog_text_view) {

    /** 全宽大弹窗(更新日志/帮助/教程/源码查看):大浮动形态,圆角+四周留边 */
    override val dialogForm = DialogForm.FULL_SCREEN

    enum class Mode {
        MD, HTML, TEXT
    }

    constructor(
        title: String,
        content: String?,
        mode: Mode = Mode.TEXT,
        time: Long = 0,
        autoClose: Boolean = false,
        showToc: Boolean = false
    ) : this() {
        arguments = Bundle().apply {
            putString("title", title)
            putString("content", IntentData.put(content))
            putString("mode", mode.name)
            putLong("time", time)
            putBoolean("showToc", showToc)
        }
        isCancelable = false
        this.autoClose = autoClose
    }

    private val binding by viewBinding(DialogTextViewBinding::bind)
    private var time = 0L
    private var autoClose: Boolean = false
    private var markwon: Markwon? = null
    private var fullContent: String = ""
    private var sections: List<HelpSections.Section> = emptyList()
    /** 目录扁平项:depth 0=父节, 1=子节 */
    private var tocEntries: List<TocEntry> = emptyList()
    private var selectedSection = 0
    private var renderJob: Job? = null

    // 文档内搜索状态
    private var searchQuery = ""
    private var searchRanges: List<IntRange> = emptyList()
    private var searchIndex = -1
    private val searchSpans = mutableListOf<Any>()

    private data class TocEntry(val depth: Int, val section: HelpSections.Section)

    override fun onStart() {
        super.onStart()
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, 0.9f)
    }

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        binding.toolBar.inflateMenu(R.menu.dialog_text)
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        binding.toolBar.menu.applyTint(requireContext())
        binding.toolBar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.menu_search -> {
                    binding.searchBar.isVisible = !binding.searchBar.isVisible
                    if (binding.searchBar.isVisible) {
                        binding.searchInput.requestFocus()
                        binding.searchInput.setSelection(binding.searchInput.text?.length ?: 0)
                    }
                }
                R.id.menu_help_toc -> binding.drawerLayout.openDrawer(GravityCompat.END)
                R.id.menu_close -> dismissAllowingStateLoss()
            }
            true
        }
        arguments?.let {
            binding.toolBar.title = it.getString("title")
            val content = IntentData.get(it.getString("content")) ?: ""
            when (it.getString("mode")) {
                Mode.MD.name -> viewLifecycleOwner.lifecycleScope.launch {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        binding.textView.setTextClassifier(TextClassifier.NO_OP)
                    }
                    binding.textView.setLineSpacing(0f, 1.3f)
                    fullContent = content
                    markwon = withContext(IO) {
                        Markwon.builder(requireContext())
                            .usePlugin(GlideImagesPlugin.create(requireContext()))
                            .usePlugin(HtmlPlugin.create())
                            .usePlugin(TablePlugin.create(HelpMarkwonTheme.tableTheme()))
                            .usePlugin(HelpMarkwonTheme.plugin())
                            .build()
                    }
                    renderMd(fullContent)
                    if (it.getBoolean("showToc")) setupToc()
                }

                Mode.HTML.name -> binding.textView.setHtml(content)
                else -> {
                    if (content.length >= 32 * 1024) {
                        val truncatedContent =
                            content.substring(0, 32 * 1024) + "\n\n数据太大，无法全部显示…"
                        binding.textView.text = truncatedContent
                    } else {
                        binding.textView.text = content
                    }
                }
            }
            time = it.getLong("time", 0L)
        }
        binding.searchInput.doAfterTextChanged { text ->
            onSearchQueryChanged(text?.toString().orEmpty())
        }
        binding.btnSearchPrev.setOnClickListener { moveToMatch(searchIndex - 1) }
        binding.btnSearchNext.setOnClickListener { moveToMatch(searchIndex + 1) }

        if (time > 0) {
            // 倒计时期间禁用关闭按钮，强制用户阅读
            val closeItem = binding.toolBar.menu.findItem(R.id.menu_close)
            closeItem?.isEnabled = false
            closeItem?.isVisible = false
            binding.badgeView.setBadgeCount((time / 1000).toInt())
            lifecycleScope.launch {
                while (time > 0) {
                    delay(1000)
                    time -= 1000
                    binding.badgeView.setBadgeCount((time / 1000).toInt())
                    if (time <= 0) {
                        view.post {
                            dialog?.setCancelable(true)
                            // 倒计时结束后恢复关闭按钮
                            closeItem?.isEnabled = true
                            closeItem?.isVisible = true
                            if (autoClose) dialog?.cancel()
                        }
                    }
                }
            }
        } else {
            view.post {
                dialog?.setCancelable(true)
            }
        }
    }

    private fun renderMd(md: String, onDone: (() -> Unit)? = null) {
        val mw = markwon ?: return
        renderJob?.cancel()
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            val parsed = withContext(IO) { mw.toMarkdown(md) }
            mw.setParsedMarkdown(binding.textView, parsed)
            binding.textView.scrollTo(0, 0)
            onDone?.invoke()
        }
    }

    /** 章节目录:切出 ≥2 节才亮入口;点章节只渲染该节,「全部」恢复全文 */
    private fun setupToc() {
        sections = HelpSections.parse(fullContent)
        if (sections.isEmpty()) return
        tocEntries = buildList {
            sections.forEach { section ->
                add(TocEntry(0, section))
                section.children.forEach { child -> add(TocEntry(1, child)) }
            }
        }
        binding.tocList.layoutManager = LinearLayoutManager(requireContext())
        binding.tocList.adapter = TocAdapter()
        binding.toolBar.menu.findItem(R.id.menu_help_toc)?.isVisible = true
        binding.drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
    }

    // ── 文档内搜索 ──

    /** 搜索始终作用于整篇文档:查询变化时若在单节视图,先切回「全部」再高亮 */
    private fun onSearchQueryChanged(query: String) {
        searchQuery = query
        if (query.isBlank()) {
            searchIndex = -1
            searchRanges = emptyList()
            clearSearchSpans()
            binding.searchCount.text = ""
            return
        }
        if (selectedSection != 0) {
            selectedSection = 0
            binding.tocList.adapter?.notifyDataSetChanged()
            renderMd(fullContent) { applySearchHighlight(query) }
        } else {
            applySearchHighlight(query)
        }
    }

    private fun applySearchHighlight(query: String, keepIndex: Boolean = false) {
        val text = binding.textView.text ?: return
        searchRanges = findTextRanges(text.toString(), query)
        if (searchRanges.isEmpty()) {
            searchIndex = -1
            clearSearchSpans()
            binding.searchCount.text = "0/0"
            return
        }
        if (!keepIndex || searchIndex !in searchRanges.indices) {
            searchIndex = 0
        }
        repaintSpans()
        scrollToCurrentMatch()
    }

    private fun moveToMatch(index: Int) {
        if (searchRanges.isEmpty()) return
        searchIndex = ((index % searchRanges.size) + searchRanges.size) % searchRanges.size
        repaintSpans()
        scrollToCurrentMatch()
    }

    private fun repaintSpans() {
        val text = binding.textView.text as? Spannable ?: return
        clearSearchSpans()
        val scheme = AppColorScheme.current
        val normal = ColorUtils.adjustAlpha(scheme.primary, 0.25f)
        val current = ColorUtils.adjustAlpha(scheme.primary, 0.50f)
        searchRanges.forEachIndexed { i, range ->
            val span = BackgroundColorSpan(if (i == searchIndex) current else normal)
            text.setSpan(span, range.first, range.last + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            searchSpans.add(span)
        }
        binding.searchCount.text = "${searchIndex + 1}/${searchRanges.size}"
    }

    private fun clearSearchSpans() {
        val text = binding.textView.text as? Spannable ?: return
        searchSpans.forEach { text.removeSpan(it) }
        searchSpans.clear()
    }

    private fun scrollToCurrentMatch() {
        if (searchIndex !in searchRanges.indices) return
        binding.textView.post {
            val layout = binding.textView.layout ?: return@post
            val line = layout.getLineForOffset(searchRanges[searchIndex].first)
            val y = (layout.getLineTop(line) - binding.textView.totalPaddingTop).coerceAtLeast(0)
            binding.textView.scrollTo(0, y)
        }
    }

    private inner class TocAdapter : RecyclerView.Adapter<TocAdapter.VH>() {

        inner class VH(val itemBinding: ItemHelpTocBinding) :
            RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            VH(ItemHelpTocBinding.inflate(layoutInflater, parent, false))

        override fun getItemCount() = tocEntries.size + 1

        override fun onBindViewHolder(holder: VH, position: Int) {
            val scheme = AppColorScheme.current
            val selected = position == selectedSection
            holder.itemBinding.root.text =
                if (position == 0) getString(R.string.all) else tocEntries[position - 1].section.title
            holder.itemBinding.root.setTextColor(if (selected) scheme.primary else scheme.onSurface)
            holder.itemBinding.root.typeface =
                if (selected || position != 0 && tocEntries[position - 1].depth == 0) {
                    Typeface.DEFAULT_BOLD
                } else {
                    Typeface.DEFAULT
                }
            // 子节缩进 + 小字号,与父节区分层级
            val child = position != 0 && tocEntries[position - 1].depth == 1
            holder.itemBinding.root.setPaddingRelative(
                if (child) 44.dp else 20.dp,
                12.dp,
                20.dp,
                12.dp
            )
            holder.itemBinding.root.textSize = if (child) 13f else 15f
            holder.itemBinding.root.setOnClickListener {
                val pos = holder.bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION && pos != selectedSection) {
                    val old = selectedSection
                    selectedSection = pos
                    notifyItemChanged(old)
                    notifyItemChanged(pos)
                    renderMd(if (pos == 0) fullContent else tocEntries[pos - 1].section.text) {
                        if (searchQuery.isNotBlank()) applySearchHighlight(searchQuery, keepIndex = true)
                    }
                }
                binding.drawerLayout.closeDrawers()
            }
        }
    }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density + 0.5f).toInt()

}
