package com.m3utoolbox

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import okhttp3.OkHttpClient
import okhttp3.Protocol
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
 * 全屏按钮被注入到 Media3 默认控制器的 basic controls 容器中，
 * 位置就是原来"设置"按钮的位置（右下角），随控制器自动显示/隐藏。
 */
@UnstableApi
class PlayerActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PlayerActivity"
        private const val DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36"
        private const val DEFAULT_TIMEOUT_MS = 15_000

        /**
         * HLS CDN 常把鉴权参数放在主播放列表 URL 上，并在分片 URI 中省略。
         * 这些参数需要在同源、无 query 的子请求上继承。
         */
        private val HLS_AUTH_QUERY_KEYS = setOf(
            "sign", "sig", "token", "auth", "authorization",
            "key", "expires", "expire", "exp", "t", "ts",
            "timestamp", "svrtime", "ytime", "ysign",
            "nonce", "session", "sid", "hmac", "md5", "hdnts"
        )

        private const val APTV_USER_AGENT =
            "AptvPlayer/1.4.25 (iPhone; CPU iPhone OS 26.3.1) AppleWebKit/605.1.15 (KHTML, like Gecko) Mobile/15E148"

        private const val FULLSCREEN_BTN_TAG = "custom_fullscreen_btn"
    }

    private var player: ExoPlayer? = null
    private var playerView: PlayerView? = null
    private var fullscreenButton: ImageButton? = null

    private val contentTypeAttempts = mutableMapOf<Int, Boolean>()

    private var currentUserAgent: String? = null
    private var currentReferer: String? = null
    private var currentRequestHeaders: Map<String, String> = emptyMap()

    /** 首选 OkHttp；遇到部分 CDN/压缩/HTTP 兼容问题时自动切到 HttpURLConnection。 */
    private var useLegacyHttpTransport = false

    private var currentVideoMimeType: String = ""
    private var currentVideoDecoder: String = ""
    private var currentAudioMimeType: String = ""
    private var currentAudioDecoder: String = ""

    private var isFullscreen: Boolean = false

    private val playerListener = object : Player.Listener {

        override fun onVideoSizeChanged(videoSize: VideoSize) {
            Log.d(TAG, "视频尺寸: ${videoSize.width}x${videoSize.height}")
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(
                TAG,
                "播放错误: ${error.errorCodeName}, code=${error.errorCode}, message=${error.message}",
                error
            )

            when (error.errorCode) {
                PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> {
                    player?.seekToDefaultPosition()
                    player?.prepare()
                }

                PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> {
                    val uri = player?.currentMediaItem?.localConfiguration?.uri
                    if (uri != null) {
                        when {
                            contentTypeAttempts[C.CONTENT_TYPE_HLS] != true -> {
                                preparePlayer(
                                    uri = uri,
                                    contentType = C.CONTENT_TYPE_HLS,
                                    userAgent = currentUserAgent,
                                    referer = currentReferer,
                                    requestHeaders = currentRequestHeaders
                                )
                            }

                            contentTypeAttempts[C.CONTENT_TYPE_OTHER] != true -> {
                                preparePlayer(
                                    uri = uri,
                                    contentType = C.CONTENT_TYPE_OTHER,
                                    userAgent = currentUserAgent,
                                    referer = currentReferer,
                                    requestHeaders = currentRequestHeaders
                                )
                            }

                            else -> showPlaybackError(error)
                        }
                    } else {
                        showPlaybackError(error)
                    }
                }

                else -> {
                    val isHls = player?.currentMediaItem?.localConfiguration?.uri?.let {
                        Util.inferContentType(it) == C.CONTENT_TYPE_HLS
                    } == true

                    // OkHttp 与 HttpURLConnection 对 Accept-Encoding、Connection、某些 CDN
                    // 的响应处理存在差异。第一次网络/清单错误时自动换传输栈再试一次。
                    if (isHls && !useLegacyHttpTransport && shouldRetryWithLegacyTransport(error)) {
                        val uri = player?.currentMediaItem?.localConfiguration?.uri
                        if (uri != null) {
                            Log.w(TAG, "OkHttp HLS 请求失败，切换到 HttpURLConnection 重试: ${error.errorCodeName}")
                            useLegacyHttpTransport = true
                            contentTypeAttempts[C.CONTENT_TYPE_HLS] = true
                            preparePlayer(
                                uri = uri,
                                contentType = C.CONTENT_TYPE_HLS,
                                userAgent = currentUserAgent,
                                referer = currentReferer,
                                requestHeaders = currentRequestHeaders
                            )
                            return
                        }
                    }
                    showPlaybackError(error)
                }
            }
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> Log.d(TAG, "播放器缓冲中")
                Player.STATE_READY -> Log.d(
                    TAG,
                    "播放器 READY; videoDecoder=$currentVideoDecoder, " +
                            "videoMime=$currentVideoMimeType, " +
                            "audioDecoder=$currentAudioDecoder, " +
                            "audioMime=$currentAudioMimeType"
                )
                Player.STATE_ENDED -> Log.d(TAG, "播放器结束")
                Player.STATE_IDLE -> Log.d(TAG, "播放器空闲")
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
            Log.d(TAG, "视频解码器: $decoderName, init=${initializationDurationMs}ms")
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
            Log.d(TAG, "音频解码器: $decoderName, init=${initializationDurationMs}ms")
        }
    }

    private val eventLogger = EventLogger()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)

        playerView = findViewById(R.id.playerView)

        isFullscreen =
            resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        // 等 PlayerView 完成一次布局后，替换设置按钮为全屏按钮
        playerView?.post { injectFullscreenButtonIntoController() }

        val fullUrl = intent.getStringExtra("video_url").orEmpty().trim()
        val inputUserAgent = intent.getStringExtra("user_agent")?.trim()
        val inputReferer = intent.getStringExtra("referer")?.trim()
        val inputRequestHeaders = readRequestHeadersExtra()

        if (fullUrl.isBlank()) {
            Toast.makeText(this, "视频链接为空", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        currentUserAgent = inputUserAgent
        currentReferer = inputReferer
        currentRequestHeaders = mergeRequestHeaders(
            inputRequestHeaders,
            inputUserAgent,
            inputReferer
        )

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
            "开始播放 host=${uri.host}, path=${uri.path}, queryParams=${uri.queryParameterNames.size}"
        )

        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory).build().apply {
            playWhenReady = true
        }

        player = exoPlayer
        playerView?.player = exoPlayer

        exoPlayer.addListener(playerListener)
        exoPlayer.addAnalyticsListener(metadataListener)
        exoPlayer.addAnalyticsListener(eventLogger)

        contentTypeAttempts.clear()
        useLegacyHttpTransport = false

        preparePlayer(
            uri = uri,
            contentType = Util.inferContentType(uri),
            userAgent = inputUserAgent,
            referer = inputReferer,
            requestHeaders = currentRequestHeaders
        )

        // 返回键：全屏时先退出全屏，否则退出播放器
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isFullscreen) {
                    toggleFullscreen()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    /**
     * 把 Media3 默认控制器右下角的"设置"按钮替换为自定义全屏按钮。
     *
     * 实现方式：
     * 1. 隐藏 PlayerView 里 id 为 exo_settings 的按钮；
     * 2. 在 exo_basic_controls 这个 LinearLayout 容器末尾添加自己的全屏按钮，
     *    这样它会与控制器一起自动显示 / 隐藏，风格保持一致。
     */
    private fun injectFullscreenButtonIntoController() {
        val pv = playerView ?: return

        // 1. 隐藏默认设置按钮
        try {
            pv.findViewById<View>(androidx.media3.ui.R.id.exo_settings)?.visibility = View.GONE
        } catch (e: Throwable) {
            Log.w(TAG, "隐藏设置按钮失败: ${e.message}")
        }

        // 2. 找到 basic controls 容器
        val basicControls = try {
            pv.findViewById<ViewGroup>(androidx.media3.ui.R.id.exo_basic_controls)
        } catch (e: Throwable) {
            null
        }
        if (basicControls == null) {
            Log.w(TAG, "未找到 exo_basic_controls 容器，跳过全屏按钮注入")
            return
        }

        // 已注入则只更新图标
        val existing = basicControls.findViewWithTag<View>(FULLSCREEN_BTN_TAG)
        if (existing is ImageButton) {
            fullscreenButton = existing
            updateFullscreenButtonIcon()
            return
        }

        // 3. 创建全屏按钮
        val density = resources.displayMetrics.density
        val sizePx = (48 * density).toInt()
        val paddingPx = (12 * density).toInt()

        // 使用与 Media3 默认按钮一致的点击背景
        val outValue = TypedValue()
        theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, outValue, true)

        val btn = ImageButton(this).apply {
            tag = FULLSCREEN_BTN_TAG
            contentDescription = "全屏切换"
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            if (outValue.resourceId != 0) {
                setBackgroundResource(outValue.resourceId)
            } else {
                background = null
            }
            layoutParams = LinearLayout.LayoutParams(sizePx, sizePx)
            setImageResource(
                if (isFullscreen) R.drawable.ic_fullscreen_exit
                else R.drawable.ic_fullscreen
            )
            setOnClickListener { toggleFullscreen() }
        }

        basicControls.addView(btn)
        fullscreenButton = btn
        Log.d(TAG, "全屏按钮已注入到控制器")
    }

    private fun toggleFullscreen() {
        isFullscreen = !isFullscreen
        if (isFullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            enterImmersiveFullscreen()
        } else {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            exitImmersiveFullscreen()
        }
        updateFullscreenButtonIcon()
    }

    private fun updateFullscreenButtonIcon() {
        fullscreenButton?.setImageResource(
            if (isFullscreen) R.drawable.ic_fullscreen_exit
            else R.drawable.ic_fullscreen
        )
    }

    private fun enterImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun exitImmersiveFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            show(WindowInsetsCompat.Type.systemBars())
        }
    }

    /**
     * Lemon-TV 风格 MediaSource 构建。
     * 绝不使用 URL 参数 Map 重建 URL，MediaItem 直接持有完整 Uri。
     */
    private fun preparePlayer(
        uri: Uri,
        contentType: Int,
        userAgent: String?,
        referer: String?,
        requestHeaders: Map<String, String>
    ) {
        val exoPlayer = player ?: return

        contentTypeAttempts[contentType] = true

        val smartUserAgent = resolveUserAgent(uri = uri, userAgent = userAgent)

        currentUserAgent = smartUserAgent
        currentReferer = referer

        val resolvedHeaders = buildResolvedRequestHeaders(
            requestHeaders = requestHeaders,
            smartUserAgent = smartUserAgent,
            referer = referer,
            stripAcceptEncodingForOkHttp = !useLegacyHttpTransport
        )

        val httpDataSourceFactory: DataSource.Factory = if (useLegacyHttpTransport) {
            createLegacyHttpDataSourceFactory(resolvedHeaders, smartUserAgent)
        } else {
            createOkHttpDataSourceFactory(resolvedHeaders, smartUserAgent)
        }

        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)

        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .apply {
                if (contentType == C.CONTENT_TYPE_HLS) {
                    setMimeType(MimeTypes.APPLICATION_M3U8)
                }
            }
            .build()

        val mediaSource = when (contentType) {
            C.CONTENT_TYPE_HLS -> {
                // HLS 的 manifest、segment、key 等所有 DataSource 请求统一经过 resolver。
                // 当主 m3u8 使用 sign/t/token 等鉴权参数，而分片省略 query 时，自动继承。
                val hlsDataSourceFactory = ResolvingDataSource.Factory(
                    dataSourceFactory,
                    ResolvingDataSource.Resolver { dataSpec ->
                        resolveHlsDataSpec(dataSpec, uri)
                    }
                )
                HlsMediaSource.Factory(hlsDataSourceFactory).createMediaSource(mediaItem)
            }
            C.CONTENT_TYPE_RTSP -> {
                RtspMediaSource.Factory().createMediaSource(mediaItem)
            }
            C.CONTENT_TYPE_OTHER -> {
                ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(mediaItem)
            }
            else -> {
                showUnsupportedType(contentType)
                return
            }
        }

        exoPlayer.setMediaSource(mediaSource)
        exoPlayer.prepare()
    }

    private fun buildResolvedRequestHeaders(
        requestHeaders: Map<String, String>,
        smartUserAgent: String,
        referer: String?,
        stripAcceptEncodingForOkHttp: Boolean
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()

        requestHeaders.forEach { (key, value) ->
            if (key.isBlank() || value.isBlank()) return@forEach

            // OkHttp 只有在自己添加 Accept-Encoding 时才会透明解 gzip。
            // 用户若填写 gzip/gzip, deflate 等值，直接透传会让 HLS parser 收到压缩后的清单。
            // 去掉该项后 OkHttp 仍会在网络层发送 Accept-Encoding: gzip，并自动解压。
            if (stripAcceptEncodingForOkHttp && key.equals("Accept-Encoding", ignoreCase = true)) {
                if (!value.equals("identity", ignoreCase = true)) {
                    Log.d(TAG, "OkHttp 传输层自动管理 Accept-Encoding，忽略自定义值: $value")
                    return@forEach
                }
            }

            putHeaderCaseInsensitive(result, key, value)
        }

        putHeaderCaseInsensitive(result, "User-Agent", smartUserAgent)
        putHeaderCaseInsensitive(result, "Referer", referer)
        return result
    }

    private fun createOkHttpDataSourceFactory(
        headers: Map<String, String>,
        userAgent: String
    ): OkHttpDataSource.Factory {
        val requiresHttp11 = headers.keys.any {
            it.equals("Host", ignoreCase = true) ||
                it.equals("Connection", ignoreCase = true)
        }

        val builder = OkHttpClient.Builder()
            .connectTimeout(DEFAULT_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(DEFAULT_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .writeTimeout(DEFAULT_TIMEOUT_MS.toLong(), java.util.concurrent.TimeUnit.MILLISECONDS)
            .callTimeout(0L, java.util.concurrent.TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .followSslRedirects(true)

        if (requiresHttp11) {
            builder.protocols(listOf(Protocol.HTTP_1_1))
        }

        val client = builder.build()
        return OkHttpDataSource.Factory(client)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers)
    }

    private fun createLegacyHttpDataSourceFactory(
        headers: Map<String, String>,
        userAgent: String
    ): DefaultHttpDataSource.Factory {
        return DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(DEFAULT_TIMEOUT_MS)
            .setReadTimeoutMs(DEFAULT_TIMEOUT_MS)
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(userAgent)
            .setDefaultRequestProperties(headers)
    }

    private fun resolveHlsDataSpec(dataSpec: DataSpec, masterUri: Uri): DataSpec {
        val targetUri = dataSpec.uri
        val baseQuery = masterUri.encodedQuery

        if (baseQuery.isNullOrBlank() || targetUri.encodedQuery?.isNotBlank() == true) {
            return dataSpec
        }

        if (!targetUri.scheme.equals(masterUri.scheme, ignoreCase = true) ||
            !targetUri.host.equals(masterUri.host, ignoreCase = true)
        ) {
            return dataSpec
        }

        if (!hasAuthLikeQuery(masterUri)) {
            return dataSpec
        }

        val resolvedUri = targetUri.buildUpon()
            .encodedQuery(baseQuery)
            .build()

        Log.d(TAG, "HLS 子请求继承主 m3u8 鉴权参数: ${targetUri} -> ${resolvedUri}")
        return dataSpec.withUri(resolvedUri)
    }

    private fun hasAuthLikeQuery(uri: Uri): Boolean {
        return uri.queryParameterNames.any {
            it.lowercase() in HLS_AUTH_QUERY_KEYS
        }
    }

    private fun shouldRetryWithLegacyTransport(error: PlaybackException): Boolean {
        return when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> true
            else -> false
        }
    }

    private fun readRequestHeadersExtra(): Map<String, String> {
        val raw = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra("request_headers", HashMap::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra("request_headers")
        }

        return when (raw) {
            is Map<*, *> -> raw.entries.mapNotNull { entry ->
                val key = entry.key as? String
                val value = entry.value as? String
                if (!key.isNullOrBlank() && value != null && value.isNotBlank()) {
                    key to value
                } else {
                    null
                }
            }.toMap()
            else -> emptyMap()
        }
    }

    private fun mergeRequestHeaders(
        headers: Map<String, String>,
        userAgent: String?,
        referer: String?
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()
        headers.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) {
                result[key] = value
            }
        }
        putHeaderCaseInsensitive(result, "User-Agent", userAgent)
        putHeaderCaseInsensitive(result, "Referer", referer)
        return result
    }

    private fun putHeaderCaseInsensitive(
        headers: MutableMap<String, String>,
        name: String,
        value: String?
    ) {
        val oldKey = headers.keys.firstOrNull { it.equals(name, ignoreCase = true) }
        if (oldKey != null) headers.remove(oldKey)
        if (!value.isNullOrBlank()) headers[name] = value
    }

    private fun resolveUserAgent(uri: Uri, userAgent: String?): String {
        val host = uri.host.orEmpty().lowercase()

        return when {
            host.contains("aptv.app") -> {
                Log.d(TAG, "使用 aptv.app 专用 User-Agent")
                APTV_USER_AGENT
            }
            !userAgent.isNullOrBlank() -> userAgent
            else -> DEFAULT_USER_AGENT
        }
    }

    private fun showUnsupportedType(contentType: Int) {
        runOnUiThread {
            Toast.makeText(this, "不支持的视频类型: $contentType", Toast.LENGTH_LONG).show()
        }
    }

    private fun showPlaybackError(error: PlaybackException) {
        val detail = when (error.errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "网络连接失败"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "网络连接超时"
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "HTTP 状态错误，服务器可能拒绝了请求"
            PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "M3U8 索引格式错误"
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "媒体容器格式错误"
            PlaybackException.ERROR_CODE_DECODING_FAILED -> "视频/音频解码失败"
            else -> error.errorCodeName.ifBlank { error.message ?: "未知播放错误" }
        }

        runOnUiThread {
            Toast.makeText(this, "播放失败: $detail", Toast.LENGTH_LONG).show()
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