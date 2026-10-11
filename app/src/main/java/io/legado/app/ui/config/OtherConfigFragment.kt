package io.legado.app.ui.config

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.postDelayed
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.preference.ListPreference
import androidx.preference.Preference
import com.jeremyliao.liveeventbus.LiveEventBus
import io.legado.app.R
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.AppFreezeMonitor
import io.legado.app.help.DispatchersMonitor
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.nostr.NostrNip46
import io.legado.app.help.nostr.NostrNip46Transport
import io.legado.app.help.nostr.NostrSignerStore
import io.legado.app.utils.QRCodeUtils
import io.legado.app.lib.dialogs.alert
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import splitties.init.appCtx

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
        updateNostrSignerSummary()
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

            "nostrSigner" -> manageNostrSigner()
            "nostrConnectQr" -> startNostrConnect()

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

    private fun manageNostrSigner() {
        val session = NostrSignerStore.load()
        if (session != null) {
            alert(getString(R.string.nostr_signer_connected)) {
                setMessage(session.userPubkey)
                positiveButton(getString(R.string.nostr_signer_disconnect)) {
                    NostrSignerStore.clear()
                    updateNostrSignerSummary()
                    toastOnUi(getString(R.string.nostr_signer_disconnected))
                }
                cancelButton()
            }
            return
        }
        alert(getString(R.string.nostr_signer_connect)) {
            val editBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "bunker://..."
                editView.setSingleLine(true)
            }
            customView { editBinding.root }
            positiveButton(getString(R.string.nostr_signer_connect)) {
                connectNostrSigner(editBinding.editView.text?.toString().orEmpty())
            }
            cancelButton()
        }
    }

    private fun connectNostrSigner(bunkerUri: String) {
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
                        NostrNip46.buildConnectRequest(keyPair, connection)
                    )
                    if (connectResponse.error != null) error(connectResponse.error)
                    if (connection.secret != null && connectResponse.result != "ack" &&
                        connectResponse.result != connection.secret
                    ) error("Remote signer did not confirm the bunker secret")

                    val publicKeyResponse = NostrNip46Transport.request(
                        connection,
                        keyPair,
                        NostrNip46.buildGetPublicKeyRequest(keyPair, connection.signerPubkey)
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
            }
            result.onSuccess {
                updateNostrSignerSummary()
                toastOnUi(getString(R.string.nostr_signer_connected))
            }.onFailure {
                AppLog.put("Nostr signer connection failed", it, tag = "Nostr")
                toastOnUi(it.localizedMessage ?: getString(R.string.nostr_signer_failed))
            }
        }
    }

    private fun updateNostrSignerSummary() {
        val preference = findPreference<Preference>("nostrSigner") ?: return
        preference.summary = NostrSignerStore.load()?.userPubkey
            ?: getString(R.string.nostr_signer_not_connected)
    }

    private fun startNostrConnect() {
        if (NostrSignerStore.load() != null) {
            toastOnUi(getString(R.string.nostr_signer_connected))
            return
        }
        val keyPair = NostrNip46.newClientKeyPair()
        val token = NostrNip46.createNostrConnectToken(
            keyPair,
            listOf("wss://relay.damus.io", "wss://nos.lol")
        )
        val bitmap = QRCodeUtils.createQRCode(token.uri, 720)
        if (bitmap == null) {
            toastOnUi(getString(R.string.nostr_signer_failed))
            return
        }
        val image = ImageView(requireContext()).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        var connectionJob: Job? = null
        val dialog = alert(getString(R.string.nostr_signer_pair_qr)) {
            setMessage(getString(R.string.nostr_signer_scan_qr))
            setCancelable(false)
            customView { image }
            cancelButton { connectionJob?.cancel() }
        }
        connectionJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val approval = NostrNip46Transport.awaitNostrConnect(
                        token.relays,
                        keyPair,
                        token.secret
                    )
                    val connection = NostrNip46.BunkerConnection(
                        signerPubkey = approval.signerPubkey,
                        relays = token.relays,
                        secret = null
                    )
                    val publicKeyResponse = NostrNip46Transport.request(
                        connection,
                        keyPair,
                        NostrNip46.buildGetPublicKeyRequest(keyPair, approval.signerPubkey)
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
            }
            result.onSuccess {
                dialog.dismiss()
                updateNostrSignerSummary()
                toastOnUi(getString(R.string.nostr_signer_connected))
            }.onFailure {
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