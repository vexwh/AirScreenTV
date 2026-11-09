package com.airscreen.tv.di

import com.airscreen.tv.core.session.SessionManagerImpl
import com.airscreen.tv.domain.contract.SessionManager
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SessionModule {
    @Binds
    @Singleton
    abstract fun bindSessionManager(implementation: SessionManagerImpl): SessionManager
}
