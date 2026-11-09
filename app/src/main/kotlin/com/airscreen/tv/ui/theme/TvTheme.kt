package com.airscreen.tv.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme

object TvColors {
    val BgPrimary = Color(0xFF070B14)
    val SurfaceCard = Color(0xFF111827)
    val SurfaceFocused = Color(0xFF1E293B)
    val AccentCyan = Color(0xFF38BDF8)
    val AccentIndigo = Color(0xFF6366F1)
    val StatusReady = Color(0xFF22C55E)
    val DangerRed = Color(0xFFEF4444)
    val TextPrimary = Color(0xFFF8FAFC)
    val TextSecondary = Color(0xFF94A3B8)
    val CardBorder = Color(0xFF1F2937)

    // Compatibility aliases used by the Phase 5B stage, gallery and modals.
    val Background = BgPrimary
    val Surface = SurfaceCard
    val SurfaceHighlight = SurfaceFocused
    val StatusGreen = StatusReady
    val RecordingRed = DangerRed
    val BorderMuted = CardBorder
}

private val TvColorScheme = darkColorScheme(
    primary = TvColors.AccentCyan,
    onPrimary = TvColors.BgPrimary,
    secondary = TvColors.AccentIndigo,
    onSecondary = TvColors.TextPrimary,
    background = TvColors.BgPrimary,
    onBackground = TvColors.TextPrimary,
    surface = TvColors.SurfaceCard,
    onSurface = TvColors.TextPrimary,
    surfaceVariant = TvColors.SurfaceFocused,
    onSurfaceVariant = TvColors.TextSecondary,
    error = TvColors.DangerRed,
    onError = TvColors.TextPrimary
)

@Composable
fun AirScreenTvTheme(content: @Composable () -> Unit) {
    val base: Typography = MaterialTheme.typography
    val typography = base.copy(
        headlineLarge = base.headlineLarge.copy(
            fontSize = 32.sp,
            lineHeight = 38.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.4).sp
        ),
        titleMedium = base.titleMedium.copy(
            fontSize = 20.sp,
            lineHeight = 25.sp,
            fontWeight = FontWeight.SemiBold
        ),
        bodyMedium = base.bodyMedium.copy(
            fontSize = 16.sp,
            lineHeight = 22.sp
        ),
        labelSmall = base.labelSmall.copy(
            fontSize = 13.sp,
            lineHeight = 17.sp,
            fontFamily = FontFamily.Monospace
        )
    )
    MaterialTheme(
        colorScheme = TvColorScheme,
        typography = typography,
        content = content
    )
}

fun Modifier.tvFocusable(
    onClick: () -> Unit,
    shape: Shape = RoundedCornerShape(16.dp)
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "tv-focus-scale"
    )
    this
        .onFocusChanged { focused = it.isFocused }
        .scale(scale)
        .clip(shape)
        .background(if (focused) TvColors.SurfaceFocused else TvColors.SurfaceCard, shape)
        .border(
            width = if (focused) 2.5.dp else 1.dp,
            color = if (focused) TvColors.AccentCyan else TvColors.CardBorder,
            shape = shape
        )
        .focusable()
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyUp) {
                false
            } else if (event.key == Key.DirectionCenter || event.key == Key.Enter) {
                onClick()
                true
            } else {
                false
            }
        }
        .pointerInput(onClick) {
            detectTapGestures(onTap = { onClick() })
        }
}

/**
 * Visual-only focus treatment for existing call sites that provide their own
 * focusable clickable child. New code should prefer [tvFocusable].
 */
fun Modifier.tvFocusableCard(
    shape: Shape = RoundedCornerShape(20.dp)
): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.04f else 1f,
        animationSpec = tween(durationMillis = 150),
        label = "tv-focus-scale-card"
    )
    this
        .onFocusChanged { focused = it.isFocused }
        .scale(scale)
        .clip(shape)
        .background(if (focused) TvColors.SurfaceFocused else TvColors.SurfaceCard, shape)
        .border(
            width = if (focused) 2.5.dp else 1.dp,
            color = if (focused) TvColors.AccentCyan else TvColors.CardBorder,
            shape = shape
        )
}

@Composable
fun OverscanSafeBox(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(TvColors.BgPrimary)
            .padding(horizontal = 48.dp, vertical = 27.dp),
        content = content
    )
}