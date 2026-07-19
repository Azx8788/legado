package io.legado.app.model

/**
 * 书源校验结果统计
 *
 * @param total    本次校验的书源总数
 * @param success  校验成功的数量
 * @param failed   校验失败的数量（不一定不可用，可能因网络抖动/超时等导致）
 */
data class CheckSourceResult(
    val total: Int,
    val success: Int,
    val failed: Int
)
