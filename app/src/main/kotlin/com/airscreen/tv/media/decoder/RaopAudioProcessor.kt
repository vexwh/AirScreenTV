package com.airscreen.tv.media.decoder

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.Process
import android.util.Log
import com.airscreen.tv.domain.contract.MirroringAudioSink
import com.airscreen.tv.domain.contract.StreamRecorderManager
import com.airscreen.tv.domain.model.AudioCodec
import com.airscreen.tv.domain.model.AudioStreamConfig
import com.airscreen.tv.domain.model.EncodedAudioFrame
import com.airscreen.tv.domain.model.FrameDisposition
import com.airscreen.tv.domain.model.SessionId
import com.airscreen.tv.protocols.airplay.audio.AudioVolume
import com.airscreen.tv.protocols.airplay.audio.RtpAudioPacketCodec
import java.io.Closeable
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class RaopPorts(
    val dataPort: Int,
    val controlPort: Int,
    val timingPort: Int
)

class RaopAudioProcessor(
    private val sessionId: SessionId,
    private val audioFormat: Long,
    private val samplesPerFrame: Int,
    private val ekey: ByteArray?,
    private val eiv: ByteArray?,
    private val streamRecorderManager: StreamRecorderManager? = null
) : MirroringAudioSink, Closeable {
    private val running = AtomicBoolean(false)
    private val frameQueue = ArrayBlockingQueue<EncodedAudioFrame>(16)
    private val codec: AudioCodec = mapCodec(audioFormat)
    private var dataSocket: DatagramSocket? = null
    private var controlSocket: DatagramSocket? = null
    private var timingSocket: DatagramSocket? = null
    private var receiveThread: Thread? = null
    private var controlThread: Thread? = null
    private var timingThread: Thread? = null
    private var decodeThread: Thread? = null
    private var mediaCodec: MediaCodec? = null
    private var audioTrack: AudioTrack? = null
    private var audioCipher: Cipher? = null
    private var firstRtpTimestamp: Long = -1L
    private var currentPtsUs: Long = 0L

    @Volatile private var volumeGain: Float = 1f
    @Volatile private var muted = false

    fun start(): RaopPorts {
        if (!running.compareAndSet(false, true)) {
            return RaopPorts(
                dataSocket?.localPort ?: 0,
                controlSocket?.localPort ?: 0,
                timingSocket?.localPort ?: 0
            )
        }
        return try {
            val ports = bindPorts()
            initCipher()
            streamRecorderManager?.onAudioConfig(
                sessionId.value,
                AudioStreamConfig(
                    sessionId = sessionId,
                    codec = codec,
                    sampleRateHz = SAMPLE_RATE_HZ,
                    channelCount = CHANNEL_COUNT,
                    samplesPerFrame = samplesPerFrame
                )
            )
            startDecodeThread()
            startReceiveThreads()
            ports
        } catch (error: Throwable) {
            close()
            throw error
        }
    }

    fun setVolumeDb(db: Float) {
        volumeGain = AudioVolume.gainFromDb(db)
        try {
            audioTrack?.setVolume(if (muted) 0f else volumeGain)
        } catch (error: Throwable) {
            Log.d(TAG, "Unable to set audio volume", error)
        }
    }

    override fun onAudioConfig(config: AudioStreamConfig) {
        Log.i(TAG, "Audio config " + config.codec + " " + config.sampleRateHz + " Hz")
    }

    fun setMuted(muted: Boolean) {
        this.muted = muted
        try {
            audioTrack?.setVolume(if (muted) 0f else volumeGain)
        } catch (error: Throwable) {
            Log.d(TAG, "Unable to set audio mute", error)
        }
    }

    override fun onAudioFrame(frame: EncodedAudioFrame): FrameDisposition {
        if (!running.get()) return FrameDisposition.REJECTED
        streamRecorderManager?.onAudioFrame(sessionId.value, frame)
        return if (frameQueue.offer(frame)) {
            FrameDisposition.ACCEPTED
        } else {
            FrameDisposition.DROPPED_BACKPRESSURE
        }
    }

    override fun onFlush() {
        frameQueue.clear()
        try {
            mediaCodec?.flush()
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to flush audio decoder", error)
        }
    }

    override fun onStreamEnd() {
        close()
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        frameQueue.clear()
        try {
            dataSocket?.close()
        } catch (_: Throwable) {
        }
        try {
            controlSocket?.close()
        } catch (_: Throwable) {
        }
        try {
            timingSocket?.close()
        } catch (_: Throwable) {
        }
        dataSocket = null
        controlSocket = null
        timingSocket = null

        val threads = listOfNotNull(receiveThread, controlThread, timingThread, decodeThread)
        threads.forEach { it.interrupt() }
        threads.forEach { thread ->
            if (thread !== Thread.currentThread()) {
                try {
                    thread.join(500L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        receiveThread = null
        controlThread = null
        timingThread = null
        decodeThread = null

        try {
            mediaCodec?.stop()
        } catch (_: Throwable) {
        }
        try {
            mediaCodec?.release()
        } catch (_: Throwable) {
        }
        mediaCodec = null
        try {
            audioTrack?.stop()
        } catch (_: Throwable) {
        }
        try {
            audioTrack?.release()
        } catch (_: Throwable) {
        }
        audioTrack = null
    }

    private fun bindPorts(): RaopPorts {
        for (base in PORT_RANGE_START..PORT_RANGE_END - 2) {
            var candidateData: DatagramSocket? = null
            var candidateControl: DatagramSocket? = null
            var candidateTiming: DatagramSocket? = null
            try {
                candidateData = DatagramSocket(base)
                candidateControl = DatagramSocket(base + 1)
                candidateTiming = DatagramSocket(base + 2)
                candidateData.soTimeout = RECEIVE_TIMEOUT_MS
                candidateControl.soTimeout = RECEIVE_TIMEOUT_MS
                candidateTiming.soTimeout = RECEIVE_TIMEOUT_MS
                dataSocket = candidateData
                controlSocket = candidateControl
                timingSocket = candidateTiming
                return RaopPorts(base, base + 1, base + 2)
            } catch (error: IOException) {
                try {
                    candidateData?.close()
                } catch (_: Throwable) {
                }
                try {
                    candidateControl?.close()
                } catch (_: Throwable) {
                }
                try {
                    candidateTiming?.close()
                } catch (_: Throwable) {
                }
            }
        }
        throw IOException("No free RAOP audio ports in 6000..6010")
    }

    private fun initCipher() {
        val key = ekey
        val iv = eiv
        if (key == null || iv == null || key.size != 16 || iv.size != 16) return
        audioCipher = Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        }
    }

    private fun startReceiveThreads() {
        val data = dataSocket ?: return
        receiveThread = Thread({ receiveLoop(data) }, "RaopAudioRtp").also {
            it.isDaemon = true
            it.start()
        }
        val control = controlSocket
        if (control != null) {
            controlThread = Thread({ drainLoop(control) }, "RaopAudioControl").also {
                it.isDaemon = true
                it.start()
            }
        }
        val timing = timingSocket
        if (timing != null) {
            timingThread = Thread({ drainLoop(timing) }, "RaopAudioTiming").also {
                it.isDaemon = true
                it.start()
            }
        }
    }

    private fun receiveLoop(socket: DatagramSocket) {
        val buffer = ByteArray(65535)
        while (running.get()) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: SocketException) {
                break
            } catch (error: IOException) {
                if (running.get()) Log.w(TAG, "RAOP RTP receive failed", error)
                break
            }
            val rtp = RtpAudioPacketCodec.parse(packet.data.copyOfRange(0, packet.length))
                ?: continue
            val payload = decrypt(rtp.payload) ?: continue
            val ptsUs = nextPtsUs(rtp.timestamp)
            onAudioFrame(
                EncodedAudioFrame(
                    sessionId = sessionId,
                    codec = codec,
                    ptsUs = ptsUs,
                    bytes = payload
                )
            )
        }
    }

    private fun drainLoop(socket: DatagramSocket) {
        val buffer = ByteArray(2048)
        while (running.get()) {
            try {
                socket.receive(DatagramPacket(buffer, buffer.size))
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: SocketException) {
                break
            } catch (error: IOException) {
                if (running.get()) Log.d(TAG, "RAOP control drain stopped", error)
                break
            }
        }
    }

    private fun decrypt(payload: ByteArray): ByteArray? {
        val cipher = audioCipher ?: return payload
        return try {
            cipher.update(payload)
        } catch (error: Throwable) {
            Log.w(TAG, "Audio payload decryption failed", error)
            null
        }
    }

    private fun nextPtsUs(rtpTimestamp: Long): Long {
        if (firstRtpTimestamp < 0L) firstRtpTimestamp = rtpTimestamp
        val delta = (rtpTimestamp - firstRtpTimestamp) and 0xFFFFFFFFL
        currentPtsUs += (delta * 1_000_000L) / SAMPLE_RATE_HZ
        firstRtpTimestamp = rtpTimestamp
        return currentPtsUs
    }

    private fun startDecodeThread() {
        decodeThread = Thread({ decodeLoop() }, "RaopAudioDecode").also {
            it.isDaemon = true
            it.start()
        }
    }

    private fun decodeLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        try {
            configureAudio()
            val decoder = mediaCodec ?: return
            val track = audioTrack ?: return
            track.play()
            val info = MediaCodec.BufferInfo()
            while (running.get()) {
                val frame = try {
                    frameQueue.poll(20, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    return
                }
                if (frame != null) queueAudioFrame(decoder, frame)
                while (true) {
                    val outputIndex = decoder.dequeueOutputBuffer(info, 0)
                    if (outputIndex >= 0) {
                        val output = decoder.getOutputBuffer(outputIndex)
                        if (output != null && info.size > 0) {
                            val pcm = ByteArray(info.size)
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            output.get(pcm)
                            track.write(pcm, 0, pcm.size, AudioTrack.WRITE_BLOCKING)
                        }
                        decoder.releaseOutputBuffer(outputIndex, false)
                    } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Log.d(TAG, "Audio output format changed")
                    } else {
                        break
                    }
                }
            }
        } catch (error: Throwable) {
            if (running.get()) Log.e(TAG, "Audio decoder failed", error)
        }
    }

    private fun configureAudio() {
        val mime = when (codec) {
            AudioCodec.ALAC -> "audio/alac"
            AudioCodec.AAC_LC, AudioCodec.AAC_ELD -> MediaFormat.MIMETYPE_AUDIO_AAC
        }
        val decoder = MediaCodec.createDecoderByType(mime)
        val format = MediaFormat.createAudioFormat(mime, SAMPLE_RATE_HZ, CHANNEL_COUNT)
        if (codec == AudioCodec.AAC_ELD) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(aacEldAudioSpecificConfig()))
            format.setInteger(MediaFormat.KEY_AAC_PROFILE, AAC_OBJECT_ELD)
        } else if (codec == AudioCodec.AAC_LC) {
            format.setByteBuffer("csd-0", ByteBuffer.wrap(aacLcAudioSpecificConfig()))
        }
        decoder.configure(format, null, null, 0)
        decoder.start()
        mediaCodec = decoder

        val minBuffer = AudioTrack.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_OUT_STEREO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val builder = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE_HZ)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuffer, 4096))
        builder.setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
        val track = builder.build()
        track.setVolume(if (muted) 0f else volumeGain)
        audioTrack = track
        Log.i(TAG, "Audio pipeline ready codec=" + mime + " rate=" + SAMPLE_RATE_HZ)
    }

    private fun queueAudioFrame(decoder: MediaCodec, frame: EncodedAudioFrame) {
        val inputIndex = decoder.dequeueInputBuffer(10_000L)
        if (inputIndex < 0) return
        val input = decoder.getInputBuffer(inputIndex) ?: return
        input.clear()
        if (frame.bytes.size > input.remaining()) {
            decoder.queueInputBuffer(inputIndex, 0, 0, frame.ptsUs, 0)
            return
        }
        input.put(frame.bytes)
        decoder.queueInputBuffer(inputIndex, 0, frame.bytes.size, frame.ptsUs, 0)
    }

    private fun aacEldAudioSpecificConfig(): ByteArray {
        val value = (AAC_OBJECT_ELD shl 11) or (AAC_FREQ_INDEX_44100 shl 7) or
            (CHANNEL_COUNT shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private fun aacLcAudioSpecificConfig(): ByteArray {
        val value = (AAC_OBJECT_LC shl 11) or (AAC_FREQ_INDEX_44100 shl 7) or
            (CHANNEL_COUNT shl 3)
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private fun mapCodec(format: Long): AudioCodec = when (format) {
        0x00000002L -> AudioCodec.ALAC
        0x00000008L -> AudioCodec.AAC_LC
        else -> AudioCodec.AAC_ELD
    }

    private companion object {
        const val TAG = "RaopAudio"
        const val SAMPLE_RATE_HZ = 44100
        const val CHANNEL_COUNT = 2
        const val AAC_OBJECT_ELD = 39
        const val AAC_OBJECT_LC = 2
        const val AAC_FREQ_INDEX_44100 = 4
        const val PORT_RANGE_START = 6000
        const val PORT_RANGE_END = 6010
        const val RECEIVE_TIMEOUT_MS = 1000
    }
}