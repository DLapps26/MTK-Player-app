package com.dlapps.mtkplayer.ui.player

import android.app.Application
import android.content.ComponentName
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import androidx.tv.material3.ColorScheme
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.request.ErrorResult
import com.dlapps.mtkplayer.MediaSessionService
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.dlapps.mtkplayer.ui.theme.generateColorSchemeFromImage
import com.dlapps.mtkplayer.util.BillingManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

@UnstableApi
data class VideoPlayerUiState(
    val videoUri: String? = null,
    val videoTitle: String? = null,
    val videoArtist: String? = null,
    val videoAlbum: String? = null,
    val artWork: Any? = null, // Can be String (URI), Uri, or ByteArray
    val poster: Any? = null,  // Can be String (URI), Uri, or ByteArray
    val logo: String? = null,
    val thumbnail: String? = null,
    val isLoading: Boolean = false,
    val isFirstFrameRendered: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val resolution: String? = null,
    val audioFormat: String? = null,
    val bitrate: String? = null,
    val audioChannels: String? = null,
    val frameRate: Float? = null,
    val hdrFormat: String? = null,
    val playbackState: Int = Player.STATE_IDLE,
    val tracks: Tracks = Tracks.EMPTY,
    val trackSelectionParameters: TrackSelectionParameters = TrackSelectionParameters.Builder().build(),
    val isPlaying: Boolean = false,
    val hdrMode: HdrMode = HdrMode.DISABLE,
    val dynamicColorScheme: ColorScheme? = null,
    val playerTheme: String = "Android",
    val surfaceAlpha: Float = 0.8f,
    val mediaType: Int = MediaMetadata.MEDIA_TYPE_MIXED,
    val isPremium: Boolean = false
)

@OptIn(UnstableApi::class)
class VideoPlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(VideoPlayerUiState())
    val uiState = _uiState.asStateFlow()
    private val _controller = MutableStateFlow<MediaController?>(null)
    private val _playbackFinished = MutableSharedFlow<Unit>()
    val playbackFinished = _playbackFinished.asSharedFlow()
    private var _controllerFuture: ListenableFuture<MediaController>? = null
    val controllerFuture: ListenableFuture<MediaController>? get() = _controllerFuture
    private var progressJob: Job? = null
    private val settingsManager = SettingsManager(application)
    private val billingManager = BillingManager(application)
    val controller: MediaController? get() = _controller.value
    var isLaunchedViaIntent = false
    
    // Maintain these for MainActivity result reporting
    var currentPosition: Long = 0L
    var duration: Long = 0L
    var mediaId: String? = null

    init {
        // Fetch initial settings synchronously to avoid UI flickering/PlayerView recreation
        try {
            val savedMode = runBlocking { settingsManager.hdrModeFlow.first() }
            val savedTheme = runBlocking { settingsManager.playerThemeFlow.first() }
            val savedAlpha = runBlocking { settingsManager.surfaceAlphaFlow.first() }
            _uiState.update { it.copy(
                hdrMode = savedMode,
                playerTheme = savedTheme,
                surfaceAlpha = savedAlpha
            ) }
        } catch (_: Exception) {
            // Error loading settings
        }

        viewModelScope.launch {
            billingManager.isPremium.collect { isPremium ->
                _uiState.update { it.copy(isPremium = isPremium ?: false) }
                // If the user is definitely not premium, revert premium features to defaults
                if (isPremium == false) {
                    if (_uiState.value.playerTheme == "Dynamic Colors") {
                        setPlayerTheme("Android")
                    }
                    if (_uiState.value.hdrMode == HdrMode.FORCE_DV) {
                        setHdrMode(HdrMode.DISABLE)
                    }
                }
            }
        }
        
        initializeController()
    }

    fun initializeController() {
        if (_controller.value != null || (_controllerFuture != null && !_controllerFuture!!.isCancelled)) return
        
        val application = getApplication<Application>()
        val sessionToken = SessionToken(application, ComponentName(application, MediaSessionService::class.java))
        val future = MediaController.Builder(application, sessionToken).buildAsync()
        _controllerFuture = future

        future.addListener({
            try {
                val player = future.get()
                _controller.value = player
                
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(state: Int) {
                        val dur = player.duration.coerceAtLeast(0L)
                        val pos = player.currentPosition
                        _uiState.update { it.copy(
                            isLoading = state == Player.STATE_BUFFERING,
                            playbackState = state,
                            duration = dur,
                            currentPosition = pos
                        ) }
                        duration = dur
                        currentPosition = pos

                        if (state == Player.STATE_READY) {
                            updateMediaMetadata(player)
                        }
                        if (state == Player.STATE_ENDED) {
                            viewModelScope.launch {
                                _playbackFinished.emit(Unit)
                            }
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _uiState.update { it.copy(isPlaying = isPlaying) }
                        if (isPlaying) {
                            startProgressPolling()
                        } else {
                            stopProgressPolling()
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        Log.e("VideoPlayerViewModel", "Player Error: ${error.message}", error)
                        _uiState.update { it.copy(isLoading = false) }
                    }

                    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                        val pos = newPosition.positionMs
                        _uiState.update { it.copy(currentPosition = pos) }
                        currentPosition = pos
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        mediaItem?.let { item ->
                            val metadata = item.mediaMetadata
                            val isMusic = metadata.mediaType == MediaMetadata.MEDIA_TYPE_MUSIC || 
                                          item.mediaId.endsWith(".mp3", ignoreCase = true) || 
                                          item.mediaId.endsWith(".flac", ignoreCase = true) ||
                                          item.mediaId.endsWith(".m4a", ignoreCase = true)
                            
                            val artwork = metadata.artworkUri ?: metadata.artworkData
                            
                            _uiState.update { it.copy(
                                videoUri = item.localConfiguration?.uri?.toString() ?: item.mediaId,
                                videoTitle = metadata.displayTitle?.toString() ?: metadata.title?.toString(),
                                videoArtist = metadata.artist?.toString() ?: metadata.albumArtist?.toString(),
                                videoAlbum = metadata.albumTitle?.toString(),
                                artWork = artwork,
                                logo = metadata.extras?.getString("logo_url"),
                                thumbnail = metadata.extras?.getString("thumb_url"),
                                poster = metadata.extras?.getString("poster_url") ?: if (!isMusic) artwork else null,
                                mediaType = if (isMusic) MediaMetadata.MEDIA_TYPE_MUSIC else (metadata.mediaType ?: MediaMetadata.MEDIA_TYPE_MIXED),
                                isFirstFrameRendered = false
                            ) }

                            updateDynamicTheme(metadata.extras?.getString("logo_url")
                                ?: metadata.extras?.getString("thumb_url")
                                ?: metadata.extras?.getString("poster_url")
                                ?: artwork
                            )
                        }
                    }

                    override fun onMediaMetadataChanged(metadata: MediaMetadata) {
                        Log.d("VideoPlayerViewModel", "onMediaMetadataChanged: title=${metadata.title}, artist=${metadata.artist}, type=${metadata.mediaType}")
                        _uiState.update { currentState ->
                            val updatedMediaType = if (metadata.mediaType != null && metadata.mediaType != MediaMetadata.MEDIA_TYPE_MIXED) {
                                metadata.mediaType!!
                            } else {
                                currentState.mediaType
                            }
                            val isMusic = updatedMediaType == MediaMetadata.MEDIA_TYPE_MUSIC
                            
                            val updatedTitle = metadata.displayTitle?.toString() ?: metadata.title?.toString() ?: currentState.videoTitle
                            val updatedArtist = metadata.artist?.toString() ?: metadata.albumArtist?.toString() ?: currentState.videoArtist
                            val updatedAlbum = metadata.albumTitle?.toString() ?: currentState.videoAlbum
                            val updatedArt = metadata.artworkUri ?: metadata.artworkData ?: currentState.artWork
                            
                            currentState.copy(
                                videoTitle = updatedTitle,
                                videoArtist = updatedArtist,
                                videoAlbum = updatedAlbum,
                                artWork = updatedArt,
                                poster = metadata.extras?.getString("poster_url") ?: if (!isMusic) updatedArt else null,
                                mediaType = updatedMediaType
                            )
                        }
                        
                        val state = _uiState.value
                        if (state.dynamicColorScheme == null) {
                            updateDynamicTheme(state.logo ?: state.thumbnail ?: state.poster ?: state.artWork)
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        _uiState.update { it.copy(tracks = tracks) }
                        updateMediaMetadata(player)
                    }

                    override fun onTrackSelectionParametersChanged(parameters: TrackSelectionParameters) {
                        _uiState.update { it.copy(trackSelectionParameters = parameters) }
                    }

                    override fun onRenderedFirstFrame() {
                        _uiState.update { it.copy(isFirstFrameRendered = true) }
                        updateMediaMetadata(player)
                    }
                })
                
                // Initialize with current values once controller is ready
                val dur = player.duration.coerceAtLeast(0L)
                val pos = player.currentPosition
                val metadata = player.mediaMetadata
                
                val item = player.currentMediaItem
                val isMusicItem = item?.let { 
                    metadata.mediaType == MediaMetadata.MEDIA_TYPE_MUSIC || 
                    it.mediaId.endsWith(".mp3", ignoreCase = true) || 
                    it.mediaId.endsWith(".flac", ignoreCase = true)
                } ?: false

                val artwork = metadata.artworkUri ?: metadata.artworkData
                
                _uiState.update { it.copy(
                    tracks = player.currentTracks,
                    trackSelectionParameters = player.trackSelectionParameters,
                    isPlaying = player.isPlaying,
                    duration = dur,
                    currentPosition = pos,
                    videoTitle = metadata.displayTitle?.toString() ?: metadata.title?.toString(),
                    videoArtist = metadata.artist?.toString() ?: metadata.albumArtist?.toString(),
                    videoAlbum = metadata.albumTitle?.toString(),
                    artWork = artwork,
                    poster = metadata.extras?.getString("poster_url") ?: if (!isMusicItem) artwork else null,
                    mediaType = if (isMusicItem) MediaMetadata.MEDIA_TYPE_MUSIC else (metadata.mediaType ?: MediaMetadata.MEDIA_TYPE_MIXED)
                ) }
                duration = dur
                currentPosition = pos

                if (player.isPlaying) {
                    startProgressPolling()
                }
                
                updateMediaMetadata(player)
            } catch (_: Exception) {
                // Failed to connect MediaController
                _controllerFuture = null
            }
        }, MoreExecutors.directExecutor())
    }

    private fun updateMediaMetadata(player: Player) {
        val tracks = player.currentTracks
        var resolution: String? = null
        var audioFormat: String? = null
        var bitrate: String? = null
        var audioChannels: String? = null
        var frameRate: Float? = null
        var hdrFormat: String? = null
        
        var hasVideo = false
        var hasAudio = false

        tracks.groups.forEach { group ->
            if (group.type == androidx.media3.common.C.TRACK_TYPE_VIDEO) hasVideo = true
            if (group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO) hasAudio = true

            if (group.isSelected) {
                val format = group.getTrackFormat(0)

                // 1. Extract Video Resolution
                if (group.type == androidx.media3.common.C.TRACK_TYPE_VIDEO) {
                    resolution = when {
                        format.width >= 3840 -> "UHD"
                        format.width >= 1920 -> "FHD"
                        format.width >= 1280 -> "HD"
                        format.height > 0 -> "${format.height}p"
                        else -> null
                    }
                    if (format.frameRate > 0f) {
                        frameRate = format.frameRate
                    }

                    val colorInfo = format.colorInfo
                    val mimeType = format.sampleMimeType ?: ""
                    val codecs = format.codecs ?: ""

                    hdrFormat = when {
                        mimeType.contains("video/dolby-vision") || codecs.startsWith("dv") -> "Dolby Vision"
                        colorInfo?.colorTransfer == androidx.media3.common.C.COLOR_TRANSFER_ST2084 -> "HDR10"
                        colorInfo?.colorTransfer == androidx.media3.common.C.COLOR_TRANSFER_HLG -> "HLG"
                        codecs.contains("hvc1.2.4") ||
                                codecs.contains("hev1.2.4") ||
                                codecs.contains("vp09.02") -> "HDR10"
                        else -> null
                    }
                    Log.d("VideoPlayerViewModel", "VIDEO TRACK --> resolution: $resolution,frameRate: $frameRate, hdrFormat: $hdrFormat")
                }

                // 2. Extract Audio Format & Channels
                if (group.type == androidx.media3.common.C.TRACK_TYPE_AUDIO) {
                    val mimeType = format.sampleMimeType ?: ""
                    val channels = format.channelCount
                    val isAtmos = mimeType.contains("eac3-joc") ||
                            format.label?.contains("Atmos", ignoreCase = true) == true ||
                            format.id?.contains("Atmos", ignoreCase = true) == true

                    audioFormat = when {
                        isAtmos -> "Dolby Atmos"
                        mimeType.contains("audio/eac3-joc") -> "Dolby Atmos"
                        mimeType.contains("audio/eac3") -> "Dolby Digital+"
                        mimeType.contains("audio/ac3") -> "Dolby Digital"
                        mimeType.contains("audio/vnd.dts") -> "DTS"
                        mimeType.contains("audio/vndet.dts.hd") -> "DTS-HD"
                        mimeType.contains("audio/true-hd") -> "TrueHD"
                        mimeType.contains("audio/mpeg") -> "MP3"
                        mimeType.contains("audio/flac") -> "FLAC"
                        mimeType.contains("audio/opus") -> "OPUS"
                        mimeType.contains("audio/ogg") || mimeType.contains("audio/vorbis") -> "OGG"
                        mimeType.contains("audio/wav") || mimeType.contains("audio/x-wav") -> "WAV"
                        else -> "AAC"
                    }

                    bitrate = if (format.bitrate > 0) "${format.bitrate / 1000} KBPS" else null

                    audioChannels = when (channels) {
                        8 -> "7.1"
                        6 -> "5.1"
                        2 -> "STEREO"
                        1 -> "MONO"
                        else -> "${channels}ch"
                    }
                    Log.d("VideoPlayerViewModel", "AUDIO TRACK --> bitrate: $bitrate, audioFormat: $audioFormat, audioChannels: $audioChannels")
                }
            }
        }

        _uiState.update { currentState ->
            // If the player reports audio but no video tracks, force mediaType to MUSIC
            // to ensure the visualizer layout is used for local MP3s.
            val inferredMediaType = if (hasAudio && !hasVideo) MediaMetadata.MEDIA_TYPE_MUSIC else currentState.mediaType
            
            currentState.copy(
                resolution = resolution,
                audioFormat = audioFormat,
                bitrate = bitrate,
                audioChannels = audioChannels,
                frameRate = frameRate,
                hdrFormat = hdrFormat,
                mediaType = inferredMediaType
            )
        }
    }

    private fun startProgressPolling() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (true) {
                controller?.let { player ->
                    val pos = player.currentPosition
                    val dur = player.duration.coerceAtLeast(0L)
                    currentPosition = pos
                    duration = dur
                    _uiState.update { it.copy(
                        currentPosition = pos,
                        duration = dur
                    ) }
                }
                delay(1000)
            }
        }
    }

    private fun stopProgressPolling() {
        progressJob?.cancel()
    }

    fun playMediaItems(items: List<MediaItem>, startIndex: Int, posterUrl: String? = null, logoUrl: String? = null, thumbUrl: String? = null) {
        val item = items.getOrNull(startIndex) ?: return
        val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri
        val mId = item.mediaId
        
        val metadata = item.mediaMetadata
        val isMusic = metadata.mediaType == MediaMetadata.MEDIA_TYPE_MUSIC || 
                      mId.endsWith(".mp3", ignoreCase = true) ||
                      mId.endsWith(".flac", ignoreCase = true)
        
        val artwork = metadata.artworkUri ?: metadata.artworkData

        _uiState.update { it.copy(
            videoUri = uri?.toString() ?: mId,
            videoTitle = metadata.displayTitle?.toString() ?: metadata.title?.toString(),
            videoArtist = metadata.artist?.toString() ?: metadata.albumArtist?.toString(),
            videoAlbum = metadata.albumTitle?.toString(),
            artWork = artwork,
            poster = posterUrl ?: if (!isMusic) artwork else null,
            mediaType = if (isMusic) MediaMetadata.MEDIA_TYPE_MUSIC else (metadata.mediaType ?: MediaMetadata.MEDIA_TYPE_MIXED),
            isLoading = true,
            isFirstFrameRendered = false
        ) }

        updateDynamicTheme(logoUrl ?: thumbUrl ?: posterUrl ?: artwork)

        initializeController()
        val future = _controllerFuture ?: return
        future.addListener({
            try {
                val p = future.get()
                p.stop()
                p.setMediaItems(items, startIndex, 0L)
                p.prepare()
                p.playWhenReady = true
            } catch (e: Exception) {
                Log.e("VideoPlayerViewModel", "Error playing media items", e)
            }
        }, MoreExecutors.directExecutor())
    }

    fun setVideoUri(uri: String?, startPos: Long = 0L, title: String? = null, posterUrl: String? = null, logoUrl: String? = null, thumbUrl: String? = null, mediaIdFromIntent: String? = null) {
        val mediaIdRegex = Regex(".*/(?:Videos|Items|Download|File)/([^/?#]+)")
        mediaId = mediaIdFromIntent ?: uri?.let { mediaIdRegex.find(it)?.groupValues?.get(1) }
        
        _uiState.update { it.copy(
            videoUri = uri,
            videoTitle = title,
            videoArtist = null, // External intent typically only has title
            videoAlbum = null,
            poster = posterUrl,
            logo = logoUrl,
            thumbnail = thumbUrl,
            mediaType = MediaMetadata.MEDIA_TYPE_VIDEO, // External intents are assumed to be video
            isLoading = uri != null,
            isFirstFrameRendered = false
        ) }

        updateDynamicTheme(logoUrl ?: thumbUrl ?: posterUrl)

        initializeController()
        val future = _controllerFuture ?: return
        future.addListener({
            try {
                val p = future.get()
                setupPlayer(p, uri, startPos, title, logoUrl, thumbUrl, posterUrl)
            } catch (_: Exception) {
                // Failed to get controller
            }
        }, MoreExecutors.directExecutor())
    }

    private fun updateDynamicTheme(imageUrl: Any?) {
        if (imageUrl == null) {
            _uiState.update { it.copy(dynamicColorScheme = null) }
            return
        }
        viewModelScope.launch {
            try {
                val application = getApplication<Application>()
                val loader = ImageLoader(application)
                val request = ImageRequest.Builder(application)
                    .data(imageUrl)
                    .allowHardware(false)
                    .build()
                
                val result = loader.execute(request)
                if (result is SuccessResult) {
                    val bitmap = (result.drawable as? BitmapDrawable)?.bitmap
                    if (bitmap != null) {
                        val colorScheme = generateColorSchemeFromImage(bitmap)
                        _uiState.update { it.copy(dynamicColorScheme = colorScheme) }
                        Log.d("VideoPlayerViewModel", "Successfully applied theme from: $imageUrl")
                    }
                } else if (result is ErrorResult) {
                    val state = _uiState.value
                    val nextFallback = when (imageUrl) {
                        state.logo -> state.thumbnail ?: state.poster
                        state.thumbnail -> state.poster
                        else -> null
                    }
                    if (nextFallback != null && nextFallback != imageUrl) {
                        Log.d("VideoPlayerViewModel", "Failed $imageUrl, trying fallback: $nextFallback")
                        updateDynamicTheme(nextFallback)
                    } else {
                        Log.e("VideoPlayerViewModel", "All image fallbacks failed for theme generation")
                    }
                }
            } catch (e: Exception) {
                Log.e("VideoPlayerViewModel", "Error generating dynamic theme", e)
            }
        }
    }

    private fun setupPlayer(player: MediaController, uri: String?, startPos: Long, title: String?, logoUrl: String?, thumbUrl: String?, posterUrl: String?) {
        player.stop()
        player.clearMediaItems()

        uri?.let { videoUrl ->
            val extras = Bundle().apply {
                putString("poster_url", posterUrl)
                putString("logo_url", logoUrl)
                putString("thumb_url", thumbUrl)
            }

            val metadata = MediaMetadata.Builder()
                .setTitle(title ?: "Video")
                .setDisplayTitle(title ?: "Video")
                .setArtworkUri(thumbUrl?.toUri())
                .setExtras(extras)
                .setMediaType(MediaMetadata.MEDIA_TYPE_VIDEO)
                .build()

            val mediaItem = MediaItem.Builder()
                .setMediaId(mediaId ?: videoUrl)
                .setUri(videoUrl)
                .setMediaMetadata(metadata)
                .build()

            player.setMediaItem(mediaItem, startPos)
            player.prepare()
            player.playWhenReady = true
        }
    }

    fun setAudioTrack(group: Tracks.Group, trackIndex: Int) {
        val player = controller ?: return
        val parameters = player.trackSelectionParameters
            .buildUpon()
            .setOverrideForType(
                androidx.media3.common.TrackSelectionOverride(
                    group.mediaTrackGroup,
                    trackIndex
                )
            )
            .build()
        player.trackSelectionParameters = parameters
    }

    fun setSubtitleTrack(group: Tracks.Group?, trackIndex: Int) {
        val player = controller ?: return
        val builder = player.trackSelectionParameters.buildUpon()
        if (group == null) {
            builder.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(androidx.media3.common.C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(
                    androidx.media3.common.TrackSelectionOverride(
                        group.mediaTrackGroup,
                        trackIndex
                    )
                )
        }
        player.trackSelectionParameters = builder.build()
    }

    fun setHdrMode(mode: HdrMode) {
        viewModelScope.launch {
            settingsManager.saveHdrMode(mode)
            val args = Bundle().apply {
                putString("hdr_mode", mode.name)
            }
            val future = controller?.sendCustomCommand(SessionCommand("RECREATE_PLAYER", Bundle.EMPTY), args)
            future?.addListener({
                try {
                    if (future.get().resultCode == androidx.media3.session.SessionResult.RESULT_SUCCESS) {
                        _uiState.update { it.copy(hdrMode = mode) }
                    }
                } catch (_: Exception) {
                    // Failed to recreate player
                }
            }, MoreExecutors.directExecutor())
        }
    }

    fun setPlayerTheme(theme: String) {
        viewModelScope.launch {
            settingsManager.savePlayerTheme(theme)
            _uiState.update { it.copy(playerTheme = theme) }
        }
    }

    fun setSurfaceAlpha(alpha: Float) {
        viewModelScope.launch {
            settingsManager.saveSurfaceAlpha(alpha)
            _uiState.update { it.copy(surfaceAlpha = alpha) }
        }
    }

    fun launchBillingFlow(activity: android.app.Activity) {
        billingManager.launchPurchaseFlow(activity)
    }

    fun releasePlayer() {
        val player = _controller.value
        if (player != null) {
            currentPosition = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            
            // Only stop playback if it is a video. 
            // Music should continue playing in the background service.
            val mediaType = player.currentMediaItem?.mediaMetadata?.mediaType ?: _uiState.value.mediaType
            val isVideo = mediaType == MediaMetadata.MEDIA_TYPE_VIDEO
            if (isVideo) {
                player.stop()
                player.sendCustomCommand(SessionCommand("STOP_SERVICE", Bundle.EMPTY), Bundle.EMPTY)
                player.clearMediaItems()
            }
        }
        _controllerFuture?.let {
            MediaController.releaseFuture(it)
        }
        _controllerFuture = null
        _controller.value = null
        stopProgressPolling()
    }

    override fun onCleared() {
        super.onCleared()
        stopProgressPolling()
        releasePlayer()
    }
}
