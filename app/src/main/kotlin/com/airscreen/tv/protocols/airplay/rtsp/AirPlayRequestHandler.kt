package com.airscreen.tv.protocols.airplay.rtsp

import android.util.Log
import com.airscreen.tv.core.identity.DeviceIdentity
import com.airscreen.tv.domain.contract.AccessControlManager
import com.airscreen.tv.domain.contract.MirroringVideoSink
import com.airscreen.tv.domain.contract.SessionManager
import com.airscreen.tv.domain.contract.StreamRecorderManager
import com.airscreen.tv.domain.model.AccessDecision
import com.airscreen.tv.domain.model.AppSettings
import com.airscreen.tv.domain.model.SenderProtocol
import com.airscreen.tv.domain.model.SessionAdmission
import com.airscreen.tv.domain.model.SessionId
import com.airscreen.tv.domain.model.SessionInfo
import com.airscreen.tv.domain.model.SessionRequest
import com.airscreen.tv.domain.model.SurfaceSlotId
import com.airscreen.tv.media.MediaPipelineFactory
import com.airscreen.tv.media.decoder.RaopAudioProcessor
import com.airscreen.tv.media.decoder.RaopPorts
import com.airscreen.tv.protocols.airplay.crypto.AirPlayCryptoSession
import com.airscreen.tv.protocols.airplay.plist.AirPlayPlistCodec
import com.airscreen.tv.protocols.airplay.stream.AirPlayMirroringStreamProcessor
import io.netty.channel.ChannelHandlerContext
import io.netty.channel.SimpleChannelInboundHandler
import java.net.InetSocketAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.bouncycastle.crypto.AsymmetricCipherKeyPair

class AirPlayRequestHandler(
    keyPair: AsymmetricCipherKeyPair,
    pinRequired: Boolean,
    private val identity: DeviceIdentity,
    private val settingsProvider: () -> AppSettings,
    private val sessionManager: SessionManager,
    private val mediaPipelineFactory: MediaPipelineFactory,
    private val urlCastingHandler: AirPlayUrlCastingHandler,
    private val streamRecorderManager: StreamRecorderManager,
    private val accessControlManager: AccessControlManager,
    private val scope: CoroutineScope
) : SimpleChannelInboundHandler<AirPlayRequest>() {

    private val crypto = AirPlayCryptoSession(keyPair, sessionManager, pinRequired)
    private val settings: AppSettings get() = settingsProvider()
    private val mediaLock = Any()

    private var session: SessionInfo? = null
    private var videoProcessor: AirPlayMirroringStreamProcessor? = null
    private var videoSink: MirroringVideoSink? = null
    private var audioProcessor: RaopAudioProcessor? = null
    private var videoPort: Int = 0
    private var audioPorts: RaopPorts? = null
    private var setupTimingSocket: java.net.DatagramSocket? = null
    private var pendingEkey: ByteArray? = null
    private var pendingEiv: ByteArray? = null

    override fun channelActive(ctx: ChannelHandlerContext) {
        Log.i(TAG, "AirPlay TCP connected from " + remoteAddress(ctx))
        super.channelActive(ctx)
    }

    override fun channelRead0(ctx: ChannelHandlerContext, request: AirPlayRequest) {
        val ip = remoteAddress(ctx)
        Log.i(TAG, "AirPlay " + request.method + " " + request.uri + " from " + ip)
        val access = accessControlManager.checkAccess(
            ip,
            ip,
            request.header("user-agent") ?: "AirPlay sender"
        )
        if (access == AccessDecision.BLOCK) {
            respond(ctx, request, 403, ByteArray(0), null, "Forbidden")
            return
        }
        try {
            handle(ctx, request)
        } catch (security: SecurityException) {
            Log.w(TAG, "AirPlay request rejected for " + request.method + " " + request.uri, security)
            respond(ctx, request, 403, ByteArray(0), null, "Forbidden")
        } catch (error: Throwable) {
            Log.e(TAG, "AirPlay request failed", error)
            respond(ctx, request, 500, ByteArray(0), null, "Internal Server Error")
        }
    }

    override fun channelInactive(ctx: ChannelHandlerContext) {
        cleanupMedia()
        crypto.close()
        super.channelInactive(ctx)
    }

    override fun exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable) {
        Log.w(TAG, "Closing AirPlay channel after error", cause)
        cleanupMedia()
        ctx.close()
    }

    private fun handle(ctx: ChannelHandlerContext, request: AirPlayRequest) {
        val path = normalizePath(request.uri)
        if (path == "/pair-setup") {
            val ip = remoteAddress(ctx)
            val access = accessControlManager.checkAccess(
                ip,
                ip,
                request.header("user-agent") ?: "AirPlay sender"
            )
            crypto.setPinRequired(access == AccessDecision.REQUIRE_PIN)
            crypto.setClientContext(
                request.header("user-agent") ?: "AirPlay sender",
                remoteAddress(ctx)
            )
        }
        if (path in CASTING_PATHS) {
            if (path == "/play" || path == "/playback-info") {
                Log.i(
                    TAG,
                    "CAST " + request.method + " " + path +
                        " headers=" + request.headers +
                        " body=" + request.body.take(200).joinToString("") { "%02x".format(it) }
                )
            }
            scope.launch {
                try {
                    val response = urlCastingHandler.handle(
                        request.method,
                        request.uri,
                        request.headers,
                        request.body
                    )
                    if (response == null) {
                        Log.w(TAG, "CAST no response for " + path)
                        respond(ctx, request, 404, ByteArray(0), null, "Not Found")
                    } else {
                        Log.i(TAG, "CAST response " + path + " status=" + response.statusCode + " body=" + response.body.size)
                        val headers = LinkedHashMap(response.headers)
                        request.header("cseq")?.let { headers["CSeq"] = it }
                        writeResponse(
                            ctx,
                            request,
                            response.statusCode,
                            response.body,
                            response.contentType,
                            headers,
                            reasonFor(response.statusCode)
                        )
                    }
                } catch (error: Throwable) {
                    Log.e(TAG, "AirPlay URL casting request failed", error)
                    respond(ctx, request, 500, ByteArray(0), null, "Internal Server Error")
                }
            }
            return
        }
        when {
            request.method.equals("GET", true) && path == "/info" -> handleInfo(ctx, request)
            request.method.equals("POST", true) && path == "/pair-pin-start" ->
                respond(ctx, request, 200, ByteArray(0))
            request.method.equals("POST", true) && path == "/pair-setup" ->
                handleBinary(ctx, request, crypto.handlePairSetup(request.body))
            request.method.equals("POST", true) && path == "/pair-verify" ->
                handleBinary(ctx, request, crypto.handlePairVerify(request.body))
            request.method.equals("POST", true) && path == "/fp-setup" ->
                handleBinary(ctx, request, crypto.handleFairPlaySetup(request.body))
            request.method.equals("OPTIONS", true) -> handleOptions(ctx, request)
            request.method.equals("SETUP", true) -> handleSetup(ctx, request)
            request.method.equals("RECORD", true) -> respond(ctx, request, 200, ByteArray(0))
            request.method.equals("ANNOUNCE", true) -> {
                Log.i(TAG, "ANNOUNCE contentType=" + request.header("content-type") + " body=" + String(request.body, Charsets.UTF_8).take(500))
                respond(ctx, request, 200, ByteArray(0))
            }
            request.method.equals("POST", true) && path == "/feedback" ->
                respond(ctx, request, 200, ByteArray(0))
            request.method.equals("GET_PARAMETER", true) -> handleGetParameter(ctx, request)
            request.method.equals("SET_PARAMETER", true) -> handleSetParameter(ctx, request)
            request.method.equals("FLUSH", true) -> handleFlush(ctx, request)
            request.method.equals("TEARDOWN", true) -> handleTeardown(ctx, request)
            else -> respond(ctx, request, 404, ByteArray(0), null, "Not Found")
        }
    }

    private fun handleInfo(ctx: ChannelHandlerContext, request: AirPlayRequest) {
        val width = settings.maxResolution.width
        val height = settings.maxResolution.height
        val maxFps = settings.fpsCap.fps
        val info = linkedMapOf<String, Any?>(
            "displays" to listOf(
                linkedMapOf<String, Any?>(
                    "width" to width.toLong(),
                    "height" to height.toLong(),
                    "refreshRate" to 60.0,
                    "maxFPS" to maxFps.toLong(),
                    "overscanned" to false,
                    "uuid" to identity.deviceUuid
                )
            ),
            "deviceID" to identity.hardwareId,
            "features" to 0x1E5A7FFFF7L,
            "featuresEx" to "0x5A7FFFF7,0x1E",
            "model" to "AppleTV6,2",
            "manufacturer" to "AirScreen",
            "serialNumber" to identity.hardwareId.replace(":", ""),
            "name" to settings.deviceName,
            "nameIsFactoryDefault" to false,
            "pi" to identity.pi,
            "pk" to hexToBytes(identity.publicKeyHex),
            "protocolVersion" to "1.1",
            "sourceVersion" to "220.68",
            "sdk" to "AirPlay;2.0.2",
            "build" to "220.68",
            "firmwareRevision" to "220.68",
            "keepAliveLowPower" to true,
            "keepAliveSendStatsAsBody" to true,
            "statusFlags" to 68L,
            "vv" to 2L
        )
        val (body, contentType) = encodePlistResponse(request, info)
        respond(ctx, request, 200, body, contentType)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim().replace(":", "").replace(" ", "")
        if (clean.length % 2 != 0 || clean.any { it.digitToIntOrNull(16) == null }) {
            return ByteArray(0)
        }
        return ByteArray(clean.length / 2) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun handleBinary(
        ctx: ChannelHandlerContext,
        request: AirPlayRequest,
        body: ByteArray
    ) {
        respond(ctx, request, 200, body, "application/octet-stream")
    }

    private fun handleOptions(ctx: ChannelHandlerContext, request: AirPlayRequest) {
        val headers = LinkedHashMap<String, String>()
        headers["Public"] = "ANNOUNCE, SETUP, RECORD, PAUSE, FLUSH, TEARDOWN, OPTIONS, " +
            "GET_PARAMETER, SET_PARAMETER"
        writeResponse(ctx, request, 200, ByteArray(0), null, headers, "OK")
    }


    /**
     * iOS screen mirroring can send a unified SETUP body with no streams array:
     * ekey/eiv/timingPort are top-level and isScreenMirroringSession is true.
     * In that case the receiver creates the video stream itself.
     */
    private fun implicitMirroringStreams(plist: Map<*, *>): List<Map<String, Any?>>? {
        val explicitMirroring = plist["isScreenMirroringSession"] == true ||
            (plist["isScreenMirroringSession"] as? Number)?.toInt() == 1
        // YouTube's AirPlay picker sends a no-streams SETUP containing
        // combinedGetInfoWithControlSetup and the stream keys, but not the
        // isScreenMirroringSession flag.
        val combinedMirroring = plist["combinedGetInfoWithControlSetup"] != null &&
            plist["ekey"] is ByteArray && plist["eiv"] is ByteArray
        if (!explicitMirroring && !combinedMirroring) return null
        return listOf(
            linkedMapOf<String, Any?>(
                "type" to 110L,
                "streamConnectionID" to 0L,
                "ekey" to plist["ekey"],
                "eiv" to plist["eiv"]
            )
        )
    }

    private fun handleSetup(ctx: ChannelHandlerContext, request: AirPlayRequest) {
        Log.i(
            TAG,
            "SETUP begin bytes=" + request.body.size +
                " contentType=" + request.header("content-type") +
                " transport=" + request.header("transport") +
                " headers=" + request.headers
        )
        val plist = try {
            AirPlayPlistCodec.decode(request.body) as? Map<*, *> ?: throw IllegalArgumentException("not a dictionary")
        } catch (error: Throwable) {
            Log.e(TAG, "SETUP plist decode failed head=" + request.body.take(24).joinToString("") { "%02x".format(it) }, error)
            respond(ctx, request, 400, ByteArray(0), null, "Bad Request")
            return
        }
        Log.i(TAG, "SETUP decoded keys=" + plist.keys)
        crypto.setPendingStreamKeys(plist["ekey"] as? ByteArray, plist["eiv"] as? ByteArray)
        val rawStreams = plist["streams"] as? List<*>
        if (rawStreams == null) {
            val explicitMirroring = plist["isScreenMirroringSession"] == true ||
                (plist["isScreenMirroringSession"] as? Number)?.toInt() == 1
            val combined = plist["combinedGetInfoWithControlSetup"] != null
            if (!explicitMirroring && combined) {
                // AirPlay 2 / YouTube video session init: do not advertise a
                // mirroring data port. Let the client send its play/URL step.
                val timing = ensureSetupTimingPort()
                val response = linkedMapOf<String, Any?>(
                    "eventPort" to 7000L,
                    "timingPort" to timing.toLong()
                )
                val (body, contentType) = encodePlistResponse(request, response)
                Log.i(TAG, "SETUP combined session init only")
                respond(ctx, request, 200, body, contentType)
                return
            }
        }
        val streams = rawStreams
            ?: implicitMirroringStreams(plist)
            ?: run {
                Log.e(TAG, "SETUP missing streams; keys=" + plist.keys)
                respond(ctx, request, 400, ByteArray(0), null, "Bad Request")
                return
            }
        val outputStreams = ArrayList<Map<String, Any?>>()
        var timingPort: Int? = null
        Log.i(TAG, "SETUP streams=" + streams.map { (it as? Map<*, *>)?.get("type") } + " keys=" + plist.keys)

        for (value in streams) {
            val stream = value as? Map<*, *> ?: continue
            when ((stream["type"] as? Number)?.toLong()) {
                110L -> {
                    val current = ensureSession(request, requiresVideo = true) ?: run {
                        respond(ctx, request, 503, ByteArray(0), null, "Service Unavailable")
                        return
                    }
                    val slot = current.surfaceSlot ?: run {
                        respond(ctx, request, 503, ByteArray(0), null, "Service Unavailable")
                        return
                    }
                    val connectionId = (stream["streamConnectionID"] as? Number)?.toLong()?.toULong()
                        ?: 0UL
                    Log.i(TAG, "SETUP video streamId=" + connectionId + " ekey=" + (stream["ekey"] as? ByteArray)?.size)
                    Log.i(TAG, "SETUP crypto " + crypto.cryptoDiagnostics())
                    crypto.initMirroringStreamCipher(
                        connectionId,
                        stream["ekey"] as? ByteArray ?: pendingEkey,
                        stream["eiv"] as? ByteArray ?: pendingEiv
                    )
                    val sink = mediaPipelineFactory.createVideoSink(
                        current,
                        settings.hardwareDecodingEnabled
                    )
                    val processor = AirPlayMirroringStreamProcessor(
                        sessionId = current.id,
                        surfaceSlot = slot,
                        crypto = crypto,
                        sink = sink,
                        onClosed = { onVideoDisconnected(current.id) }
                    )
                    closeVideoProcessor()
                    sessionManager.attachVideoSink(current.id, sink)
                    videoSink = sink
                    videoProcessor = processor
                    videoPort = processor.start(7100 + slot.index)
                    outputStreams += mapOf(
                        "type" to 110L,
                        "dataPort" to videoPort.toLong()
                    )
                }
                96L -> {
                    val current = ensureSession(request, requiresVideo = false) ?: run {
                        respond(ctx, request, 503, ByteArray(0), null, "Service Unavailable")
                        return
                    }
                    closeAudioProcessor()
                    val audioFormat = (stream["audioFormat"] as? Number)?.toLong() ?: 0L
                    val spf = (stream["spf"] as? Number)?.toInt() ?: 1024
                    val processor = RaopAudioProcessor(
                        sessionId = current.id,
                        audioFormat = audioFormat,
                        samplesPerFrame = spf,
                        ekey = stream["ekey"] as? ByteArray,
                        eiv = stream["eiv"] as? ByteArray,
                        streamRecorderManager = streamRecorderManager
                    )
                    val ports = processor.start()
                    sessionManager.attachAudioSink(current.id, processor)
                    audioProcessor = processor
                    audioPorts = ports
                    timingPort = ports.timingPort
                    outputStreams += linkedMapOf<String, Any?>(
                        "type" to 96L,
                        "dataPort" to ports.dataPort.toLong(),
                        "controlPort" to ports.controlPort.toLong()
                    )
                }
            }
        }

        if (outputStreams.isEmpty()) {
            respond(ctx, request, 400, ByteArray(0), null, "Bad Request")
            return
        }
        if (timingPort == null) {
            timingPort = try {
                setupTimingSocket?.close()
                val socket = java.net.DatagramSocket(0)
                setupTimingSocket = socket
                socket.localPort
            } catch (error: Throwable) {
                Log.w(TAG, "Unable to bind SETUP timing port", error)
                null
            }
        }
        val response = linkedMapOf<String, Any?>(
            "streams" to outputStreams
        )
        response["eventPort"] = 7000L
        timingPort?.let { response["timingPort"] = it.toLong() }
        val (body, contentType) = encodePlistResponse(request, response)
        Log.i(TAG, "SETUP response streams=" + outputStreams + " timing=" + timingPort)
        respond(ctx, request, 200, body, contentType)
    }


    private fun handleGetParameter(ctx: ChannelHandlerContext, request: AirPlayRequest) {
        val body = try {
            String(request.body, Charsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
        Log.i(
            TAG,
            "GET_PARAMETER contentType=" + request.header("content-type") +
                " body=" + body.take(200)
        )
        if (body.contains("volume", ignoreCase = true)) {
            respond(
                ctx,
                request,
                200,
                "volume: 0.0\r\n".toByteArray(Charsets.UTF_8),
                "text/parameters"
            )
        } else {
            respond(ctx, request, 200, ByteArray(0))
        }
    }

    private fun handleSetParameter(ctx: ChannelHandlerContext, request: AirPlayRequest) {
        val text = try {
            String(request.body, Charsets.UTF_8)
        } catch (_: Throwable) {
            ""
        }
        Log.i(TAG, "SET_PARAMETER contentType=" + request.header("content-type") + " body=" + text.take(500) + " headers=" + request.headers)
        val contentLocation = request.header("content-location")
            ?: Regex("https?://\\S+").find(text)?.value
        if (contentLocation != null) {
            Log.i(TAG, "SET_PARAMETER play URL=" + contentLocation.take(120))
            scope.launch {
                try {
                    urlCastingHandler.handle("POST", "/play", request.headers, request.body)
                } catch (error: Throwable) {
                    Log.e(TAG, "URL casting from SET_PARAMETER failed", error)
                }
            }
        }
        val fromText = parseVolumeText(text)
        val fromPlist = if (fromText == null) {
            try {
                val plist = AirPlayPlistCodec.decode(request.body) as? Map<*, *>
                (plist?.get("volume") as? Number)?.toFloat()
            } catch (_: Throwable) {
                null
            }
        } else {
            null
        }
        val db = fromText ?: fromPlist
        if (db != null) {
            audioProcessor?.setVolumeDb(db)
        }
        respond(ctx, request, 200, ByteArray(0))
    }

    private fun handleFlush(ctx: ChannelHandlerContext, request: AirPlayRequest) {
        try {
            videoSink?.onFlush()
        } catch (error: Throwable) {
            Log.w(TAG, "Video flush failed", error)
        }
        try {
            audioProcessor?.onFlush()
        } catch (error: Throwable) {
            Log.w(TAG, "Audio flush failed", error)
        }
        respond(ctx, request, 200, ByteArray(0))
    }

    private fun handleTeardown(ctx: ChannelHandlerContext, request: AirPlayRequest) {
        cleanupMedia()
        crypto.close()
        respond(ctx, request, 200, ByteArray(0))
    }

    private fun ensureSession(request: AirPlayRequest, requiresVideo: Boolean): SessionInfo? {
        synchronized(mediaLock) {
            val existing = session
            if (existing != null && (!requiresVideo || existing.surfaceSlot != null)) {
                return existing
            }
            if (existing != null) {
                cleanupMediaLocked()
            }
            val senderName = request.header("user-agent") ?: "AirPlay sender"
            val admission = sessionManager.allocateSession(
                SessionRequest(
                    protocol = SenderProtocol.AIRPLAY,
                    senderName = senderName,
                    remoteAddress = "unknown",
                    requiresVideo = requiresVideo
                )
            )
            return when (admission) {
                is SessionAdmission.Accepted -> {
                    session = admission.session
                    admission.session
                }
                is SessionAdmission.Rejected -> {
                    Log.w(TAG, "Session admission rejected: " + admission.reason)
                    null
                }
            }
        }
    }

    private fun cleanupMedia() {
        synchronized(mediaLock) {
            cleanupMediaLocked()
        }
        crypto.close()
    }

    private fun closeSetupTimingSocket() {
        try {
            setupTimingSocket?.close()
        } catch (_: Throwable) {
        }
        setupTimingSocket = null
    }

    private fun ensureSetupTimingPort(): Int {
        setupTimingSocket?.let { return it.localPort }
        return try {
            val socket = java.net.DatagramSocket(0)
            setupTimingSocket = socket
            socket.localPort
        } catch (error: Throwable) {
            Log.w(TAG, "Unable to bind SETUP timing port", error)
            0
        }
    }

    private fun cleanupMediaLocked() {
        closeSetupTimingSocket()
        closeVideoProcessor()
        closeAudioProcessor()
        videoSink = null
        videoPort = 0
        audioPorts = null
        val current = session
        session = null
        if (current != null) sessionManager.releaseSession(current.id)
    }

    private fun closeVideoProcessor() {
        // Detach before closing: the processor's onClosed callback must not
        // release the session while a replacement stream is being configured.
        val current = videoProcessor
        videoProcessor = null
        try {
            current?.close()
        } catch (error: Throwable) {
            Log.d(TAG, "Unable to close mirroring processor", error)
        }
    }

    private fun closeAudioProcessor() {
        try {
            audioProcessor?.close()
        } catch (error: Throwable) {
            Log.d(TAG, "Unable to close audio processor", error)
        }
        audioProcessor = null
    }

    private fun onVideoDisconnected(sessionId: SessionId) {
        synchronized(mediaLock) {
            if (videoProcessor == null) return
            if (session?.id == sessionId) {
                closeVideoProcessor()
                closeAudioProcessor()
                videoSink = null
                audioPorts = null
                val current = session
                session = null
                if (current != null) sessionManager.releaseSession(current.id)
            }
        }
    }

    private fun respond(
        ctx: ChannelHandlerContext,
        request: AirPlayRequest,
        status: Int,
        body: ByteArray,
        contentType: String? = null,
        reason: String? = null
    ) {
        val headers = LinkedHashMap<String, String>()
        request.header("cseq")?.let { headers["CSeq"] = it }
        writeResponse(ctx, request, status, body, contentType, headers, reason ?: reasonFor(status))
    }

    private fun writeResponse(
        ctx: ChannelHandlerContext,
        request: AirPlayRequest,
        status: Int,
        body: ByteArray,
        contentType: String?,
        headers: MutableMap<String, String>,
        reason: String
    ) {
        if (!ctx.channel().isActive) return
        ctx.writeAndFlush(
            AirPlayResponse(
                version = request.version,
                statusCode = status,
                reason = reason,
                headers = headers,
                body = body,
                contentType = contentType
            )
        )
    }

    private fun encodePlistResponse(request: AirPlayRequest, value: Any?): Pair<ByteArray, String> {
        val requestContentType = request.header("content-type") ?: ""
        return if (requestContentType.contains("xml", ignoreCase = true)) {
            Pair(
                AirPlayPlistCodec.encodeXml(value),
                "text/xml; charset=utf-8"
            )
        } else {
            Pair(
                AirPlayPlistCodec.encodeBinary(value),
                PLIST_CONTENT_TYPE
            )
        }
    }

    private fun normalizePath(uri: String): String {
        val withoutQuery = uri.substringBefore('?')
        val schemeIndex = withoutQuery.indexOf("://")
        if (schemeIndex >= 0) {
            val slash = withoutQuery.indexOf('/', schemeIndex + 3)
            return if (slash >= 0) withoutQuery.substring(slash) else "/"
        }
        return withoutQuery.ifBlank { "/" }
    }

    private fun remoteAddress(ctx: ChannelHandlerContext): String {
        val address = ctx.channel().remoteAddress() as? InetSocketAddress ?: return "unknown"
        return address.address?.hostAddress ?: "unknown"
    }

    private fun parseVolumeText(text: String): Float? {
        val index = text.indexOf("volume:", ignoreCase = true)
        if (index < 0) return null
        val tail = text.substring(index + 7).trim().substringBefore('\r').substringBefore('\n')
        return tail.substringBefore(';').trim().toFloatOrNull()
    }

    private fun reasonFor(status: Int): String = when (status) {
        200 -> "OK"
        400 -> "Bad Request"
        403 -> "Forbidden"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        503 -> "Service Unavailable"
        else -> "OK"
    }

    private companion object {
        const val TAG = "AirPlayRtspHandler"
        const val PLIST_CONTENT_TYPE = "application/x-apple-binary-plist"
        val CASTING_PATHS = setOf("/play", "/scrub", "/rate", "/stop", "/photo", "/playback-info")
    }
}