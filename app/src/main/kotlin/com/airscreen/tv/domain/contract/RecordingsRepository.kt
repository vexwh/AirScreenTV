package com.airscreen.tv.domain.contract

import com.airscreen.tv.domain.model.RecordedVideoItem
import kotlinx.coroutines.flow.StateFlow

interface RecordingsRepository {
    val recordings: StateFlow<List<RecordedVideoItem>>

    suspend fun refresh()
    suspend fun delete(item: RecordedVideoItem)
    suspend fun playRecording(item: RecordedVideoItem)
    suspend fun deleteRecording(item: RecordedVideoItem) = delete(item)
}