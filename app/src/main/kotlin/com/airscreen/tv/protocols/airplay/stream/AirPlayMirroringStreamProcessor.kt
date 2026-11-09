package com.airscreen.tv.protocols.airplay.stream

import android.util.Log
import com.airscreen.tv.domain.contract.MirroringVideoSink
import com.airscreen.tv.domain.model.EncodedVideoFrame
import com.airscreen.tv.domain.model.VideoCodec
import com.airscreen.tv.domain.model.VideoStreamConfig
import com.airscreen.tv.domain.model.SessionId
import com.airscreen.tv.domain.model.SurfaceSlotId
import com.airscreen.tv.protocols.airplay.crypto.AirPlayCryptoSession
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

class AirPlayMirroringStreamProcessor(
    private val sessionId: SessionId,
    private val surfaceSlot: SurfaceSlotId,
    private val crypto: AirPlayCryptoSession,
    private val sink: MirroringVideoSink,
    private val onClosed: () -> Unit
) {
    private val closed = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private var thread: Thread? = null
    private var codec: VideoCodec = VideoCodec.H264
    private var firstTimestamp: Long = 0L;
    private var loggedFirstFrame = false
    private var malformedFrames = 0

    fun start(preferredPort: Int): Int {
        val server = bindServer(preferredPort)
        Log.i(TAG, "Mirroring processor starting on " + server.localPort)
        serverSocket = server
        thread = Thread({ acceptLoop(server) }, "AirPlayMirroring-" + sessionId.value)
            .also { it.isDaemon = true; it.start() }
        return server.localPort
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            clientSocket?.close()
        } catch (_: IOException) {
        }
        try {
            serverSocket?.close()
        } catch (_: IOException) {
        }
        val current = thread
        current?.interrupt()
        if (current != null && current !== Thread.currentThread()) {
            try {
                current.join(500L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        thread = null
        clientSocket = null
        serverSocket = null
    }

    private fun bindServer(preferredPort: Int): ServerSocket {
        // iPhone selects the TV's IPv6 link-local address for RTSP, so the
        // mirroring data listener must be an explicit IPv6 socket.
        try {
            val preferred = ServerSocket()
            preferred.reuseAddress = true
            preferred.bind(
                InetSocketAddress(Inet6Address.getByName("::"), preferredPort),
                1
            )
            Log.i(TAG, "Mirroring IPv6 wildcard listening on " + preferred.localPort)
            return preferred
        } catch (error: Throwable) {
            Log.w(TAG, "IPv6 wildcard bind failed", error)
        }
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val network = interfaces.nextElement()
                if (!network.isUp || network.isLoopback) continue
                for (address in network.inetAddresses) {
                    if (address is Inet6Address && address.isLinkLocalAddress) {
                        try {
                            val preferred = ServerSocket()
                            preferred.reuseAddress = true
                            preferred.bind(InetSocketAddress(address, preferredPort), 1)
                            Log.i(
                                TAG,
                                "Mirroring IPv6 link-local listening on " +
                                    address.hostAddress + ":" + preferred.localPort
                            )
                            return preferred
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        } catch (_: Throwable) {
        }
        try {
            val preferred = ServerSocket()
            preferred.reuseAddress = true
            preferred.bind(InetSocketAddress(preferredPort), 1)
            Log.w(TAG, "Mirroring fell back to IPv4 on " + preferred.localPort)
            return preferred
        } catch (_: Throwable) {
        }
        val ephemeral = ServerSocket()
        ephemeral.reuseAddress = true
        ephemeral.bind(InetSocketAddress(0), 1)
        Log.w(TAG, "Mirroring fell back to ephemeral IPv4 on " + ephemeral.localPort)
        return ephemeral
    }

    private fun acceptLoop(server: ServerSocket) {
        try {
            while (!closed.get()) {
                val client = server.accept()
                Log.i(TAG, "Mirroring data accepted from " + client.remoteSocketAddress)
                client.tcpNoDelay = true
                clientSocket = client
                readLoop(client)
                break
            }
        } catch (error: IOException) {
            if (!closed.get()) Log.w(TAG, "Mirroring accept failed", error)
        } finally {
            close()
            sink.onStreamEnd()
            onClosed()
        }
    }

    private fun readLoop(client: Socket) {
        val input = DataInputStream(BufferedInputStream(client.getInputStream()))
        val header = ByteArray(MirroringPacketCodec.HEADER_SIZE)
        while (!closed.get()) {
            try {
                input.readFully(header)
            } catch (_: EOFException) {
                return
            } catch (error: IOException) {
                if (!closed.get()) Log.w(TAG, "Mirroring header read failed", error)
                return
            }
            val parsed = try {
                MirroringPacketCodec.parseHeader(header)
            } catch (error: Throwable) {
                Log.w(TAG, "Invalid mirroring header", error)
                return
            }
            if (parsed.payloadSize < 0 ||
                parsed.payloadSize > MirroringPacketCodec.MAX_PAYLOAD_SIZE
            ) {
                Log.w(TAG, "Rejected mirroring payload size " + parsed.payloadSize)
                return
            }
            val payload = ByteArray(parsed.payloadSize)
            if (payload.isNotEmpty()) {
                try {
                    input.readFully(payload)
                } catch (error: IOException) {
                    if (!closed.get()) Log.w(TAG, "Mirroring payload read failed", error)
                    return
                }
            }
            when (parsed.payloadType) {
                1 -> handleCodecConfig(parsed, payload)
                0 -> handleVideoFrame(parsed, payload)
                2, 5 -> Unit
                else -> Log.d(TAG, "Ignoring mirroring payload type " + parsed.payloadType)
            }
        }
    }

    private fun handleCodecConfig(header: MirroringPacketHeader, payload: ByteArray) {
        val config = try {
            MirroringPacketCodec.parseCodecConfig(payload)
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to parse codec configuration", error)
            return
        }
        codec = config.codec
        val width = normalizeDimension(header.width)
        val height = normalizeDimension(header.height)
        sink.onCodecConfig(
            VideoStreamConfig(
                sessionId = sessionId,
                surfaceSlot = surfaceSlot,
                codec = config.codec,
                width = width,
                height = height,
                sps = config.sps,
                pps = config.pps,
                vps = config.vps
            )
        )
        Log.i(TAG, "Codec configured " + config.codec + " " + width + "x" + height)
    }

    private fun handleVideoFrame(header: MirroringPacketHeader, payload: ByteArray) {
        if (payload.isEmpty()) return
        val firstFrame = !loggedFirstFrame
        if (firstFrame) {
            loggedFirstFrame = true
            Log.i(TAG, "Frame size=" + payload.size + " before=" + payload.take(16).joinToString("") { "%02x".format(it) })
        }
        try {
            crypto.decryptVideoInPlace(payload, 0, payload.size)
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to decrypt mirroring payload", error)
            return
        }
        if (firstFrame) Log.i(TAG, "Frame after=" + payload.take(16).joinToString("") { "%02x".format(it) })
        if (!MirroringPacketCodec.avccToAnnexBInPlace(payload, 0, payload.size)) {
            malformedFrames++
            if (malformedFrames <= 5 || malformedFrames % 100 == 0) {
                Log.w(TAG, "Discarding malformed AVCC frame #" + malformedFrames)
            }
            return
        }
        if (firstTimestamp == 0L && header.ntpTimestamp != 0L) {
            firstTimestamp = header.ntpTimestamp
        }
        val ptsUs = MirroringPacketCodec.relativeMicros(header.ntpTimestamp, firstTimestamp)
        val keyFrame = MirroringPacketCodec.containsKeyFrame(payload, 0, payload.size, codec)
        sink.onVideoFrame(
            EncodedVideoFrame(
                sessionId = sessionId,
                codec = codec,
                ptsUs = ptsUs,
                bytes = payload,
                isKeyFrame = keyFrame
            )
        )
    }

    private fun normalizeDimension(value: Float): Int {
        val dimension = value.toInt()
        return if (dimension in 16..4096) dimension else 1920
    }

    private companion object {
        const val TAG = "AirPlayMirror"
    }
}