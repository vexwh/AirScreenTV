package com.airscreen.tv.media.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.airscreen.tv.domain.contract.CastUrlController
import com.airscreen.tv.domain.model.CastPlaybackState
import com.airscreen.tv.domain.model.PlaybackProgress
import com.airscreen.tv.domain.model.SurfaceSlotId
import com.airscreen.tv.media.decoder.SurfaceLease
import com.airscreen.tv.media.decoder.SurfaceSlotRegistry
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
@Singleton
class UnifiedExoPlayerManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val surfaceSlotRegistry: SurfaceSlotRegistry
) : CastUrlController {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val _playbackState = MutableStateFlow<CastPlaybackState>(CastPlaybackState.Idle)
    private val _playbackProgress = MutableStateFlow(PlaybackProgress())
    override val playbackState: StateFlow<CastPlaybackState> = _playbackState.asStateFlow()
    override val playbackProgress: StateFlow<PlaybackProgress> = _playbackProgress.asStateFlow()

    private var player: ExoPlayer? = null

    val exoPlayer: ExoPlayer? get() = player
    private var surfaceLease: SurfaceLease? = null
    private var tickerRunning = false
    private var volumePercent = 100
    private var muted = false
    private var lastAudiblePercent = 100
    @Volatile private var suppressPlayerEvents = false

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (suppressPlayerEvents) return
            if (_playbackState.value is CastPlaybackState.PhotoDisplayed) return
            val state = when (playbackState) {
                Player.STATE_IDLE -> CastPlaybackState.Idle
                Player.STATE_BUFFERING -> CastPlaybackState.Loading
                Player.STATE_READY -> if (player?.playWhenReady == true) {
                    CastPlaybackState.Playing
                } else {
                    CastPlaybackState.Paused
                }
                Player.STATE_ENDED -> CastPlaybackState.Ended
                else -> CastPlaybackState.Idle
            }
            _playbackState.value = state
            updateProgress()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (suppressPlayerEvents) return
            if (_playbackState.value is CastPlaybackState.PhotoDisplayed) return
            _playbackState.value = if (isPlaying) CastPlaybackState.Playing else CastPlaybackState.Paused
            updateProgress()
        }

        override fun onPlayerError(error: PlaybackException) {
            if (suppressPlayerEvents) return
            _playbackState.value = CastPlaybackState.Error(
                error.errorCodeName + ": " + (error.message ?: "playback error")
            )
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (player == null) {
                tickerRunning = false
                return
            }
            updateProgress()
            mainHandler.postDelayed(this, POSITION_TICK_MS)
        }
    }

    override suspend fun loadMedia(
        url: String,
        title: String?,
        mimeType: String?,
        startPositionMs: Long,
        autoPlay: Boolean
    ) = withContext(Dispatchers.Main.immediate) {
        try {
            suppressPlayerEvents = true
            releaseSurface()
            val exoPlayer = ensurePlayer()
            _playbackState.value = CastPlaybackState.Loading
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            val resolvedMime = mimeType ?: inferMimeType(url)
            if (isVideo(url, resolvedMime)) {
                val lease = surfaceSlotRegistry.acquire(SURFACE_SLOT, SURFACE_WIDTH, SURFACE_HEIGHT)
                surfaceLease = lease
                exoPlayer.setVideoSurface(lease.surface)
            } else {
                exoPlayer.setVideoSurface(null)
            }
            val item = MediaItem.Builder()
                .setUri(url)
                .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
                .apply { if (resolvedMime != null) setMimeType(resolvedMime) }
                .build()
            exoPlayer.setMediaItem(item, startPositionMs.coerceAtLeast(0L))
            suppressPlayerEvents = false
            exoPlayer.prepare()
            exoPlayer.playWhenReady = autoPlay
            if (autoPlay) exoPlayer.play()
            applyVolumeToPlayer()
            startTicker()
            updateProgress()
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to load media", error)
            releaseSurface()
            _playbackState.value = CastPlaybackState.Error(
                error.message ?: error.javaClass.simpleName
            )
        }
    }

    override suspend fun displayPhoto(jpegOrPngBytes: ByteArray) {
        val bitmap = withContext(Dispatchers.Default) {
            BitmapFactory.decodeByteArray(jpegOrPngBytes, 0, jpegOrPngBytes.size)
        }
        if (bitmap == null) {
            _playbackState.value = CastPlaybackState.Error("Unable to decode photo")
            return
        }
        withContext(Dispatchers.Main.immediate) {
            suppressPlayerEvents = true
            player?.pause()
            releaseSurface()
            stopTicker()
            _playbackState.value = CastPlaybackState.PhotoDisplayed(bitmap)
            _playbackProgress.update {
                it.copy(positionMs = 0L, durationMs = 0L)
            }
        }
    }

    override suspend fun play() = withContext(Dispatchers.Main.immediate) {
        val exoPlayer = player ?: return@withContext
        if (_playbackState.value is CastPlaybackState.PhotoDisplayed) return@withContext
        if (exoPlayer.playbackState == Player.STATE_ENDED) exoPlayer.seekTo(0L)
        exoPlayer.play()
    }

    override suspend fun pause() {
        withContext(Dispatchers.Main.immediate) {
            player?.pause()
        }
    }

    override suspend fun stop() = withContext(Dispatchers.Main.immediate) {
        suppressPlayerEvents = true
        stopTicker()
        player?.stop()
        player?.clearMediaItems()
        releaseSurface()
        _playbackState.value = CastPlaybackState.Idle
        _playbackProgress.value = PlaybackProgress(
            isMuted = muted,
            volumePercent = if (muted) 0 else volumePercent
        )
    }

    override suspend fun seekTo(positionMs: Long) = withContext(Dispatchers.Main.immediate) {
        player?.seekTo(positionMs.coerceAtLeast(0L))
        updateProgress()
    }

    override fun systemVolumePercent(): Int = try {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) 0 else ((audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max) * 100f).roundToInt()
    } catch (error: Throwable) {
        volumePercent
    }

    override suspend fun setVolume(percent: Int) = withContext(Dispatchers.Main.immediate) {
        val clamped = percent.coerceIn(0, 100)
        volumePercent = clamped
        if (clamped > 0) lastAudiblePercent = clamped
        muted = false
        applyVolumeToPlayer()
        applySystemVolume(clamped)
        updateProgress()
    }

    override suspend fun setMuted(muted: Boolean) = withContext(Dispatchers.Main.immediate) {
        this@UnifiedExoPlayerManager.muted = muted
        if (!muted) {
            volumePercent = lastAudiblePercent
        }
        applyVolumeToPlayer()
        applySystemVolume(if (muted) 0 else volumePercent)
        updateProgress()
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val created = ExoPlayer.Builder(context)
            .setRenderersFactory(DefaultRenderersFactory(context))
            .setMediaSourceFactory(DefaultMediaSourceFactory(context))
            .build()
        created.addListener(playerListener)
        player = created
        return created
    }

    private fun applyVolumeToPlayer() {
        player?.volume = if (muted) 0f else volumePercent / 100f
    }

    private fun applySystemVolume(percent: Int) {
        try {
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val index = ((percent.coerceIn(0, 100) / 100f) * max).roundToInt()
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, index.coerceIn(0, max), 0)
        } catch (error: Throwable) {
            Log.d(TAG, "Unable to set system music volume", error)
        }
    }

    private fun updateProgress() {
        val exoPlayer = player
        val duration = exoPlayer?.duration ?: C.TIME_UNSET
        val safeDuration = if (duration == C.TIME_UNSET || duration < 0L) 0L else duration
        _playbackProgress.update {
            it.copy(
                positionMs = exoPlayer?.currentPosition ?: it.positionMs,
                durationMs = safeDuration,
                isMuted = muted,
                volumePercent = if (muted) 0 else volumePercent
            )
        }
    }

    private fun startTicker() {
        if (tickerRunning) return
        tickerRunning = true
        mainHandler.postDelayed(ticker, POSITION_TICK_MS)
    }

    private fun stopTicker() {
        tickerRunning = false
        mainHandler.removeCallbacks(ticker)
    }

    private fun releaseSurface() {
        try {
            player?.setVideoSurface(null)
        } catch (_: Throwable) {
        }
        try {
            surfaceLease?.close()
        } catch (_: Throwable) {
        }
        surfaceLease = null
    }

    private fun isVideo(url: String, mimeType: String?): Boolean {
        val type = mimeType?.lowercase()
        if (type != null) {
            return type.startsWith("video/") || type.contains("mpegurl") ||
                type.contains("dash+xml")
        }
        val path = url.substringBefore('?').lowercase()
        return path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mkv") ||
            path.endsWith(".webm") || path.endsWith(".m3u8") || path.endsWith(".mpd")
    }

    private fun inferMimeType(url: String): String? {
        val path = url.substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") -> "application/x-mpegURL"
            path.endsWith(".mpd") -> "application/dash+xml"
            path.endsWith(".mp4") || path.endsWith(".m4v") -> "video/mp4"
            path.endsWith(".mkv") -> "video/x-matroska"
            path.endsWith(".webm") -> "video/webm"
            path.endsWith(".mp3") -> "audio/mpeg"
            path.endsWith(".m4a") -> "audio/mp4"
            path.endsWith(".aac") -> "audio/aac"
            path.endsWith(".flac") -> "audio/flac"
            else -> null
        }
    }

    private companion object {
        const val TAG = "UnifiedExoPlayer"
        const val POSITION_TICK_MS = 500L
        const val SURFACE_WIDTH = 1920
        const val SURFACE_HEIGHT = 1080
        val SURFACE_SLOT = SurfaceSlotId(0)
    }
}