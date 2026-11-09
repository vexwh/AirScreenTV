package com.airscreen.tv.di

import com.airscreen.tv.domain.contract.CastUrlController
import com.airscreen.tv.media.player.UnifiedExoPlayerManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CastModule {
    @Binds
    @Singleton
    abstract fun bindCastUrlController(
        implementation: UnifiedExoPlayerManager
    ): CastUrlController
}
