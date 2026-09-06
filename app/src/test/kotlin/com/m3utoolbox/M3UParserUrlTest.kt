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

}
