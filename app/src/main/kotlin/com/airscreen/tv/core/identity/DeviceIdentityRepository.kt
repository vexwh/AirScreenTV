package com.airscreen.tv.core.identity

import android.annotation.SuppressLint
import android.content.Context
import android.provider.Settings
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.airscreen.tv.di.IdentityDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters

data class DeviceIdentity(
    val deviceUuid: String,
    val hardwareId: String,
    val keyPair: AsymmetricCipherKeyPair
) {
    val pi: String
        get() = deviceUuid

    val publicKeyHex: String =
        (keyPair.public as Ed25519PublicKeyParameters).encoded.toHexLower()
}

@SuppressLint("HardwareIds")
@Singleton
class DeviceIdentityRepository @Inject constructor(
    @ApplicationContext context: Context,
    @IdentityDataStore private val dataStore: DataStore<Preferences>
) {
    private val androidId: String? = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ANDROID_ID
    )
    private val mutex = Mutex()

    suspend fun identity(): DeviceIdentity = mutex.withLock {
        withContext(Dispatchers.IO) { loadOrCreateIdentity() }
    }

    suspend fun ensureHardwareId(actualMac: String?): String = mutex.withLock {
        withContext(Dispatchers.IO) {
            val preferences = dataStore.data.first()
            preferences[KEY_HARDWARE_ID]?.takeIf { it.isNotBlank() }?.let { return@withContext it }
            val uuid = preferences[KEY_DEVICE_UUID]?.takeIf { it.isNotBlank() }
                ?: UUID.randomUUID().toString()
            val hardwareId = normalizeMac(actualMac) ?: deterministicHardwareId(uuid)
            dataStore.edit { editable ->
                editable[KEY_DEVICE_UUID] = uuid
                editable[KEY_HARDWARE_ID] = hardwareId
            }
            hardwareId
        }
    }

    private suspend fun loadOrCreateIdentity(): DeviceIdentity {
        val preferences = dataStore.data.first()
        val uuid = preferences[KEY_DEVICE_UUID]?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString()
        val hardwareId = preferences[KEY_HARDWARE_ID]?.takeIf { it.isNotBlank() }
            ?: deterministicHardwareId(uuid)

        val storedSeed = preferences[KEY_ED25519_PRIVATE]?.decodeBase64()
            ?.takeIf { it.size == ED25519_KEY_BYTES }
        val storedPublicKey = preferences[KEY_ED25519_PUBLIC]?.decodeBase64()
            ?.takeIf { it.size == ED25519_KEY_BYTES }

        val seed: ByteArray
        val publicKey: ByteArray
        if (storedSeed == null) {
            seed = ByteArray(ED25519_KEY_BYTES).also { SecureRandom().nextBytes(it) }
            publicKey = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
        } else {
            seed = storedSeed
            publicKey = storedPublicKey
                ?: Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded
        }

        val needsWrite = preferences[KEY_DEVICE_UUID] != uuid ||
            preferences[KEY_HARDWARE_ID] != hardwareId ||
            preferences[KEY_ED25519_PRIVATE]?.decodeBase64()?.contentEquals(seed) != true ||
            preferences[KEY_ED25519_PUBLIC]?.decodeBase64()?.contentEquals(publicKey) != true

        if (needsWrite) {
            dataStore.edit { editable ->
                editable[KEY_DEVICE_UUID] = uuid
                editable[KEY_HARDWARE_ID] = hardwareId
                editable[KEY_ED25519_PRIVATE] = seed.encodeBase64()
                editable[KEY_ED25519_PUBLIC] = publicKey.encodeBase64()
            }
        }

        val privateKey = Ed25519PrivateKeyParameters(seed, 0)
        val publicParameters = Ed25519PublicKeyParameters(publicKey, 0)
        return DeviceIdentity(
            deviceUuid = uuid,
            hardwareId = hardwareId,
            keyPair = AsymmetricCipherKeyPair(publicParameters, privateKey)
        )
    }

    private fun deterministicHardwareId(deviceUuid: String): String {
        val seed = (androidId ?: "android-id-unavailable") + ":" + deviceUuid
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        val bytes = digest.copyOfRange(0, HARDWARE_ID_BYTES)
        bytes[0] = ((bytes[0].toInt() or 0x02) and 0xFE).toByte()
        return bytes.toMacString()
    }

    private fun normalizeMac(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.trim().split(':', '-')
        if (parts.size != HARDWARE_ID_BYTES) return null
        val bytes = ByteArray(HARDWARE_ID_BYTES)
        for (index in parts.indices) {
            val value = parts[index].toIntOrNull(16) ?: return null
            if (value !in 0..0xFF) return null
            bytes[index] = value.toByte()
        }
        if (bytes.all { it == 0.toByte() }) return null
        if (bytes.all { it == 0xFF.toByte() }) return null
        if (bytes[0] == 0x02.toByte() && bytes.drop(1).all { it == 0.toByte() }) return null
        return bytes.toMacString()
    }

    private fun ByteArray.toMacString(): String = joinToString(":") { byte ->
        String.format(Locale.US, "%02X", byte.toInt() and 0xFF)
    }

    private fun String.decodeBase64(): ByteArray? = try {
        Base64.getDecoder().decode(this)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun ByteArray.encodeBase64(): String = Base64.getEncoder().encodeToString(this)

    private companion object {
        const val ED25519_KEY_BYTES = 32
        const val HARDWARE_ID_BYTES = 6
        val KEY_DEVICE_UUID = stringPreferencesKey("device_uuid")
        val KEY_HARDWARE_ID = stringPreferencesKey("hardware_id")
        val KEY_ED25519_PRIVATE = stringPreferencesKey("ed25519_private")
        val KEY_ED25519_PUBLIC = stringPreferencesKey("ed25519_public")
    }
}

internal fun ByteArray.toHexLower(): String = joinToString("") { byte ->
    String.format(Locale.US, "%02x", byte.toInt() and 0xFF)
}