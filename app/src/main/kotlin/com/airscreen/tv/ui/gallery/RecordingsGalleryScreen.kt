package com.airscreen.tv.ui.gallery

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.airscreen.tv.domain.model.RecordedVideoItem
import com.airscreen.tv.ui.theme.OverscanSafeBox
import com.airscreen.tv.ui.theme.TvColors
import com.airscreen.tv.ui.theme.tvFocusable
import com.airscreen.tv.ui.viewmodel.MainViewModel
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun RecordingsGalleryRoute(
    viewModel: MainViewModel,
    onBack: () -> Unit,
    onPlayed: () -> Unit
) {
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { viewModel.refreshRecordings() }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val backFocus = remember { FocusRequester() }
    val actionFocus = remember { FocusRequester() }
    val selected = recordings.firstOrNull { it.id == selectedId }
    var backFocused by remember { mutableStateOf(false) }

    BackHandler(onBack = onBack)

    LaunchedEffect(selectedId) {
        if (selected != null) {
            withFrameNanos { }
            delay(100L)
            runCatching { actionFocus.requestFocus() }
        }
    }
    LaunchedEffect(Unit) {
        repeat(8) {
            withFrameNanos { }
            if (backFocused) return@LaunchedEffect
            runCatching { backFocus.requestFocus() }
            delay(110L)
        }
    }

    OverscanSafeBox(
        modifier = Modifier
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Back) {
                    onBack()
                    true
                } else {
                    false
                }
            }
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .onFocusChanged { backFocused = it.isFocused }
                        .focusRequester(backFocus)
                        .tvFocusable(onClick = onBack, shape = RoundedCornerShape(10.dp))
                        .padding(horizontal = 14.dp, vertical = 9.dp)
                ) {
                    Text(
                        "Back",
                        color = TvColors.AccentCyan,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                Spacer(Modifier.width(18.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Recordings",
                        style = MaterialTheme.typography.headlineLarge,
                        color = TvColors.TextPrimary
                    )
                    Text(
                        recordings.size.toString() + " saved MP4 clips",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TvColors.TextSecondary
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            if (recordings.isEmpty()) {
                EmptyGallery(Modifier.fillMaxSize())
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    itemsIndexed(
                        items = recordings,
                        key = { _, item -> item.id }
                    ) { _, item ->
                        RecordingCard(
                            item = item,
                            selected = item.id == selectedId,
                            onClick = { selectedId = item.id }
                        )
                    }
                }
                if (selected != null) {
                    Spacer(Modifier.height(10.dp))
                    GalleryActionBar(
                        item = selected,
                        focusRequester = actionFocus,
                        onPlay = {
                            viewModel.playRecordedFile(selected)
                            onPlayed()
                        },
                        onDelete = {
                            viewModel.deleteRecordedFile(selected)
                            selectedId = null
                        },
                        onClose = { selectedId = null }
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordingCard(
    item: RecordedVideoItem,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .tvFocusable(onClick = onClick, shape = RoundedCornerShape(14.dp))
            .then(
                if (selected) {
                    Modifier.border(2.dp, TvColors.AccentCyan, RoundedCornerShape(14.dp))
                } else {
                    Modifier
                }
            )
            .padding(9.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val thumbnail = item.thumbnailBitmap
            if (thumbnail != null) {
                Image(
                    bitmap = thumbnail.asImageBitmap(),
                    contentDescription = item.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Text("MP4", color = TvColors.AccentCyan)
            }
            Text(
                resolutionPill(item.resolution),
                color = TvColors.TextPrimary,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            item.fileName,
            color = TvColors.TextPrimary,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
            maxLines = 1
        )
        Text(
            formatDuration(item.durationMs) + "  •  " + formatSize(item.fileSizeBytes),
            color = TvColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun GalleryActionBar(
    item: RecordedVideoItem,
    focusRequester: FocusRequester,
    onPlay: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(TvColors.SurfaceFocused, RoundedCornerShape(14.dp))
            .border(1.dp, TvColors.AccentCyan.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.fileName,
                color = TvColors.TextPrimary,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp),
                maxLines = 1
            )
            Text(
                formatDuration(item.durationMs) + "  •  " + resolutionPill(item.resolution),
                color = TvColors.TextSecondary,
                style = MaterialTheme.typography.labelSmall
            )
        }
        GalleryButton(
            "Play in Built-in Player",
            onPlay,
            Modifier.focusRequester(focusRequester)
        )
        Spacer(Modifier.width(7.dp))
        GalleryButton("Delete File", onDelete, destructive = true)
        Spacer(Modifier.width(7.dp))
        GalleryButton("Close", onClose)
    }
}

@Composable
private fun GalleryButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false
) {
    Box(
        modifier = modifier
            .tvFocusable(onClick = onClick, shape = RoundedCornerShape(10.dp))
            .padding(horizontal = 11.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (destructive) TvColors.DangerRed else TvColors.AccentCyan,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1
        )
    }
}

@Composable
private fun EmptyGallery(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(modifier = Modifier.size(150.dp)) {
                val frame = androidx.compose.ui.geometry.Rect(
                    left = 10f,
                    top = 20f,
                    right = size.width - 10f,
                    bottom = size.height - 20f
                )
                drawRoundRect(
                    color = TvColors.SurfaceCard,
                    topLeft = frame.topLeft,
                    size = frame.size,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(14f),
                    style = Stroke(width = 5f)
                )
                drawRoundRect(
                    color = TvColors.AccentCyan.copy(alpha = 0.65f),
                    topLeft = frame.topLeft,
                    size = frame.size,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(14f),
                    style = Stroke(width = 2f)
                )
                val play = Path().apply {
                    moveTo(size.width * 0.43f, size.height * 0.32f)
                    lineTo(size.width * 0.43f, size.height * 0.68f)
                    lineTo(size.width * 0.68f, size.height * 0.50f)
                    close()
                }
                drawPath(play, color = TvColors.AccentCyan)
                for (i in 0..2) {
                    drawCircle(
                        color = TvColors.AccentCyan.copy(alpha = 0.35f),
                        radius = 4f,
                        center = androidx.compose.ui.geometry.Offset(
                            size.width * (0.23f + i * 0.08f),
                            size.height * 0.5f
                        )
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                "No recordings yet",
                style = MaterialTheme.typography.headlineLarge,
                color = TvColors.TextPrimary
            )
            Text(
                "Your saved MP4 clips will appear here after you record a mirroring stream.",
                color = TvColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

private fun resolutionPill(resolution: String): String {
    return when {
        resolution.contains("3840") || resolution.contains("2160") -> "4K"
        resolution.contains("1920") || resolution.contains("1080") -> "1080p"
        resolution.contains("1280") || resolution.contains("720") -> "720p"
        resolution == "unknown" || resolution.isBlank() -> "MP4"
        else -> resolution
    }
}

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0L) / 1000L
    return String.format(
        Locale.US,
        "%02d:%02d",
        totalSeconds / 60L,
        totalSeconds % 60L
    )
}

private fun formatSize(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))