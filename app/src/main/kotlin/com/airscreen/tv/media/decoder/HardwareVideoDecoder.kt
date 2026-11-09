package com.airscreen.tv.media.decoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.graphics.SurfaceTexture
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.view.Surface
import android.util.Log
import com.airscreen.tv.domain.contract.MirroringVideoSink
import com.airscreen.tv.domain.contract.SessionManager
import com.airscreen.tv.domain.contract.StreamRecorderManager
import com.airscreen.tv.domain.model.EncodedVideoFrame
import com.airscreen.tv.domain.model.FrameDisposition
import com.airscreen.tv.domain.model.SessionId
import com.airscreen.tv.domain.model.SessionMetrics
import com.airscreen.tv.domain.model.SurfaceSlotId
import com.airscreen.tv.domain.model.VideoCodec
import com.airscreen.tv.domain.model.VideoStreamConfig
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

class HardwareVideoDecoder(
    private val sessionId: SessionId,
    private val surfaceSlot: SurfaceSlotId,
    private val surfaceRegistry: SurfaceSlotRegistry,
    private val sessionManager: SessionManager,
    private val streamRecorderManager: StreamRecorderManager,
    private val hardwarePreferred: Boolean
) : MirroringVideoSink {

    private val inputQueue = ArrayBlockingQueue<EncodedVideoFrame>(8)
    @Volatile private var running = false
    private var worker: Thread? = null
    private var codec: MediaCodec? = null
    private var lease: SurfaceLease? = null
    @Volatile var decoderName: String = ""
        private set
    val isHardwareDecoder: Boolean get() {
        val name = decoderName.lowercase(java.util.Locale.US)
        return name.isNotBlank() && !name.startsWith("omx.google.") &&
            !name.startsWith("c2.android.")
    }
    private var placeholderTexture: SurfaceTexture? = null
    private var placeholderSurface: Surface? = null
    private var requestedSurface: Surface? = null
    private var hasSurfaceBinding = false
    private val surfaceLock = Any()

    override fun onCodecConfig(config: VideoStreamConfig) {
        Log.i(TAG, "Codec config " + config.codec + " " + config.width + "x" + config.height)
        streamRecorderManager.onVideoConfig(sessionId.value, config)
        stopPipeline()
        inputQueue.clear()
        running = true
        worker = Thread(
            { decodeLoop(config) },
            "AirPlayVideo-" + sessionId.value
        ).also { thread ->
            thread.isDaemon = true
            thread.start()
        }
    }

    private var receivedFrames = 0L

    override fun onVideoFrame(frame: EncodedVideoFrame): FrameDisposition {
        receivedFrames++
        if (receivedFrames <= 5 || receivedFrames % 120 == 0L) {
            Log.i(TAG, "Video frame " + receivedFrames + " size=" + frame.bytes.size)
        }
        if (!running) return FrameDisposition.REJECTED
        streamRecorderManager.onVideoFrame(sessionId.value, frame)
        return if (inputQueue.offer(frame)) {
            FrameDisposition.ACCEPTED
        } else {
            FrameDisposition.DROPPED_BACKPRESSURE
        }
    }

    override fun onFlush() {
        inputQueue.clear()
        try {
            codec?.flush()
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to flush decoder", error)
        }
    }

    override fun onStreamEnd() {
        stopPipeline()
    }

    fun setOutputSurface(surface: Surface?) {
        Log.i(TAG, "Set output surface " + (surface != null))
        synchronized(surfaceLock) {
            requestedSurface = surface
            hasSurfaceBinding = true
            applyOutputSurfaceLocked(surface)
        }
    }

    private fun applyOutputSurfaceLocked(surface: Surface?) {
        val decoder = codec ?: return
        try {
            if (surface == null) {
                val texture = SurfaceTexture(0)
                texture.setDefaultBufferSize(1920, 1080)
                val placeholder = Surface(texture)
                decoder.setOutputSurface(placeholder)
                releasePlaceholderLocked()
                placeholderTexture = texture
                placeholderSurface = placeholder
            } else {
                decoder.setOutputSurface(surface)
                releasePlaceholderLocked()
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to rebind decoder output surface", error)
        }
    }

    private fun releasePlaceholderLocked() {
        try {
            placeholderSurface?.release()
        } catch (_: Throwable) {
        }
        try {
            placeholderTexture?.release()
        } catch (_: Throwable) {
        }
        placeholderSurface = null
        placeholderTexture = null
    }

    private fun decodeLoop(config: VideoStreamConfig) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY)
        try {
            val acquiredLease = surfaceRegistry.acquire(
                surfaceSlot,
                config.width,
                config.height
            )
            lease = acquiredLease
            val selected = createDecoder(config, acquiredLease.surface, hardwarePreferred)
            Log.i(TAG, "Using decoder " + selected.name + " for " + config.width + "x" + config.height)
            codec = selected
            decoderName = selected.name
            selected.start()
            synchronized(surfaceLock) {
                if (hasSurfaceBinding) applyOutputSurfaceLocked(requestedSurface)
            }
            drainLoop(selected, config)
        } catch (error: Throwable) {
            if (!running) return
            Log.e(TAG, "Hardware decoder failed; attempting software fallback", error)
            try {
                releaseCodec()
                val fallbackLease = lease ?: surfaceRegistry.acquire(
                    surfaceSlot,
                    config.width,
                    config.height
                ).also { lease = it }
                val fallback = createDecoder(config, fallbackLease.surface, false)
                codec = fallback
                decoderName = fallback.name
                fallback.start()
                drainLoop(fallback, config)
            } catch (fallbackError: Throwable) {
                Log.e(TAG, "Software decoder failed", fallbackError)
            }
        } finally {
            running = false
            releaseCodec()
            lease?.close()
            lease = null
        }
    }

    private fun drainLoop(decoder: MediaCodec, config: VideoStreamConfig) {
        val info = MediaCodec.BufferInfo()
        var width = config.width
        var height = config.height
        var framesInWindow = 0
        var bytesInWindow = 0L
        var windowStart = SystemClock.elapsedRealtime()

        while (running) {
            val frame = try {
                inputQueue.poll(5, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                return
            }
            if (frame != null) {
                val queued = queueFrame(decoder, frame)
                if (queued) {
                    framesInWindow++
                    bytesInWindow += frame.bytes.size.toLong()
                }
            }

            while (true) {
                val outputIndex = try {
                    decoder.dequeueOutputBuffer(info, 0)
                } catch (error: Throwable) {
                    if (!running) return
                    throw error
                }
                if (outputIndex >= 0) {
                    decoder.releaseOutputBuffer(outputIndex, true)
                } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val outputFormat = decoder.outputFormat
                    width = outputFormat.getInteger(MediaFormat.KEY_WIDTH)
                    height = outputFormat.getInteger(MediaFormat.KEY_HEIGHT)
                } else {
                    break
                }
            }

            val now = SystemClock.elapsedRealtime()
            val elapsed = now - windowStart
            if (elapsed >= 1000L) {
                val fps = framesInWindow * 1000.0 / elapsed.toDouble()
                val kbps = bytesInWindow * 8.0 / elapsed.toDouble()
                sessionManager.updateMetrics(
                    sessionId,
                    SessionMetrics(
                        fps = fps,
                        kbps = kbps,
                        width = width,
                        height = height,
                        codec = decoderName
                    )
                )
                framesInWindow = 0
                bytesInWindow = 0L
                windowStart = now
            }
        }
    }

    private fun queueFrame(decoder: MediaCodec, frame: EncodedVideoFrame): Boolean {
        val inputIndex = try {
            decoder.dequeueInputBuffer(10_000L)
        } catch (error: Throwable) {
            if (running) Log.w(TAG, "Unable to dequeue input buffer", error)
            return false
        }
        if (inputIndex < 0) return false
        val inputBuffer = decoder.getInputBuffer(inputIndex) ?: return false
        inputBuffer.clear()
        if (frame.bytes.size > inputBuffer.remaining()) {
            Log.w(TAG, "Dropping oversized access unit " + frame.bytes.size)
            decoder.queueInputBuffer(inputIndex, 0, 0, frame.ptsUs, 0)
            return false
        }
        inputBuffer.put(frame.bytes)
        val flags = if (frame.isKeyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
        decoder.queueInputBuffer(inputIndex, 0, frame.bytes.size, frame.ptsUs, flags)
        return true
    }

    private fun createDecoder(
        config: VideoStreamConfig,
        surface: android.view.Surface,
        preferHardware: Boolean
    ): MediaCodec {
        val mime = when (config.codec) {
            VideoCodec.H264 -> MediaFormat.MIMETYPE_VIDEO_AVC
            VideoCodec.HEVC -> MediaFormat.MIMETYPE_VIDEO_HEVC
        }
        val selected = selectCodec(mime, preferHardware)
            ?: throw IllegalStateException("No decoder for " + mime)
        val decoder = MediaCodec.createByCodecName(selected.name)
        val format = MediaFormat.createVideoFormat(mime, config.width, config.height)
        if (config.codec == VideoCodec.H264) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(config.sps))
            format.setByteBuffer("csd-1", ByteBuffer.wrap(config.pps))
        } else {
            val vps = config.vps ?: ByteArray(0)
            val csd = ByteArray(vps.size + config.sps.size + config.pps.size)
            var offset = 0
            System.arraycopy(vps, 0, csd, offset, vps.size)
            offset += vps.size
            System.arraycopy(config.sps, 0, csd, offset, config.sps.size)
            offset += config.sps.size
            System.arraycopy(config.pps, 0, csd, offset, config.pps.size)
            format.setByteBuffer("csd-0", ByteBuffer.wrap(csd))
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
        }
        format.setInteger(MediaFormat.KEY_PRIORITY, 0)
        try {
            decoder.configure(format, surface, null, 0)
            return decoder
        } catch (error: Throwable) {
            try {
                decoder.release()
            } catch (_: Throwable) {
            }
            throw error
        }
    }

    private fun selectCodec(mime: String, preferHardware: Boolean): MediaCodecInfo? {
        val candidates = MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos.filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
        }
        if (candidates.isEmpty()) return null
        val hardware = candidates.filter { isHardwareAccelerated(it) }
        val software = candidates.filterNot { isHardwareAccelerated(it) }
        return if (preferHardware) {
            hardware.firstOrNull() ?: software.firstOrNull()
        } else {
            software.firstOrNull() ?: hardware.firstOrNull()
        }
    }

    private fun isHardwareAccelerated(info: MediaCodecInfo): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return info.isHardwareAccelerated
        }
        val name = info.name.lowercase(java.util.Locale.US)
        return !name.startsWith("omx.google.") && !name.startsWith("c2.android.")
    }

    private fun stopPipeline() {
        running = false
        inputQueue.clear()
        val thread = worker
        worker = null
        if (thread != null && thread !== Thread.currentThread()) {
            thread.interrupt()
            try {
                thread.join(1000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        releaseCodec()
        synchronized(surfaceLock) { releasePlaceholderLocked() }
        lease?.close()
        lease = null
    }

    private fun releaseCodec() {
        val current = codec ?: return
        codec = null
        try {
            current.stop()
        } catch (_: Throwable) {
        }
        try {
            current.release()
        } catch (_: Throwable) {
        }
    }

    private companion object {
        const val TAG = "AirPlayVideoDecoder"
    }
}