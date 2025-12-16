package com.provideoplayer

import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.view.ScaleGestureDetector
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import okhttp3.OkHttpClient
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.TimeUnit
import androidx.media3.ui.PlayerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.provideoplayer.databinding.ActivityPlayerBinding
import com.provideoplayer.model.AspectRatioMode
import com.provideoplayer.model.VideoFilter
import kotlin.math.abs

class PlayerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_VIDEO_URI = "video_uri"
        const val EXTRA_VIDEO_TITLE = "video_title"
        const val EXTRA_VIDEO_POSITION = "video_position"  // Playlist index (Int)
        const val EXTRA_PLAYBACK_POSITION = "playback_position"  // Resume position in ms (Long)
        const val EXTRA_PLAYLIST = "playlist"
        const val EXTRA_PLAYLIST_TITLES = "playlist_titles"
        const val EXTRA_IS_NETWORK_STREAM = "is_network_stream"
        
        private const val SEEK_INCREMENT = 10000L // 10 seconds
        private const val HIDE_CONTROLS_DELAY = 4000L
        
        // Track current instance to close old PiP when new video starts
        private var currentInstance: PlayerActivity? = null
        
        fun finishExistingInstance() {
            currentInstance?.let { oldInstance ->
                android.util.Log.d("PlayerActivity", "Finishing existing instance (PiP cleanup)")
                oldInstance.player?.stop()
                oldInstance.finish()
            }
            currentInstance = null
        }
    }

    private lateinit var binding: ActivityPlayerBinding
    private var player: ExoPlayer? = null
    private lateinit var trackSelector: DefaultTrackSelector
    private lateinit var audioManager: AudioManager
    private lateinit var gestureDetector: GestureDetectorCompat
    private lateinit var scaleGestureDetector: ScaleGestureDetector
    
    // Zoom/Pan state
    private var currentScale = 1.0f
    private var currentTranslateX = 0f
    private var currentTranslateY = 0f
    private val minScale = 1.0f
    private val maxScale = 4.0f
    
    private var playlist: List<String> = emptyList()
    private var playlistTitles: List<String> = emptyList()
    private var currentIndex = 0
    private var isNetworkStream = false
    
    // Gesture controls
    private var screenWidth = 0
    private var screenHeight = 0
    private var currentBrightness = 0.5f
    private var currentVolume = 0
    private var maxVolume = 0
    private var isGestureActive = false
    private var gestureType = GestureType.NONE
    
    // Pan gesture tracking for zoomed state
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var isPanning = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    
    // Seek state
    private var isSeeking = false
    private var pendingSeekPosition: Long = -1L  // Track target seek position
    
    // Control visibility
    private val hideHandler = Handler(Looper.getMainLooper())
    private val progressHandler = Handler(Looper.getMainLooper()) // Separate handler for progress updates
    private var controlsVisible = true
    private var isLocked = false
    
    // Playback state
    private var playbackSpeed = 1.0f
    private var currentAspectRatio = AspectRatioMode.FIT
    private var currentFilter = VideoFilter.NONE
    
    // PiP
    private var isPipMode = false
    private var wasInPipMode = false  // Track if we were in PiP before it was closed
    
    // Video ended state - set true on STATE_ENDED, cleared on play/seek
    private var videoHasEnded = false
    
    // Debug logging for troubleshooting
    private val debugLogs = mutableListOf<String>()
    private val maxLogEntries = 200  // Keep last 200 logs
    
    private fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault()).format(java.util.Date())
        val logEntry = "[$timestamp] $message"
        debugLogs.add(logEntry)
        android.util.Log.d("PlayerActivity", message)
        
        // Keep only last maxLogEntries
        while (debugLogs.size > maxLogEntries) {
            debugLogs.removeAt(0)
        }
    }
    
    // Audio playback visualization
    private var isAudioFile = false
    private var cdAnimator: android.animation.ObjectAnimator? = null
    
    // Error recovery for stream parsing - try different MIME types
    // Order: auto-detect first, then common formats, finally streaming formats
    private var currentMimeTypeIndex = 0
    private val fallbackMimeTypes = listOf(
        null,                            // First: let ExoPlayer auto-detect
        MimeTypes.VIDEO_MP4,             // Then: MP4 (most common for downloads)
        MimeTypes.VIDEO_MATROSKA,        // Then: MKV
        MimeTypes.VIDEO_WEBM,            // Then: WebM
        MimeTypes.VIDEO_MP2T,            // Then: MPEG-TS
        MimeTypes.APPLICATION_M3U8       // Last: HLS (less common for direct links)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Close any existing PlayerActivity (including PiP) before starting new one
        finishExistingInstance()
        currentInstance = this
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        binding = ActivityPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Get screen dimensions
        val displayMetrics = resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        
        // Initialize audio manager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        
        // Get current brightness
        currentBrightness = window.attributes.screenBrightness
        if (currentBrightness < 0) {
            currentBrightness = Settings.System.getInt(
                contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                125
            ) / 255f
        }
        
        // Parse intent
        parseIntent()
        
        // Setup player
        initializePlayer()
        setupGestureControls()
        setupUIControls()
        
        // Hide system UI
        hideSystemUI()
    }
    
    override fun onNewIntent(newIntent: Intent?) {
        super.onNewIntent(newIntent)
        newIntent?.let { intentData ->
            android.util.Log.d("PlayerActivity", "onNewIntent: New video intent received, isPipMode=$isPipMode")
            
            // Update activity intent properly using setIntent()
            setIntent(intentData)
            
            // Clear current player state completely
            player?.let { exoPlayer ->
                exoPlayer.stop()
                exoPlayer.clearMediaItems()
            }
            
            // Re-parse intent with new data
            parseIntent()
            
            // Reload playlist with new video
            loadPlaylist()
            
            // Reset PiP mode flag if we were in PiP
            if (isPipMode) {
                isPipMode = false
            }
            
            // Ensure UI is visible and controls are shown
            binding.controlsContainer.visibility = View.VISIBLE
            binding.topBar.visibility = View.VISIBLE
            binding.bottomBar.visibility = View.VISIBLE
            binding.gestureOverlay.visibility = View.VISIBLE
            showControls()
            
            android.util.Log.d("PlayerActivity", "onNewIntent: New video loaded successfully")
        }
    }

    private fun parseIntent() {
        intent?.let { intentData ->
            val uri = intentData.getStringExtra(EXTRA_VIDEO_URI)
            val title = intentData.getStringExtra(EXTRA_VIDEO_TITLE) ?: "Video"
            currentIndex = intentData.getIntExtra(EXTRA_VIDEO_POSITION, 0)
            isNetworkStream = intentData.getBooleanExtra(EXTRA_IS_NETWORK_STREAM, false)
            
            // Get playlist from intent
            val playlistFromIntent = intentData.getStringArrayListExtra(EXTRA_PLAYLIST)
            val titlesFromIntent = intentData.getStringArrayListExtra(EXTRA_PLAYLIST_TITLES)
            
            // Handle different scenarios
            when {
                // Case 1: Playlist provided in extras
                !playlistFromIntent.isNullOrEmpty() -> {
                    playlist = playlistFromIntent.filter { it.isNotEmpty() }
                    playlistTitles = titlesFromIntent ?: playlist.map { "Video" }
                }
                // Case 2: Single URI provided
                !uri.isNullOrEmpty() -> {
                    playlist = listOf(uri)
                    playlistTitles = listOf(title)
                    currentIndex = 0
                }
                // Case 3: Intent data (from file manager or external app)
                intentData.data != null -> {
                    playlist = listOf(intentData.data.toString())
                    playlistTitles = listOf(intentData.data?.lastPathSegment ?: "Video")
                    currentIndex = 0
                }
                else -> {
                    // No video source
                    playlist = emptyList()
                    playlistTitles = emptyList()
                }
            }
            
            // Validate currentIndex
            currentIndex = currentIndex.coerceIn(0, (playlist.size - 1).coerceAtLeast(0))
            
            // Set title
            binding.videoTitle.text = playlistTitles.getOrNull(currentIndex) ?: "Video"
            
            // Check if current media is audio
            val currentUri = playlist.getOrNull(currentIndex) ?: ""
            isAudioFile = currentUri.endsWith(".mp3", true) ||
                         currentUri.endsWith(".m4a", true) ||
                         currentUri.endsWith(".aac", true) ||
                         currentUri.endsWith(".wav", true) ||
                         currentUri.endsWith(".flac", true) ||
                         currentUri.endsWith(".ogg", true) ||
                         currentUri.contains("/audio/")
            
            // Setup CD visualization for audio
            setupAudioVisualization()
            
            // Debug log
            android.util.Log.d("PlayerActivity", "Playlist size: ${playlist.size}, currentIndex: $currentIndex, isAudio: $isAudioFile")
            if (playlist.isNotEmpty()) {
                android.util.Log.d("PlayerActivity", "First URI: ${playlist[0]}")
            }
        }
    }
    
    private fun setupAudioVisualization() {
        if (isAudioFile) {
            // Show CD container for audio files
            binding.audioCdContainer.visibility = View.VISIBLE
            
            // Create rotation animation
            cdAnimator = android.animation.ObjectAnimator.ofFloat(
                binding.audioCdImage,
                "rotation",
                0f,
                360f
            ).apply {
                duration = 3000  // 3 seconds per rotation
                repeatCount = android.animation.ObjectAnimator.INFINITE
                interpolator = android.view.animation.LinearInterpolator()
            }
        } else {
            // Hide CD container for video files
            binding.audioCdContainer.visibility = View.GONE
            cdAnimator?.cancel()
            cdAnimator = null
        }
    }

    private fun initializePlayer() {
        // Check if we have videos to play
        if (playlist.isEmpty()) {
            Toast.makeText(this, "No video to play", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        
        android.util.Log.d("PlayerActivity", ">>> initializePlayer START - playlist size: ${playlist.size}")
        
        // Track selector - NO quality restrictions, allow fallback for unsupported formats
        trackSelector = DefaultTrackSelector(this).apply {
            setParameters(buildUponParameters()
                .setForceHighestSupportedBitrate(true) // Always use best quality
                .setExceedAudioConstraintsIfNecessary(true) // Allow audio even if constraints not met
                .setExceedVideoConstraintsIfNecessary(true) // Allow video even if constraints not met
                .setExceedRendererCapabilitiesIfNecessary(true) // IMPORTANT: Allow unsupported formats to try playing
            )
        }
        
        // Create OkHttp client with cookie jar for better streaming server compatibility
        val cookieJar = object : CookieJar {
            private val cookieStore = mutableMapOf<String, MutableList<Cookie>>()
            
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies.toMutableList()
            }
            
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: emptyList()
            }
        }
        
        val okHttpClient = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            // Network interceptor to handle HTML responses from streaming servers
            .addNetworkInterceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)
                
                // If server returns HTML instead of video, it might need specific headers
                val contentType = response.header("Content-Type") ?: ""
                if (contentType.contains("text/html")) {
                    // Log for debugging
                    android.util.Log.w("PlayerActivity", "Server returned HTML for: ${request.url}")
                }
                response
            }
            .addInterceptor { chain ->
                val original = chain.request()
                val url = original.url.toString()
                
                // Extract host for Referer header
                val host = original.url.host
                val referer = "http://$host/"
                
                val requestBuilder = original.newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                    .header("Accept", "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .header("Accept-Encoding", "identity")
                    .header("Referer", referer)
                    .header("Origin", "http://$host")
                    .header("Sec-Fetch-Dest", "video")
                    .header("Sec-Fetch-Mode", "cors")
                    .header("Sec-Fetch-Site", "same-origin")
                    .header("Connection", "keep-alive")
                    .header("Range", "bytes=0-")
                    .method(original.method, original.body)
                chain.proceed(requestBuilder.build())
            }
            .build()
        
        // Create OkHttp data source factory
        val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        
        // Create data source factory that uses OkHttp for network and default for local
        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        
        // Create custom extractors factory
        // Note: Removed FLAG_DISABLE_SEEK_FOR_CUES as it was preventing seeking in large videos
        val extractorsFactory = DefaultExtractorsFactory()
        
        // Create media source factory with HLS/DASH support and custom extractors
        val mediaSourceFactory = DefaultMediaSourceFactory(this, extractorsFactory)
            .setDataSourceFactory(dataSourceFactory)
        
        // Create RenderersFactory with software decoder support
        // EXTENSION_RENDERER_MODE_PREFER = Use software decoders when hardware not available
        val renderersFactory = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true) // Enable fallback to alternative decoders
        
        // Build ExoPlayer and ASSIGN FIRST!
        val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                true // Handle audio focus
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        
        // ASSIGN player variable BEFORE calling loadPlaylist!
        player = exoPlayer
        android.util.Log.d("PlayerActivity", ">>> Player assigned, player is now: $player")
        
        // Attach to view
        binding.playerView.player = exoPlayer
        binding.playerView.useController = false // Use custom controls
        
        // Add listener
        exoPlayer.addListener(playerListener)
        
        // NOW load playlist (player is no longer null!)
        loadPlaylist()
        
        // Set repeat mode to OFF - video stops at end (no loop, no auto-play next)
        exoPlayer.repeatMode = Player.REPEAT_MODE_OFF
        
        // Start playback
        android.util.Log.d("PlayerActivity", ">>> Calling prepare() and setting playWhenReady=true")
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
        
        android.util.Log.d("PlayerActivity", ">>> initializePlayer END - playWhenReady: ${exoPlayer.playWhenReady}")
    }

    private fun loadPlaylist() {
        player?.let { exoPlayer ->
            if (playlist.isEmpty()) {
                Toast.makeText(this, "No video to play", Toast.LENGTH_SHORT).show()
                return@let
            }
            
            // Get start position from intent (for resume playback)
            val startPosition = intent.getLongExtra(EXTRA_PLAYBACK_POSITION, 0L)
            
            // For network streams, use ProgressiveMediaSource directly
            if (isNetworkStream) {
                loadNetworkStreams(exoPlayer, startPosition)
            } else {
                loadLocalVideos(exoPlayer, startPosition)
            }
        }
    }
    
    /**
     * Load network streams using ProgressiveMediaSource - this bypasses HLS/DASH auto-detection
     * and forces direct progressive download parsing
     */
    private fun loadNetworkStreams(exoPlayer: ExoPlayer, startPosition: Long) {
        android.util.Log.d("PlayerActivity", "Loading network streams with ProgressiveMediaSource")
        
        // Create OkHttp data source for better network compatibility
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val original = chain.request()
                val host = original.url.host
                val requestBuilder = original.newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36")
                    .header("Accept", "*/*")
                    .header("Referer", "http://$host/")
                    .method(original.method, original.body)
                chain.proceed(requestBuilder.build())
            }
            .build()
        
        val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
        val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
        
        // Create extractors factory for format detection from binary content
        val extractorsFactory = DefaultExtractorsFactory()
            .setConstantBitrateSeekingEnabled(true)
        
        // Create ProgressiveMediaSource factory
        val progressiveMediaSourceFactory = ProgressiveMediaSource.Factory(
            dataSourceFactory,
            extractorsFactory
        )
        
        // Create media sources for all items
        val mediaSources = playlist.mapNotNull { uriString ->
            try {
                val uri = Uri.parse(uriString)
                val mediaItem = MediaItem.Builder()
                    .setUri(uri)
                    .build()
                progressiveMediaSourceFactory.createMediaSource(mediaItem)
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivity", "Error creating MediaSource for: $uriString", e)
                null
            }
        }
        
        if (mediaSources.isEmpty()) {
            Toast.makeText(this, "Failed to load stream", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Set media sources with start position
        val validIndex = currentIndex.coerceIn(0, mediaSources.size - 1)
        exoPlayer.setMediaSources(mediaSources, validIndex, startPosition)
        
        android.util.Log.d("PlayerActivity", "Loaded ${mediaSources.size} network streams, starting at index $validIndex, position $startPosition")
    }
    
    /**
     * Load local videos using standard MediaItem approach
     */
    private fun loadLocalVideos(exoPlayer: ExoPlayer, startPosition: Long) {
        val mediaItems = playlist.mapNotNull { uriString ->
            try {
                val uri = Uri.parse(uriString)
                val mimeType = getMimeType(uriString)
                
                android.util.Log.d("PlayerActivity", "Creating MediaItem - URI: $uriString, MIME: $mimeType")
                
                MediaItem.Builder()
                    .setUri(uri)
                    .apply {
                        mimeType?.let { setMimeType(it) }
                    }
                    .build()
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivity", "Error creating MediaItem for: $uriString", e)
                null
            }
        }
        
        if (mediaItems.isEmpty()) {
            Toast.makeText(this, "Failed to load video", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Ensure currentIndex is valid
        val validIndex = currentIndex.coerceIn(0, mediaItems.size - 1)
        exoPlayer.setMediaItems(mediaItems, validIndex, startPosition)
        
        android.util.Log.d("PlayerActivity", "Loaded ${mediaItems.size} local videos, starting at index $validIndex, position $startPosition")
    }
    
    /**
     * Detect MIME type for the given URI string
     */
    private fun getMimeType(uriString: String): String? {
        return try {
            val uri = Uri.parse(uriString)
            when (uri.scheme) {
                "content" -> {
                    // For content:// URIs, query ContentResolver for MIME type
                    contentResolver.getType(uri)
                }
                "file" -> {
                    // For file:// URIs, detect from extension
                    val extension = uriString.substringAfterLast(".", "").lowercase()
                    getVideoMimeTypeFromExtension(extension)
                }
                "http", "https" -> {
                    // For network streams, detect from URL patterns
                    when {
                        uriString.contains(".m3u8", true) -> MimeTypes.APPLICATION_M3U8
                        uriString.contains(".mpd", true) -> MimeTypes.APPLICATION_MPD
                        uriString.contains(".mp4", true) -> MimeTypes.VIDEO_MP4
                        uriString.contains(".mkv", true) -> MimeTypes.VIDEO_MATROSKA
                        uriString.contains(".webm", true) -> MimeTypes.VIDEO_WEBM
                        else -> null // Let ExoPlayer auto-detect
                    }
                }
                else -> null
            }
        } catch (e: Exception) {
            android.util.Log.w("PlayerActivity", "Failed to detect MIME type for: $uriString", e)
            null
        }
    }
    
    /**
     * Get video MIME type from file extension
     */
    private fun getVideoMimeTypeFromExtension(extension: String): String? {
        return when (extension) {
            "mp4", "m4v" -> MimeTypes.VIDEO_MP4
            "mkv" -> MimeTypes.VIDEO_MATROSKA
            "webm" -> MimeTypes.VIDEO_WEBM
            "3gp", "3gpp" -> MimeTypes.VIDEO_H263
            "avi" -> "video/avi"
            "mov" -> MimeTypes.VIDEO_MP4
            "flv" -> "video/x-flv"
            "wmv" -> "video/x-ms-wmv"
            "ts" -> MimeTypes.VIDEO_MP2T
            "m3u8" -> MimeTypes.APPLICATION_M3U8
            "mpd" -> MimeTypes.APPLICATION_MPD
            else -> MimeTypes.VIDEO_UNKNOWN
        }
    }
    // Error dialog removed for Play Store release - using simple Toast instead
    
    /**
     * Retry playback using ProgressiveMediaSource - this forces direct progressive download
     * and bypasses HLS/DASH detection which was causing parsing failures
     */
    private fun retryWithMimeType(uriString: String, mimeType: String?) {
        player?.let { exoPlayer ->
            try {
                val uri = Uri.parse(uriString)
                
                android.util.Log.d("PlayerActivity", "Retrying with ProgressiveMediaSource, MIME hint: $mimeType, URI: $uriString")
                
                // Create OkHttp data source for network requests
                val okHttpClient = OkHttpClient.Builder()
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .addInterceptor { chain ->
                        val original = chain.request()
                        val host = original.url.host
                        val requestBuilder = original.newBuilder()
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13; SM-G991B) AppleWebKit/537.36")
                            .header("Accept", "*/*")
                            .header("Referer", "http://$host/")
                            .method(original.method, original.body)
                        chain.proceed(requestBuilder.build())
                    }
                    .build()
                
                val httpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
                val dataSourceFactory = DefaultDataSource.Factory(this, httpDataSourceFactory)
                
                // Create extractors factory - uses all available extractors to detect format
                val extractorsFactory = DefaultExtractorsFactory()
                    .setConstantBitrateSeekingEnabled(true) // Enable CBR seeking
                
                // Create ProgressiveMediaSource - this forces progressive download parsing
                // and uses extractors to detect format from actual binary content
                val progressiveMediaSourceFactory = ProgressiveMediaSource.Factory(
                    dataSourceFactory,
                    extractorsFactory
                )
                
                val mediaItem = MediaItem.Builder()
                    .setUri(uri)
                    .apply {
                        mimeType?.let { setMimeType(it) }
                    }
                    .build()
                
                // Create the progressive media source
                val mediaSource = progressiveMediaSourceFactory.createMediaSource(mediaItem)
                
                // Stop current playback
                exoPlayer.stop()
                
                // Set media source directly and prepare
                exoPlayer.setMediaSource(mediaSource)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
                
                binding.progressBar.visibility = View.VISIBLE
                
                android.util.Log.d("PlayerActivity", "ProgressiveMediaSource created and set")
            } catch (e: Exception) {
                android.util.Log.e("PlayerActivity", "Error retrying with ProgressiveMediaSource", e)
                Toast.makeText(this, "Failed to retry: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val stateName = when (state) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN"
            }
            android.util.Log.d("PlayerActivity", "Playback state changed: $stateName")
            
            when (state) {
                Player.STATE_BUFFERING -> {
                    addLog("STATE: BUFFERING")
                    binding.progressBar.visibility = View.VISIBLE
                    startLoadingAnimation()
                }
                Player.STATE_READY -> {
                    addLog("STATE: READY - Duration: ${player?.duration}")
                    stopLoadingAnimation()
                    binding.progressBar.visibility = View.GONE
                    updateDuration()
                }
                Player.STATE_ENDED -> {
                    // Video ended - ROBUST handling to prevent any loop/restart
                    addLog("STATE: ENDED - videoHasEnded was: $videoHasEnded")
                    
                    // SET THE FLAG - this is 100% reliable indicator that video has completed
                    videoHasEnded = true
                    addLog("FLAG SET: videoHasEnded = true")
                    
                    player?.let { p ->
                        // 1. First pause playback
                        p.pause()
                        addLog("ACTION: Called pause()")
                        
                        // 2. Stop progress updates
                        stopLoadingAnimation()
                        
                        // 3. CLEAR the saved position immediately (set to 0)
                        val currentUri = playlist.getOrNull(currentIndex) ?: ""
                        if (currentUri.isNotEmpty()) {
                            val prefs = getSharedPreferences("pro_video_player_prefs", MODE_PRIVATE)
                            val positionsJson = prefs.getString("video_positions", "{}") ?: "{}"
                            val positionsObj = try {
                                org.json.JSONObject(positionsJson)
                            } catch (e: Exception) {
                                org.json.JSONObject()
                            }
                            // Set position to 0 for this video
                            val uriKey = currentUri.hashCode().toString()
                            positionsObj.put(uriKey, 0L)
                            prefs.edit()
                                .putString("video_positions", positionsObj.toString())
                                .putLong("last_video_position", 0L)
                                .apply()
                            addLog("ACTION: Cleared saved position for completed video")
                        }
                        
                        // 4. DIRECTLY SET RESTART ICON - no function calls, no delays
                        binding.progressBar.visibility = View.GONE
                        binding.btnPlayPause.setImageResource(R.drawable.ic_restart)
                        addLog(">>> ICON: Set RESTART icon directly <<<")
                        showControls()
                    }
                }
                Player.STATE_IDLE -> {
                    // Check if there's an error
                    player?.playerError?.let { error ->
                        addLog("STATE: IDLE with ERROR - ${error.message}")
                    } ?: addLog("STATE: IDLE")
                }
            }
        }
        
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            addLog("isPlayingChanged: $isPlaying, videoHasEnded: $videoHasEnded")
            
            // CRITICAL: Don't override restart icon if video has ended
            if (videoHasEnded && !isPlaying) {
                addLog(">>> BLOCKED: Not calling updatePlayPauseButton, keeping restart icon <<<")
                // Just update CD animation and progress, but keep restart icon
            } else {
                addLog("Calling updatePlayPauseButton()")
                updatePlayPauseButton()
            }
            
            if (isPlaying) {
                startProgressUpdates()
                scheduleHideControls()
                // Start CD animation for audio files
                if (isAudioFile) {
                    cdAnimator?.let {
                        if (!it.isStarted) {
                            it.start()
                        } else if (it.isPaused) {
                            it.resume()
                        }
                    }
                }
            } else {
                stopProgressUpdates()
                showControls()
                // Pause CD animation for audio files
                if (isAudioFile) {
                    cdAnimator?.pause()
                }
            }
        }
        
        override fun onPlayerError(error: PlaybackException) {
            addLog("Player error: ${error.errorCodeName} - ${error.message}")
            binding.progressBar.visibility = View.GONE
            
            val currentUri = playlist.getOrNull(currentIndex) ?: ""
            val isNetworkStream = currentUri.startsWith("http://") || currentUri.startsWith("https://")
            
            // Check for all parsing-related errors that should trigger ProgressiveMediaSource retry
            val isParsingError = error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED ||
                                 error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED ||
                                 error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED ||
                                 error.errorCode == PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED ||
                                 error.message?.contains("Multiple Segment elements", true) == true ||
                                 error.cause?.message?.contains("Multiple Segment elements", true) == true ||
                                 error.cause?.message?.contains("EXTM3U", true) == true ||
                                 error.cause?.message?.contains("ParserException", true) == true
            
            // For network streams with parsing errors, try ProgressiveMediaSource once
            if (isNetworkStream && isParsingError && currentMimeTypeIndex == 0) {
                currentMimeTypeIndex = 1  // Mark that we've tried progressive
                addLog("Retrying with ProgressiveMediaSource")
                
                Toast.makeText(
                    this@PlayerActivity, 
                    "Trying another format...", 
                    Toast.LENGTH_SHORT
                ).show()
                
                // Reload using ProgressiveMediaSource
                retryWithMimeType(currentUri, null)
                return
            }
            
            // Reset retry counter for next playback
            currentMimeTypeIndex = 0
            
            // Show simple Toast for user
            Toast.makeText(
                this@PlayerActivity,
                "Unable to play this video",
                Toast.LENGTH_SHORT
            ).show()
            
            addLog("Playback failed: ${error.errorCodeName}")
        }
        
        override fun onTracksChanged(tracks: Tracks) {
            android.util.Log.d("PlayerActivity", "Tracks changed - groups: ${tracks.groups.size}")
            
            // Count audio tracks
            var audioTrackCount = 0
            for (group in tracks.groups) {
                android.util.Log.d("PlayerActivity", "Track type: ${group.type}, length: ${group.length}")
                if (group.type == C.TRACK_TYPE_AUDIO) {
                    audioTrackCount += group.length
                }
            }
            
            // Show onboarding if multiple audio tracks and never shown before
            if (audioTrackCount > 1) {
                showAudioTrackOnboardingIfNeeded()
            }
        }
        
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            val reasonName = when (reason) {
                Player.MEDIA_ITEM_TRANSITION_REASON_AUTO -> "AUTO"
                Player.MEDIA_ITEM_TRANSITION_REASON_SEEK -> "SEEK"
                Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT -> "REPEAT"
                Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED -> "PLAYLIST_CHANGED"
                else -> "UNKNOWN($reason)"
            }
            addLog("onMediaItemTransition: reason=$reasonName, URI=${mediaItem?.localConfiguration?.uri}")
            
            // PREVENT AUTO-ADVANCE: If this transition was automatic (not user-triggered), handle end of video
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                addLog(">>> AUTO TRANSITION DETECTED - Video ended! <<<")
                
                player?.let { p ->
                    // Set the ended flag
                    videoHasEnded = true
                    addLog("FLAG SET: videoHasEnded = true (from onMediaItemTransition)")
                    
                    // Pause playback
                    p.pause()
                    addLog("ACTION: Called pause()")
                    
                    // Go back to the previous (current) video 
                    val targetIndex = currentIndex.coerceIn(0, playlist.size - 1)
                    p.seekTo(targetIndex, 0)  // Seek to start of current video
                    addLog("ACTION: Seeked back to index $targetIndex, position 0")
                    
                    // DIRECTLY SET RESTART ICON
                    binding.btnPlayPause.setImageResource(R.drawable.ic_restart)
                    addLog(">>> ICON: Set RESTART icon (from onMediaItemTransition) <<<")
                    showControls()
                }
                return  // Don't update UI for blocked transition
            }
            
            currentIndex = player?.currentMediaItemIndex ?: 0
            binding.videoTitle.text = playlistTitles.getOrNull(currentIndex) ?: "Video"
            updatePrevNextButtons()
            
            // Check if new media is audio file and update CD visualization
            val currentUri = playlist.getOrNull(currentIndex) ?: ""
            val wasAudio = isAudioFile
            isAudioFile = currentUri.endsWith(".mp3", true) ||
                         currentUri.endsWith(".m4a", true) ||
                         currentUri.endsWith(".aac", true) ||
                         currentUri.endsWith(".wav", true) ||
                         currentUri.endsWith(".flac", true) ||
                         currentUri.endsWith(".ogg", true) ||
                         currentUri.contains("/audio/")
            
            // Update visualization if audio state changed
            if (wasAudio != isAudioFile) {
                setupAudioVisualization()
            }
        }
        
        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            when (reason) {
                Player.DISCONTINUITY_REASON_SEEK -> {
                    android.util.Log.d("PlayerActivity", "Seek completed: ${oldPosition.positionMs} -> ${newPosition.positionMs}")
                    // Seek completed successfully
                    if (isSeeking) {
                        isSeeking = false
                        pendingSeekPosition = -1L
                        startProgressUpdates()
                    }
                }
                Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> {
                    android.util.Log.d("PlayerActivity", "Seek adjusted: ${oldPosition.positionMs} -> ${newPosition.positionMs}")
                    // Seek was adjusted (e.g., to nearest keyframe)
                    if (isSeeking) {
                        isSeeking = false
                        pendingSeekPosition = -1L
                        startProgressUpdates()
                    }
                }
                Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> {
                    android.util.Log.d("PlayerActivity", "Auto transition")
                }
                else -> {
                    android.util.Log.d("PlayerActivity", "Position discontinuity: reason=$reason")
                }
            }
        }
    }

    private fun setupGestureControls() {
        gestureDetector = GestureDetectorCompat(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (!isLocked) {
                    toggleControls()
                }
                return true
            }
            
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (!isLocked) {
                    val x = e.x
                    if (x < screenWidth / 3) {
                        // Left third - rewind
                        seekBackward()
                        showSeekIndicator(-10, isLeft = true)
                    } else if (x > screenWidth * 2 / 3) {
                        // Right third - forward
                        seekForward()
                        showSeekIndicator(10, isLeft = false)
                    } else {
                        // Center - play/pause
                        togglePlayPause()
                    }
                }
                return true
            }
            
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                if (isLocked || e1 == null) return false
                
                // Use cumulative delta only for determining gesture TYPE
                val cumulativeDeltaY = e1.y - e2.y
                val cumulativeDeltaX = e1.x - e2.x
                
                // Determine gesture type on first scroll
                if (!isGestureActive) {
                    isGestureActive = true
                    gestureType = when {
                        abs(cumulativeDeltaX) > abs(cumulativeDeltaY) -> GestureType.SEEK
                        e1.x < screenWidth / 2 -> GestureType.BRIGHTNESS
                        else -> GestureType.VOLUME
                    }
                }
                
                // Use distanceY for actual adjustment (per-frame, immediate response)
                // Swipe UP = increase, Swipe DOWN = decrease (distanceY positive when moving up)
                val frameDelta = -distanceY / screenHeight
                
                when (gestureType) {
                    GestureType.BRIGHTNESS -> {
                        adjustBrightness(frameDelta)
                    }
                    GestureType.VOLUME -> {
                        adjustVolume(frameDelta)
                    }
                    GestureType.SEEK -> {
                        // Horizontal seek handled separately
                    }
                    GestureType.NONE -> {}
                }
                
                return true
            }
        })
        
        // Setup scale gesture detector for pinch zoom with integrated pan
        scaleGestureDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                // Save initial focus point for pan tracking
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                return true
            }
            
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (isLocked) return false
                
                // Calculate new scale
                val scaleFactor = detector.scaleFactor
                val newScale = (currentScale * scaleFactor).coerceIn(minScale, maxScale)
                
                // Calculate focal point relative to view center
                val focusX = detector.focusX
                val focusY = detector.focusY
                val playerView = binding.playerView
                val viewCenterX = playerView.width / 2f
                val viewCenterY = playerView.height / 2f
                
                // Handle pan while zooming (focus point movement)
                if (currentScale > 1.0f || newScale > 1.0f) {
                    val panDx = focusX - lastFocusX
                    val panDy = focusY - lastFocusY
                    currentTranslateX += panDx
                    currentTranslateY += panDy
                }
                
                // Adjust translation for zoom focal point
                if (newScale > currentScale) {
                    // Zooming in - move towards focal point
                    val dx = (focusX - viewCenterX - currentTranslateX) * (1 - 1/scaleFactor) * 0.3f
                    val dy = (focusY - viewCenterY - currentTranslateY) * (1 - 1/scaleFactor) * 0.3f
                    currentTranslateX += dx
                    currentTranslateY += dy
                } else if (newScale < currentScale && newScale > minScale) {
                    // Zooming out - return towards center
                    currentTranslateX *= scaleFactor
                    currentTranslateY *= scaleFactor
                }
                
                // Limit translation to zoomed content bounds
                val maxTranslateX = (newScale - 1f) * screenWidth / 2f
                val maxTranslateY = (newScale - 1f) * screenHeight / 2f
                currentTranslateX = currentTranslateX.coerceIn(-maxTranslateX, maxTranslateX)
                currentTranslateY = currentTranslateY.coerceIn(-maxTranslateY, maxTranslateY)
                
                currentScale = newScale
                
                // Apply zoom and translation
                playerView.scaleX = currentScale
                playerView.scaleY = currentScale
                playerView.translationX = currentTranslateX
                playerView.translationY = currentTranslateY
                
                // Update last focus for next pan calculation
                lastFocusX = focusX
                lastFocusY = focusY
                
                return true
            }
            
            override fun onScaleEnd(detector: ScaleGestureDetector) {
                // Reset if zoomed out beyond minimum
                if (currentScale <= minScale) {
                    resetZoom()
                }
            }
        })
        
        binding.gestureOverlay.setOnTouchListener { _, event ->
            // Handle scale gesture first (2+ fingers)
            scaleGestureDetector.onTouchEvent(event)
            
            // Handle two-finger pan when zoomed
            if (currentScale > 1.0f && event.pointerCount == 2 && !scaleGestureDetector.isInProgress) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_POINTER_DOWN -> {
                        isPanning = true
                        lastTouchX = (event.getX(0) + event.getX(1)) / 2f
                        lastTouchY = (event.getY(0) + event.getY(1)) / 2f
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (isPanning && event.pointerCount >= 2) {
                            val currentX = (event.getX(0) + event.getX(1)) / 2f
                            val currentY = (event.getY(0) + event.getY(1)) / 2f
                            val dx = currentX - lastTouchX
                            val dy = currentY - lastTouchY
                            
                            // Apply pan translation
                            currentTranslateX += dx
                            currentTranslateY += dy
                            
                            // Limit translation to zoomed content bounds
                            val maxTranslateX = (currentScale - 1f) * screenWidth / 2f
                            val maxTranslateY = (currentScale - 1f) * screenHeight / 2f
                            currentTranslateX = currentTranslateX.coerceIn(-maxTranslateX, maxTranslateX)
                            currentTranslateY = currentTranslateY.coerceIn(-maxTranslateY, maxTranslateY)
                            
                            binding.playerView.translationX = currentTranslateX
                            binding.playerView.translationY = currentTranslateY
                            
                            lastTouchX = currentX
                            lastTouchY = currentY
                        }
                    }
                    MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                        isPanning = false
                    }
                }
            }
            
            // Only process single-finger gestures if not scaling and not panning
            if (!scaleGestureDetector.isInProgress && !isPanning && event.pointerCount == 1) {
                gestureDetector.onTouchEvent(event)
            }
            
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                isGestureActive = false
                gestureType = GestureType.NONE
                isPanning = false
                hideGestureIndicator()
            }
            
            true
        }
    }
    
    private fun resetZoom() {
        currentScale = 1.0f
        currentTranslateX = 0f
        currentTranslateY = 0f
        binding.playerView.scaleX = 1.0f
        binding.playerView.scaleY = 1.0f
        binding.playerView.translationX = 0f
        binding.playerView.translationY = 0f
    }

    private fun setupUIControls() {
        // Back button
        binding.btnBack.setOnClickListener {
            onBackPressed()
        }
        
        // Play/Pause button
        binding.btnPlayPause.setOnClickListener {
            togglePlayPause()
        }
        
        // Rewind button
        binding.btnRewind.setOnClickListener {
            seekBackward()
        }
        
        // Forward button
        binding.btnForward.setOnClickListener {
            seekForward()
        }
        
        // Previous video
        binding.btnPrevious.setOnClickListener {
            playPrevious()
        }
        
        // Next video
        binding.btnNext.setOnClickListener {
            playNext()
        }
        
        // Lock button
        binding.btnLock.setOnClickListener {
            toggleLock()
        }
        
        // Unlock button
        binding.btnUnlock.setOnClickListener {
            toggleLock()
        }
        
        // Settings button - tap to show menu, LONG PRESS to show debug logs
        binding.btnSettings.setOnClickListener {
            showSettingsMenu()
        }
        binding.btnSettings.setOnLongClickListener {
            showDebugLogsDialog()
            true
        }
        
        // Subtitle button
        binding.btnSubtitle.setOnClickListener {
            showSubtitleDialog()
        }
        
        // Audio track button
        binding.btnAudioTrack.setOnClickListener {
            showAudioTrackDialog()
        }
        
        // Filter button
        binding.btnFilter.setOnClickListener {
            showFilterDialog()
        }
        
        // Aspect ratio button
        binding.btnAspectRatio.setOnClickListener {
            cycleAspectRatio()
        }
        
        // Speed button
        binding.btnSpeed.setOnClickListener {
            showSpeedDialog()
        }
        
        // PiP button
        binding.btnPip.setOnClickListener {
            enterPictureInPicture()
        }
        
        // Rotate button
        binding.btnRotate.setOnClickListener {
            toggleOrientation()
        }
        
        // SeekBar
        binding.seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = player?.duration ?: C.TIME_UNSET
                    // Check for valid duration (not TIME_UNSET and > 0)
                    if (duration != C.TIME_UNSET && duration > 0) {
                        // Use Long arithmetic to avoid overflow for large durations
                        val position = (duration.toLong() * progress.toLong() / 100L)
                        binding.currentTime.text = formatTime(position)
                    }
                }
            }
            
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isSeeking = true
                stopProgressUpdates()
                // Cancel any pending hide operations and timeout handlers while seeking
                hideHandler.removeCallbacksAndMessages(null)
                progressHandler.removeCallbacksAndMessages(null)
            }
            
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val currentPlayer = player
                if (currentPlayer == null) {
                    isSeeking = false
                    pendingSeekPosition = -1L
                    startProgressUpdates()
                    return
                }
                
                val duration = currentPlayer.duration
                val progress = seekBar?.progress ?: 0
                val currentMediaItemIndex = currentPlayer.currentMediaItemIndex
                
                android.util.Log.d("PlayerActivity", "SeekBar: onStopTrackingTouch - progress: $progress%, duration: $duration ms, mediaIndex: $currentMediaItemIndex")
                
                // Check for valid duration (not TIME_UNSET and > 0)
                if (duration != C.TIME_UNSET && duration > 0) {
                    // Use Long arithmetic to avoid overflow for large durations
                    val targetPosition = (duration.toLong() * progress.toLong() / 100L)
                    
                    android.util.Log.d("PlayerActivity", "SeekBar: Seeking to position: $targetPosition ms (${formatTime(targetPosition)})")
                    
                    // Store pending seek position for tracking
                    pendingSeekPosition = targetPosition
                    
                    // Perform the seek with media item index to ensure correct seeking
                    currentPlayer.seekTo(currentMediaItemIndex, targetPosition)
                    
                    // Update UI immediately to show target position
                    binding.currentTime.text = formatTime(targetPosition)
                    
                    // Safety timeout - if onPositionDiscontinuity doesn't fire within 2 seconds, reset seeking state
                    progressHandler.postDelayed({
                        if (isSeeking) {
                            android.util.Log.w("PlayerActivity", "SeekBar: Seek timeout - forcing state reset")
                            isSeeking = false
                            pendingSeekPosition = -1L
                            startProgressUpdates()
                        }
                    }, 2000)
                } else {
                    android.util.Log.w("PlayerActivity", "SeekBar: Invalid duration ($duration), trying alternative seek method")
                    
                    // For streams without known duration, try to seek using contentDuration or buffered position
                    val contentDuration = currentPlayer.contentDuration
                    val bufferedPosition = currentPlayer.bufferedPosition
                    
                    android.util.Log.d("PlayerActivity", "SeekBar: contentDuration: $contentDuration, bufferedPosition: $bufferedPosition")
                    
                    if (contentDuration != C.TIME_UNSET && contentDuration > 0) {
                        val targetPosition = (contentDuration.toLong() * progress.toLong() / 100L)
                        pendingSeekPosition = targetPosition
                        currentPlayer.seekTo(currentMediaItemIndex, targetPosition)
                        binding.currentTime.text = formatTime(targetPosition)
                    } else if (bufferedPosition > 0 && progress > 0) {
                        // Last resort: estimate based on buffered content
                        val estimatedPosition = (bufferedPosition.toLong() * progress.toLong() / 100L)
                        pendingSeekPosition = estimatedPosition
                        android.util.Log.d("PlayerActivity", "SeekBar: Attempting seek with estimated position: $estimatedPosition")
                        currentPlayer.seekTo(currentMediaItemIndex, estimatedPosition)
                    }
                    
                    // Reset state after delay
                    progressHandler.postDelayed({
                        isSeeking = false
                        pendingSeekPosition = -1L
                        startProgressUpdates()
                    }, 500)
                }
            }
        })
        
        updatePrevNextButtons()
    }

    private fun togglePlayPause() {
        player?.let {
            if (it.isPlaying) {
                addLog("togglePlayPause: PAUSING video")
                it.pause()
            } else {
                // If video has ended OR position is at end, restart from beginning
                val isAtEnd = videoHasEnded || (it.duration > 0 && it.currentPosition >= it.duration - 1500)
                addLog("togglePlayPause: PLAYING - isAtEnd=$isAtEnd, videoHasEnded=$videoHasEnded, pos=${it.currentPosition}/${it.duration}")
                if (isAtEnd) {
                    addLog("togglePlayPause: Seeking to 0, clearing videoHasEnded flag")
                    it.seekTo(0)
                    videoHasEnded = false  // Clear the flag after restart
                }
                it.play()
            }
        }
    }

    private fun updatePlayPauseButton() {
        player?.let { p ->
            val isPlaying = p.isPlaying
            val playbackState = p.playbackState
            
            // SIMPLE LOGIC: Only use videoHasEnded flag or STATE_ENDED - NO position checks
            val isEnded = videoHasEnded || playbackState == Player.STATE_ENDED
            
            val iconName = when {
                isEnded && !isPlaying -> "RESTART"
                isPlaying -> "PAUSE"
                else -> "PLAY"
            }
            addLog("updatePlayPauseButton: Setting icon to $iconName (isPlaying=$isPlaying, isEnded=$isEnded, videoHasEnded=$videoHasEnded, state=$playbackState)")
            
            val iconRes = when {
                isEnded && !isPlaying -> R.drawable.ic_restart
                isPlaying -> R.drawable.ic_pause
                else -> R.drawable.ic_play
            }
            binding.btnPlayPause.setImageResource(iconRes)
        }
    }

    private fun seekForward() {
        player?.let {
            val newPosition = (it.currentPosition + SEEK_INCREMENT).coerceAtMost(it.duration)
            it.seekTo(newPosition)
        }
    }

    private fun seekBackward() {
        player?.let {
            val newPosition = (it.currentPosition - SEEK_INCREMENT).coerceAtLeast(0)
            it.seekTo(newPosition)
        }
    }

    private fun playNext() {
        player?.let {
            if (it.hasNextMediaItem()) {
                it.seekToNextMediaItem()
            }
        }
    }

    private fun playPrevious() {
        player?.let {
            if (it.hasPreviousMediaItem()) {
                it.seekToPreviousMediaItem()
            } else {
                it.seekTo(0)
            }
        }
    }

    private fun updatePrevNextButtons() {
        binding.btnPrevious.alpha = if (player?.hasPreviousMediaItem() == true) 1f else 0.5f
        binding.btnNext.alpha = if (player?.hasNextMediaItem() == true) 1f else 0.5f
    }

    private fun toggleLock() {
        isLocked = !isLocked
        if (isLocked) {
            binding.controlsContainer.visibility = View.GONE
            binding.topBar.visibility = View.GONE
            binding.bottomBar.visibility = View.GONE
            binding.lockContainer.visibility = View.VISIBLE
        } else {
            binding.lockContainer.visibility = View.GONE
            showControls()
        }
    }

    private fun toggleControls() {
        if (controlsVisible) {
            hideControls()
        } else {
            showControls()
        }
    }

    private fun showControls() {
        controlsVisible = true
        binding.controlsContainer.visibility = View.VISIBLE
        binding.topBar.visibility = View.VISIBLE
        binding.bottomBar.visibility = View.VISIBLE
        scheduleHideControls()
    }

    private fun hideControls() {
        controlsVisible = false
        binding.controlsContainer.visibility = View.GONE
        binding.topBar.visibility = View.GONE
        binding.bottomBar.visibility = View.GONE
    }

    private fun scheduleHideControls() {
        hideHandler.removeCallbacksAndMessages(null)
        hideHandler.postDelayed({
            if (player?.isPlaying == true && !isLocked) {
                hideControls()
            }
        }, HIDE_CONTROLS_DELAY)
    }

    private fun adjustBrightness(delta: Float) {
        // Sensitivity for per-frame delta (1.5 for 1:1 smooth response)
        val sensitivity = 1.5f
        currentBrightness = (currentBrightness + delta * sensitivity).coerceIn(0.01f, 1f)
        
        val layoutParams = window.attributes
        layoutParams.screenBrightness = currentBrightness
        window.attributes = layoutParams
        
        showGestureIndicator(GestureType.BRIGHTNESS, (currentBrightness * 100).toInt())
    }

    private fun adjustVolume(delta: Float) {
        // Sensitivity for per-frame delta (1.5 for 1:1 smooth response)
        val sensitivity = 1.5f
        val volumeChange = (delta * maxVolume * sensitivity).toInt()
        val newVolume = (currentVolume + volumeChange).coerceIn(0, maxVolume)
        currentVolume = newVolume
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
        
        showGestureIndicator(GestureType.VOLUME, (currentVolume * 100 / maxVolume))
    }

    private fun showGestureIndicator(type: GestureType, value: Int) {
        binding.gestureIndicator.visibility = View.VISIBLE
        when (type) {
            GestureType.BRIGHTNESS -> {
                binding.gestureIcon.setImageResource(R.drawable.ic_brightness)
                binding.gestureText.text = "$value%"
            }
            GestureType.VOLUME -> {
                binding.gestureIcon.setImageResource(R.drawable.ic_volume)
                binding.gestureText.text = "$value%"
            }
            else -> {}
        }
        binding.gestureProgress.progress = value
    }

    private fun hideGestureIndicator() {
        binding.gestureIndicator.visibility = View.GONE
    }

    // Runnable for hiding seek indicator
    private val hideSeekIndicatorRunnable = Runnable {
        binding.seekIndicator.visibility = View.GONE
    }
    
    private fun showSeekIndicator(seconds: Int, isLeft: Boolean) {
        // Cancel any pending hide
        binding.seekIndicator.removeCallbacks(hideSeekIndicatorRunnable)
        
        binding.seekIndicator.visibility = View.VISIBLE
        binding.seekIndicator.text = "${if (seconds > 0) "+" else ""}$seconds sec"
        
        // Position on left or right side based on where user tapped
        val params = binding.seekIndicator.layoutParams as android.widget.FrameLayout.LayoutParams
        params.gravity = android.view.Gravity.CENTER_VERTICAL or 
            (if (isLeft) android.view.Gravity.START else android.view.Gravity.END)
        params.marginStart = if (isLeft) (screenWidth / 6) else 0
        params.marginEnd = if (isLeft) 0 else (screenWidth / 6)
        binding.seekIndicator.layoutParams = params
        
        // Schedule hide after 800ms
        binding.seekIndicator.postDelayed(hideSeekIndicatorRunnable, 800)
    }


    private fun showSettingsMenu() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.dialog_settings, null)
        
        view.findViewById<LinearLayout>(R.id.menuSpeed).setOnClickListener {
            dialog.dismiss()
            showSpeedDialog()
        }
        
        view.findViewById<LinearLayout>(R.id.menuAspectRatio).setOnClickListener {
            dialog.dismiss()
            showAspectRatioDialog()
        }
        
        view.findViewById<LinearLayout>(R.id.menuSubtitle).setOnClickListener {
            dialog.dismiss()
            showSubtitleDialog()
        }
        
        view.findViewById<LinearLayout>(R.id.menuAudioTrack).setOnClickListener {
            dialog.dismiss()
            showAudioTrackDialog()
        }
        
        view.findViewById<LinearLayout>(R.id.menuFilter).setOnClickListener {
            dialog.dismiss()
            showFilterDialog()
        }
        
        dialog.setContentView(view)
        dialog.show()
    }

    private fun showSpeedDialog() {
        val speeds = arrayOf("0.25x", "0.5x", "0.75x", "1.0x (Normal)", "1.25x", "1.5x", "1.75x", "2.0x")
        val speedValues = floatArrayOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
        val currentIndex = speedValues.toList().indexOf(playbackSpeed).takeIf { it >= 0 } ?: 3
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Playback Speed")
            .setSingleChoiceItems(speeds, currentIndex) { dialog, which ->
                playbackSpeed = speedValues[which]
                player?.setPlaybackSpeed(playbackSpeed)
                binding.btnSpeed.text = speeds[which].replace(" (Normal)", "")
                dialog.dismiss()
            }
            .show()
    }

    private fun showAspectRatioDialog() {
        val modes = AspectRatioMode.values()
        val names = modes.map { it.displayName }.toTypedArray()
        val currentIndex = modes.indexOf(currentAspectRatio)
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Aspect Ratio")
            .setSingleChoiceItems(names, currentIndex) { dialog, which ->
                currentAspectRatio = modes[which]
                applyAspectRatio(currentAspectRatio)
                dialog.dismiss()
            }
            .show()
    }

    private fun cycleAspectRatio() {
        val modes = AspectRatioMode.values()
        val currentIndex = modes.indexOf(currentAspectRatio)
        currentAspectRatio = modes[(currentIndex + 1) % modes.size]
        applyAspectRatio(currentAspectRatio)
        Toast.makeText(this, currentAspectRatio.displayName, Toast.LENGTH_SHORT).show()
    }

    private fun applyAspectRatio(mode: AspectRatioMode) {
        binding.playerView.resizeMode = when (mode) {
            AspectRatioMode.FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
            AspectRatioMode.FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
            AspectRatioMode.STRETCH -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
            AspectRatioMode.RATIO_16_9 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            AspectRatioMode.RATIO_4_3 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
            AspectRatioMode.RATIO_21_9 -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH
        }
    }

    private fun showSubtitleDialog() {
        val tracks = player?.currentTracks ?: return
        val textTracks = mutableListOf<Pair<String, Int>>()
        textTracks.add("Off" to -1)
        
        var trackIndex = 0
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val label = format.label ?: format.language ?: "Track ${trackIndex + 1}"
                    textTracks.add(label to trackIndex)
                    trackIndex++
                }
            }
        }
        
        if (textTracks.size == 1) {
            Toast.makeText(this, "No subtitles available", Toast.LENGTH_SHORT).show()
            return
        }
        
        val names = textTracks.map { it.first }.toTypedArray()
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Subtitles")
            .setItems(names) { dialog, which ->
                if (which == 0) {
                    // Disable subtitles - use setTrackTypeDisabled for correct disable
                    trackSelector.setParameters(
                        trackSelector.buildUponParameters()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .build()
                    )
                } else {
                    // Enable subtitles and select the specific track
                    val selectedIndex = textTracks[which].second
                    trackSelector.setParameters(
                        trackSelector.buildUponParameters()
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .build()
                    )
                }
                dialog.dismiss()
            }
            .show()
    }

    private fun showAudioTrackDialog() {
        val tracks = player?.currentTracks ?: return
        val audioTracks = mutableListOf<Pair<String, Tracks.Group>>()
        
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val label = buildString {
                        // Get language name from code
                        val languageCode = format.language
                        val languageName = if (languageCode != null) {
                            try {
                                java.util.Locale(languageCode).displayLanguage
                            } catch (e: Exception) {
                                languageCode.uppercase()
                            }
                        } else null
                        
                        // Build label with language highlighted
                        val trackLabel = format.label ?: "Track ${i + 1}"
                        
                        if (languageName != null && languageName.isNotEmpty() && languageName != languageCode) {
                            // Show: "Track Name [Language]" format
                            append(trackLabel)
                            append(" [")
                            append(languageName)
                            if (languageCode != null) {
                                append(" - ")
                                append(languageCode.uppercase())
                            }
                            append("]")
                        } else if (languageCode != null) {
                            // Just show code if no display name
                            append(trackLabel)
                            append(" [")
                            append(languageCode.uppercase())
                            append("]")
                        } else {
                            append(trackLabel)
                        }
                        
                        // Add technical details
                        if (format.channelCount > 0) {
                            append(" • ${format.channelCount}ch")
                        }
                        if (format.sampleRate > 0) {
                            append(" ${format.sampleRate / 1000}kHz")
                        }
                    }
                    audioTracks.add(label to group)
                }
            }
        }
        
        if (audioTracks.isEmpty()) {
            Toast.makeText(this, "No audio tracks available", Toast.LENGTH_SHORT).show()
            return
        }
        
        val names = audioTracks.map { it.first }.toTypedArray()
        
        // Find currently selected track index
        var selectedIndex = 0
        for ((index, pair) in audioTracks.withIndex()) {
            if (pair.second.isSelected) {
                selectedIndex = index
                break
            }
        }
        
        MaterialAlertDialogBuilder(this)
            .setTitle("🎵 Audio Track")
            .setSingleChoiceItems(names, selectedIndex) { dialog, which ->
                val group = audioTracks[which].second
                trackSelector.setParameters(
                    trackSelector.buildUponParameters()
                        .setOverrideForType(
                            TrackSelectionOverride(group.mediaTrackGroup, 0)
                        )
                )
                dialog.dismiss()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFilterDialog() {
        val filters = VideoFilter.values()
        val names = filters.map { it.displayName }.toTypedArray()
        val currentIndex = filters.indexOf(currentFilter)
        
        MaterialAlertDialogBuilder(this)
            .setTitle("Video Filter")
            .setSingleChoiceItems(names, currentIndex) { dialog, which ->
                currentFilter = filters[which]
                applyVideoFilter(currentFilter)
                dialog.dismiss()
            }
            .show()
    }
    
    private fun applyVideoFilter(filter: VideoFilter) {
        val colorMatrix = when (filter) {
            VideoFilter.NONE -> null
            VideoFilter.GRAYSCALE -> android.graphics.ColorMatrix().apply { setSaturation(0f) }
            VideoFilter.SEPIA -> android.graphics.ColorMatrix(floatArrayOf(
                0.393f, 0.769f, 0.189f, 0f, 0f,
                0.349f, 0.686f, 0.168f, 0f, 0f,
                0.272f, 0.534f, 0.131f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            VideoFilter.NEGATIVE -> android.graphics.ColorMatrix(floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f
            ))
            VideoFilter.BRIGHTNESS -> android.graphics.ColorMatrix(floatArrayOf(
                1.2f, 0f, 0f, 0f, 30f,
                0f, 1.2f, 0f, 0f, 30f,
                0f, 0f, 1.2f, 0f, 30f,
                0f, 0f, 0f, 1f, 0f
            ))
            VideoFilter.CONTRAST -> android.graphics.ColorMatrix(floatArrayOf(
                1.5f, 0f, 0f, 0f, -50f,
                0f, 1.5f, 0f, 0f, -50f,
                0f, 0f, 1.5f, 0f, -50f,
                0f, 0f, 0f, 1f, 0f
            ))
            VideoFilter.SATURATION -> android.graphics.ColorMatrix().apply { setSaturation(1.5f) }
            VideoFilter.SHARPEN -> android.graphics.ColorMatrix().apply { setSaturation(1.2f) }
            VideoFilter.VIGNETTE -> android.graphics.ColorMatrix(floatArrayOf(
                0.9f, 0f, 0f, 0f, 0f,
                0f, 0.9f, 0f, 0f, 0f,
                0f, 0f, 0.9f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f
            ))
            VideoFilter.WARM -> android.graphics.ColorMatrix(floatArrayOf(
                1.2f, 0f, 0f, 0f, 10f,
                0f, 1.0f, 0f, 0f, 0f,
                0f, 0f, 0.8f, 0f, -10f,
                0f, 0f, 0f, 1f, 0f
            ))
            VideoFilter.COOL -> android.graphics.ColorMatrix(floatArrayOf(
                0.8f, 0f, 0f, 0f, -10f,
                0f, 1.0f, 0f, 0f, 0f,
                0f, 0f, 1.2f, 0f, 10f,
                0f, 0f, 0f, 1f, 0f
            ))
        }
        
        // Apply color filter using PlayerView's overlay framebuffer
        val overlayView = binding.playerView.overlayFrameLayout
        if (colorMatrix != null) {
            val paint = android.graphics.Paint().apply {
                colorFilter = android.graphics.ColorMatrixColorFilter(colorMatrix)
            }
            overlayView?.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
            // Also apply to the entire player view for effect
            binding.playerView.setLayerType(View.LAYER_TYPE_HARDWARE, paint)
            Toast.makeText(this, "Filter: ${filter.displayName}", Toast.LENGTH_SHORT).show()
        } else {
            overlayView?.setLayerType(View.LAYER_TYPE_NONE, null)
            binding.playerView.setLayerType(View.LAYER_TYPE_NONE, null)
            Toast.makeText(this, "Filter removed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleOrientation() {
        requestedOrientation = if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    private fun enterPictureInPicture() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                val aspectRatio = Rational(16, 9)
                val params = PictureInPictureParams.Builder()
                    .setAspectRatio(aspectRatio)
                    .build()
                enterPictureInPictureMode(params)
            } else {
                Toast.makeText(this, "PiP not supported on this device", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "PiP requires Android 8.0+", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Update screen dimensions when orientation changes (important for double tap gesture)
        val displayMetrics = resources.displayMetrics
        screenWidth = displayMetrics.widthPixels
        screenHeight = displayMetrics.heightPixels
        android.util.Log.d("PlayerActivity", "Configuration changed: screenWidth=$screenWidth, screenHeight=$screenHeight")
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {

        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        
        if (isInPictureInPictureMode) {
            // Entering PiP
            wasInPipMode = true
            isPipMode = true
            addLog("PiP: Entering PiP mode")
            // Hide all UI in PiP
            binding.controlsContainer.visibility = View.GONE
            binding.topBar.visibility = View.GONE
            binding.bottomBar.visibility = View.GONE
            binding.gestureOverlay.visibility = View.GONE
        } else {
            // Exiting PiP mode
            isPipMode = false
            addLog("PiP: Exiting PiP mode - isFinishing: $isFinishing")
            
            // Check multiple conditions to determine if PiP was dismissed (not expanded)
            // When user swipes away PiP, sometimes isFinishing is false but activity is being destroyed
            val isPipClosedByUser = isFinishing || !hasWindowFocus() || isDestroyed
            
            if (isPipClosedByUser) {
                // User closed PiP by swiping away - stop player IMMEDIATELY
                wasInPipMode = false
                addLog("PiP: Closed by user - stopping player")
                player?.pause()
                player?.stop()
                finish()  // Finish the activity
            } else {
                // User tapped PiP to return to full screen - restore UI
                wasInPipMode = false
                binding.gestureOverlay.visibility = View.VISIBLE
                binding.controlsContainer.visibility = View.VISIBLE
                binding.topBar.visibility = View.VISIBLE
                binding.bottomBar.visibility = View.VISIBLE
                showControls()
                addLog("PiP: Expanded to full screen")
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Auto-enter PiP when user presses home while video is playing
        if (player?.isPlaying == true && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            enterPictureInPicture()
        }
    }

    // Progress updates
    private val progressRunnable = object : Runnable {
        override fun run() {
            updateProgress()
            progressHandler.postDelayed(this, 500) // Update every 500ms for smoother progress
        }
    }

    private fun startProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable) // Remove any pending callbacks first
        progressHandler.post(progressRunnable)
    }

    private fun stopProgressUpdates() {
        progressHandler.removeCallbacks(progressRunnable)
    }

    private fun updateProgress() {
        // Don't update progress while user is seeking
        if (isSeeking) return
        
        player?.let {
            val position = it.currentPosition
            val duration = it.duration
            
            // Update current time
            binding.currentTime.text = formatTime(position)
            
            // Check for valid duration (not TIME_UNSET and > 0)
            if (duration != C.TIME_UNSET && duration > 0) {
                binding.totalTime.text = formatTime(duration)
                // Use Long arithmetic to avoid overflow
                binding.seekBar.progress = (position.toLong() * 100L / duration.toLong()).toInt()
            } else {
                // For streams with unknown duration, show buffered time as reference
                val bufferedDuration = it.bufferedPosition
                if (bufferedDuration > 0) {
                    binding.totalTime.text = formatTime(bufferedDuration)
                }
            }
        }
    }

    private fun updateDuration() {
        player?.let {
            val duration = it.duration
            if (duration != C.TIME_UNSET && duration > 0) {
                binding.totalTime.text = formatTime(duration)
            }
        }
    }

    private fun formatTime(millis: Long): String {
        // Handle invalid values
        if (millis < 0 || millis == C.TIME_UNSET) {
            return "00:00"
        }
        
        val hours = millis / 3600000
        val minutes = (millis % 3600000) / 60000
        val seconds = (millis % 60000) / 1000
        
        return if (hours > 0) {
            String.format("%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }

    private fun hideSystemUI() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).let { controller ->
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onStart() {
        super.onStart()
        if (player == null) {
            initializePlayer()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        // Only auto-play if not in PiP mode
        val inPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInPictureInPictureMode else false
        if (!inPip) {
            player?.playWhenReady = true
        }
    }

    override fun onPause() {
        super.onPause()
        // Check if entering PiP mode - if so, keep playing
        val inPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInPictureInPictureMode else false
        if (!inPip) {
            saveToHistory()
            player?.playWhenReady = false
        }
    }
    
    private fun saveToHistory() {
        if (playlist.isEmpty()) return
        
        val currentUri = playlist.getOrNull(currentIndex) ?: return
        val currentTitle = playlistTitles.getOrNull(currentIndex) ?: "Video"
        val position = player?.currentPosition ?: 0
        
        // Skip audio files - only save videos to history
        val isAudio = currentUri.contains("/audio/") ||
                     currentUri.endsWith(".mp3", true) ||
                     currentUri.endsWith(".m4a", true) ||
                     currentUri.endsWith(".flac", true) ||
                     currentUri.endsWith(".wav", true) ||
                     currentUri.endsWith(".aac", true)
        
        if (isAudio) return  // Don't save audio to history
        
        val prefs = getSharedPreferences("pro_video_player_prefs", MODE_PRIVATE)
        
        // Load existing history
        val historyJson = prefs.getString("video_history", "[]")
        val historyArray = try {
            org.json.JSONArray(historyJson)
        } catch (e: Exception) {
            org.json.JSONArray()
        }
        
        // Remove if already exists (to move to end)
        val newArray = org.json.JSONArray()
        for (i in 0 until historyArray.length()) {
            val uri = historyArray.getString(i)
            if (uri != currentUri) {
                newArray.put(uri)
            }
        }
        
        // Add current video to end
        newArray.put(currentUri)
        
        // Keep only last 20 items
        val finalArray = org.json.JSONArray()
        val startIndex = if (newArray.length() > 20) newArray.length() - 20 else 0
        for (i in startIndex until newArray.length()) {
            finalArray.put(newArray.getString(i))
        }
        
        // Load existing per-URI positions
        val positionsJson = prefs.getString("video_positions", "{}")
        val positionsObj = try {
            org.json.JSONObject(positionsJson)
        } catch (e: Exception) {
            org.json.JSONObject()
        }
        
        // Save position for this URI (use hashCode as key to avoid special chars issues)
        val uriKey = currentUri.hashCode().toString()
        
        // Don't save position if video is complete (near end) - save 0 instead
        val duration = player?.duration ?: 0
        val positionToSave = if (duration > 0 && position >= duration - 1000) {
            0L  // Video completed, save 0 so it starts from beginning next time
        } else {
            position
        }
        positionsObj.put(uriKey, positionToSave)
        
        // Clean up old position entries (keep only for URIs in history)
        val validKeys = (0 until finalArray.length()).map { 
            finalArray.getString(it).hashCode().toString() 
        }.toSet()
        val keysToRemove = positionsObj.keys().asSequence().filter { it !in validKeys }.toList()
        keysToRemove.forEach { positionsObj.remove(it) }
        
        prefs.edit()
            .putString("video_history", finalArray.toString())
            .putString("video_positions", positionsObj.toString())
            .putString("last_video_uri", currentUri)
            .putString("last_video_title", currentTitle)
            .putLong("last_video_position", position)
            .apply()
        
        android.util.Log.d("PlayerActivity", "Saved position $position for URI: ${currentUri.take(50)}...")
    }
    
    // Audio track onboarding - shows tooltip when multiple audio tracks detected
    private fun showAudioTrackOnboardingIfNeeded() {
        // Keep controls visible
        showControls()
        hideHandler.removeCallbacksAndMessages(null)
        
        // Create tooltip view
        val tooltipView = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, 0)
        }
        
        // Arrow pointing up
        val arrow = android.widget.TextView(this).apply {
            text = "▲"
            setTextColor(android.graphics.Color.parseColor("#4CAF50"))
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            includeFontPadding = false
        }
        
        // Instruction card
        val card = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(36, 22, 36, 22)
            gravity = android.view.Gravity.CENTER_VERTICAL
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.parseColor("#E8141414"))
                cornerRadius = 18f
                setStroke(2, android.graphics.Color.parseColor("#4CAF50"))
            }
        }
        
        // Icon
        val icon = android.widget.ImageView(this).apply {
            setImageResource(R.drawable.ic_audio_track)
            setColorFilter(android.graphics.Color.parseColor("#4CAF50"))
            layoutParams = android.widget.LinearLayout.LayoutParams(44, 44).apply {
                marginEnd = 14
            }
        }
        
        // Text
        val textView = android.widget.TextView(this).apply {
            text = "Multiple Audio Tracks\nTap to switch"
            setTextColor(android.graphics.Color.WHITE)
            textSize = 13f
            setLineSpacing(2f, 1f)
        }
        
        card.addView(icon)
        card.addView(textView)
        tooltipView.addView(arrow)
        tooltipView.addView(card)
        
        // Create PopupWindow - NOT focusable so controls remain touchable
        val popup = android.widget.PopupWindow(
            tooltipView,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            false  // NOT focusable - allows touches to pass through to controls
        ).apply {
            elevation = 16f
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            isOutsideTouchable = true
            isTouchable = true
            isFocusable = false  // Allow controls behind to be touched
        }
        
        // Measure tooltip to calculate center offset
        tooltipView.measure(
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        )
        val tooltipWidth = tooltipView.measuredWidth
        val btnWidth = binding.btnAudioTrack.width
        val xOffset = (btnWidth - tooltipWidth) / 2  // Center under button
        
        // Start invisible for animation
        tooltipView.alpha = 0f
        
        // Show popup directly below the button
        popup.showAsDropDown(binding.btnAudioTrack, xOffset, 0)
        
        // Fade in animation
        tooltipView.animate()
            .alpha(1f)
            .setDuration(250)
            .start()
        
        // Dismiss listener
        popup.setOnDismissListener {
            scheduleHideControls()
        }
        
        // Click to dismiss
        tooltipView.setOnClickListener {
            popup.dismiss()
        }
        
        // Auto dismiss after 4 seconds
        tooltipView.postDelayed({
            if (popup.isShowing) {
                tooltipView.animate()
                    .alpha(0f)
                    .setDuration(180)
                    .withEndAction { 
                        try { popup.dismiss() } catch (e: Exception) {}
                    }
                    .start()
            }
        }, 4000)
    }




    private var loadingAnimation: android.view.animation.Animation? = null
    
    private fun startLoadingAnimation() {
        if (loadingAnimation == null) {
            loadingAnimation = android.view.animation.AnimationUtils.loadAnimation(this, R.anim.rotate_loading)
        }
        binding.progressBar.startAnimation(loadingAnimation)
    }
    
    private fun stopLoadingAnimation() {
        binding.progressBar.clearAnimation()
    }

    override fun onStop() {
        super.onStop()
        // Don't pause if in PiP mode
        val inPip = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) isInPictureInPictureMode else false
        if (!inPip) {
            player?.playWhenReady = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hideHandler.removeCallbacksAndMessages(null)
        progressHandler.removeCallbacksAndMessages(null) // Clean up progress handler too
        cdAnimator?.cancel() // Clean up CD animation
        cdAnimator = null
        // Stop playback completely before releasing
        player?.stop()
        player?.release()
        player = null
        // Clear static reference
        if (currentInstance == this) {
            currentInstance = null
        }
        android.util.Log.d("PlayerActivity", "onDestroy: Player stopped and released")
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isLocked) {
            Toast.makeText(this, "Unlock to go back", Toast.LENGTH_SHORT).show()
        } else {
            super.onBackPressed()
        }
    }
    
    /**
     * Show debug logs dialog - accessible via long press on settings icon
     */
    private fun showDebugLogsDialog() {
        val logsText = if (debugLogs.isEmpty()) {
            "No logs yet. Play/pause video to generate logs."
        } else {
            debugLogs.joinToString("\n")
        }
        
        // Add current player state info at top
        val stateInfo = buildString {
            appendLine("=== PLAYER STATE ===")
            player?.let { p ->
                appendLine("isPlaying: ${p.isPlaying}")
                appendLine("playbackState: ${p.playbackState}")
                appendLine("position: ${p.currentPosition}/${p.duration}")
                appendLine("videoHasEnded: $videoHasEnded")
                appendLine("currentIndex: $currentIndex/${playlist.size}")
            } ?: appendLine("Player is null")
            appendLine("===================")
            appendLine()
        }
        
        val fullLog = stateInfo + logsText
        
        MaterialAlertDialogBuilder(this)
            .setTitle("🔧 Debug Logs")
            .setMessage(fullLog)
            .setPositiveButton("📋 Copy All") { dialog, _ ->
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Player Debug Logs", fullLog)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(this, "Logs copied to clipboard!", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton("Close") { dialog, _ ->
                dialog.dismiss()
            }
            .setNeutralButton("🗑️ Clear") { dialog, _ ->
                debugLogs.clear()
                Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .show()
    }

    enum class GestureType {
        NONE, BRIGHTNESS, VOLUME, SEEK
    }
}
