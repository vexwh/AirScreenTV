package com.airscreen.tv.di

import com.airscreen.tv.discovery.DiscoveryControllerImpl
import com.airscreen.tv.domain.contract.DiscoveryController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DiscoveryModule {
    @Binds
    @Singleton
    abstract fun bindDiscoveryController(
        implementation: DiscoveryControllerImpl
    ): DiscoveryController
}
