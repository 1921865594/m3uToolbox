package com.m3utoolbox

import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.ui.PlayerView

/**
 * Lemon-TV 风格的 Media3/ExoPlayer 播放器。
 *
 * 核心原则：
 * 1. channel.getFullUrl() 返回原始完整 URL，不拆分、不重组查询参数。
 * 2. 完整 URL 直接交给 Media3 的 DefaultHttpDataSource。
 * 3. .m3u8 入口由 HlsMediaSource 直接请求并解析。
 * 4. User-Agent / Referer 作为 HTTP 请求头随 HLS 请求链发送。
 * 5. 开启 Media3 extension renderer，让本地 FFmpeg AAR 提供额外解码能力。
 */
@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PlayerActivity"
        private const val DEFAULT_USER_AGENT = "ExoPlayer"

        // 与 Lemon-TV 的视频播放器配置保持一致：默认 15 秒。
        private const val DEFAULT_TIMEOUT_MS = 15_000

        // Lemon-TV 对 aptv.app 使用专用 UA；这里保留相同规则。
        private const val APTV_USER_AGENT =
            "AptvPlayer/1.4.25 (iPhone; CPU iPhone OS 26.3.1) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"
    }

    private var player: ExoPlayer? = null

    /**
     * 记录已经尝试过的 Media3 内容类型，避免解析失败时无限重试。
     */
    private val contentTypeAttempts = mutableMapOf<Int, Boolean>()

    private var currentUserAgent: String? = null
    private var currentReferer: String? = null

    private var currentVideoMimeType: String = ""
    private var currentVideoDecoder: String = ""
    private var currentAudioMimeType: String = ""
    private var currentAudioDecoder: String = ""

    private val playerListener = object : Player.Listener {

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            Log.d(
                TAG,
                "视频尺寸: ${videoSize.width}x${videoSize.height}"
            )
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(
                TAG,
                "播放错误: ${error.errorCodeName}, code=${error.errorCode}, message=${error.message}",
                error
            )

            when (error.errorCode) {
                PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                    // 直播窗口已经滚动过去：回到当前 live window 默认位置。
                    player?.seekToDefaultPosition()
                    player?.prepare()
                }

                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> {
                    // 与 Lemon-TV 一致：优先按 HLS 重新创建 MediaSource，再退回普通 Progressive。
                    val uri = player?.currentMediaItem?.localConfiguration?.uri
                    if (uri != null) {
                        when {
                            contentTypeAttempts[C.CONTENT_TYPE_HLS] != true -> {
                                preparePlayer(
                                    uri = uri,
                                    contentType = C.CONTENT_TYPE_HLS,
                                    userAgent = currentUserAgent,
                                    referer = currentReferer
                                )
                            }

                            contentTypeAttempts[C.CONTENT_TYPE_OTHER] != true -> {
                                preparePlayer(
                                    uri = uri,
                                    contentType = C.CONTENT_TYPE_OTHER,
                                    userAgent = currentUserAgent,
                                    referer = currentReferer
                                )
                            }

                            else -> {
                                showPlaybackError(error)
                            }
                        }
                    } else {
                        showPlaybackError(error)
                    }
                }

                else -> {
                    showPlaybackError(error)
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> {
                    Log.d(TAG, "播放器缓冲中")
                }

                Player.STATE_READY -> {
                    Log.d(
                        TAG,
                        "播放器 READY; videoDecoder=$currentVideoDecoder, " +
                            "videoMime=$currentVideoMimeType, " +
                            "audioDecoder=$currentAudioDecoder, " +
                            "audioMime=$currentAudioMimeType"
                    )
                }

                Player.STATE_ENDED -> {
                    Log.d(TAG, "播放器结束")
                }

                Player.STATE_IDLE -> {
                    Log.d(TAG, "播放器空闲")
                }
            }
        }
    }

    private val metadataListener = object : AnalyticsListener {

        override fun onVideoInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            currentVideoMimeType = format.sampleMimeType.orEmpty()

            Log.d(
                TAG,
                "视频格式变化: mime=$currentVideoMimeType, " +
                    "size=${format.width}x${format.height}, " +
                    "fps=${format.frameRate}, bitrate=${format.bitrate}"
            )
        }

        override fun onVideoDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            currentVideoDecoder = decoderName
            Log.d(
                TAG,
                "视频解码器: $decoderName, init=${initializationDurationMs}ms"
            )
        }

        override fun onAudioInputFormatChanged(
            eventTime: AnalyticsListener.EventTime,
            format: Format,
            decoderReuseEvaluation: DecoderReuseEvaluation?,
        ) {
            currentAudioMimeType = format.sampleMimeType.orEmpty()

            Log.d(
                TAG,
                "音频格式变化: mime=$currentAudioMimeType, " +
                    "channels=${format.channelCount}, sampleRate=${format.sampleRate}"
            )
        }

        override fun onAudioDecoderInitialized(
            eventTime: AnalyticsListener.EventTime,
            decoderName: String,
            initializedTimestampMs: Long,
            initializationDurationMs: Long,
        ) {
            currentAudioDecoder = decoderName
            Log.d(
                TAG,
                "音频解码器: $decoderName, init=${initializationDurationMs}ms"
            )
        }
    }

    private val eventLogger = EventLogger()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        val playerView = findViewById<PlayerView>(R.id.playerView)

        val fullUrl = intent.getStringExtra("video_url").orEmpty().trim()
        val inputUserAgent = intent.getStringExtra("user_agent")?.trim()
        val inputReferer = intent.getStringExtra("referer")?.trim()

        if (fullUrl.isBlank()) {
            Toast.makeText(this, "视频链接为空", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        currentUserAgent = inputUserAgent
        currentReferer = inputReferer

        val uri = runCatching { Uri.parse(fullUrl) }.getOrElse {
            Toast.makeText(this, "视频链接格式错误", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        if (uri.scheme !in setOf("http", "https", "rtsp")) {
            Toast.makeText(this, "仅支持 HTTP/HTTPS/RTSP 视频地址", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        Log.d(
            TAG,
            "开始播放 host=${uri.host}, path=${uri.path}, " +
                "queryParams=${uri.queryParameterNames.size}"
        )

        /*
         * Lemon-TV 的关键解码/渲染设置：
         * EXTENSION_RENDERER_MODE_ON = 系统解码器优先，FFmpeg 扩展作为补充。
         *
         * 这样不会强制所有视频走 FFmpeg 软解，避免 HEVC/4K 场景不必要的内存压力，
         * 同时可以使用本地 media3-ffmpeg-decoder AAR 补充 AC3/DTS/MP2 等系统不支持的编码。
         */
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )

        val exoPlayer = ExoPlayer.Builder(
            this,
            renderersFactory
        ).build().apply {
            playWhenReady = true
        }

        player = exoPlayer
        playerView.player = exoPlayer

        exoPlayer.addListener(playerListener)
        exoPlayer.addAnalyticsListener(metadataListener)
        exoPlayer.addAnalyticsListener(eventLogger)

        contentTypeAttempts.clear()

        preparePlayer(
            uri = uri,
            contentType = Util.inferContentType(uri),
            userAgent = inputUserAgent,
            referer = inputReferer
        )
    }

    /**
     * Lemon-TV 风格 MediaSource 构建。
     *
     * 注意：这里绝不使用 URL 参数 Map 重建 URL。
     * Uri 本身就是完整地址，MediaItem 直接持有完整 query。
     * 对于：
     *
     * http://host/index.m3u8?a=1&b=2&c=3
     *
     * HlsMediaSource 会先把这个完整 URI 作为普通 HTTP URL 请求服务器，
     * 获取 m3u8 playlist 后，再由 Media3 HLS 播放器解析 playlist。
     */
    private fun preparePlayer(
        uri: Uri,
        contentType: Int,
        userAgent: String?,
        referer: String?
    ) {
        val exoPlayer = player ?: return

        contentTypeAttempts[contentType] = true

        val smartUserAgent = resolveUserAgent(
            uri = uri,
            userAgent = userAgent
        )

        currentUserAgent = smartUserAgent
        currentReferer = referer

        val httpFactory = DefaultHttpDataSource.Factory().apply {
            setUserAgent(smartUserAgent)

            // 与 Lemon-TV 相同：连接和读取超时都使用播放器加载超时。
            setConnectTimeoutMs(DEFAULT_TIMEOUT_MS)
            setReadTimeoutMs(DEFAULT_TIMEOUT_MS)

            // 保留 Lemon-TV 的 302/跨协议行为。
            setKeepPostFor302Redirects(true)
            setAllowCrossProtocolRedirects(true)

            if (!referer.isNullOrBlank()) {
                setDefaultRequestProperties(
                    mapOf("Referer" to referer)
                )
            }
        }

        val dataSourceFactory = DefaultDataSource.Factory(
            this,
            httpFactory
        )

        /*
         * 这里非常关键：
         * MediaItem.fromUri(uri) 接收的是完整 Uri。
         * 不取 uri.path，不拼接 query，不重新编码参数。
         */
        val mediaItem = MediaItem.fromUri(uri)

        val mediaSource = when (contentType) {
            C.CONTENT_TYPE_HLS -> {
                HlsMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem)
            }

            C.CONTENT_TYPE_RTSP -> {
                RtspMediaSource.Factory()
                    .createMediaSource(mediaItem)
            }

            C.CONTENT_TYPE_OTHER -> {
                ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem)
            }

            else -> {
                // Lemon-TV 对未知类型不直接把 URI 拆掉，而是报告 Unsupported Type。
                showUnsupportedType(contentType)
                return
            }
        }

        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
    }

    /**
     * Lemon-TV 的 UA 思路：频道 UA 优先；没有时使用默认播放器 UA；
     * aptv.app 保留专用 UA 规则。
     */
    private fun resolveUserAgent(
        uri: Uri,
        userAgent: String?
    ): String {
        val host = uri.host.orEmpty().lowercase()

        return when {
            host.contains("aptv.app") -> {
                Log.d(TAG, "使用 aptv.app 专用 User-Agent")
                APTV_USER_AGENT
            }

            !userAgent.isNullOrBlank() -> {
                userAgent
            }

            else -> {
                DEFAULT_USER_AGENT
            }
        }
    }

    private fun showUnsupportedType(contentType: Int) {
        runOnUiThread {
            Toast.makeText(
                this,
                "不支持的视频类型: $contentType",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showPlaybackError(error: PlaybackException) {
        val detail = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED ->
                "网络连接失败"

            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT ->
                "网络连接超时"

            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
                "HTTP 状态错误，服务器可能拒绝了请求"

            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ->
                "M3U8 索引格式错误"

            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ->
                "媒体容器格式错误"

            PlaybackException.ERROR_CODE_DECODING_FAILED ->
                "视频/音频解码失败"


            else ->
                error.errorCodeName.ifBlank {
                    error.message ?: "未知播放错误"
                }
        }

        runOnUiThread {
            Toast.makeText(
                this,
                "播放失败: $detail",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    override fun onStop() {
        super.onStop()

        player?.removeListener(playerListener)
        player?.removeAnalyticsListener(metadataListener)
        player?.removeAnalyticsListener(eventLogger)
        player?.release()
        player = null
        contentTypeAttempts.clear()
    }
}
