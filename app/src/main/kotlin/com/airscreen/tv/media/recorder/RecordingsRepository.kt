package com.airscreen.tv.media.recorder

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.util.Log
import com.airscreen.tv.di.ApplicationScope
import com.airscreen.tv.domain.contract.CastUrlController
import com.airscreen.tv.domain.contract.RecordingsRepository
import com.airscreen.tv.domain.model.RecordedVideoItem
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Singleton
class FileRecordingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val castUrlController: CastUrlController,
    @ApplicationScope private val scope: CoroutineScope
) : RecordingsRepository {
    private val _recordings = MutableStateFlow<List<RecordedVideoItem>>(emptyList())
    override val recordings: StateFlow<List<RecordedVideoItem>> = _recordings.asStateFlow()

    private val directory: File
        get() {
            val movies = context.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: context.filesDir
            return File(movies, "AirScreenRecordings").apply { mkdirs() }
        }

    init {
        scope.launch { refresh() }
    }

    override suspend fun refresh() {
        val items = withContext(Dispatchers.IO) {
            directory.listFiles()
                ?.filter { it.isFile && it.extension.equals("mp4", true) }
                ?.map { file -> toItem(file) }
                ?.sortedByDescending { it.createdAtEpochMs }
                .orEmpty()
        }
        _recordings.value = items
    }

    override suspend fun delete(item: RecordedVideoItem) {
        withContext(Dispatchers.IO) {
            runCatching { File(item.filePath).delete() }
                .onFailure { Log.w(TAG, "Unable to delete recording", it) }
        }
        refresh()
    }

    override suspend fun playRecording(item: RecordedVideoItem) {
        castUrlController.loadMedia(
            url = Uri.fromFile(File(item.filePath)).toString(),
            title = item.title,
            mimeType = item.mimeType
        )
    }

    private fun toItem(file: File): RecordedVideoItem {
        val retriever = MediaMetadataRetriever()
        var duration = 0L
        var resolution = "unknown"
        var thumbnail: Bitmap? = null
        try {
            retriever.setDataSource(file.absolutePath)
            duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                ?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                ?.toIntOrNull() ?: 0
            resolution = if (width > 0 && height > 0) width.toString() + "x" + height else "unknown"
            thumbnail = retriever.getFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            )
        } catch (error: Throwable) {
            Log.d(TAG, "Unable to inspect recording " + file.name, error)
        } finally {
            runCatching { retriever.release() }
        }
        return RecordedVideoItem(
            id = file.absolutePath,
            title = file.nameWithoutExtension,
            fileName = file.name,
            filePath = file.absolutePath,
            fileSizeBytes = file.length(),
            durationMs = duration,
            resolution = resolution,
            createdAtEpochMs = file.lastModified(),
            thumbnailBitmap = thumbnail
        )
    }

    private companion object {
        const val TAG = "RecordingsRepository"
    }
}
