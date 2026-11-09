package com.airscreen.tv.ui.stage

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.airscreen.tv.domain.model.ClientSession
import com.airscreen.tv.domain.model.RecordingState
import com.airscreen.tv.ui.theme.TvColors
import java.util.Locale

@Composable
fun DebugHudOverlay(
    sessions: List<ClientSession>,
    recordingState: RecordingState,
    activeAudioSessionId: String?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .width(352.dp)
            .background(Color.Black.copy(alpha = 0.78f), RoundedCornerShape(8.dp))
            .border(1.dp, TvColors.AccentCyan.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(
            "STREAM TELEMETRY",
            color = TvColors.AccentCyan,
            style = MaterialTheme.typography.labelSmall
        )
        if (sessions.isEmpty()) {
            Text(
                "No mirroring streams active",
                color = TvColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 6.dp)
            )
        } else {
            sessions.forEach { session ->
                val metrics = session.streamMetrics
                val decoder = session.videoDecoder
                val codec = when {
                    metrics.codec.contains("hevc", true) ||
                        metrics.codec.contains("265", true) -> "HEVC"
                    metrics.codec.isNotBlank() -> "H.264"
                    else -> "—"
                }
                val decoderKind = when {
                    decoder == null -> "Decoder pending"
                    decoder.isHardwareDecoder -> "Hardware MediaCodec"
                    else -> "Software"
                }
                val recording = recordingState is RecordingState.Recording &&
                    recordingState.sessionId == session.sessionId
                val audio = session.sessionId == activeAudioSessionId
                Column(
                    modifier = Modifier.padding(top = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp)
                ) {
                    Text(
                        "Client: " + session.clientName + " (" +
                            session.clientIp + ")" + if (audio) "  ♪" else "",
                        color = TvColors.TextPrimary,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        "Codec: $codec ($decoderKind)",
                        color = TvColors.TextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        "Resolution: " + metrics.width + "x" + metrics.height +
                            " @ " + String.format(Locale.US, "%.1f", metrics.fps) + " fps",
                        color = TvColors.TextSecondary,
                        style = MaterialTheme.typography.labelSmall
                    )
                    Text(
                        "Bitrate: " + String.format(Locale.US, "%.0f", metrics.kbps) +
                            " kbps | Recording: " + if (recording) "Active" else "Idle",
                        color = if (recording) TvColors.DangerRed else TvColors.TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

