package com.airscreen.tv.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

private val Context.settingsStore by preferencesDataStore(
    name = "airscreen_settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

private val Context.identityStore by preferencesDataStore(
    name = "airscreen_identity",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }
)

@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {
    @Provides
    @Singleton
    @SettingsDataStore
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.settingsStore

    @Provides
    @Singleton
    @IdentityDataStore
    fun provideIdentityDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        context.identityStore
}
