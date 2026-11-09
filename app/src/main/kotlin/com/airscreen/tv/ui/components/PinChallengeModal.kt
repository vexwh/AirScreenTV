package com.airscreen.tv.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.airscreen.tv.domain.model.PinChallengeState
import com.airscreen.tv.ui.theme.TvColors
import com.airscreen.tv.ui.theme.tvFocusable
import kotlinx.coroutines.delay

@Composable
fun PinChallengeModal(
    state: PinChallengeState,
    onAllowOnce: () -> Unit,
    onAlwaysTrust: () -> Unit,
    onBlockDevice: () -> Unit
) {
    val visible = state as? PinChallengeState.Visible ?: return
    val focusRequester = remember(visible.challengeId) { FocusRequester() }
    var allowFocused by remember(visible.challengeId) { mutableStateOf(false) }
    val progress by animateFloatAsState(
        targetValue = (visible.secondsRemaining / 60f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 550, easing = LinearEasing),
        label = "pin-countdown"
    )
    LaunchedEffect(visible.challengeId) {
        repeat(10) {
            withFrameNanos { }
            if (allowFocused) return@LaunchedEffect
            runCatching { focusRequester.requestFocus() }
            delay(120L)
        }
    }
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.74f))
                .padding(36.dp)
                .onPreviewKeyEvent { event ->
                    event.type == KeyEventType.KeyUp && event.key == Key.Back
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .width(760.dp)
                    .background(TvColors.SurfaceFocused, RoundedCornerShape(24.dp))
                    .border(2.dp, TvColors.AccentCyan, RoundedCornerShape(24.dp))
                    .padding(horizontal = 32.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "Incoming connection",
                    style = MaterialTheme.typography.labelSmall,
                    color = TvColors.TextSecondary
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    visible.clientName,
                    style = MaterialTheme.typography.headlineLarge,
                    color = TvColors.TextPrimary,
                    maxLines = 1
                )
                Text(
                    visible.ipAddress,
                    style = MaterialTheme.typography.labelSmall,
                    color = TvColors.AccentCyan
                )
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    visible.pinCode.take(4).forEach { digit ->
                        PinDigitBox(digit)
                    }
                }
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .background(TvColors.CardBorder, RoundedCornerShape(50))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .height(8.dp)
                            .background(
                                if (visible.secondsRemaining <= 10) {
                                    TvColors.DangerRed
                                } else {
                                    TvColors.AccentCyan
                                },
                                RoundedCornerShape(50)
                            )
                    )
                }
                Spacer(Modifier.height(5.dp))
                Text(
                    visible.secondsRemaining.toString() + " seconds remaining",
                    style = MaterialTheme.typography.labelSmall,
                    color = TvColors.TextSecondary
                )
                Spacer(Modifier.height(18.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PinActionButton(
                        label = "Allow Once",
                        onClick = onAllowOnce,
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { allowFocused = it.isFocused }
                            .focusRequester(focusRequester)
                    )
                    PinActionButton(
                        label = "Always Trust Device",
                        onClick = onAlwaysTrust,
                        modifier = Modifier.weight(1f)
                    )
                    PinActionButton(
                        label = "Block Device",
                        onClick = onBlockDevice,
                        destructive = true,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun PinDigitBox(digit: Char) {
    Box(
        modifier = Modifier
            .size(width = 74.dp, height = 88.dp)
            .background(TvColors.BgPrimary, RoundedCornerShape(12.dp))
            .border(1.5.dp, TvColors.AccentCyan.copy(alpha = 0.7f), RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(
            digit.toString(),
            fontSize = 56.sp,
            lineHeight = 58.sp,
            fontWeight = FontWeight.Bold,
            color = TvColors.AccentCyan
        )
    }
}

@Composable
private fun PinActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false
) {
    Box(
        modifier = modifier
            .tvFocusable(onClick = onClick, shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (destructive) TvColors.DangerRed else TvColors.TextPrimary,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}
