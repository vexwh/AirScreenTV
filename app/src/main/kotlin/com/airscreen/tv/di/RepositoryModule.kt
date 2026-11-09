package com.airscreen.tv.di

import com.airscreen.tv.core.settings.DataStoreAppSettingsRepository
import com.airscreen.tv.domain.contract.AppSettingsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindAppSettingsRepository(
        implementation: DataStoreAppSettingsRepository
    ): AppSettingsRepository
}
