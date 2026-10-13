package io.legado.app.ui.config

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.postDelayed
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.jeremyliao.liveeventbus.LiveEventBus
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.databinding.DialogNostrSignerBinding
import io.legado.app.help.AppFreezeMonitor
import io.legado.app.help.DispatchersMonitor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.nostr.NostrNip46
import io.legado.app.help.nostr.NostrNip46Transport
import io.legado.app.help.nostr.NostrNip55
import io.legado.app.help.nostr.NostrProfileStore
import io.legado.app.help.nostr.NostrRelays
import io.legado.app.help.nostr.NostrSignerStore
import io.legado.app.utils.QRCodeUtils
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.prefs.NostrSignerPreference
import io.legado.app.lib.prefs.fragment.PreferenceFragment
import io.legado.app.lib.theme.primaryColor
import io.legado.app.model.CheckSource
import io.legado.app.model.ImageProvider
import io.legado.app.receiver.SharedReceiverActivity
import io.legado.app.service.WebService
import io.legado.app.service.McpService
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.widget.number.NumberPickerDialog
import io.legado.app.utils.LogUtils
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.postEvent
import io.legado.app.utils.putPrefBoolean
import io.legado.app.utils.putPrefString
import io.legado.app.utils.removePref
import io.legado.app.utils.restart
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 其它设置
 */
class OtherConfigFragment : PreferenceFragment(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private val viewModel by activityViewModels<ConfigViewModel>()
    private val packageManager = appCtx.packageManager
    private val componentName = ComponentName(
        appCtx,
        SharedReceiverActivity::class.java.name
    )
    private val localBookTreeSelect = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { treeUri ->
            AppConfig.defaultBookTreeUri = treeUri.toString()
        }
    }

    /** NIP-55：本机签名器用 get_public_key 回包（公钥 + 包名）完成连接。 */
    private val nostrExternalSignerConnect =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            handleExternalSignerResult(result.resultCode, result.data)
        }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        putPrefBoolean(PreferKey.processText, isProcessTextEnabled())
        addPreferencesFromResource(R.xml.pref_config_other)
        upPreferenceSummary(PreferKey.userAgent, AppConfig.userAgent)
        upPreferenceSummary(PreferKey.preDownloadNum, AppConfig.preDownloadNum.toString())
        upPreferenceSummary(PreferKey.threadCount, AppConfig.threadCount.toString())
        upPreferenceSummary(PreferKey.webPort, AppConfig.webPort.toString())
        upPreferenceSummary(PreferKey.mcpPort, AppConfig.mcpPort.toString())
        upPreferenceSummary(
            PreferKey.mcpToken,
            getString(R.string.mcp_token_summary, maskToken(McpService.ensureToken()))
        )
        AppConfig.defaultBookTreeUri?.let {
            upPreferenceSummary(PreferKey.defaultBookTreeUri, it)
        }
        upPreferenceSummary(PreferKey.checkSource, CheckSource.summary)
        upPreferenceSummary(PreferKey.bitmapCacheSize, AppConfig.bitmapCacheSize.toString())
        upPreferenceSummary(PreferKey.imageRetainNum, AppConfig.imageRetainNum.toString())
        upPreferenceSummary(PreferKey.sourceEditMaxLine, AppConfig.sourceEditMaxLine.toString())
        findPreference<Preference>("nostrSigner")?.let { preference ->
            (preference as? NostrSignerPreference)?.apply {
                onConnectClick = { showNostrSignerDialog() }
                onLogoutClick = { logoutNostrSigner() }
            }
        }
        updateNostrSignerSummary()
        updateNostrRelaysSummary()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        activity?.setTitle(R.string.other_setting)
        preferenceManager.sharedPreferences?.registerOnSharedPreferenceChangeListener(this)
        listView.setEdgeEffectColor(primaryColor)
    }

    override fun onDestroy() {
        super.onDestroy()
        preferenceManager.sharedPreferences?.unregisterOnSharedPreferenceChangeListener(this)
    }

    override fun onPreferenceTreeClick(preference: Preference): Boolean {
        when (preference.key) {
            PreferKey.userAgent -> showUserAgentDialog()
            PreferKey.defaultBookTreeUri -> localBookTreeSelect.launch {
                title = getString(R.string.select_book_folder)
                mode = HandleFileContract.DIR_SYS
            }

            PreferKey.preDownloadNum -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.pre_download))
                .setMaxValue(9999)
                .setMinValue(0)
                .setValue(AppConfig.preDownloadNum)
                .show {
                    AppConfig.preDownloadNum = it
                }

            PreferKey.threadCount -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.threads_num_title))
                .setMaxValue(999)
                .setMinValue(1)
                .setValue(AppConfig.threadCount)
                .show {
                    AppConfig.threadCount = it
                }

            PreferKey.webPort -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.web_port_title))
                .setMaxValue(60000)
                .setMinValue(1024)
                .setValue(AppConfig.webPort)
                .show {
                    AppConfig.webPort = it
                }

            PreferKey.mcpPort -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.mcp_port_title))
                .setMaxValue(60000)
                .setMinValue(1024)
                .setValue(AppConfig.mcpPort)
                .show {
                    AppConfig.mcpPort = it
                }

            PreferKey.mcpToken -> {
                val token = McpService.ensureToken()
                appCtx.sendToClip(token)
                toastOnUi(getString(R.string.mcp_token_copied))
            }

            "mcpTokenRegenerate" -> {
                McpService.regenerateToken()
                upPreferenceSummary(
                    PreferKey.mcpToken,
                    getString(R.string.mcp_token_summary, maskToken(AppConfig.mcpToken))
                )
                toastOnUi(getString(R.string.mcp_token_regenerated))
            }

            "nostrRelays" -> showNostrRelaysDialog()
            "nostrRelayDiscover" -> discoverNostrRelays()

            PreferKey.cleanCache -> clearCache()
            PreferKey.uploadRule -> showDialogFragment<DirectLinkUploadConfig>()
            PreferKey.checkSource -> showDialogFragment<CheckSourceConfig>()
            PreferKey.bitmapCacheSize -> {
                NumberPickerDialog(requireContext())
                    .setTitle(getString(R.string.bitmap_cache_size))
                    .setMaxValue(1024)
                    .setMinValue(1)
                    .setValue(AppConfig.bitmapCacheSize)
                    .show {
                        AppConfig.bitmapCacheSize = it
                        ImageProvider.bitmapLruCache.resize(ImageProvider.cacheSize)
                    }
            }
            PreferKey.imageRetainNum -> NumberPickerDialog(requireContext())
                .setTitle(getString(R.string.image_retain_number))
                .setMaxValue(999)
                .setMinValue(0)
                .setValue(AppConfig.imageRetainNum)
                .show {
                    AppConfig.imageRetainNum = it
                }

            PreferKey.sourceEditMaxLine -> {
                NumberPickerDialog(requireContext())
                    .setTitle(getString(R.string.source_edit_text_max_line))
                    .setMaxValue(Int.MAX_VALUE)
                    .setMinValue(10)
                    .setValue(AppConfig.sourceEditMaxLine)
                    .show {
                        AppConfig.sourceEditMaxLine = it
                    }
            }

            PreferKey.clearWebViewData -> clearWebViewData()
            "localPassword" -> alertLocalPassword()
            PreferKey.shrinkDatabase -> shrinkDatabase()
        }
        return super.onPreferenceTreeClick(preference)
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        when (key) {
            PreferKey.preDownloadNum -> {
                upPreferenceSummary(key, AppConfig.preDownloadNum.toString())
            }

            PreferKey.threadCount -> {
                upPreferenceSummary(key, AppConfig.threadCount.toString())
                postEvent(PreferKey.threadCount, "")
            }

            PreferKey.webPort -> {
                upPreferenceSummary(key, AppConfig.webPort.toString())
                if (WebService.isRun) {
                    WebService.stop(requireContext())
                    WebService.start(requireContext())
                }
            }

            PreferKey.mcpPort -> {
                upPreferenceSummary(key, AppConfig.mcpPort.toString())
                if (McpService.isRun) {
                    McpService.stop(requireContext())
                    McpService.start(requireContext())
                }
            }

            PreferKey.defaultBookTreeUri -> {
                upPreferenceSummary(key, AppConfig.defaultBookTreeUri)
            }

            PreferKey.recordLog -> {
                AppConfig.recordLog = appCtx.getPrefBoolean(PreferKey.recordLog)
                LogUtils.upLevel()
                LogUtils.logDeviceInfo()
                LiveEventBus.config().enableLogger(AppConfig.recordLog)
                AppFreezeMonitor.init(appCtx)
                DispatchersMonitor.init()
            }

            PreferKey.processText -> sharedPreferences?.let {
                setProcessTextEnable(it.getBoolean(key, true))
            }

            PreferKey.showDiscovery, PreferKey.showRss -> postEvent(EventBus.NOTIFY_MAIN, true)
            PreferKey.language -> listView.postDelayed(1000) {
                appCtx.restart()
            }

            PreferKey.userAgent -> listView.post {
                upPreferenceSummary(PreferKey.userAgent, AppConfig.userAgent)
            }

            PreferKey.checkSource -> listView.post {
                upPreferenceSummary(PreferKey.checkSource, CheckSource.summary)
            }

            PreferKey.bitmapCacheSize -> {
                upPreferenceSummary(key, AppConfig.bitmapCacheSize.toString())
            }

            PreferKey.imageRetainNum -> {
                upPreferenceSummary(key, AppConfig.imageRetainNum.toString())
            }

            PreferKey.sourceEditMaxLine -> {
                upPreferenceSummary(key, AppConfig.sourceEditMaxLine.toString())
            }
        }
    }

    private fun upPreferenceSummary(preferenceKey: String, value: String?) {
        val preference = findPreference<Preference>(preferenceKey) ?: return
        when (preferenceKey) {
            PreferKey.preDownloadNum -> preference.summary =
                getString(R.string.pre_download_s, value)

            PreferKey.threadCount -> preference.summary = getString(R.string.threads_num, value)
            PreferKey.webPort -> preference.summary = getString(R.string.web_port_summary, value)
            PreferKey.mcpPort -> preference.summary = getString(R.string.mcp_port_summary, value)
            PreferKey.bitmapCacheSize -> preference.summary =
                getString(R.string.bitmap_cache_size_summary, value)
            PreferKey.imageRetainNum -> preference.summary =
                getString(R.string.image_retain_number_summary, value)

            PreferKey.sourceEditMaxLine -> preference.summary =
                getString(R.string.source_edit_max_line_summary, value)

            else -> if (preference is ListPreference) {
                val index = preference.findIndexOfValue(value)
                // Set the summary to reflect the new value.
                preference.summary = if (index >= 0) preference.entries[index] else null
            } else {
                preference.summary = value
            }
        }
    }

    @SuppressLint("InflateParams")
    private fun showUserAgentDialog() {
        alert(getString(R.string.user_agent)) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = getString(R.string.user_agent)
                editView.setText(AppConfig.userAgent)
            }
            customView { alertBinding.root }
            okButton {
                val userAgent = alertBinding.editView.text?.toString()
                if (userAgent.isNullOrBlank()) {
                    removePref(PreferKey.userAgent)
                } else {
                    putPrefString(PreferKey.userAgent, userAgent)
                }
            }
            cancelButton()
        }
    }

    private var nostrSignerDialog: AlertDialog? = null

    /**
     * 合并后的签名器入口：二维码配对 / 本机签名器（NIP-55） / bunker 三种连接方式。
     * 三个方式登录成功后都会自动关闭本弹窗。
     */
    private fun showNostrSignerDialog() {
        val binding = DialogNostrSignerBinding.inflate(layoutInflater)
        val secondaryColor = requireContext().getCompatColor(R.color.secondaryText)
        binding.tvQrSummary.setTextColor(secondaryColor)
        binding.tvLocalSummary.setTextColor(secondaryColor)
        binding.tvQrSummary.setText(
            if (NostrSignerStore.load() != null) R.string.nostr_signer_qr_connected
            else R.string.nostr_signer_qr_summary
        )
        val external = NostrSignerStore.loadExternal()
        binding.tvLocalSummary.text = when {
            external == null && !NostrNip55.isSignerInstalled(appCtx) ->
                getString(R.string.nostr_external_signer_not_installed)

            external == null -> getString(R.string.nostr_signer_local_connect)
            NostrSignerStore.activeSigner == NostrSignerStore.SIGNER_NIP55 ->
                getString(R.string.nostr_signer_local_in_use, appName(external.packageName))

            else -> getString(R.string.nostr_signer_local_switch, appName(external.packageName))
        }
        binding.etBunker.setText(
            NostrSignerStore.load()?.bunkerUri ?: AppConfig.nostrRestoredBunkerUri.orEmpty()
        )

        binding.rowQr.setOnClickListener {
            // 二维码弹窗自行显示与等待扫码，先关掉本弹窗避免叠层。
            closeNostrSignerDialog()
            startNostrConnect()
        }
        binding.rowLocal.setOnClickListener {
            if (external == null) {
                connectLocalSigner()
            } else {
                closeNostrSignerDialog()
                showLocalSignerManageDialog(external)
            }
        }
        binding.btnBunkerLogin.setOnClickListener {
            val bunkerUri = binding.etBunker.text?.toString().orEmpty().trim()
            if (bunkerUri.isBlank()) {
                toastOnUi(getString(R.string.nostr_signer_invalid_uri))
                return@setOnClickListener
            }
            connectNostrSigner(bunkerUri) { closeNostrSignerDialog() }
        }

        nostrSignerDialog = alert(getString(R.string.nostr_signer)) {
            customView { binding.root }
        }
    }

    private fun closeNostrSignerDialog() {
        nostrSignerDialog?.takeIf { it.isShowing }?.dismiss()
        nostrSignerDialog = null
    }

    /** 退出当前使用的签名器；另一台已连接时由 store 的回退逻辑自动接上。 */
    private fun logoutNostrSigner() {
        val session = NostrSignerStore.load()
        val external = NostrSignerStore.loadExternal()
        if (session == null && external == null) return
        alert(getString(R.string.nostr_signer_logout), getString(R.string.nostr_signer_logout_confirm)) {
            okButton {
                when (NostrSignerStore.activeSigner) {
                    NostrSignerStore.SIGNER_NIP55 -> NostrSignerStore.clearExternal()
                    NostrSignerStore.SIGNER_NIP46 -> NostrSignerStore.clear()
                    else -> if (NostrSignerStore.load() != null) NostrSignerStore.clear()
                    else NostrSignerStore.clearExternal()
                }
                updateNostrSignerSummary()
                // 退出后清掉可能残留的「可首评」占位标记。
                postEvent(EventBus.REFRESH_REVIEW_SUMMARY, "")
                toastOnUi(getString(R.string.nostr_signer_disconnected))
            }
            cancelButton()
        }
    }

    /** 签名器 App 名（取不到时回退包名），用于弹窗内的行副标题。 */
    private fun appName(packageName: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(packageName, 0))
            .toString()
    }.getOrDefault(packageName)

    private fun connectNostrSigner(bunkerUri: String, onSuccess: () -> Unit = {}) {
        val connection = runCatching { NostrNip46.parseBunkerUri(bunkerUri) }
            .getOrElse {
                toastOnUi(it.localizedMessage ?: getString(R.string.nostr_signer_invalid_uri))
                return
            }
        val keyPair = runCatching { NostrNip46.newClientKeyPair() }
            .getOrElse {
                toastOnUi(getString(R.string.nostr_signer_failed))
                return
            }
        toastOnUi(getString(R.string.nostr_signer_connecting))
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val connectResponse = NostrNip46Transport.request(
                        connection,
                        keyPair,
                        NostrNip46.buildConnectRequest(keyPair, connection),
                        onAuthUrl = ::showNostrAuthUrl
                    )
                    if (connectResponse.error != null) error(connectResponse.error)
                    if (connection.secret != null && connectResponse.result != "ack" &&
                        connectResponse.result != connection.secret
                    ) error("Remote signer did not confirm the bunker secret")

                    val publicKeyResponse = NostrNip46Transport.request(
                        connection,
                        keyPair,
                        NostrNip46.buildGetPublicKeyRequest(keyPair, connection.signerPubkey),
                        onAuthUrl = ::showNostrAuthUrl
                    )
                    if (publicKeyResponse.error != null) error(publicKeyResponse.error)
                    val userPubkey = publicKeyResponse.result.orEmpty().lowercase()
                    require(userPubkey.length == 64 && userPubkey.all { it.digitToIntOrNull(16) != null }) {
                        "Remote signer returned an invalid user public key"
                    }

                    val relayResponse = runCatching {
                        NostrNip46Transport.request(
                            connection,
                            keyPair,
                            NostrNip46.buildSwitchRelaysRequest(keyPair, connection.signerPubkey)
                        ).result
                    }.getOrNull()
                    val relays = relayResponse?.let { value ->
                        runCatching { org.json.JSONArray(value) }.getOrNull()?.let { array ->
                            (0 until array.length()).map { array.getString(it) }
                        }
                    }?.filter { it.startsWith("wss://", ignoreCase = true) }
                        ?.takeIf { it.isNotEmpty() } ?: connection.relays

                    NostrSignerStore.save(
                        NostrSignerStore.Session(
                            bunkerUri = bunkerUri,
                            clientPrivateKey = keyPair.privateKey,
                            clientPublicKey = keyPair.publicKey,
                            signerPubkey = connection.signerPubkey,
                            userPubkey = userPubkey,
                            relays = relays
                        )
                    )
                    userPubkey
                }
            }.onFailure { if (it is CancellationException) throw it }
            result.onSuccess { userPubkey ->
                updateNostrSignerSummary()
                onSuccess()
                syncNostrProfile(userPubkey)
                // 登录成功后让阅读页重新统计段评,以便给无评论的段落显示可首评图标。
                postEvent(EventBus.REFRESH_REVIEW_SUMMARY, "")
                toastOnUi(getString(R.string.nostr_signer_connected))
            }.onFailure {
                AppLog.put("Nostr signer connection failed", it, tag = "Nostr")
                toastOnUi(it.localizedMessage ?: getString(R.string.nostr_signer_failed))
            }
        }
    }

    /** 本机签名器：未连接时发起 NIP-55 配对（多个签名器由系统弹选择）。 */
    private fun connectLocalSigner() {
        if (!NostrNip55.isSignerInstalled(appCtx)) {
            toastOnUi(getString(R.string.nostr_external_signer_not_installed))
            return
        }
        nostrExternalSignerConnect.launch(NostrNip55.buildGetPublicKeyIntent())
    }

    /** 本机签名器已连接：断开（远程也已连接时可切换为当前使用）。 */
    private fun showLocalSignerManageDialog(account: NostrSignerStore.ExternalAccount) {
        alert(getString(R.string.nostr_external_signer)) {
            setMessage(account.userPubkey)
            positiveButton(getString(R.string.nostr_signer_disconnect)) {
                NostrSignerStore.clearExternal()
                updateNostrSignerSummary()
                // 断开后清掉可能残留的「可首评」占位标记。
                postEvent(EventBus.REFRESH_REVIEW_SUMMARY, "")
                toastOnUi(getString(R.string.nostr_signer_disconnected))
            }
            if (NostrSignerStore.load() != null &&
                NostrSignerStore.activeSigner != NostrSignerStore.SIGNER_NIP55
            ) {
                neutralButton(getString(R.string.nostr_signer_switch_to)) {
                    activateSigner(NostrSignerStore.SIGNER_NIP55)
                }
            }
            cancelButton()
        }
    }

    private fun handleExternalSignerResult(resultCode: Int, data: Intent?) {
        when (val outcome = NostrNip55.outcomeOf(resultCode, data)) {
            is NostrNip55.Outcome.Success -> {
                val userPubkey = NostrNip55.publicKeyFrom(outcome.result)
                val packageName = NostrNip55.signerPackageFrom(outcome.result)
                if (userPubkey == null || packageName.isNullOrBlank()) {
                    toastOnUi(getString(R.string.nostr_external_signer_failed))
                    return
                }
                NostrSignerStore.saveExternal(
                    NostrSignerStore.ExternalAccount(packageName, userPubkey)
                )
                updateNostrSignerSummary()
                closeNostrSignerDialog()
                syncNostrProfile(userPubkey)
                // 登录成功后让阅读页重新统计段评，以便给无评论的段落显示可首评图标。
                postEvent(EventBus.REFRESH_REVIEW_SUMMARY, "")
                toastOnUi(getString(R.string.nostr_signer_connected))
            }

            is NostrNip55.Outcome.Failed -> {
                AppLog.put("Nostr external signer failed: ${outcome.reason}", tag = "Nostr")
                toastOnUi(outcome.reason)
            }

            NostrNip55.Outcome.Rejected ->
                toastOnUi(getString(R.string.nostr_external_signer_rejected))
        }
    }

    /** 切换当前使用的签名器；两个签名器可同时保留，连接谁就用谁。 */
    private fun activateSigner(signer: String) {
        NostrSignerStore.activeSigner = signer
        updateNostrSignerSummary()
        postEvent(EventBus.REFRESH_REVIEW_SUMMARY, "")
        toastOnUi(getString(R.string.nostr_signer_switched))
    }

    private fun updateNostrSignerSummary() {
        findPreference<Preference>("nostrSigner")?.let { (it as? NostrSignerPreference)?.refresh() }
    }

    /** 登录后补拉账号资料（头像/昵称），拉完刷新父条目。 */
    private fun syncNostrProfile(pubkey: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { NostrProfileStore.sync(listOf(pubkey)) }
            }
            updateNostrSignerSummary()
        }
    }

    private fun updateNostrRelaysSummary() {
        val preference = findPreference<Preference>("nostrRelays") ?: return
        val relays = NostrRelays.effective()
        val current = if (relays.isEmpty()) {
            getString(R.string.nostr_relays_empty)
        } else {
            relays.joinToString(" ")
        }
        val builder = StringBuilder(getString(R.string.nostr_relays_summary, current))
        val cached = NostrRelays.cached()
        if (cached.isNotEmpty()) {
            builder.append('\n')
                .append(getString(R.string.nostr_relays_cached, cached.size))
            val time = NostrRelays.cacheTime
            if (time > 0) {
                builder.append('\n')
                    .append(
                        getString(
                            R.string.nostr_relays_last_check,
                            SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                                .format(Date(time))
                        )
                    )
            }
        }
        preference.summary = builder.toString()
    }

    /**
     * 对「用户配置 + 内置种子」的中继逐个做 NIP-11 + WebSocket 健康检查，
     * 过滤掉要求鉴权/付费/禁止写入或连不上的中继，把可用结果写回设置与缓存。
     */
    private fun discoverNostrRelays() {
        var job: Job? = null
        val dialog = alert(
            getString(R.string.nostr_relays_discover),
            getString(R.string.nostr_relays_discover_summary)
        ) {
            setCancelable(false)
            negativeButton(R.string.cancel) { job?.cancel() }
        }
        job = viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    NostrRelays.discover { done, total, relay ->
                        val message = getString(
                            R.string.nostr_relays_discovering, done, total, relay
                        )
                        activity?.runOnUiThread {
                            if (isAdded && dialog.isShowing) {
                                dialog.setMessage(message)
                            }
                        }
                    }
                }
            }.onFailure { if (it is CancellationException) throw it }
            result.onSuccess { relays ->
                dialog.dismiss()
                updateNostrRelaysSummary()
                toastOnUi(
                    if (relays.isEmpty()) {
                        getString(R.string.nostr_relays_discover_none)
                    } else {
                        getString(R.string.nostr_relays_discover_result, relays.size)
                    }
                )
            }.onFailure {
                AppLog.put("Nostr relay discovery failed", it, tag = "Nostr")
                dialog.dismiss()
                toastOnUi(
                    getString(
                        R.string.nostr_relays_discover_failed,
                        it.localizedMessage.orEmpty()
                    )
                )
            }
        }
    }

    private fun showNostrRelaysDialog() {
        alert(getString(R.string.nostr_relays)) {
            val editBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = getString(R.string.nostr_relays_hint)
                editView.inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_FLAG_MULTI_LINE
                editView.setText(AppConfig.nostrRelays)
            }
            customView { editBinding.root }
            okButton {
                AppConfig.nostrRelays = editBinding.editView.text?.toString().orEmpty()
                updateNostrRelaysSummary()
            }
            cancelButton()
        }
    }

    private fun showNostrAuthUrl(url: String) {
        activity?.runOnUiThread {
            if (!isAdded) return@runOnUiThread
            val uri = Uri.parse(url)
            if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
                AppLog.put("Nostr signer returned an invalid authorization URL", tag = "Nostr")
                return@runOnUiThread
            }
            alert(getString(R.string.nostr_signer_connecting), url) {
                positiveButton(R.string.open_in_browser) {
                    runCatching {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                    }.onFailure {
                        AppLog.put("Unable to open Nostr signer authorization URL", it, tag = "Nostr")
                    }
                }
            }
        }
    }

    private fun startNostrConnect() {
        if (NostrSignerStore.load() != null) {
            toastOnUi(getString(R.string.nostr_signer_connected))
            return
        }
        val keyPair = NostrNip46.newClientKeyPair()
        // 配对二维码里的中继必须由用户配置或自动发现：签名器只会向这些中继回传结果。
        val relays = NostrRelays.effective()
        if (relays.isEmpty()) {
            toastOnUi(getString(R.string.nostr_relays_missing))
            return
        }
        val token = NostrNip46.createNostrConnectToken(keyPair, relays)
        val bitmap = QRCodeUtils.createQRCode(token.uri, 720)
        if (bitmap == null) {
            toastOnUi(getString(R.string.nostr_signer_failed))
            return
        }
        val image = ImageView(requireContext()).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            contentDescription = getString(R.string.nostr_signer_scan_qr)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // 部分签名器无法生成二维码时，用户可以手动粘贴配对链接。
            setOnClickListener { requireContext().sendToClip(token.uri) }
        }
        var connectionJob: Job? = null
        val dialog = alert(getString(R.string.nostr_signer_pair_qr)) {
            setMessage(getString(R.string.nostr_signer_qr_copy_hint))
            setCancelable(false)
            customView { image }
            cancelButton { connectionJob?.cancel() }
        }
        connectionJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val message = NostrNip46Transport.awaitNostrConnect(
                        token.relays,
                        keyPair,
                        token.secret
                    )
                    val approval = message.approval
                    if (message.isConnectRequest) {
                        // 部分签名器先发 connect 请求，客户端回 ack 后才会继续。
                        NostrNip46Transport.publish(
                            token.relays,
                            NostrNip46.buildConnectAck(
                                keyPair = keyPair,
                                signerPubkey = approval.signerPubkey,
                                requestId = approval.requestId
                            )
                        )
                    }
                    val connection = NostrNip46.BunkerConnection(
                        signerPubkey = approval.signerPubkey,
                        relays = token.relays,
                        secret = null
                    )
                    val publicKeyResponse = NostrNip46Transport.request(
                        connection,
                        keyPair,
                        NostrNip46.buildGetPublicKeyRequest(keyPair, approval.signerPubkey),
                        onAuthUrl = ::showNostrAuthUrl
                    )
                    if (publicKeyResponse.error != null) error(publicKeyResponse.error)
                    val userPubkey = publicKeyResponse.result.orEmpty().lowercase()
                    require(userPubkey.length == 64 && userPubkey.all { it.digitToIntOrNull(16) != null }) {
                        "Remote signer returned an invalid user public key"
                    }
                    val relays = runCatching {
                        NostrNip46Transport.request(
                            connection,
                            keyPair,
                            NostrNip46.buildSwitchRelaysRequest(keyPair, approval.signerPubkey)
                        ).result
                    }.getOrNull()?.let { value ->
                        runCatching { org.json.JSONArray(value) }.getOrNull()?.let { array ->
                            (0 until array.length()).map { array.getString(it) }
                        }
                    }?.filter { it.startsWith("wss://", ignoreCase = true) }
                        ?.takeIf { it.isNotEmpty() } ?: token.relays
                    NostrSignerStore.save(
                        NostrSignerStore.Session(
                            bunkerUri = token.uri,
                            clientPrivateKey = keyPair.privateKey,
                            clientPublicKey = keyPair.publicKey,
                            signerPubkey = approval.signerPubkey,
                            userPubkey = userPubkey,
                            relays = relays
                        )
                    )
                    userPubkey
                }
            }.onFailure { if (it is CancellationException) throw it }
            result.onSuccess {
                dialog.dismiss()
                updateNostrSignerSummary()
                // 登录成功后让阅读页重新统计段评,以便给无评论的段落显示可首评图标。
                postEvent(EventBus.REFRESH_REVIEW_SUMMARY, "")
                toastOnUi(getString(R.string.nostr_signer_connected))
            }.onFailure {
                AppLog.put("Nostr signer pairing failed", it, tag = "Nostr")
                dialog.dismiss()
                toastOnUi(it.localizedMessage ?: getString(R.string.nostr_signer_failed))
            }
        }
    }

    private fun clearCache() {
        requireContext().alert(
            titleResource = R.string.clear_cache,
            messageResource = R.string.sure_del
        ) {
            okButton {
                viewModel.clearCache()
            }
            noButton()
        }
    }

    private fun shrinkDatabase() {
        alert(R.string.sure, R.string.shrink_database) {
            okButton {
                viewModel.shrinkDatabase()
            }
            noButton()
        }
    }

    private fun clearWebViewData() {
        alert(R.string.clear_webview_data, R.string.sure_del) {
            okButton {
                viewModel.clearWebViewData()
            }
            noButton()
        }
    }

    private fun isProcessTextEnabled(): Boolean {
        return packageManager.getComponentEnabledSetting(componentName) != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    private fun maskToken(token: String): String =
        if (token.length > 8) token.take(8) + "…" else token

    private fun setProcessTextEnable(enable: Boolean) {
        if (enable) {
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP
            )
        } else {
            packageManager.setComponentEnabledSetting(
                componentName,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP
            )
        }
    }

    private fun alertLocalPassword() {
        context?.alert(R.string.set_local_password, R.string.set_local_password_summary) {
            val editTextBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "password"
            }
            customView {
                editTextBinding.root
            }
            okButton {
                LocalConfig.password = editTextBinding.editView.text.toString()
            }
            cancelButton()
        }
    }

}