package com.airscreen.tv.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.airscreen.tv.domain.model.AppSettings
import com.airscreen.tv.domain.model.FpsCap
import com.airscreen.tv.domain.model.MaxResolution
import com.airscreen.tv.ui.theme.TvColors
import com.airscreen.tv.ui.theme.tvFocusable
import kotlinx.coroutines.delay

@Composable
fun ProSettingsDrawer(
    visible: Boolean,
    settings: AppSettings,
    onDismiss: () -> Unit,
    onDeviceNameChange: (String) -> Unit,
    onSelectResolution: (MaxResolution) -> Unit,
    onSelectFps: (FpsCap) -> Unit,
    onToggleHardwareAcceleration: () -> Unit,
    onToggleAutoStart: () -> Unit,
    onTogglePinSecurity: () -> Unit
) {
    var rendered by remember { mutableStateOf(visible) }
    var closeFocused by remember { mutableStateOf(false) }
    var editingName by remember { mutableStateOf(false) }
    var editName by remember(settings.deviceName) { mutableStateOf(settings.deviceName) }
    val nameFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val initialFocus = remember { FocusRequester() }

    LaunchedEffect(visible) {
        if (visible) {
            rendered = true
        } else {
            delay(260L)
            rendered = false
        }
    }
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        repeat(10) {
            withFrameNanos { }
            if (closeFocused) return@LaunchedEffect
            runCatching { initialFocus.requestFocus() }
            delay(120L)
        }
    }
    LaunchedEffect(editingName) {
        if (editingName) {
            withFrameNanos { }
            delay(120L)
            runCatching { nameFocus.requestFocus() }
        }
    }
    if (!rendered) return

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.62f))
                .padding(horizontal = 48.dp, vertical = 27.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyUp && event.key == Key.Back) {
                        onDismiss()
                        true
                    } else {
                        false
                    }
                },
            contentAlignment = Alignment.CenterEnd
        ) {
            AnimatedVisibility(
                visible = visible,
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .width(420.dp)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(24.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(TvColors.SurfaceFocused, TvColors.SurfaceCard)
                            )
                        )
                        .border(
                            1.5.dp,
                            TvColors.AccentCyan.copy(alpha = 0.65f),
                            RoundedCornerShape(24.dp)
                        )
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Settings",
                                style = MaterialTheme.typography.headlineLarge,
                                color = TvColors.TextPrimary
                            )
                            Text(
                                "Receiver, streaming & security",
                                style = MaterialTheme.typography.labelSmall,
                                color = TvColors.TextSecondary
                            )
                        }
                        DrawerButton(
                            label = "Close",
                            onClick = onDismiss,
                            modifier = Modifier
                                .onFocusChanged { closeFocused = it.isFocused }
                                .focusRequester(initialFocus)
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            Spacer(Modifier.height(4.dp))
                            SectionLabel("Video & Decoder")
                            Text(
                                "Max resolution",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TvColors.TextPrimary
                            )
                            Spacer(Modifier.height(4.dp))
                            SelectorOption(
                                "Auto (sender native)",
                                settings.maxResolution == MaxResolution.AUTO
                            ) { onSelectResolution(MaxResolution.AUTO) }
                            SelectorOption(
                                "4K UHD 2160p",
                                settings.maxResolution == MaxResolution.UHD_4K
                            ) { onSelectResolution(MaxResolution.UHD_4K) }
                            SelectorOption(
                                "1080p Full HD",
                                settings.maxResolution == MaxResolution.FHD_1080P
                            ) { onSelectResolution(MaxResolution.FHD_1080P) }
                            SelectorOption(
                                "720p HD",
                                settings.maxResolution == MaxResolution.HD_720P
                            ) { onSelectResolution(MaxResolution.HD_720P) }
                        }
                        item {
                            Text(
                                "Frame rate cap",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TvColors.TextPrimary
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                SelectorPill(
                                    "120 FPS",
                                    settings.fpsCap == FpsCap.FPS_120,
                                    Modifier.weight(1f)
                                ) { onSelectFps(FpsCap.FPS_120) }
                                SelectorPill(
                                    "60 FPS",
                                    settings.fpsCap == FpsCap.FPS_60,
                                    Modifier.weight(1f)
                                ) { onSelectFps(FpsCap.FPS_60) }
                                SelectorPill(
                                    "30 FPS",
                                    settings.fpsCap == FpsCap.FPS_30,
                                    Modifier.weight(1f)
                                ) { onSelectFps(FpsCap.FPS_30) }
                            }
                        }
                        item {
                            ToggleRow(
                                "Hardware acceleration",
                                if (settings.hardwareDecodingEnabled) {
                                    "MediaCodec Direct Surface"
                                } else {
                                    "Software Fallback"
                                },
                                settings.hardwareDecodingEnabled,
                                onToggleHardwareAcceleration
                            )
                        }
                        item {
                            Spacer(Modifier.height(4.dp))
                            SectionLabel("System & Security")
                            ToggleRow(
                                "Auto-start receiver",
                                if (settings.autoStartOnBoot) "On TV boot" else "Manual start",
                                settings.autoStartOnBoot,
                                onToggleAutoStart
                            )
                        }
                        item {
                            ToggleRow(
                                "Require 4-digit PIN",
                                if (settings.pinSecurityEnabled) "PIN Protection: ON" else "PIN Protection: OFF",
                                settings.pinSecurityEnabled,
                                onTogglePinSecurity
                            )
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(label: String) {
    Text(
        label.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = TvColors.AccentCyan,
        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
    )
}

@Composable
private fun DrawerButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .tvFocusable(onClick = onClick, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = TvColors.TextPrimary,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun PresetChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .tvFocusable(onClick = onClick, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 6.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = TvColors.AccentCyan,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2
        )
    }
}

@Composable
private fun SelectorOption(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .tvFocusable(onClick = onClick, shape = RoundedCornerShape(10.dp))
            .then(
                if (selected) {
                    Modifier.border(
                        1.5.dp,
                        TvColors.AccentCyan,
                        RoundedCornerShape(10.dp)
                    )
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = if (selected) TvColors.AccentCyan else TvColors.TextPrimary,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun SelectorPill(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .tvFocusable(onClick = onClick, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) TvColors.AccentCyan else TvColors.TextPrimary,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun ToggleRow(
    label: String,
    stateLabel: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable(onClick = onClick, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodyMedium,
                color = TvColors.TextPrimary
            )
            Text(
                stateLabel,
                style = MaterialTheme.typography.labelSmall,
                color = TvColors.TextSecondary
            )
        }
        Box(
            modifier = Modifier
                .width(38.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(50))
                .background(if (enabled) TvColors.AccentCyan else TvColors.CardBorder)
                .padding(3.dp),
            contentAlignment = if (enabled) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                modifier = Modifier
                    .width(16.dp)
                    .height(16.dp)
                    .background(TvColors.BgPrimary, RoundedCornerShape(50))
            )
        }
    }
}