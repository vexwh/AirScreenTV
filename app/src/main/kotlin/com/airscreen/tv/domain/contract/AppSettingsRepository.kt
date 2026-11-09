package com.airscreen.tv.domain.contract

import com.airscreen.tv.domain.model.AppSettings
import com.airscreen.tv.domain.model.FpsCap
import com.airscreen.tv.domain.model.MaxResolution
import kotlinx.coroutines.flow.Flow

interface AppSettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun update(transform: (AppSettings) -> AppSettings)

    suspend fun setDeviceName(deviceName: String)

    suspend fun setMaxResolution(value: MaxResolution)

    suspend fun setFpsCap(value: FpsCap)

    suspend fun setHardwareDecodingEnabled(enabled: Boolean)

    suspend fun setPinSecurityEnabled(enabled: Boolean)

    suspend fun setAutoStartOnBoot(enabled: Boolean)

    suspend fun resetToDefaults()
}
