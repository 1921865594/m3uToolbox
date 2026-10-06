package com.m3utoolbox

import java.io.Serializable
import java.util.UUID

data class M3UFile(
    val headerParams: MutableList<HeaderParam> = mutableListOf(),
    val categories: MutableList<Category> = mutableListOf()
)

data class HeaderParam(
    var key: String,
    var value: String
) : Serializable

data class Category(
    var name: String,
    val channels: MutableList<Channel> = mutableListOf()
)

/**
 * 频道模型。
 *
 * 注意：这里改成了普通 class，并使用基于 uid 的 equals / hashCode，
 * 目的：复制出来的"参数完全相同"的频道在勾选集合中仍被视为两个不同的对象，
 * 否则用 data class 的自动 equals，复制频道与原频道会因为属性相同而被合并。
 */
class Channel(
    var displayName: String = "",
    var url: String = "",
    val extinfAttributes: MutableMap<String, String> = mutableMapOf(),
    val urlParams: MutableMap<String, String> = mutableMapOf(),

    /**
     * M3U 中 URL 行的原始内容。
     *
     * 播放时必须优先使用它，避免 Uri/Map 拆解后产生：
     * - 参数顺序变化
     * - 重复参数丢失
     * - 百分号编码变化
     * - 空参数丢失
     * - 非标准 query 被重新编码
     */
    var originalUrl: String = "",

    // 频道级 User-Agent / Referer（保留旧字段兼容已有 M3U 数据）
    var userAgent: String? = null,
    var referer: String? = null,

    /**
     * 频道级自定义 HTTP 请求头。
     *
     * User-Agent / Referer 也会同步保存到这里的同名键，
     * 这样播放器可以统一把所有频道请求头传给底层 HTTP 数据源。
     */
    val requestHeaders: MutableList<HeaderParam> = mutableListOf()
) : Serializable {

    /**
     * 每个 Channel 实例的唯一身份标识。确保复制出的相同参数频道也不会被合并。
     */
    val uid: String = UUID.randomUUID().toString()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Channel) return false
        return uid == other.uid
    }

    override fun hashCode(): Int = uid.hashCode()

    /**
     * 返回播放所需的完整 URL。
     *
     * originalUrl 是 M3U URL 行的权威来源。
     * urlParams 只作为旧数据/手工构造 Channel 的兼容回退。
     */
    fun getFullUrl(): String {
        if (originalUrl.isNotBlank()) {
            return originalUrl
        }

        if (urlParams.isEmpty()) {
            return url
        }

        val query = urlParams.entries.joinToString("&") {
            "${it.key}=${it.value}"
        }

        return if (url.contains("?")) {
            "$url&$query"
        } else {
            "$url?$query"
        }
    }

    /**
     * 返回当前频道真正保存的 HTTP 请求头。
     *
     * requestHeaders 是唯一数据源。不要再从 userAgent/referer 反向补回，
     * 否则用户在编辑页删除请求头后，只要旧兼容字段还存在，删除的参数就会重新出现。
     */
    fun getRequestHeaders(): Map<String, String> {
        val result = linkedMapOf<String, String>()
        requestHeaders.forEach { header ->
            val name = header.key.trim()
            val value = header.value
            if (name.isNotEmpty() && value.isNotBlank()) {
                val existingKey = result.keys.firstOrNull {
                    it.equals(name, ignoreCase = true)
                }
                if (existingKey != null) result.remove(existingKey)
                result[name] = value
            }
        }
        return result
    }

    /**
     * 将编辑页返回的频道完整内容写回当前实例，但保留当前实例 uid。
     *
     * 这很重要：RecyclerView/勾选导出保存的是 Channel 对象引用。
     * 编辑频道时如果直接用新对象替换旧对象，已勾选集合仍会持有旧对象，
     * 导出就可能把编辑前的请求头再次导出。
     */
    fun overwriteFrom(source: Channel) {
        displayName = source.displayName
        url = source.url

        extinfAttributes.clear()
        extinfAttributes.putAll(source.extinfAttributes)

        urlParams.clear()
        urlParams.putAll(source.urlParams)

        originalUrl = source.originalUrl
        userAgent = source.userAgent
        referer = source.referer

        requestHeaders.clear()
        source.requestHeaders.forEach { header ->
            requestHeaders.add(HeaderParam(header.key, header.value))
        }
    }

    fun setRequestHeader(name: String, value: String) {
        // 同名请求头全部清理，避免历史重复项在导出时复活
        val iterator = requestHeaders.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().key.equals(name, ignoreCase = true)) {
                iterator.remove()
            }
        }

        if (value.isNotBlank()) {
            requestHeaders.add(HeaderParam(name.trim(), value))
        }
    }
}