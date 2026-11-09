package com.airscreen.tv.ui.dashboard

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.airscreen.tv.domain.model.AppSettings
import com.airscreen.tv.domain.model.MaxResolution
import com.airscreen.tv.airplay2.AirPlayEngineController
import com.airscreen.tv.domain.model.DiscoveryStatus
import com.airscreen.tv.domain.model.MultiScreenState
import com.airscreen.tv.domain.model.RecordedVideoItem
import com.airscreen.tv.domain.model.RecordingState
import com.airscreen.tv.domain.model.SessionInfo
import com.airscreen.tv.ui.theme.OverscanSafeBox
import com.airscreen.tv.ui.theme.TvColors
import com.airscreen.tv.ui.theme.tvFocusable
import com.airscreen.tv.ui.viewmodel.MainViewModel
import kotlinx.coroutines.delay
import java.util.Locale

@Composable
fun HomeDashboardRoute(
    viewModel: MainViewModel,
    onOpenRecordings: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val discovery by viewModel.discoveryStatus.collectAsStateWithLifecycle()
    val multiScreen by viewModel.multiScreenState.collectAsStateWithLifecycle()
    val recording by viewModel.recordingState.collectAsStateWithLifecycle()
    val settings by viewModel.appSettings.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()

    HomeDashboardScreen(
        discoveryStatus = discovery,
        multiScreenState = multiScreen,
        recordingState = recording,
        appSettings = settings,
        recordings = recordings,
        onUpdateDeviceName = viewModel::updateDeviceName,
        onToggleRecording = viewModel::toggleRecording,
        onRotateSession = viewModel::rotateSession,
        onCycleAspectRatio = viewModel::cycleAspectRatio,
        onKickSession = viewModel::kickSession,
        onOpenRecordings = onOpenRecordings,
        onOpenSettings = onOpenSettings
    )
}

@Composable
fun HomeDashboardScreen(
    discoveryStatus: DiscoveryStatus,
    multiScreenState: MultiScreenState,
    recordingState: RecordingState,
    appSettings: AppSettings,
    recordings: List<RecordedVideoItem>,
    onUpdateDeviceName: (String) -> Unit,
    onToggleRecording: (String) -> Unit,
    onRotateSession: (String) -> Unit,
    onCycleAspectRatio: (String) -> Unit,
    onKickSession: (String) -> Unit,
    onOpenRecordings: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val engineReady by AirPlayEngineController.engineReady.collectAsStateWithLifecycle()
    var showRename by remember { mutableStateOf(false) }
    val primaryFocus = remember { FocusRequester() }
    val context = LocalContext.current
    val castTargetName = remember {
        runCatching {
            Settings.Global.getString(context.contentResolver, "device_name")
        }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
            ?: Build.MODEL
    }
    val airPlayResolution = when (appSettings.maxResolution) {
        MaxResolution.AUTO -> "Auto"
        MaxResolution.UHD_4K -> "4K UHD"
        MaxResolution.FHD_1080P -> "1080p"
        MaxResolution.HD_720P -> "720p"
    }
    val airPlayDetail = airPlayResolution + " • " + appSettings.fpsCap.fps + " FPS"

    LaunchedEffect(Unit) {
        withFrameNanos { }
        delay(120L)
        runCatching { primaryFocus.requestFocus() }
    }
    LaunchedEffect(showRename) {
        if (!showRename) {
            delay(80L)
            runCatching { primaryFocus.requestFocus() }
        }
    }

    OverscanSafeBox {
        Column(modifier = Modifier.fillMaxSize()) {
            DashboardHeader(
                status = discoveryStatus,
                settings = appSettings,
                engineReady = engineReady,
                onRename = { showRename = true }
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Column(
                    modifier = Modifier
                        .weight(1.7f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(22.dp))
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    TvColors.AccentCyan.copy(alpha = 0.18f),
                                    TvColors.AccentIndigo.copy(alpha = 0.12f),
                                    TvColors.SurfaceCard
                                )
                            )
                        )
                        .border(1.dp, TvColors.CardBorder, RoundedCornerShape(22.dp))
                        .padding(26.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Ready to receive",
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = TvColors.TextPrimary
                    )
                    Text(
                        "Connect from your Apple device or Android device.",
                        style = MaterialTheme.typography.titleMedium,
                        color = TvColors.TextSecondary
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusChip("AirPlay 2", discoveryStatus.airplayReady || engineReady)
                        StatusChip("Miracast", true)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        "iPhone / iPad  ·  Control Center → Screen Mirroring\nAndroid  ·  Screen Casting → " + castTargetName,
                        style = MaterialTheme.typography.bodyMedium,
                        color = TvColors.TextSecondary,
                        lineHeight = 22.sp
                    )
                }
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    DashboardActionCard(
                        title = "Settings",
                        subtitle = "Streaming, PIN & display",
                        onClick = onOpenSettings,
                        focusRequester = primaryFocus,
                        modifier = Modifier.weight(1f)
                    )
                    DashboardActionCard(
                        title = "Rename Receiver",
                        subtitle = appSettings.deviceName,
                        onClick = { showRename = true },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.height(180.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                ProtocolCard(
                    title = "AirPlay & Screen Mirroring",
                    status = "Ready (AirPlay 2)",
                    detail = airPlayDetail,
                    instructions = listOf(
                        "iPhone/iPad: Control Center > Screen Mirroring",
                    ),
                    ready = discoveryStatus.airplayReady || engineReady,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
                ProtocolCard(
                    title = "Miracast",
                    status = "Ready",
                    detail = "Android screen mirroring",
                    instructions = listOf(
                        "Open Screen Casting",
                        "Select " + castTargetName
                    ),
                    ready = true,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                )
            }
        }
    }

    if (showRename) {
        QuickRenameDialog(
            currentName = appSettings.deviceName,
            onDismiss = { showRename = false },
            onSave = { name ->
                onUpdateDeviceName(name)
                showRename = false
            }
        )
    }
}

@Composable
private fun DashboardHeader(
    status: DiscoveryStatus,
    settings: AppSettings,
    engineReady: Boolean,
    onRename: () -> Unit
) {
    val allReady = status.airplayReady || engineReady
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        Brush.horizontalGradient(
                            listOf(
                                TvColors.AccentCyan.copy(alpha = 0.18f),
                                TvColors.AccentIndigo.copy(alpha = 0.14f)
                            )
                        )
                    )
                    .border(
                        1.dp,
                        TvColors.AccentCyan.copy(alpha = 0.55f),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 5.dp)
            ) {
                Text(
                    "AirScreen TV",
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = 24.sp,
                        lineHeight = 29.sp
                    ),
                    color = TvColors.TextPrimary,
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(12.dp))
            LiveReceiversPill(allReady)
        }
        Text(
            status.ipAddress ?: "Waiting for network",
            style = MaterialTheme.typography.labelSmall,
            color = TvColors.AccentCyan,
            fontFamily = FontFamily.Monospace
        )
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .widthIn(max = 232.dp)
                .tvFocusable(onClick = onRename, shape = RoundedCornerShape(10.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                "Device Name: " + settings.deviceName,
                style = MaterialTheme.typography.bodySmall,
                color = TvColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LiveReceiversPill(active: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(TvColors.SurfaceCard.copy(alpha = 0.85f))
            .border(
                1.dp,
                if (active) TvColors.StatusReady.copy(alpha = 0.55f) else TvColors.CardBorder,
                RoundedCornerShape(50)
            )
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PulsingStatusDot(active)
        Spacer(Modifier.width(6.dp))
        Text(
            if (active) "All Receivers Active" else "Starting Receivers",
            style = MaterialTheme.typography.bodySmall,
            color = if (active) TvColors.StatusReady else TvColors.TextSecondary,
            maxLines = 1
        )
    }
}

@Composable
private fun NetworkBadge(network: String) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(TvColors.SurfaceCard.copy(alpha = 0.8f))
            .border(1.dp, TvColors.CardBorder, RoundedCornerShape(50))
            .padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .background(TvColors.AccentCyan, CircleShape)
        )
        Spacer(Modifier.width(5.dp))
        Text(
            network,
            style = MaterialTheme.typography.bodySmall,
            color = TvColors.TextSecondary,
            maxLines = 1
        )
    }
}

@Composable
private fun StatusChip(label: String, ready: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (ready) TvColors.StatusReady.copy(alpha = 0.14f) else TvColors.SurfaceCard)
            .border(1.dp, if (ready) TvColors.StatusReady.copy(alpha = 0.45f) else TvColors.CardBorder, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("●", color = if (ready) TvColors.StatusReady else TvColors.TextSecondary, style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.width(7.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = TvColors.TextPrimary)
    }
}

@Composable
private fun DashboardActionCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null
) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .tvFocusable(onClick = onClick, shape = shape)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, color = TvColors.TextPrimary)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = TvColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.weight(1f))
        Text("Open  ›", style = MaterialTheme.typography.labelMedium, color = TvColors.AccentCyan)
    }
}

@Composable
private fun ProtocolCard(
    title: String,
    status: String,
    detail: String,
    instructions: List<String>,
    ready: Boolean,
    statusText: String = if (ready) "Ready" else "Starting",
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(TvColors.SurfaceCard, shape)
            .border(1.dp, TvColors.CardBorder, shape)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                color = TvColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            StatusBadge(ready, statusText)
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                status,
                style = MaterialTheme.typography.labelSmall,
                color = if (ready) TvColors.StatusReady else TvColors.TextSecondary,
                maxLines = 1
            )
            Spacer(Modifier.weight(1f))
            Text(
                detail,
                style = MaterialTheme.typography.labelSmall,
                color = TvColors.AccentCyan,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        instructions.forEach { line ->
            Text(
                line,
                style = MaterialTheme.typography.labelSmall,
                color = TvColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StatusBadge(ready: Boolean, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PulsingStatusDot(ready)
        Spacer(Modifier.width(5.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = if (ready) TvColors.StatusReady else TvColors.TextSecondary
        )
    }
}

@Composable
private fun QuickRenameDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember(currentName) { mutableStateOf(currentName) }
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        delay(120L)
        runCatching { nameFocus.requestFocus() }
    }
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.66f))
                .padding(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .width(560.dp)
                    .background(TvColors.SurfaceFocused, RoundedCornerShape(22.dp))
                    .border(1.5.dp, TvColors.AccentCyan.copy(alpha = 0.7f), RoundedCornerShape(22.dp))
                    .padding(24.dp)
            ) {
                Text(
                    "Rename Receiver",
                    style = MaterialTheme.typography.headlineLarge,
                    color = TvColors.TextPrimary
                )
                Text(
                    "This name appears in AirPlay pickers.",
                    style = MaterialTheme.typography.labelSmall,
                    color = TvColors.TextSecondary
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(48) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(nameFocus),
                    label = { Text("Device name") },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = TvColors.TextPrimary,
                        unfocusedTextColor = TvColors.TextPrimary,
                        focusedBorderColor = TvColors.AccentCyan,
                        unfocusedBorderColor = TvColors.TextSecondary.copy(alpha = 0.45f),
                        focusedLabelColor = TvColors.AccentCyan,
                        unfocusedLabelColor = TvColors.TextSecondary,
                        cursorColor = TvColors.AccentCyan
                    )
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RenamePreset("Living Room TV") { name = it }
                    RenamePreset("Bedroom TV") { name = it }
                    RenamePreset("Office Display") { name = it }
                }
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DialogButton("Cancel", onDismiss, Modifier.weight(1f))
                    DialogButton(
                        "Save",
                        { onSave(name.trim().ifBlank { "AirScreen TV" }) },
                        Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun RenamePreset(label: String, onClick: (String) -> Unit) {
    Box(
        modifier = Modifier
            .tvFocusable(onClick = { onClick(label) }, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = TvColors.AccentCyan,
            maxLines = 1
        )
    }
}

@Composable
private fun DialogButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .tvFocusable(onClick = onClick, shape = RoundedCornerShape(10.dp))
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = TvColors.TextPrimary
        )
    }
}

@Composable
private fun PulsingStatusDot(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "status-dot")
    val alpha by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(850),
            repeatMode = RepeatMode.Reverse
        ),
        label = "status-dot-alpha"
    )
    Box(
        modifier = Modifier
            .size(9.dp)
            .background(
                if (active) TvColors.StatusReady.copy(alpha = alpha) else TvColors.TextSecondary,
                CircleShape
            )
    )
}