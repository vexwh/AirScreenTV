package com.airscreen.tv.ui.stage

import android.graphics.PixelFormat
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.airscreen.tv.airplay2.AirPlayEngineController

@Composable
fun AirPlay2StageScreen(onExit: () -> Unit) {
    var mirrorSurface by remember { mutableStateOf<Surface?>(null) }
    var playbackSurface by remember { mutableStateOf<Surface?>(null) }
    val mirrorAspect by AirPlayEngineController.videoAspect.collectAsStateWithLifecycle()
    val playbackAspect by AirPlayEngineController.playbackAspect.collectAsStateWithLifecycle()
    val playbackActive by AirPlayEngineController.playbackActive.collectAsStateWithLifecycle()
    val targetAspect = (if (playbackActive) playbackAspect else mirrorAspect)
        .takeIf { it > 0f } ?: (16f / 9f)

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val containerAspect = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
            val videoModifier = if (targetAspect > containerAspect) {
                Modifier.fillMaxWidth().aspectRatio(targetAspect)
            } else {
                Modifier.fillMaxHeight().aspectRatio(targetAspect)
            }

            // HLS surface, created immediately and kept underneath the mirror view.
            AndroidView(
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.setFormat(PixelFormat.OPAQUE)
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                playbackSurface = holder.surface
                                AirPlayEngineController.setPlaybackSurface(holder.surface)
                            }

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                AirPlayEngineController.setPlaybackSurface(holder.surface)
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                AirPlayEngineController.clearPlaybackSurface(holder.surface)
                                playbackSurface = null
                            }
                        })
                    }
                },
                modifier = videoModifier
            )

            // Mirroring surface on top; hidden while AirPlay video is active.
            AndroidView(
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.setFormat(PixelFormat.OPAQUE)
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                mirrorSurface = holder.surface
                                AirPlayEngineController.setSurface(holder.surface)
                            }

                            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                AirPlayEngineController.setSurface(holder.surface)
                            }

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                AirPlayEngineController.clearSurface(holder.surface)
                                mirrorSurface = null
                            }
                        })
                    }
                },
                update = { view -> view.visibility = if (playbackActive) View.GONE else View.VISIBLE },
                modifier = videoModifier
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            mirrorSurface?.let { AirPlayEngineController.clearSurface(it) }
            playbackSurface?.let { AirPlayEngineController.clearPlaybackSurface(it) }
        }
    }
}