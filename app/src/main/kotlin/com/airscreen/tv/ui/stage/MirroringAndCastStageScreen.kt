package com.airscreen.tv.ui.stage

import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.airscreen.tv.domain.model.AspectRatioMode
import com.airscreen.tv.domain.model.CastPlaybackState
import com.airscreen.tv.domain.model.ClientSession
import com.airscreen.tv.domain.model.LayoutMode
import com.airscreen.tv.domain.model.MultiScreenState
import com.airscreen.tv.domain.model.RecordingState
import com.airscreen.tv.ui.theme.TvColors
import com.airscreen.tv.ui.viewmodel.MainViewModel
import kotlinx.coroutines.delay

@Composable
fun MirroringAndCastStageRoute(
    viewModel: MainViewModel,
    onExit: () -> Unit
) {
    val multi by viewModel.multiScreenState.collectAsStateWithLifecycle()
    val castState by viewModel.castPlaybackState.collectAsStateWithLifecycle()
    val castProgress by viewModel.castProgress.collectAsStateWithLifecycle()
    val recording by viewModel.recordingState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val stageFocus = remember { FocusRequester() }
    val cellFocusRequesters = remember { mutableMapOf<String, FocusRequester>() }
    var osdVisible by remember { mutableStateOf(true) }
    var debugHudVisible by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    var fullScreenFocus by remember { mutableStateOf(false) }
    var selectedSessionId by remember { mutableStateOf<String?>(null) }
    var backArmedAt by remember { mutableLongStateOf(0L) }

    val sessions = multi.sessions
    val sessionIdsKey = sessions.joinToString("|") { it.sessionId }
    val selectedSession = sessions.firstOrNull { it.sessionId == selectedSessionId }
        ?: sessions.firstOrNull { it.sessionId == multi.activeAudioSessionId }
        ?: sessions.firstOrNull()

    LaunchedEffect(sessionIdsKey, multi.activeAudioSessionId) {
        if (selectedSessionId == null || sessions.none { it.sessionId == selectedSessionId }) {
            selectedSessionId = multi.activeAudioSessionId ?: sessions.firstOrNull()?.sessionId
        }
    }
    LaunchedEffect(osdVisible, interaction) {
        if (osdVisible) {
            delay(5_000L)
            osdVisible = false
            focusManager.clearFocus()
        }
    }
    LaunchedEffect(osdVisible, selectedSessionId, sessionIdsKey, fullScreenFocus) {
        if (!osdVisible) {
            delay(90L)
            if (sessions.isNotEmpty()) {
                val id = selectedSession?.sessionId
                val requester = id?.let { cellFocusRequesters.getOrPut(it) { FocusRequester() } }
                runCatching { requester?.requestFocus() }
            } else {
                runCatching { stageFocus.requestFocus() }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(stageFocus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                interaction++
                when (event.key) {
                    Key.Back -> {
                        if (osdVisible) {
                            osdVisible = false
                            backArmedAt = 0L
                            focusManager.clearFocus()
                        } else {
                            val now = SystemClock.elapsedRealtime()
                            if (now - backArmedAt in 1..3_000L) {
                                viewModel.stopStagePlayback()
                                onExit()
                            } else {
                                backArmedAt = now
                                Toast.makeText(
                                    context,
                                    "Press BACK again to disconnect stream",
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.Menu -> {
                        if (!osdVisible) {
                            osdVisible = true
                            backArmedAt = 0L
                        }
                        false
                    }
                    Key.DirectionUp, Key.DirectionDown -> {
                        if (!osdVisible && sessions.size <= 1) {
                            osdVisible = true
                            backArmedAt = 0L
                        }
                        false
                    }
                    else -> false
                }
            }
    ) {
        if (sessions.isNotEmpty()) {
            MirroringGrid(
                state = multi,
                selectedSessionId = selectedSession?.sessionId,
                recordingState = recording,
                activeAudioSessionId = multi.activeAudioSessionId,
                fullScreenFocus = fullScreenFocus,
                cellFocusRequesters = cellFocusRequesters,
                viewModel = viewModel,
                onCellFocused = { id -> selectedSessionId = id }
            )
        } else {
            CastStage(castState, viewModel)
        }

        if (debugHudVisible) {
            DebugHudOverlay(
                sessions = sessions,
                recordingState = recording,
                activeAudioSessionId = multi.activeAudioSessionId,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(18.dp)
            )
        }

        PlaybackOsdOverlay(
            visible = osdVisible,
            showDebugHud = debugHudVisible,
            sessions = sessions,
            castPlaybackState = castState,
            castProgress = castProgress,
            recordingState = recording,
            focusedSessionId = selectedSession?.sessionId,
            activeAudioSessionId = multi.activeAudioSessionId,
            fullScreenFocus = fullScreenFocus,
            onRecord = { sessionId -> viewModel.toggleRecording(sessionId) },
            onRotate = { sessionId -> viewModel.rotateSession(sessionId) },
            onCycleAspect = { sessionId -> viewModel.cycleAspectRatio(sessionId) },
            onSetActiveAudio = { sessionId ->
                selectedSessionId = sessionId
                viewModel.setActiveAudioSession(sessionId)
            },
            onSetAudioMuted = { sessionId, muted -> viewModel.setAudioMuted(sessionId, muted) },
            onToggleLayout = { fullScreenFocus = !fullScreenFocus },
            onToggleDebug = { debugHudVisible = !debugHudVisible },
            onPlayPause = viewModel::togglePlayPause,
            onSeekBack = { viewModel.seekBy(-10_000L) },
            onSeekForward = { viewModel.seekBy(10_000L) },
            onVolumeChange = viewModel::setCastVolume,
            onToggleCastMute = viewModel::toggleCastMute,
            onDisconnect = {
                val session = selectedSession
                if (session != null) {
                    viewModel.kickSession(session.sessionId)
                } else {
                    viewModel.stopCast()
                }
            }
        )
    }
}

@Composable
private fun MirroringGrid(
    state: MultiScreenState,
    selectedSessionId: String?,
    recordingState: RecordingState,
    activeAudioSessionId: String?,
    fullScreenFocus: Boolean,
    cellFocusRequesters: MutableMap<String, FocusRequester>,
    viewModel: MainViewModel,
    onCellFocused: (String) -> Unit
) {
    val sessions = state.sessions
    if (sessions.isEmpty()) return
    if (fullScreenFocus && sessions.size > 1) {
        val session = sessions.firstOrNull { it.sessionId == selectedSessionId } ?: sessions.first()
        key(session.sessionId) {
            SessionCell(
                session = session,
                selected = true,
                isAudioActive = session.sessionId == activeAudioSessionId,
                recordingState = recordingState,
                viewModel = viewModel,
                focusRequester = rememberCellFocusRequester(session.sessionId, cellFocusRequesters),
                onFocused = { onCellFocused(session.sessionId) },
                modifier = Modifier.fillMaxSize()
            )
        }
        return
    }
    when (state.layoutMode) {
        LayoutMode.SINGLE_1X1, LayoutMode.IDLE -> {
            sessions.firstOrNull()?.let { session ->
                key(session.sessionId) {
                    SessionCell(
                        session = session,
                        selected = session.sessionId == selectedSessionId,
                        isAudioActive = session.sessionId == activeAudioSessionId,
                        recordingState = recordingState,
                        viewModel = viewModel,
                        focusRequester = rememberCellFocusRequester(session.sessionId, cellFocusRequesters),
                        onFocused = { onCellFocused(session.sessionId) },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        LayoutMode.SPLIT_1X2 -> Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            sessions.take(2).forEach { session ->
                key(session.sessionId) {
                    SessionCell(
                        session = session,
                        selected = session.sessionId == selectedSessionId,
                        isAudioActive = session.sessionId == activeAudioSessionId,
                        recordingState = recordingState,
                        viewModel = viewModel,
                        focusRequester = rememberCellFocusRequester(session.sessionId, cellFocusRequesters),
                        onFocused = { onCellFocused(session.sessionId) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                    )
                }
            }
        }
        LayoutMode.QUAD_2X2 -> Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            for (rowIndex in 0..1) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    for (columnIndex in 0..1) {
                        val session = sessions.getOrNull(rowIndex * 2 + columnIndex)
                        if (session != null) {
                            key(session.sessionId) {
                                SessionCell(
                                    session = session,
                                    selected = session.sessionId == selectedSessionId,
                                    isAudioActive = session.sessionId == activeAudioSessionId,
                                    recordingState = recordingState,
                                    viewModel = viewModel,
                                    focusRequester = rememberCellFocusRequester(session.sessionId, cellFocusRequesters),
                                    onFocused = { onCellFocused(session.sessionId) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxSize()
                                )
                            }
                        } else {
                            Spacer(
                                Modifier
                                    .weight(1f)
                                    .fillMaxSize()
                                    .background(Color.Black)
                            )
                        }
                    }
                }
            }
        }
        LayoutMode.PIP -> {
            Box(Modifier.fillMaxSize()) {
                sessions.firstOrNull()?.let { session ->
                    key(session.sessionId) {
                        SessionCell(
                            session = session,
                            selected = session.sessionId == selectedSessionId,
                            isAudioActive = session.sessionId == activeAudioSessionId,
                            recordingState = recordingState,
                            viewModel = viewModel,
                            focusRequester = rememberCellFocusRequester(session.sessionId, cellFocusRequesters),
                            onFocused = { onCellFocused(session.sessionId) },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
                sessions.getOrNull(1)?.let { session ->
                    key(session.sessionId) {
                        SessionCell(
                            session = session,
                            selected = session.sessionId == selectedSessionId,
                            isAudioActive = session.sessionId == activeAudioSessionId,
                            recordingState = recordingState,
                            viewModel = viewModel,
                            focusRequester = rememberCellFocusRequester(session.sessionId, cellFocusRequesters),
                            onFocused = { onCellFocused(session.sessionId) },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(24.dp)
                                .size(width = 360.dp, height = 202.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionCell(
    session: ClientSession,
    selected: Boolean,
    isAudioActive: Boolean,
    recordingState: RecordingState,
    viewModel: MainViewModel,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isRecording = recordingState is RecordingState.Recording &&
        recordingState.sessionId == session.sessionId
    Box(
        modifier = modifier
            .background(Color.Black)
            .border(
                width = if (selected) 2.5.dp else 2.dp,
                color = if (selected) TvColors.AccentCyan else TvColors.CardBorder,
                shape = RoundedCornerShape(2.dp)
            )
            .focusRequester(focusRequester)
            .onFocusChanged { if (it.isFocused) onFocused() }
            .focusable()
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val cellAspect = maxWidth.value / maxHeight.value.coerceAtLeast(1f)
            val rawAspect = if (session.metrics.height > 0) {
                session.metrics.width.toFloat() / session.metrics.height
            } else {
                16f / 9f
            }
            val rotated = session.rotationDegrees == 90 || session.rotationDegrees == 270
            val streamAspect = if (rotated) 1f / rawAspect else rawAspect
            val (scaleX, scaleY) = when (session.aspectRatioMode) {
                AspectRatioMode.FIT -> if (streamAspect > cellAspect) {
                    1f to (cellAspect / streamAspect)
                } else {
                    (streamAspect / cellAspect) to 1f
                }
                AspectRatioMode.FILL -> if (streamAspect > cellAspect) {
                    (streamAspect / cellAspect) to 1f
                } else {
                    1f to (cellAspect / streamAspect)
                }
                AspectRatioMode.STRETCH_16_9 -> 1f to 1f
            }
            AndroidView(
                factory = { context ->
                    SurfaceView(context).apply {
                        holder.setFormat(PixelFormat.TRANSLUCENT)
                        holder.addCallback(object : SurfaceHolder.Callback {
                            override fun surfaceCreated(holder: SurfaceHolder) {
                                viewModel.bindSurface(session.sessionId, holder.surface)
                            }

                            override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int
                            ) = Unit

                            override fun surfaceDestroyed(holder: SurfaceHolder) {
                                viewModel.unbindSurface(session.sessionId)
                            }
                        })
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        rotationZ = session.rotationDegrees.toFloat(),
                        scaleX = scaleX,
                        scaleY = scaleY
                    )
            )
        }
        Row(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(Color.Black.copy(alpha = 0.66f))
                .border(
                    1.dp,
                    if (selected) TvColors.AccentCyan.copy(alpha = 0.7f) else TvColors.CardBorder,
                    RoundedCornerShape(7.dp)
                )
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                session.clientName,
                color = TvColors.TextPrimary,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
            if (isAudioActive) {
                Spacer(Modifier.width(6.dp))
                Text(
                    if (session.audioMuted) "\uD83D\uDD07" else "\uD83D\uDD0A",
                    fontSize = 12.sp,
                    color = TvColors.AccentCyan
                )
            }
            if (isRecording) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "REC",
                    color = TvColors.DangerRed,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .background(
                            TvColors.DangerRed.copy(alpha = 0.16f),
                            RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }
    }
    DisposableEffect(session.sessionId) {
        onDispose { viewModel.unbindSurface(session.sessionId) }
    }
}

@OptIn(UnstableApi::class)
@Composable
private fun CastStage(state: CastPlaybackState, viewModel: MainViewModel) {
    when (state) {
        is CastPlaybackState.PhotoDisplayed -> {
            Crossfade(
                targetState = state.bitmap,
                animationSpec = tween(350),
                label = "photo-crossfade"
            ) { bitmap ->
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Cast photo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black)
                )
            }
        }
        is CastPlaybackState.Loading, is CastPlaybackState.Playing,
        is CastPlaybackState.Paused -> {
            val player = viewModel.exoPlayer
            if (player == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Preparing player…", color = TvColors.TextSecondary)
                }
            } else {
                AndroidView(
                    factory = { context ->
                        PlayerView(context).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            keepScreenOn = true
                        }
                    },
                    update = { view ->
                        view.player = player
                        view.useController = false
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        else -> Box(Modifier.fillMaxSize())
    }
}

@Composable
private fun rememberCellFocusRequester(
    sessionId: String,
    cache: MutableMap<String, FocusRequester>
): FocusRequester = remember(sessionId) {
    cache.getOrPut(sessionId) { FocusRequester() }
}