package com.m3utoolbox

import android.net.Uri

/**
 * M3U/M3U8 playlist parser.
 *
 * Important playback rule:
 * - The URL line is kept byte-for-byte (except surrounding whitespace/newline).
 * - Query parameters are parsed only for display/backward compatibility.
 * - Playback/export always uses Channel.originalUrl through getFullUrl().
 */
object M3UParser {

    fun parse(content: String): M3UFile {
        val m3uFile = M3UFile()
        val normalized = content.removePrefix("\uFEFF")
        val lines = normalized.lines()

        var currentCategory: Category? = null
        var pendingChannel: Channel? = null
        // 只保存“下一个频道”之前出现的请求头指令。
        // 不能使用全局 currentUserAgent/currentReferer，否则前一个频道的请求头会泄漏到后续频道。
        val currentCustomHeaders = linkedMapOf<String, HeaderParam>()

        for (rawLine in lines) {
            val trimmed = rawLine.trim().removePrefix("\uFEFF")
            if (trimmed.isEmpty()) continue

            when {
                trimmed.startsWith("#EXTM3U", ignoreCase = true) -> {
                    parseExtM3U(trimmed, m3uFile)
                }

                trimmed.startsWith("#EXTVLCOPT:http-user-agent", ignoreCase = true) -> {
                    val value = parseDirectiveValue(trimmed, "http-user-agent")
                    if (pendingChannel != null) {
                        pendingChannel?.setRequestHeader("User-Agent", value.orEmpty())
                    } else if (value != null) {
                        currentCustomHeaders["user-agent"] = HeaderParam("User-Agent", value)
                    }
                }

                trimmed.startsWith("#EXTVLCOPT:http-referer", ignoreCase = true) -> {
                    val value = parseDirectiveValue(trimmed, "http-referer")
                        ?: parseDirectiveValue(trimmed, "http-referrer")
                    if (pendingChannel != null) {
                        pendingChannel?.setRequestHeader("Referer", value.orEmpty())
                    } else if (value != null) {
                        currentCustomHeaders["referer"] = HeaderParam("Referer", value)
                    }
                }

                trimmed.startsWith("#EXTVLCOPT:http-referrer", ignoreCase = true) -> {
                    val value = parseDirectiveValue(trimmed, "http-referrer")
                    if (pendingChannel != null) {
                        pendingChannel?.setRequestHeader("Referer", value.orEmpty())
                    } else if (value != null) {
                        currentCustomHeaders["referer"] = HeaderParam("Referer", value)
                    }
                }

                trimmed.startsWith("#EXTVLCOPT:http-origin", ignoreCase = true) -> {
                    val value = parseDirectiveValue(trimmed, "http-origin")
                    if (pendingChannel != null) {
                        pendingChannel?.setRequestHeader("Origin", value.orEmpty())
                    } else if (value != null) {
                        currentCustomHeaders["origin"] = HeaderParam("Origin", value)
                    }
                }

                trimmed.startsWith("#EXTVLCOPT:http-host", ignoreCase = true) -> {
                    val value = parseDirectiveValue(trimmed, "http-host")
                    if (pendingChannel != null) {
                        pendingChannel?.setRequestHeader("Host", value.orEmpty())
                    } else if (value != null) {
                        currentCustomHeaders["host"] = HeaderParam("Host", value)
                    }
                }

                trimmed.startsWith("#EXTVLCOPT:http-connection", ignoreCase = true) -> {
                    val value = parseDirectiveValue(trimmed, "http-connection")
                    if (pendingChannel != null) {
                        pendingChannel?.setRequestHeader("Connection", value.orEmpty())
                    } else if (value != null) {
                        currentCustomHeaders["connection"] = HeaderParam("Connection", value)
                    }
                }

                trimmed.startsWith("#EXTVLCOPT:http-header", ignoreCase = true) -> {
                    val header = parseHttpHeaderDirective(trimmed)
                    if (header != null) {
                        val (name, value) = header
                        pendingChannel?.setRequestHeader(name, value)
                        if (pendingChannel == null) {
                            // Header directive before #EXTINF applies to the next channel.
                            // Keep a lightweight pending map in currentCustomHeaders below.
                            currentCustomHeaders[name.lowercase()] = HeaderParam(name, value)
                        }
                    }
                }

                trimmed.startsWith("#EXTINF", ignoreCase = true) -> {
                    val newChannel = parseExtInf(trimmed)
                    newChannel.userAgent = extractAttribute(trimmed, "http-user-agent")
                    newChannel.referer = extractAttribute(trimmed, "http-referer")
                    currentCustomHeaders.values.forEach { header ->
                        newChannel.setRequestHeader(header.key, header.value)
                    }
                    currentCustomHeaders.clear()
                    newChannel.userAgent?.let { newChannel.setRequestHeader("User-Agent", it) }
                    newChannel.referer?.let { newChannel.setRequestHeader("Referer", it) }
                    pendingChannel = newChannel
                }

                // The custom category marker generated by this application.
                trimmed.startsWith("# =") -> {
                    val categoryName = parseCategoryComment(trimmed)
                    if (categoryName.isNotEmpty()) {
                        currentCategory = Category(categoryName)
                        m3uFile.categories.add(currentCategory)
                    }
                }

                // Other M3U metadata/comments are not categories.
                trimmed.startsWith("#") -> Unit

                else -> {
                    val channel = pendingChannel ?: continue
                    parseUrlIntoChannel(trimmed, channel)

                    // 请求头只属于当前 pendingChannel；这里不再从前一频道继承任何头。

                    if (currentCategory == null) {
                        currentCategory = Category("未分类")
                        m3uFile.categories.add(currentCategory)
                    }
                    currentCategory.channels.add(channel)
                    pendingChannel = null
                }
            }
        }

        return m3uFile
    }

    /**
     * Parse the #EXTM3U header without requiring quoted values.
     * Handles UTF-8 BOM and values such as:
     *   #EXTM3U x-tvg-url="..."
     *   #EXTM3U url-tvg=https://...
     *   #EXTM3U catchup-days=7
     */
    private fun parseExtM3U(line: String, m3uFile: M3UFile) {
        val content = line.removePrefix("#EXTM3U").trim()
        if (content.isEmpty()) return

        parseKeyValueAttributes(content).forEach { (key, value) ->
            m3uFile.headerParams.add(HeaderParam(key, value))
        }
    }

    private fun parseExtInf(line: String): Channel {
        val channel = Channel()
        val afterPrefix = line.substringAfter("#EXTINF", "").trim()
        val lastComma = afterPrefix.lastIndexOf(',')

        if (lastComma == -1) {
            channel.displayName = "未命名"
            return channel
        }

        val attrsPart = afterPrefix.substring(0, lastComma)
        channel.displayName = afterPrefix.substring(lastComma + 1).trim().ifEmpty { "未命名" }
        channel.extinfAttributes.putAll(parseKeyValueAttributes(attrsPart))
        return channel
    }

    private fun parseCategoryComment(line: String): String {
        return line.removePrefix("#")
            .trim()
            .trim('=', ' ')
            .trim()
    }

    /**
     * Save the complete original URL and only then parse a compatibility view of it.
     * Never rebuild the playback URL from urlParams.
     */
    fun parseUrlIntoChannel(url: String, channel: Channel) {
        val rawUrl = url.trim()
        channel.originalUrl = rawUrl

        val uri = Uri.parse(rawUrl)
        channel.url = runCatching {
            uri.buildUpon().clearQuery().build().toString()
        }.getOrDefault(rawUrl)

        channel.urlParams.clear()
        val rawQuery = runCatching { uri.encodedQuery }.getOrNull()
        if (!rawQuery.isNullOrEmpty()) {
            rawQuery.split('&').forEach { param ->
                val idx = param.indexOf('=')
                if (idx > 0) {
                    channel.urlParams[param.substring(0, idx)] = param.substring(idx + 1)
                }
            }
        }
    }

    /**
     * Parse arbitrary header directives such as:
     * #EXTVLCOPT:http-header=Origin: https://example.com
     * #EXTVLCOPT:http-header=X-Token: abc
     */
    private fun parseHttpHeaderDirective(line: String): Pair<String, String>? {
        val raw = line.substringAfter("=", "").trim()
        val idx = raw.indexOf(':')
        if (idx <= 0) return null

        val name = raw.substring(0, idx).trim()
        val value = raw.substring(idx + 1).trim()
        if (name.isEmpty()) return null
        return name to value
    }

    /** Parse #EXTVLCOPT:key=value, retaining quoted values when present. */
    private fun parseDirectiveValue(line: String, key: String): String? {
        val regex = Regex("""${Regex.escape(key)}\s*=\s*(.*)$""", RegexOption.IGNORE_CASE)
        return regex.find(line)?.groupValues?.get(1)
            ?.trim()
            ?.removeSurrounding("\"")
            ?.removeSurrounding("'")
            ?.ifEmpty { null }
    }

    private fun extractAttribute(line: String, key: String): String? {
        return parseKeyValueAttributes(line)[key]
    }

    /**
     * Attribute parser supporting quoted and unquoted M3U attributes.
     * Quoted values may contain spaces and commas.
     */
    private fun parseKeyValueAttributes(text: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val regex = Regex("""([A-Za-z0-9_-]+)\s*=\s*("([^"]*)"|'([^']*)'|([^\s]+))""")
        regex.findAll(text).forEach { match ->
            val value = when {
                match.groupValues[3].isNotEmpty() -> match.groupValues[3]
                match.groupValues[4].isNotEmpty() -> match.groupValues[4]
                else -> match.groupValues[5]
            }
            result[match.groupValues[1]] = value
        }
        return result
    }

    fun generate(m3uFile: M3UFile): String {
        val sb = StringBuilder("#EXTM3U")
        m3uFile.headerParams.forEach { param ->
            sb.append(' ').append(param.key).append("=\"")
                .append(param.value.replace("\"", "&quot;"))
                .append('"')
        }
        sb.append('\n')

        for (category in m3uFile.categories) {
            sb.append("# ============ ").append(category.name).append(" ============\n")
            for (channel in category.channels) {
                val headers = channel.getRequestHeaders()

                headers.entries
                    .filter { it.key.equals("User-Agent", ignoreCase = true) }
                    .firstOrNull()
                    ?.value
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        sb.append("#EXTVLCOPT:http-user-agent=")
                            .append(it).append('\n')
                    }

                headers.entries
                    .filter {
                        it.key.equals("Referer", ignoreCase = true) ||
                            it.key.equals("Referrer", ignoreCase = true)
                    }
                    .firstOrNull()
                    ?.value
                    ?.takeIf { it.isNotBlank() }
                    ?.let {
                        sb.append("#EXTVLCOPT:http-referrer=")
                            .append(it).append('\n')
                    }

                headers.entries
                    .filter {
                        !it.key.equals("User-Agent", ignoreCase = true) &&
                            !it.key.equals("Referer", ignoreCase = true) &&
                            !it.key.equals("Referrer", ignoreCase = true)
                    }
                    .forEach { header ->
                        if (header.key.isNotBlank() && header.value.isNotBlank()) {
                            sb.append("#EXTVLCOPT:http-header=")
                                .append(header.key)
                                .append(": ")
                                .append(header.value)
                                .append('\n')
                        }
                    }

                val attrs = StringBuilder()
                val groupTitle = channel.extinfAttributes["group-title"] ?: category.name
                channel.extinfAttributes.forEach { (key, value) ->
                    if (key != "group-title" && key != "http-user-agent" && key != "http-referer") {
                        attrs.append(' ').append(key).append("=\"").append(value).append('"')
                    }
                }

                sb.append("#EXTINF:-1")
                    .append(attrs)
                    .append(" group-title=\"")
                    .append(groupTitle)
                    .append("\",")
                    .append(channel.displayName)
                    .append('\n')
                    .append(channel.getFullUrl())
                    .append('\n')
            }
        }
        return sb.toString()
    }
}
