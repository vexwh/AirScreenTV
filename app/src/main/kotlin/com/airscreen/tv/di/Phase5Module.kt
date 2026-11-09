package com.airscreen.tv.di

import com.airscreen.tv.media.recorder.FileRecordingsRepository
import com.airscreen.tv.media.recorder.MediaMuxerStreamRecorder
import com.airscreen.tv.core.security.AccessControlManagerImpl
import com.airscreen.tv.domain.contract.AccessControlManager
import com.airscreen.tv.domain.contract.RecordingsRepository
import com.airscreen.tv.domain.contract.StreamRecorderManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class Phase5Module {
    @Binds
    @Singleton
    abstract fun bindAccessControlManager(
        implementation: AccessControlManagerImpl
    ): AccessControlManager

    @Binds
    @Singleton
    abstract fun bindRecordingsRepository(
        implementation: FileRecordingsRepository
    ): RecordingsRepository

    @Binds
    @Singleton
    abstract fun bindStreamRecorderManager(
        implementation: MediaMuxerStreamRecorder
    ): StreamRecorderManager
}