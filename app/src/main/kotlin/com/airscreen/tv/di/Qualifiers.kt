package com.airscreen.tv.di

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IdentityDataStore

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
