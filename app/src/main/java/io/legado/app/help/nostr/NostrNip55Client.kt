package io.legado.app.help.nostr

import android.content.ActivityNotFoundException
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import splitties.init.appCtx
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * NIP-55 请求分发：把前台 Intent 拿到的签名器回包按请求 id 交还给等待中的调用方。
 *
 * 有两条通道：用户已「记住选择」时签名器可直接应答 ContentResolver 查询（无需界面），
 * 否则必须由前台 Activity 打开签名器等用户授权。因此签名请求需要界面活着，
 * [registerLauncher] 由当前前台 Activity 在 onStart/onStop 注册与注销。
 */
object NostrNip55Client {

    /** 等用户授权的上限；签名器自身也有超时，这里只兜底避免永久挂起。 */
    private const val APPROVAL_TIMEOUT_MILLIS = 120_000L

    private val pending = ConcurrentHashMap<String, Channel<NostrNip55.Outcome>>()

    @Volatile
    private var launcher: ((Intent) -> Unit)? = null

    fun registerLauncher(launch: (Intent) -> Unit) {
        launcher = launch
    }

    fun unregisterLauncher(launch: (Intent) -> Unit) {
        if (launcher === launch) launcher = null
    }

    /** 由 Activity 的 registerForActivityResult 回调转发；额外/迟到的回包直接丢弃。 */
    fun onActivityResult(resultCode: Int, data: Intent?) {
        val requestId = data?.getStringExtra("id") ?: return
        val channel = pending.remove(requestId) ?: return
        channel.trySend(NostrNip55.outcomeOf(resultCode, data))
    }

    suspend fun signEvent(
        packageName: String,
        userPubkey: String,
        payloadJson: String
    ): NostrNip55.Result {
        queryBackground(packageName, NostrNip55.COMMAND_SIGN_EVENT, payloadJson, userPubkey)
            ?.let { return it }
        return awaitForeground(
            NostrNip55.buildSignEventIntent(payloadJson, userPubkey, packageName)
        )
    }

    /**
     * 后台通道：查询 `content://<包名>.<方法>`，projection 依次是 [载荷, 对方公钥, 当前用户公钥]。
     * 返回 null 表示签名器无法后台应答（未记住选择 / 不认识该方法 / 需要前台授权），调用方退回 Intent。
     */
    private suspend fun queryBackground(
        packageName: String,
        command: String,
        payload: String,
        userPubkey: String
    ): NostrNip55.Result? = withBackgroundQuery {
        appCtx.contentResolver.query(
            Uri.parse("content://$packageName.$command"),
            arrayOf(payload, "", userPubkey),
            null,
            null,
            null
        )
    }

    private suspend fun awaitForeground(intent: Intent): NostrNip55.Result =
        withContext(Dispatchers.Main.immediate) {
            val launch = launcher
                ?: throw IllegalStateException(
                    "Opening the signer requires a foreground screen"
                )
            val requestId = UUID.randomUUID().toString().replace("-", "")
            intent.putExtra("id", requestId)
            // 不设 SINGLE_TOP/CLEAR_TOP：那两个 flag 是给「不等回包连发多个请求」用的，
            // 签名器收到后会改回 `results` 数组，而本客户端每次只等一个单结果回包。
            val channel = Channel<NostrNip55.Outcome>(capacity = 1)
            pending[requestId] = channel
            try {
                try {
                    launch(intent)
                } catch (e: ActivityNotFoundException) {
                    throw IllegalStateException("No NIP-55 signer app is installed", e)
                }
                val outcome = withTimeoutOrNull(APPROVAL_TIMEOUT_MILLIS) { channel.receive() }
                    ?: throw IllegalStateException("Timed out waiting for the signer")
                when (outcome) {
                    is NostrNip55.Outcome.Success -> outcome.result
                    is NostrNip55.Outcome.Failed -> throw IllegalStateException(outcome.reason)
                    NostrNip55.Outcome.Rejected ->
                        throw IllegalStateException("The signer request was rejected")
                }
            } finally {
                pending.remove(requestId)
                channel.close()
            }
        }

    /**
     * 读后台查询结果。查询本身是跨进程 binder 调用，必须离开主线程；
     * 签名器未开后台权限时 `query` 返回 null。
     */
    private suspend fun withBackgroundQuery(
        query: () -> Cursor?
    ): NostrNip55.Result? = withContext(Dispatchers.IO) {
        runCatching {
            val cursor = query() ?: return@runCatching null
            cursor.use {
                // 出现 rejected 列表示签名器明确拒绝，按规范不再退回前台弹窗。
                if (it.getColumnIndex("rejected") >= 0) return@use null
                if (!it.moveToFirst()) return@use null
                val result = it.column("result")
                val event = it.column("event")
                if (result.isNullOrBlank() && event.isNullOrBlank()) return@use null
                NostrNip55.Result(
                    result = result,
                    event = event,
                    packageName = null,
                    rejected = false
                )
            }
        }.getOrNull()
    }

    private fun Cursor.column(name: String): String? {
        val index = getColumnIndex(name)
        return if (index >= 0) getString(index) else null
    }
}
