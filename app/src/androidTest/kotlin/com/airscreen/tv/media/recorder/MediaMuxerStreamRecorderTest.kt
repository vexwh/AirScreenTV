package com.airscreen.tv.media.recorder

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airscreen.tv.domain.contract.RecordingsRepository
import com.airscreen.tv.domain.model.RecordedVideoItem
import com.airscreen.tv.domain.model.RecordingState
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaMuxerStreamRecorderTest {
    private lateinit var context: Context
    private lateinit var scope: CoroutineScope
    private lateinit var recorder: MediaMuxerStreamRecorder

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        recorder = MediaMuxerStreamRecorder(context, EmptyRecordings(), scope)
    }

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun stopBeforeFirstKeyframeDoesNotCrashOrLeaveEmptyFile() = runBlocking {
        val directory = File(
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
                ?: context.filesDir,
            "AirScreenRecordings"
        )
        directory.mkdirs()
        val before = directory.listFiles()?.size ?: 0
        recorder.startRecording("test-session")
        assertTrue(recorder.state.value is RecordingState.Recording)
        recorder.stopRecording()
        assertTrue(recorder.state.value is RecordingState.Idle)
        val after = directory.listFiles()?.size ?: 0
        assertTrue(after <= before)
    }

    private class EmptyRecordings : RecordingsRepository {
        private val state = MutableStateFlow<List<RecordedVideoItem>>(emptyList())
        override val recordings: StateFlow<List<RecordedVideoItem>> = state
        override suspend fun refresh() = Unit
        override suspend fun delete(item: RecordedVideoItem) = Unit
        override suspend fun playRecording(item: RecordedVideoItem) = Unit
    }
}