package com.airscreen.tv.media.recorder

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import com.airscreen.tv.di.ApplicationScope
import com.airscreen.tv.domain.contract.RecordingsRepository
import com.airscreen.tv.domain.contract.StreamRecorderManager
import com.airscreen.tv.domain.model.AudioCodec
import com.airscreen.tv.domain.model.AudioStreamConfig
import com.airscreen.tv.domain.model.EncodedAudioFrame
import com.airscreen.tv.domain.model.EncodedVideoFrame
import com.airscreen.tv.domain.model.RecordingState
import com.airscreen.tv.domain.model.VideoCodec
import com.airscreen.tv.domain.model.VideoStreamConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@Singleton
class MediaMuxerStreamRecorder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val recordingsRepository: RecordingsRepository,
    @ApplicationScope private val scope: CoroutineScope
) : StreamRecorderManager {
    private val lock = Any()
    private val _state = MutableStateFlow<RecordingState>(RecordingState.Idle)
    override val state: StateFlow<RecordingState> = _state.asStateFlow()

    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var audioTrack = -1
    private var started = false
    private var currentSessionId: String? = null
    private var currentFile: File? = null
    private var startedAtEpochMs = 0L
    private var basePtsUs = 0L
    private var lastVideoPtsUs = -1L
    private var lastAudioPtsUs = -1L
    private var videoConfig: VideoStreamConfig? = null
    private var audioConfig: AudioStreamConfig? = null
    private var ticker: Job? = null
    private val videoConfigs = HashMap<String, VideoStreamConfig>()
    private val audioConfigs = HashMap<String, AudioStreamConfig>()

    override fun startRecording(sessionId: String) {
        synchronized(lock) {
            if (currentSessionId == sessionId && muxer != null) return
            stopLocked(keepCompletedState = false)
            val directory = outputDirectory()
            val file = nextFile(directory)
            try {
                val newMuxer = MediaMuxer(
                    file.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
                muxer = newMuxer
                currentSessionId = sessionId
                currentFile = file
                startedAtEpochMs = System.currentTimeMillis()
                videoTrack = -1
                audioTrack = -1
                started = false
                basePtsUs = 0L
                lastVideoPtsUs = -1L
                lastAudioPtsUs = -1L
                videoConfig = videoConfigs[sessionId]
                audioConfig = audioConfigs[sessionId]
                _state.value = RecordingState.Recording(sessionId, file.absolutePath, 0L, 0L)
                startTicker(sessionId, file)
            } catch (error: Throwable) {
                Log.e(TAG, "Unable to start MP4 muxer", error)
                muxer?.release()
                muxer = null
                currentSessionId = null
                currentFile = null
                _state.value = RecordingState.Error(error.message ?: "Unable to start recording")
            }
        }
    }

    override fun stopRecording() {
        synchronized(lock) {
            stopLocked(keepCompletedState = true)
        }
    }

    override suspend fun toggleRecording(sessionId: String) {
        val current = _state.value
        if (current is RecordingState.Recording && current.sessionId == sessionId) {
            stopRecording()
        } else {
            startRecording(sessionId)
        }
    }

    override fun onVideoConfig(sessionId: String, config: VideoStreamConfig) {
        synchronized(lock) {
            videoConfigs[sessionId] = config
            if (sessionId == currentSessionId) videoConfig = config
        }
    }

    override fun onVideoFrame(sessionId: String, frame: EncodedVideoFrame) {
        synchronized(lock) {
            if (sessionId != currentSessionId || muxer == null) return
            if (!started) {
                if (!frame.isKeyFrame) return
                if (!initializeTracksLocked()) return
                basePtsUs = frame.ptsUs
            }
            val pts = normalize(frame.ptsUs, isVideo = true) ?: return
            if (started) {
                val info = MediaCodec.BufferInfo().apply {
                    offset = 0
                    size = frame.bytes.size
                    presentationTimeUs = pts
                    flags = if (frame.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                }
                try {
                    muxer?.writeSampleData(
                        videoTrack,
                        ByteBuffer.wrap(frame.bytes),
                        info
                    )
                    byteSizeUpdateLocked()
                } catch (error: Throwable) {
                    failLocked(error)
                }
            }
        }
    }

    override fun onAudioConfig(sessionId: String, config: AudioStreamConfig) {
        synchronized(lock) {
            audioConfigs[sessionId] = config
            if (sessionId == currentSessionId) audioConfig = config
        }
    }

    override fun onAudioFrame(sessionId: String, frame: EncodedAudioFrame) {
        synchronized(lock) {
            if (sessionId != currentSessionId || muxer == null || !started) return
            if (audioTrack < 0) return
            val pts = normalize(frame.ptsUs, isVideo = false) ?: return
            val info = MediaCodec.BufferInfo().apply {
                offset = 0
                size = frame.bytes.size
                presentationTimeUs = pts
                flags = 0
            }
            try {
                muxer?.writeSampleData(audioTrack, ByteBuffer.wrap(frame.bytes), info)
                byteSizeUpdateLocked()
            } catch (error: Throwable) {
                failLocked(error)
            }
        }
    }

    private fun initializeTracksLocked(): Boolean {
        val activeMuxer = muxer ?: return false
        val config = videoConfig ?: videoConfigs[currentSessionId] ?: return false
        return try {
            val videoFormat = MediaFormat.createVideoFormat(
                if (config.codec == VideoCodec.HEVC) {
                    MediaFormat.MIMETYPE_VIDEO_HEVC
                } else {
                    MediaFormat.MIMETYPE_VIDEO_AVC
                },
                config.width,
                config.height
            )
            if (config.codec == VideoCodec.HEVC) {
                val vps = stripStartCode(config.vps ?: ByteArray(0))
                val sps = stripStartCode(config.sps)
                val pps = stripStartCode(config.pps)
                val csd = ByteArray(vps.size + sps.size + pps.size)
                var offset = 0
                System.arraycopy(vps, 0, csd, offset, vps.size)
                offset += vps.size
                System.arraycopy(sps, 0, csd, offset, sps.size)
                offset += sps.size
                System.arraycopy(pps, 0, csd, offset, pps.size)
                videoFormat.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
            } else {
                videoFormat.setByteBuffer("csd-0", ByteBuffer.wrap(stripStartCode(config.sps)))
                videoFormat.setByteBuffer("csd-1", ByteBuffer.wrap(stripStartCode(config.pps)))
            }
            videoTrack = activeMuxer.addTrack(videoFormat)

            val audio = audioConfig
            if (audio != null &&
                (audio.codec == AudioCodec.AAC_LC || audio.codec == AudioCodec.AAC_ELD)
            ) {
                val audioFormat = MediaFormat.createAudioFormat(
                    MediaFormat.MIMETYPE_AUDIO_AAC,
                    audio.sampleRateHz.coerceAtLeast(8000),
                    audio.channelCount.coerceIn(1, 2)
                )
                audioFormat.setByteBuffer("csd-0", ByteBuffer.wrap(byteArrayOf(0x12, 0x10)))
                audioTrack = activeMuxer.addTrack(audioFormat)
            }
            activeMuxer.start()
            started = true
            true
        } catch (error: Throwable) {
            failLocked(error)
            false
        }
    }

    private fun stripStartCode(data: ByteArray): ByteArray {
        return when {
            data.size >= 4 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
                data[2] == 0.toByte() && data[3] == 1.toByte() -> data.copyOfRange(4, data.size)
            data.size >= 3 && data[0] == 0.toByte() && data[1] == 0.toByte() &&
                data[2] == 1.toByte() -> data.copyOfRange(3, data.size)
            else -> data
        }
    }

    private fun normalize(ptsUs: Long, isVideo: Boolean): Long? {
        var normalized = ptsUs - basePtsUs
        if (normalized < 0L) return null
        val last = if (isVideo) lastVideoPtsUs else lastAudioPtsUs
        if (normalized <= last) normalized = last + 1L
        if (isVideo) lastVideoPtsUs = normalized else lastAudioPtsUs = normalized
        return normalized
    }

    private fun startTicker(sessionId: String, file: File) {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                delay(500L)
                synchronized(lock) {
                    if (currentSessionId != sessionId || currentFile == null) break
                    _state.value = RecordingState.Recording(
                        sessionId = sessionId,
                        filePath = file.absolutePath,
                        elapsedMs = (System.currentTimeMillis() - startedAtEpochMs)
                            .coerceAtLeast(0L),
                        bytesWritten = file.length()
                    )
                }
            }
        }
    }

    private fun stopLocked(keepCompletedState: Boolean) {
        ticker?.cancel()
        ticker = null
        val hadStarted = started
        val file = currentFile
        val duration = if (hadStarted && basePtsUs >= 0L && lastVideoPtsUs >= 0L) {
            (lastVideoPtsUs - 0L) / 1000L
        } else {
            0L
        }
        try {
            if (muxer != null && hadStarted) muxer?.stop()
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to stop muxer cleanly", error)
        }
        try {
            muxer?.release()
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to release muxer", error)
        }
        muxer = null
        started = false
        currentSessionId = null
        currentFile = null
        videoTrack = -1
        audioTrack = -1
        lastVideoPtsUs = -1L
        lastAudioPtsUs = -1L
        if (file != null && file.exists()) {
            if (hadStarted && file.length() > 0L) {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(file.absolutePath),
                    arrayOf("video/mp4"),
                    null
                )
                if (keepCompletedState) {
                    _state.value = RecordingState.Completed(
                        fileUri = file.toURI().toString(),
                        durationMs = duration
                    )
                    scope.launch { recordingsRepository.refresh() }
                }
            } else {
                file.delete()
                if (keepCompletedState) _state.value = RecordingState.Idle
            }
        } else if (keepCompletedState) {
            _state.value = RecordingState.Idle
        }
    }

    private fun byteSizeUpdateLocked() {
        val file = currentFile ?: return
        val current = _state.value
        if (current is RecordingState.Recording) {
            _state.value = current.copy(
                elapsedMs = (System.currentTimeMillis() - startedAtEpochMs).coerceAtLeast(0L),
                bytesWritten = file.length()
            )
        }
    }

    private fun failLocked(error: Throwable) {
        Log.e(TAG, "MediaMuxer write failed", error)
        val file = currentFile
        try {
            muxer?.release()
        } catch (_: Throwable) {
        }
        muxer = null
        started = false
        currentSessionId = null
        currentFile = null
        file?.delete()
        _state.value = RecordingState.Error(error.message ?: "Muxer failed")
    }

    private fun outputDirectory(): File {
        val movies = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES) ?: context.filesDir
        return File(movies, "AirScreenRecordings").apply { mkdirs() }
    }

    private fun nextFile(directory: File): File {
        val formatter = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        var candidate = File(directory, "AS_" + formatter.format(Date()) + ".mp4")
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(directory, "AS_" + formatter.format(Date()) + "_" + suffix + ".mp4")
            suffix++
        }
        return candidate
    }

    private companion object {
        const val TAG = "StreamRecorder"
    }
}