package io.legado.app.help.nostr

import io.legado.app.help.config.AppConfig

/**
 * Nostr 中继来源统一入口。
 *
 * 连接远程签名器时，中继完全来自 bunker:// 或 nostrconnect:// 里签名器提供的信息；
 * 只有签名器没提供中继的场景（生成配对二维码、读取公开段评）才回退到用户在设置里配置的中继。
 */
object NostrRelays {

    /** 用户在「其它设置-Nostr」中配置的中继。 */
    fun configured(): List<String> = parse(AppConfig.nostrRelays)

    /** 读取公开事件时使用：已连接签名器则沿用会话中继，否则用用户配置的中继。 */
    fun forReading(): List<String> = NostrSignerStore.load()?.relays
        ?.let(::parse)
        ?.takeIf { it.isNotEmpty() }
        ?: configured()

    /** 支持每行一个，也兼容逗号/分号/空格分隔。 */
    private fun parse(value: String): List<String> = value
        .split('\n', ',', ';', ' ')
        .map(String::trim)
        .filter { it.startsWith("wss://", ignoreCase = true) }
        .distinct()
}
