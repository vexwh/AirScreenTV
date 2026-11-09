package com.airscreen.tv.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import com.airscreen.tv.di.SettingsDataStore
import com.airscreen.tv.domain.contract.AppSettingsRepository
import com.airscreen.tv.domain.model.AppSettings
import com.airscreen.tv.domain.model.FpsCap
import com.airscreen.tv.domain.model.MaxResolution
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

@Singleton
class DataStoreAppSettingsRepository @Inject constructor(
    @SettingsDataStore private val dataStore: DataStore<Preferences>
) : AppSettingsRepository {

    override val settings: Flow<AppSettings> =
        dataStore.data
            .catch { error ->
                if (error is IOException) {
                    emit(emptyPreferences())
                } else {
                    throw error
                }
            }
            .map { preferences -> preferences.readSettings() }
            .distinctUntilChanged()

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        dataStore.edit { preferences ->
            preferences.writeSettings(transform(preferences.readSettings()))
        }
    }

    override suspend fun setDeviceName(deviceName: String) {
        update { current -> current.copy(deviceName = deviceName) }
    }

    override suspend fun setMaxResolution(value: MaxResolution) {
        update { current -> current.copy(maxResolution = value) }
    }

    override suspend fun setFpsCap(value: FpsCap) {
        update { current -> current.copy(fpsCap = value) }
    }

    override suspend fun setHardwareDecodingEnabled(enabled: Boolean) {
        update { current -> current.copy(hardwareDecodingEnabled = enabled) }
    }

    override suspend fun setPinSecurityEnabled(enabled: Boolean) {
        update { current -> current.copy(pinSecurityEnabled = enabled) }
    }

    override suspend fun setAutoStartOnBoot(enabled: Boolean) {
        update { current -> current.copy(autoStartOnBoot = enabled) }
    }

    override suspend fun resetToDefaults() {
        dataStore.edit { preferences -> preferences.clear() }
    }

    private fun Preferences.readSettings(): AppSettings {
        val storedName = this[KEY_DEVICE_NAME] ?: AppSettings.DEFAULT_DEVICE_NAME
        return AppSettings(
            deviceName = sanitizeDeviceName(storedName),
            maxResolution = this[KEY_MAX_RESOLUTION].toEnumOrNull<MaxResolution>()
                ?: MaxResolution.FHD_1080P,
            fpsCap = this[KEY_FPS_CAP].toEnumOrNull<FpsCap>() ?: FpsCap.FPS_60,
            hardwareDecodingEnabled = this[KEY_HARDWARE_DECODING] ?: true,
            pinSecurityEnabled = this[KEY_PIN_SECURITY] ?: true,
            autoStartOnBoot = this[KEY_AUTO_START] ?: true
        )
    }

    private fun MutablePreferences.writeSettings(settings: AppSettings) {
        this[KEY_DEVICE_NAME] = sanitizeDeviceName(settings.deviceName)
        this[KEY_MAX_RESOLUTION] = settings.maxResolution.name
        this[KEY_FPS_CAP] = settings.fpsCap.name
        this[KEY_HARDWARE_DECODING] = settings.hardwareDecodingEnabled
        this[KEY_PIN_SECURITY] = settings.pinSecurityEnabled
        this[KEY_AUTO_START] = settings.autoStartOnBoot
    }

    private fun sanitizeDeviceName(raw: String): String {
        val withoutControls = CONTROL_CHARACTERS.replace(raw, "").trim()
        var value = withoutControls.ifBlank { AppSettings.DEFAULT_DEVICE_NAME }
        while (value.toByteArray(Charsets.UTF_8).size > AppSettings.MAX_DEVICE_NAME_BYTES) {
            value = value.dropLast(1)
        }
        return value.ifBlank { AppSettings.DEFAULT_DEVICE_NAME }
    }

    private inline fun <reified T : Enum<T>> String?.toEnumOrNull(): T? {
        if (this == null) return null
        return runCatching { enumValueOf<T>(this) }.getOrNull()
    }

    private companion object {
        val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
        val KEY_MAX_RESOLUTION = stringPreferencesKey("max_resolution")
        val KEY_FPS_CAP = stringPreferencesKey("fps_cap")
        val KEY_HARDWARE_DECODING = booleanPreferencesKey("hardware_decoding")
        val KEY_PIN_SECURITY = booleanPreferencesKey("pin_security")
        val KEY_AUTO_START = booleanPreferencesKey("auto_start_on_boot")
        val CONTROL_CHARACTERS = Regex("[\\p{Cntrl}]")
    }
}