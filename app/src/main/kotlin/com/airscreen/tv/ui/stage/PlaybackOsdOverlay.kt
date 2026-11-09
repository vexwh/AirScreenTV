package com.airscreen.tv.ui.stage

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.airscreen.tv.domain.model.CastPlaybackState
import com.airscreen.tv.domain.model.ClientSession
import com.airscreen.tv.domain.model.PlaybackProgress
import com.airscreen.tv.domain.model.RecordingState
import com.airscreen.tv.ui.theme.TvColors
import com.airscreen.tv.ui.theme.tvFocusable
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun PlaybackOsdOverlay(
    visible: Boolean,
    showDebugHud: Boolean,
    sessions: List<ClientSession>,
    castPlaybackState: CastPlaybackState,
    castProgress: PlaybackProgress,
    recordingState: RecordingState,
    focusedSessionId: String?,
    activeAudioSessionId: String?,
    fullScreenFocus: Boolean,
    onRecord: (String) -> Unit,
    onRotate: (String) -> Unit,
    onCycleAspect: (String) -> Unit,
    onSetActiveAudio: (String) -> Unit,
    onSetAudioMuted: (String, Boolean) -> Unit,
    onToggleLayout: () -> Unit,
    onToggleDebug: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onVolumeChange: (Int) -> Unit,
    onToggleCastMute: () -> Unit,
    onDisconnect: () -> Unit
) {
    if (!visible) return
    val focusedSession = sessions.firstOrNull { it.sessionId == focusedSessionId }
        ?: sessions.firstOrNull()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(visible, focusedSession?.sessionId) {
        withFrameNanos { }
        delay(120L)
        runCatching { focusRequester.requestFocus() }
    }
    val recordActive = focusedSession != null &&
        recordingState is RecordingState.Recording &&
        (recordingState as RecordingState.Recording).sessionId == focusedSession.sessionId
    val castActive = castPlaybackState is CastPlaybackState.Playing ||
        castPlaybackState is CastPlaybackState.Paused ||
        castPlaybackState is CastPlaybackState.Loading

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.9f)),
                    startY = 350f
                )
            )
            .padding(horizontal = 30.dp, vertical = 18.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                focusedSession != null -> MirroringControls(
                    session = focusedSession,
                    recordActive = recordActive,
                    recordingState = recordingState,
                    activeAudioSessionId = activeAudioSessionId,
                    fullScreenFocus = fullScreenFocus,
                    showDebugHud = showDebugHud,
                    sessionsCount = sessions.size,
                    focusRequester = focusRequester,
                    onRecord = onRecord,
                    onRotate = onRotate,
                    onCycleAspect = onCycleAspect,
                    onSetActiveAudio = onSetActiveAudio,
                    onSetAudioMuted = onSetAudioMuted,
                    onToggleLayout = onToggleLayout,
                    onToggleDebug = onToggleDebug,
                    onDisconnect = onDisconnect
                )
                castPlaybackState is CastPlaybackState.PhotoDisplayed -> Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OsdButton(if (showDebugHud) "Hide Debug" else "Debug HUD", onToggleDebug)
                    OsdButton(
                        "Stop Casting",
                        onClick = onDisconnect,
                        modifier = Modifier.focusRequester(focusRequester)
                    )
                }
                castActive -> CastControls(
                    state = castPlaybackState,
                    progress = castProgress,
                    showDebugHud = showDebugHud,
                    focusRequester = focusRequester,
                    onPlayPause = onPlayPause,
                    onSeekBack = onSeekBack,
                    onSeekForward = onSeekForward,
                    onVolumeChange = onVolumeChange,
                    onToggleCastMute = onToggleCastMute,
                    onToggleDebug = onToggleDebug,
                    onDisconnect = onDisconnect
                )
                else -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OsdButton("Stop Casting", onDisconnect, Modifier.focusRequester(focusRequester))
                }
            }
            Text(
                "BACK hides OSD · press BACK again to disconnect",
                color = TvColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun MirroringControls(
    session: ClientSession,
    recordActive: Boolean,
    recordingState: RecordingState,
    activeAudioSessionId: String?,
    fullScreenFocus: Boolean,
    showDebugHud: Boolean,
    sessionsCount: Int,
    focusRequester: FocusRequester,
    onRecord: (String) -> Unit,
    onRotate: (String) -> Unit,
    onCycleAspect: (String) -> Unit,
    onSetActiveAudio: (String) -> Unit,
    onSetAudioMuted: (String, Boolean) -> Unit,
    onToggleLayout: () -> Unit,
    onToggleDebug: () -> Unit,
    onDisconnect: () -> Unit
) {
    val isActiveAudio = session.sessionId == activeAudioSessionId
    val audioLabel = when {
        session.audioMuted -> "Unmute"
        !isActiveAudio -> "Audio Focus"
        else -> "Mute"
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        OsdButton(
            label = if (recordActive) {
                "Stop  " + formatRecordClock(
                    (recordingState as? RecordingState.Recording)?.elapsedMs ?: 0L
                )
            } else {
                "Record"
            },
            onClick = { onRecord(session.sessionId) },
            modifier = Modifier.focusRequester(focusRequester),
            active = recordActive
        )
        OsdButton("Rotate") { onRotate(session.sessionId) }
        OsdButton("Aspect") { onCycleAspect(session.sessionId) }
        OsdButton(audioLabel) {
            when {
                session.audioMuted -> onSetAudioMuted(session.sessionId, false)
                !isActiveAudio -> onSetActiveAudio(session.sessionId)
                else -> onSetAudioMuted(session.sessionId, true)
            }
        }
        if (sessionsCount > 1) {
            OsdButton(if (fullScreenFocus) "Grid" else "Focus", onToggleLayout)
        }
        OsdButton(if (showDebugHud) "Hide Debug" else "Debug HUD", onToggleDebug)
        OsdButton("Disconnect") { onDisconnect() }
    }
}

@Composable
private fun CastControls(
    state: CastPlaybackState,
    progress: PlaybackProgress,
    showDebugHud: Boolean,
    focusRequester: FocusRequester,
    onPlayPause: () -> Unit,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onVolumeChange: (Int) -> Unit,
    onToggleCastMute: () -> Unit,
    onToggleDebug: () -> Unit,
    onDisconnect: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CastSeekBar(
            progress = progress,
            onSeekBack = onSeekBack,
            onSeekForward = onSeekForward,
            onPlayPause = onPlayPause,
            modifier = Modifier.focusRequester(focusRequester)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                formatHms(progress.positionMs) + " / " + formatHms(progress.durationMs),
                color = TvColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(Modifier.weight(1f))
            VolumeSlider(
                volume = progress.volumePercent,
                muted = progress.isMuted,
                onVolumeChange = onVolumeChange,
                onToggleMute = onToggleCastMute
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            OsdButton("-10s") { onSeekBack() }
            OsdButton(
                if (state is CastPlaybackState.Playing) "Pause" else "Play",
                onClick = onPlayPause
            )
            OsdButton("+10s") { onSeekForward() }
            OsdButton(if (showDebugHud) "Hide Debug" else "Debug HUD", onToggleDebug)
            OsdButton("Stop Casting") { onDisconnect() }
        }
    }
}

@Composable
private fun CastSeekBar(
    progress: PlaybackProgress,
    onSeekBack: () -> Unit,
    onSeekForward: () -> Unit,
    onPlayPause: () -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val fraction = if (progress.durationMs > 0L) {
        (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f)
    } else {
        0f
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(22.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) {
                    false
                } else {
                    when (event.key) {
                        Key.DirectionLeft -> { onSeekBack(); true }
                        Key.DirectionRight -> { onSeekForward(); true }
                        Key.DirectionCenter, Key.Enter -> { onPlayPause(); true }
                        else -> false
                    }
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(TvColors.CardBorder, RoundedCornerShape(50))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .height(6.dp)
                    .background(TvColors.AccentCyan, RoundedCornerShape(50))
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(22.dp)
                .border(
                    if (focused) 1.5.dp else 0.dp,
                    if (focused) TvColors.AccentCyan else Color.Transparent,
                    RoundedCornerShape(8.dp)
                )
        )
    }
}

@Composable
private fun VolumeSlider(
    volume: Int,
    muted: Boolean,
    onVolumeChange: (Int) -> Unit,
    onToggleMute: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val actual = if (muted) 0 else volume
    Row(
        modifier = Modifier
            .width(250.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyUp) {
                    false
                } else {
                    when (event.key) {
                        Key.DirectionLeft -> {
                            onVolumeChange((actual - 5).coerceAtLeast(0))
                            true
                        }
                        Key.DirectionRight -> {
                            onVolumeChange((actual + 5).coerceAtMost(100))
                            true
                        }
                        Key.DirectionCenter, Key.Enter -> {
                            onToggleMute()
                            true
                        }
                        else -> false
                    }
                }
            }
            .padding(horizontal = 6.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            if (muted) "Muted" else "Vol " + actual + "%",
            color = if (focused) TvColors.AccentCyan else TvColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall
        )
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(5.dp)
                .background(TvColors.CardBorder, RoundedCornerShape(50))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(actual / 100f)
                    .height(5.dp)
                    .background(
                        if (muted) TvColors.TextSecondary else TvColors.AccentCyan,
                        RoundedCornerShape(50)
                    )
            )
        }
    }
}

@Composable
private fun OsdButton(label: String, onClick: () -> Unit) {
    OsdButton(label = label, onClick = onClick, modifier = Modifier, active = false)
}

@Composable
private fun OsdButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false
) {
    Row(
        modifier = modifier
            .tvFocusable(onClick = onClick, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (active) {
            PulsingRecordDot()
            Spacer(Modifier.width(5.dp))
        }
        Text(
            label,
            color = if (active) TvColors.DangerRed else TvColors.TextPrimary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

@Composable
private fun PulsingRecordDot() {
    val transition = rememberInfiniteTransition(label = "record-dot")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "record-alpha"
    )
    Box(
        modifier = Modifier
            .size(9.dp)
            .background(TvColors.DangerRed.copy(alpha = alpha), CircleShape)
    )
}

private fun formatRecordClock(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1000L
    return String.format(Locale.US, "%02d:%02d", totalSeconds / 60L, totalSeconds % 60L)
}

private fun formatHms(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
}