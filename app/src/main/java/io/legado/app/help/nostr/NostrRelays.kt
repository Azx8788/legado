package io.legado.app.help.nostr

import io.legado.app.constant.AppLog
import io.legado.app.help.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicInteger

/**
 * Nostr 中继来源统一入口。
 *
 * 连接远程签名器时，中继完全来自 bunker:// 或 nostrconnect:// 里签名器提供的信息；
 * 只有签名器没提供中继的场景（生成配对二维码、读取公开段评）才回退到用户配置的中继。
 *
 * 用户配置的中继既可以手填，也可以用内置种子 + NIP-11/WebSocket 健康检查自动发现。
 */
object NostrRelays {

    /** 出厂种子中继，只在用户没有配置任何中继时作为发现起点。 */
    fun seeds(): List<String> = parse(AppConfig.seedNostrRelays)

    /** 用户在「其它设置-Nostr-Nostr 中继」中显式配置的中继。 */
    fun configured(): List<String> = parse(AppConfig.nostrRelaysConfigured)

    /** 上次健康检查通过的中继（单独缓存，不随用户编辑设置项丢失）。 */
    fun cached(): List<String> = parse(AppConfig.nostrRelayCache)

    /** 上次健康检查时间戳，0 表示从未检测过。 */
    val cacheTime: Long get() = AppConfig.nostrRelayCacheTime

    /** 当前实际使用的中继：用户配置优先，其次健康检查缓存，最后内置种子。 */
    fun effective(): List<String> = configured().takeIf { it.isNotEmpty() }
        ?: cached().takeIf { it.isNotEmpty() }
        ?: seeds()

    /** 读取公开事件时使用：已连接签名器则沿用会话中继，否则用本地中继。 */
    fun forReading(): List<String> = NostrSignerStore.load()?.relays
        ?.let(::sanitize)
        ?.takeIf { it.isNotEmpty() }
        ?: effective()

    /**
     * 自动发现：对「用户配置 + 缓存 + 内置种子」的并集逐个做 NIP-11 + WebSocket 健康检查，
     * 过滤掉要求鉴权/付费/禁止写入以及连不上的中继。
     *
     * @param onProgress 每检查完一个中继回调一次（可能在任意线程），用于更新进度。
     * @return 通过检查的中继列表。
     */
    suspend fun discover(
        onProgress: (done: Int, total: Int, relay: String) -> Unit = { _, _, _ -> }
    ): List<String> {
        val candidates = (configured() + cached() + seeds()).distinct()
        require(candidates.isNotEmpty()) { "No relay available for discovery" }
        val total = candidates.size
        val done = AtomicInteger(0)
        val healthy = coroutineScope {
            candidates.map { relay ->
                async(Dispatchers.IO) {
                    val info = runCatching { NostrRelayHealth.fetchInfo(relay) }.getOrNull()
                    val compatible = info?.compatible != false
                    val reachable = compatible && NostrRelayHealth.checkWebSocket(relay)
                    if (!reachable) {
                        val reason = info?.incompatibleReason ?: "unreachable"
                        AppLog.put("Nostr relay ${relayHost(relay)} skipped: $reason", tag = "Nostr")
                    }
                    onProgress(done.incrementAndGet(), total, relay)
                    relay.takeIf { reachable }
                }
            }.awaitAll()
        }.filterNotNull()
        applyDiscovery(healthy)
        return healthy
    }

    /** 把发现结果写入设置项，并单独缓存一份（含时间戳）。 */
    fun applyDiscovery(relays: List<String>) {
        val value = relays.joinToString("\n")
        // 只有全部失败时才保留原配置，避免把可用配置清空。
        if (relays.isNotEmpty()) {
            AppConfig.nostrRelays = value
        }
        AppConfig.nostrRelayCache = value
        AppConfig.nostrRelayCacheTime = System.currentTimeMillis()
    }

    /** 支持每行一个，也兼容逗号/分号/空格分隔。 */
    private fun parse(value: String): List<String> = sanitize(
        value.split('\n', ',', ';', ' ')
    )

    /** 归一化中继列表：去空白，只保留 wss://，并去重。 */
    private fun sanitize(values: List<String>): List<String> = values
        .map(String::trim)
        .filter { it.startsWith("wss://", ignoreCase = true) }
        .distinct()
}
