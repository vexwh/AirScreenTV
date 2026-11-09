package com.airscreen.tv.protocols.airplay.rtsp

import android.util.Log
import com.airscreen.tv.core.identity.DeviceIdentity
import com.airscreen.tv.core.identity.DeviceIdentityRepository
import com.airscreen.tv.di.ApplicationScope
import com.airscreen.tv.domain.contract.AccessControlManager
import com.airscreen.tv.domain.contract.AppSettingsRepository
import com.airscreen.tv.domain.contract.DiscoveryController
import com.airscreen.tv.domain.contract.SessionManager
import com.airscreen.tv.domain.contract.StreamRecorderManager
import com.airscreen.tv.domain.model.AppSettings
import com.airscreen.tv.media.MediaPipelineFactory
import io.netty.bootstrap.ServerBootstrap
import io.netty.channel.Channel
import io.netty.channel.ChannelInitializer
import io.netty.channel.EventLoopGroup
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.SocketChannel
import io.netty.channel.socket.nio.NioServerSocketChannel
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.AsymmetricCipherKeyPair

@Singleton
class AirPlayRtspServer @Inject constructor(
    private val settingsRepository: AppSettingsRepository,
    private val sessionManager: SessionManager,
    private val identityRepository: DeviceIdentityRepository,
    private val discoveryController: DiscoveryController,
    private val mediaPipelineFactory: MediaPipelineFactory,
    private val urlCastingHandler: AirPlayUrlCastingHandler,
    private val streamRecorderManager: StreamRecorderManager,
    private val accessControlManager: AccessControlManager,
    @ApplicationScope private val scope: CoroutineScope
) {
    private var bossGroup: EventLoopGroup? = null
    private var workerGroup: EventLoopGroup? = null
    private var channels: List<Channel> = emptyList()
    private var running = false
    @Volatile private var liveSettings: AppSettings? = null

    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        if (running) return@withContext true
        val identity = identityRepository.identity()
        val settings = settingsRepository.settings.first()
        liveSettings = settings
        scope.launch {
            settingsRepository.settings.collect { liveSettings = it }
        }
        val keyPair = discoveryController.status.value.ed25519KeyPair ?: identity.keyPair
        val newBoss = NioEventLoopGroup(1)
        val newWorker = NioEventLoopGroup()
        val bound = ArrayList<Channel>()
        try {
            val bootstrap = ServerBootstrap()
                .group(newBoss, newWorker)
                .channel(NioServerSocketChannel::class.java)
                .childHandler(object : ChannelInitializer<SocketChannel>() {
                    override fun initChannel(channel: SocketChannel) {
                        channel.pipeline().addLast(
                            AirPlayRequestDecoder(),
                            AirPlayResponseEncoder(),
                            AirPlayRequestHandler(
                                keyPair = keyPair,
                                pinRequired = (liveSettings ?: settings).pinSecurityEnabled,
                                identity = identity,
                                settingsProvider = { liveSettings ?: settings },
                                sessionManager = sessionManager,
                                mediaPipelineFactory = mediaPipelineFactory,
                                urlCastingHandler = urlCastingHandler,
                                streamRecorderManager = streamRecorderManager,
                                accessControlManager = accessControlManager,
                                scope = scope
                            )
                        )
                    }
                })
                .option(io.netty.channel.ChannelOption.SO_BACKLOG, 64)
                .childOption(io.netty.channel.ChannelOption.TCP_NODELAY, true)
                .childOption(io.netty.channel.ChannelOption.SO_KEEPALIVE, true)

            bound += bootstrap.bind(AIRPLAY_PORT).sync().channel()
            bound += bootstrap.bind(RAOP_PORT).sync().channel()
            bossGroup = newBoss
            workerGroup = newWorker
            channels = bound
            running = true
            Log.i(TAG, "AirPlay/RAOP RTSP listening on " + AIRPLAY_PORT + " and " + RAOP_PORT)
            true
        } catch (error: Throwable) {
            Log.e(TAG, "Unable to start RTSP server", error)
            bound.forEach { channel -> runCatching { channel.close() } }
            newBoss.shutdownGracefully(0, 1, TimeUnit.SECONDS)
            newWorker.shutdownGracefully(0, 1, TimeUnit.SECONDS)
            false
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        running = false
        channels.forEach { channel -> runCatching { channel.close().sync() } }
        channels = emptyList()
        bossGroup?.shutdownGracefully(0, 2, TimeUnit.SECONDS)
        workerGroup?.shutdownGracefully(0, 2, TimeUnit.SECONDS)
        bossGroup = null
        workerGroup = null
        sessionManager.closeAll()
        mediaPipelineFactory.closeAll()
        Log.i(TAG, "AirPlay/RAOP RTSP stopped")
    }

    private companion object {
        const val TAG = "AirPlayRtsp"
        const val AIRPLAY_PORT = 7000
        const val RAOP_PORT = 5000
    }
}