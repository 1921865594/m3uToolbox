package com.m3utoolbox

import java.io.Serializable

data class M3UFile(
    val headerParams: MutableList<HeaderParam> = mutableListOf(),
    val categories: MutableList<Category> = mutableListOf()
)

data class HeaderParam(
    var key: String,
    var value: String
)

data class Category(
    var name: String,
    val channels: MutableList<Channel> = mutableListOf()
)

data class Channel(
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

    // 频道级 User-Agent / Referer
    var userAgent: String? = null,
    var referer: String? = null
) : Serializable {

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
}
