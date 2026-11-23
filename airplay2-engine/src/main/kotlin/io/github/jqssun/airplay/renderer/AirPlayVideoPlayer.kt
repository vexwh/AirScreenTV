package io.github.jqssun.airplay.renderer

import io.github.jqssun.airplay.Prefs

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.VideoSize
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector

// rate is 0 while buffering; speed is the configured rate regardless of pause state
// native reads the effective rate, overlay reads playWhenReady + speed + skipSilence
data class PlaybackSnapshot(
    val position: Float,
    val duration: Float,
    val rate: Float,
    val ready: Boolean,
    val playWhenReady: Boolean,
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
    val buffering: Boolean = false,
)

// exoplayer calls stay on the main thread; native only reads the onPlaybackInfo snapshot
class AirPlayVideoPlayer(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var preferSoftware = false
    private val renderersFactory = DefaultRenderersFactory(context).setMediaCodecSelector(
        object : MediaCodecSelector {
            override fun getDecoderInfos(
                mimeType: String,
                requiresSecureDecoder: Boolean,
                requiresTunnelingDecoder: Boolean
            ): List<MediaCodecInfo> {
                if (mimeType == MimeTypes.VIDEO_VP9) return emptyList()
                val all = MediaCodecSelector.DEFAULT.getDecoderInfos(
                    mimeType,
                    requiresSecureDecoder,
                    requiresTunnelingDecoder
                )
                val hardwareEnabled = context.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
                    .getBoolean(Prefs.HARDWARE_DECODING.key, true)
                if ((preferSoftware || !hardwareEnabled) && mimeType == MimeTypes.VIDEO_H264) {
                    val software = all.filter {
                        it.name.startsWith("c2.android.") || it.name.startsWith("OMX.google.")
                    }
                    if (software.isNotEmpty()) return software
                }
                return all
            }
        }
    )
    private var player: ExoPlayer? = null
    private var lastLocation: String? = null
    private var liveRetries = 0
    private var pendingSurface: Surface? = null
    private var pendingPlay: Pair<String, Long>? = null
    private var clientGain = 1f
    @Volatile private var pendingStartSeekMs = 0L

    var onPlaybackInfo: ((PlaybackSnapshot) -> Unit)? = null
    var onVideoSize: ((width: Int, height: Int, aspect: Float) -> Unit)? = null
    var onTitle: ((String?) -> Unit)? = null
    var onEnded: (() -> Unit)? = null
    var onNaturalEnd: (() -> Unit)? = null

    private val _reportTick = object : Runnable {
        override fun run() {
            _reportPlaybackInfo()
            mainHandler.postDelayed(this, REPORT_INTERVAL_MS)
        }
    }

    private val _listener = object : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.w(TAG, "playback error code=${error.errorCodeName} msg=${error.message}", error)
            val p = player
            val location = lastLocation
            // a live stream can fail on a segment right at the live edge (the newest chunk may
            // not be published yet, especially right after a seek to LIVE). rejoining is better
            // than dropping the session, which is what the sender sees as "playback stopped"
            if (p?.isCurrentMediaItemLive == true && location != null && liveRetries < MAX_LIVE_RETRIES) {
                liveRetries++
                Log.i(TAG, "live playback failed; rejoining at the live edge (attempt $liveRetries)")
                mainHandler.postDelayed({ startPlayback(location, 0f) }, LIVE_RETRY_DELAY_MS)
                return
            }
            onEnded?.invoke()
        }
        override fun onPlaybackStateChanged(state: Int) {
            // diagnostics: black screen + sender spinner means the timeline never became ready
            Log.i(TAG, "state=${stateName(state)} live=${player?.isCurrentMediaItemLive} " +
                "position=${player?.currentPosition} duration=${player?.duration} " +
                "buffered=${player?.bufferedPosition}")
            if (state == Player.STATE_READY) {
                liveRetries = 0
            }
            if (state == Player.STATE_ENDED) {
                val p = player
                // a live window "ends" when the last chunk we have has played and the refreshed
                // playlist has not landed yet. pausing here is what made a seek near LIVE look
                // like an automatic pause, so rejoin at the edge instead of stopping
                if (p != null && p.isCurrentMediaItemLive && liveRetries < MAX_LIVE_RETRIES) {
                    liveRetries++
                    Log.i(TAG, "live window drained; rejoining at the live edge (attempt $liveRetries)")
                    mainHandler.postDelayed({
                        p.seekToDefaultPosition()
                        p.playWhenReady = true
                    }, LIVE_RETRY_DELAY_MS)
                    return
                }
                player?.pause()
                // the service sets either onNaturalEnd (newer) or onEnded (original); with the
                // original wiring a natural end must still notify so playback state is cleaned up
                onNaturalEnd?.invoke() ?: onEnded?.invoke()
            }
        }
        override fun onIsLoadingChanged(isLoading: Boolean) {
            Log.i(TAG, "loading=$isLoading state=${player?.playbackState}")
        }
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // senders express the start position in their own live timeline (e.g. 3818s on a
            // 3600s DVR window). once the timeline is known to be live, that absolute seek is
            // meaningless and leaves the player buffering past the end of the window, so snap
            // to the live edge instead.
            val p = player
            if (p != null && p.isCurrentMediaItemLive && pendingStartSeekMs >= 0) {
                pendingStartSeekMs = -1
                // live content has no meaningful absolute start position: a restarted stream
                // (quality switch, re-negotiation) must begin at the live edge. stop a little
                // short of it so the newest chunk is not requested before it exists
                val duration = p.duration
                if (duration != C.TIME_UNSET && duration > LIVE_EDGE_MARGIN_MS) {
                    Log.i(TAG, "live timeline: seeking near the live edge (${(duration - LIVE_EDGE_MARGIN_MS) / 1000}s)")
                    p.seekTo(duration - LIVE_EDGE_MARGIN_MS)
                } else {
                    Log.i(TAG, "live timeline: seeking to live edge")
                    p.seekToDefaultPosition()
                }
            }
            // duration is usually established here (esp. hls): report so the held /play releases
            _reportPlaybackInfo()
        }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            onTitle?.invoke(mediaMetadata.title?.toString())
        }
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            Log.i(TAG, "videoSize ${videoSize.width}x${videoSize.height} par=${videoSize.pixelWidthHeightRatio}")
            if (videoSize.height == 0) return
            val width = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt()
            onVideoSize?.invoke(width, videoSize.height, width.toFloat() / videoSize.height)
        }
    }

    fun play(location: String, startPositionSeconds: Float) {
        _startPending?.let { mainHandler.removeCallbacks(it) }
        val pending = Runnable { startPlay(location, startPositionSeconds) }
        _startPending = pending
        // the proxy fetches the renditions over the AirPlay reverse channel right after /play.
        // giving it a moment means the audio rendition is already stored when the player asks for
        // the master, so the master can advertise audio instead of being served video-only
        val delay = if (location.startsWith(PROXY_PREFIX)) PROXY_START_DELAY_MS else 0L
        mainHandler.postDelayed(pending, delay)
    }

    private var _startPending: Runnable? = null

    private fun startPlay(location: String, startPositionSeconds: Float) {
        // recycling must not report the stopped sentinel: senders poll right after /play
        _stopInternal(reportStopped = false)
        val startSeconds = sanitizeStartPosition(startPositionSeconds)
        if (pendingSurface == null) {
            // HLS video cannot initialize a MediaCodec without a valid surface
            pendingPlay = location to (startSeconds * 1000).toLong()
            onPlaybackInfo?.invoke(PlaybackSnapshot(startSeconds, 0f, 0f, false, true))
            return
        }
        startPlayback(location, startSeconds)
    }

    // senders address live content with an "unknown/live edge" position that reaches us as a
    // huge float (INT64_MAX microseconds ~= 9.2e12 s). seeking there makes exoplayer jump to the
    // end of a live window, so a live stream ends seconds after it starts and the sender restarts it
    private fun sanitizeStartPosition(seconds: Float): Float {
        if (seconds.isNaN() || seconds.isInfinite() || seconds < 0f) return 0f
        return if (seconds > MAX_START_POSITION_SECONDS) 0f else seconds
    }

    private fun startPlayback(location: String, startPositionSeconds: Float) {
        preferSoftware = false
        Log.i(TAG, "startPlayback url=$location start=${startPositionSeconds}s " +
            "surface=${pendingSurface} valid=${pendingSurface?.isValid} software=$preferSoftware")
        val mediaSourceFactory = DefaultMediaSourceFactory(
            DefaultHttpDataSource.Factory().setAllowCrossProtocolRedirects(true)
        ).setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(10))
        val p = ExoPlayer.Builder(context)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().also {
            it.addListener(_listener)
            // diagnostics: EventLogger reports every load error with its HTTP status, plus
            // decoder init/format details, which is what a black screen needs to be explained
            it.addAnalyticsListener(androidx.media3.exoplayer.util.EventLogger())
            pendingSurface?.let { s -> it.setVideoSurface(s) }
            it.volume = clientGain
        }
        player = p
        lastLocation = location
        pendingStartSeekMs = (startPositionSeconds * 1000).toLong()
        p.setMediaItem(MediaItem.fromUri(location), pendingStartSeekMs)
        p.playWhenReady = true
        p.prepare()
        onPlaybackInfo?.invoke(PlaybackSnapshot(startPositionSeconds, 0f, 0f, false, true))
        mainHandler.postDelayed(_reportTick, REPORT_INTERVAL_MS)
    }

    fun scrub(positionSeconds: Float) = mainHandler.post {
        val p = player ?: return@post
        // senders scrub in their own (whole DVR window) timeline. for live content stay a little
        // inside the window: the very last chunk may not be published yet, and a seek onto it
        // makes the load fail exactly when the user taps LIVE
        val duration = p.duration
        val target = (positionSeconds * 1000).toLong()
        val invalid = positionSeconds.isNaN() || positionSeconds.isInfinite() ||
            positionSeconds < 0f || positionSeconds > MAX_START_POSITION_SECONDS
        if (p.isCurrentMediaItemLive) {
            val hi = if (duration != C.TIME_UNSET) (duration - LIVE_EDGE_MARGIN_MS).coerceAtLeast(0L) else null
            var t = if (invalid) (hi ?: p.currentPosition) else target
            if (hi != null) t = t.coerceIn(0L, hi)
            Log.i(TAG, "scrub to $positionSeconds s -> live window target ${t / 1000}s")
            p.seekTo(t)
        } else if (invalid) {
            Log.i(TAG, "scrub to $positionSeconds s is invalid; ignoring")
        } else {
            p.seekTo(target)
        }
    }

    // coalesces the seek spam from drag-seeking
    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    fun setScrubbing(enabled: Boolean) = mainHandler.post {
        player?.isScrubbingModeEnabled = enabled
    }

    fun setRate(rate: Float) = mainHandler.post {
        val p = player ?: return@post
        if (rate <= 0f) {
            p.playWhenReady = false
        } else {
            p.playbackParameters = PlaybackParameters(rate.coerceAtLeast(0.1f))
            p.playWhenReady = true
        }
    }

    // local-only: the sender self-syncs from its next /playback-info poll
    fun setPlaying(playing: Boolean) = mainHandler.post {
        player?.playWhenReady = playing
    }

    // local speed toggle: unlike setRate it must not resume a paused player
    fun setSpeed(speed: Float) = mainHandler.post {
        player?.playbackParameters = PlaybackParameters(speed.coerceAtLeast(0.1f))
    }

    // linear, unity unless sender volume isn't synced to receiver
    fun setClientGain(gain: Float) = mainHandler.post {
        clientGain = gain
        player?.volume = gain
    }

    fun setSkipSilence(enabled: Boolean) = mainHandler.post {
        player?.skipSilenceEnabled = enabled
    }

    fun seekBy(deltaMs: Long) = mainHandler.post {
        val p = player ?: return@post
        var target = (p.currentPosition + deltaMs).coerceAtLeast(0)
        val duration = p.duration
        if (duration != C.TIME_UNSET) {
            target = target.coerceAtMost(duration)
        }
        p.seekTo(target)
    }

    fun setSurface(surface: Surface) = mainHandler.post {
        Log.i(TAG, "setSurface ${surface} valid=${surface.isValid}")
        pendingSurface = surface
        player?.setVideoSurface(surface)
        val pending = pendingPlay
        if (pending != null && player == null) {
            pendingPlay = null
            startPlayback(pending.first, pending.second / 1000f)
        }
    }

    // no-op if a newer surface already replaced this one
    fun clearSurface(surface: Surface) = mainHandler.post {
        if (pendingSurface !== surface) return@post
        pendingSurface = null
        player?.setVideoSurface(null)
    }


    fun stop() = mainHandler.post { _stopInternal(reportStopped = true) }

    private fun _stopInternal(reportStopped: Boolean) {
        _startPending?.let { mainHandler.removeCallbacks(it) }
        _startPending = null
        mainHandler.removeCallbacks(_reportTick)
        player?.let {
            it.removeListener(_listener)
            it.release()
        }
        player = null
        pendingPlay = null
        // duration=-1 is the "video finished" sentinel for the playback-info handler
        if (reportStopped) {
            onPlaybackInfo?.invoke(PlaybackSnapshot(0f, -1f, 0f, false, false))
        }
    }

    private fun _reportPlaybackInfo() {
        val p = player ?: return
        val durationMs = p.duration
        val position = p.currentPosition / 1000f
        // 0 = live/unknown (TIME_UNSET); a real value only exists for vod
        val duration = if (durationMs == C.TIME_UNSET) 0f else durationMs / 1000f
        // rate drives the sender's play/pause UI. reporting 0 while buffering (which every seek
        // triggers for a second or two) made the phone switch to "paused" after each seek, so the
        // user had to press play again. only an explicit pause should report 0.
        val rate = if (p.playWhenReady) p.playbackParameters.speed else 0f
        // readyToPlay = the timeline is established, NOT exoplayer's buffering state: for vod that means the duration is known; for live there is no duration so being playable is enough. the sender holds its timeline (and /play) until this, so it must not go true early
        val ready = if (p.isCurrentMediaItemLive) p.playbackState == Player.STATE_READY
                    else durationMs != C.TIME_UNSET
        onPlaybackInfo?.invoke(
            PlaybackSnapshot(
                position = position,
                duration = duration,
                rate = rate,
                ready = ready,
                playWhenReady = p.playWhenReady,
                speed = p.playbackParameters.speed,
                skipSilence = p.skipSilenceEnabled,
                buffering = p.playbackState == Player.STATE_BUFFERING,
            )
        )
    }

    private fun stateName(state: Int): String = when (state) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "?$state"
    }

    companion object {
        private const val TAG = "AirPlayVideoPlayer"
        private const val REPORT_INTERVAL_MS = 250L
        // url of the in-app HLS proxy; only that path needs the audio-rendition head start
        private const val PROXY_PREFIX = "http://localhost:"
        private const val PROXY_START_DELAY_MS = 500L
        // keep a small distance from the very edge of the live window
        // just enough to avoid requesting the newest chunk before it exists; 10 s was visible
        // on the phone as "-0:10" after tapping LIVE
        private const val LIVE_EDGE_MARGIN_MS = 1_000L
        private const val MAX_LIVE_RETRIES = 3
        private const val LIVE_RETRY_DELAY_MS = 800L
        // anything beyond a day is a live-edge sentinel, not a real resume position
        private const val MAX_START_POSITION_SECONDS = 24f * 60f * 60f
    }
}