package com.m3utoolbox

import org.junit.Assert.assertEquals
import org.junit.Test

class M3UParserUrlTest {

    @Test
    fun fullUrlPreservesOriginalQueryExactly() {
        val url =
            "https://example.com/live/index.m3u8" +
                "?token=abc%2B123" +
                "&empty=" +
                "&spid=699004" +
                "&timestamp=20260901012046" +
                "&channel_id=0116_2600034600-99000-201600010010028" +
                "&codec=h264"


        val channel = Channel()
        M3UParser.parseUrlIntoChannel(url, channel)

        assertEquals(url, channel.getFullUrl())
    }

    @Test
    fun parseDoesNotLoseEmptyQueryParameters() {
        val url = "https://example.com/live/index.m3u8?a=1&empty=&b=2"

        val file = M3UParser.parse(
            "#EXTM3U\n#EXTINF:-1 group-title=\"Test\",CCTV1\n$url\n"
        )

        val channel = file.categories
            .flatMap { it.channels }
            .single()

        assertEquals(url, channel.getFullUrl())
    }

    @Test
    fun importPreservesHeaderParametersAndChannelHeaders() {
        val url = "http://example.com/index.m3u8?token=abc&empty=&spid=699004"
        val file = M3UParser.parse(
            "\uFEFF#EXTM3U x-tvg-url=\"http://epg.example.com/a.xml\" catchup-days=7\n" +
                "#EXTVLCOPT:http-user-agent=Mozilla/5.0\n" +
                "#EXTVLCOPT:http-referer=\"https://example.com/\"\n" +
                "#EXTINF:-1 tvg-id=\"1\" tvg-name=\"CCTV1\" group-title=\"央视\",CCTV1\n" +
                url + "\n"
        )

        assertEquals(2, file.headerParams.size)
        assertEquals("x-tvg-url", file.headerParams[0].key)
        assertEquals("http://epg.example.com/a.xml", file.headerParams[0].value)
        assertEquals("catchup-days", file.headerParams[1].key)
        assertEquals("7", file.headerParams[1].value)

        val channel = file.categories.single().channels.single()
        assertEquals(url, channel.getFullUrl())
        assertEquals("Mozilla/5.0", channel.userAgent)
        assertEquals("https://example.com/", channel.referer)
    }


    @Test
    fun requestHeadersRoundTripThroughM3U() {
        val url =
            "https://sztv-live.sztv.com.cn/AxeFRth/500/v37Abe0.m3u8" +
                "?sign=8e389b38b49eafc6dabb7b4e480664a4&t=6ac4f1b1"

        val channel = Channel(displayName = "深圳卫视")
        M3UParser.parseUrlIntoChannel(url, channel)
        channel.setRequestHeader("User-Agent", "Mozilla/5.0")
        channel.setRequestHeader("Referer", "https://www.sztv.com.cn/")
        channel.setRequestHeader("Origin", "https://www.sztv.com.cn")
        channel.setRequestHeader("Host", "sztv-live.sztv.com.cn")
        channel.setRequestHeader("Connection", "keep-alive")
        channel.setRequestHeader("X-Test-Token", "abc:123")

        val file = M3UFile().apply {
            categories.add(Category("卫视").apply {
                channels.add(channel)
            })
        }

        val generated = M3UParser.generate(file)
        val parsed = M3UParser.parse(generated)
        val restored = parsed.categories.single().channels.single()

        assertEquals(url, restored.getFullUrl())
        assertEquals("Mozilla/5.0", restored.getRequestHeaders()["User-Agent"])
        assertEquals("https://www.sztv.com.cn/", restored.getRequestHeaders()["Referer"])
        assertEquals("https://www.sztv.com.cn", restored.getRequestHeaders()["Origin"])
        assertEquals("sztv-live.sztv.com.cn", restored.getRequestHeaders()["Host"])
        assertEquals("keep-alive", restored.getRequestHeaders()["Connection"])
        assertEquals("abc:123", restored.getRequestHeaders()["X-Test-Token"])
    }

    @Test
    fun customHeaderDirectiveCanAppearAfterExtInf() {
        val url = "https://example.com/live/index.m3u8?token=a=b&empty="
        val file = M3UParser.parse(
            "#EXTM3U\n" +
                "#EXTINF:-1 group-title=\"Test\",CCTV1\n" +
                "#EXTVLCOPT:http-header=Origin: https://example.com\n" +
                "#EXTVLCOPT:http-header=X-Token: abc:123\n" +
                url + "\n"
        )

        val channel = file.categories.single().channels.single()
        assertEquals(url, channel.getFullUrl())
        assertEquals("https://example.com", channel.getRequestHeaders()["Origin"])
        assertEquals("abc:123", channel.getRequestHeaders()["X-Token"])
    }


    @Test
    fun deletedHeadersMustNotReappearAfterSaveLikeOverwrite() {
        val original = Channel(displayName = "Test", url = "https://example.com/live.m3u8")
        original.userAgent = "qqlive"
        original.referer = "bytes=0-"
        original.setRequestHeader("User-Agent", "qqlive")
        original.setRequestHeader("Referer", "bytes=0-")
        original.setRequestHeader("Origin", "https://example.com")
        original.setRequestHeader("X-Test", "old")

        val edited = Channel(displayName = "Test", url = "https://example.com/live.m3u8")
        // 模拟编辑页删除全部请求头，只保留普通频道字段。
        M3UParser.parseUrlIntoChannel("https://example.com/live.m3u8", edited)

        original.overwriteFrom(edited)

        check(original.getRequestHeaders().isEmpty())
        val generated = M3UParser.generate(M3UFile(categories = mutableListOf(Category("测试", mutableListOf(original)))))
        check("#EXTVLCOPT:" !in generated)
        check("X-Test" !in generated)
    }

    @Test
    fun deletedSingleHeaderMustNotReappearFromLegacyFields() {
        val channel = Channel(displayName = "Test", url = "https://example.com/live.m3u8")
        channel.userAgent = "qqlive"
        channel.referer = "bytes=0-"
        channel.setRequestHeader("User-Agent", "qqlive")
        channel.setRequestHeader("Referer", "bytes=0-")

        // 模拟用户删除内置 UA/Referer。
        channel.requestHeaders.clear()
        channel.userAgent = null
        channel.referer = null

        val generated = M3UParser.generate(M3UFile(categories = mutableListOf(Category("测试", mutableListOf(channel)))))
        check("#EXTVLCOPT:" !in generated)
    }


    @Test
    fun headersMustNotLeakFromPreviousChannel() {
        val content = """#EXTM3U
# ============ 导出频道 ============
#EXTVLCOPT:http-user-agent=qqlive
#EXTVLCOPT:http-referrer=bytes=0-
#EXTINF:-1 tvg-id="深圳卫视" tvg-name="深圳卫视" group-title="卫视",深圳卫视
http://example.com/a.m3u8
#EXTVLCOPT:http-user-agent=qqlive
#EXTVLCOPT:http-referrer=bytes=0-
#EXTINF:-1 tvg-id="深圳卫视" tvg-name="深圳卫视" group-title="卫视",深圳卫视
http://example.com/b.m3u8
#EXTINF:-1 tvg-id="深圳卫视" tvg-name="深圳卫视" group-title="卫视",深圳卫视
https://sztv-live.sztv.com.cn/AxeFRth/500/v37Abe0.m3u8?sign=abc&t=def
#EXTINF:-1 tvg-id="深圳卫视" tvg-name="深圳卫视" group-title="卫视",深圳卫视
https://sztv-live.sztv.com.cn/R77mK1v/500/z63Rb70.m3u8?sign=ghi&t=jkl
"""
        val file = M3UParser.parse(content)
        val channels = file.categories.single().channels
        check(channels.size == 4)
        check(channels[0].getRequestHeaders()["User-Agent"] == "qqlive")
        check(channels[0].getRequestHeaders()["Referer"] == "bytes=0-")
        check(channels[1].getRequestHeaders()["User-Agent"] == "qqlive")
        check(channels[1].getRequestHeaders()["Referer"] == "bytes=0-")
        check(channels[2].getRequestHeaders().isEmpty())
        check(channels[3].getRequestHeaders().isEmpty())
    }

}
