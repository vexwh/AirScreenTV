package com.airscreen.tv.protocols.airplay.crypto

import com.airscreen.tv.domain.contract.SessionManager
import com.airscreen.tv.domain.model.PinChallenge
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.agreement.srp.SRP6Server
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups
import org.bouncycastle.crypto.agreement.srp.SRP6VerifierGenerator
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

class AirPlayCryptoSession(
    serverKeyPair: AsymmetricCipherKeyPair,
    private val sessionManager: SessionManager,
    pinRequired: Boolean
) {
    @Volatile private var pinRequiredEnabled: Boolean = pinRequired
    private val lock = Any()
    @Volatile private var clientName: String = "AirPlay sender"
    @Volatile private var clientAddress: String = "unknown"
    private val random = SecureRandom()
    private val serverEdPrivate = serverKeyPair.private as Ed25519PrivateKeyParameters
    private val serverEdPublic = serverKeyPair.public as Ed25519PublicKeyParameters
    private val serverPublicKey: ByteArray = serverEdPublic.encoded

    private var pairSetupServer: SRP6Server? = null
    private var pairSetupChallenge: PinChallenge? = null
    private var pairSetupSalt: ByteArray? = null

    private var verifyStage = 0
    private var clientX25519Public: ByteArray? = null
    private var serverX25519Private: X25519PrivateKeyParameters? = null
    private var serverX25519Public: ByteArray? = null
    private var clientEd25519Public: Ed25519PublicKeyParameters? = null
    private var sharedSecret: ByteArray? = null
    private var pairVerifyCipher: Cipher? = null
    private var fairPlayKeyMessage: ByteArray? = null
    @Volatile private var pendingEkey: ByteArray? = null
    @Volatile private var pendingEiv: ByteArray? = null

    private var videoCipher: Cipher? = null
    private var videoKey: SecretKeySpec? = null
    private var videoIv: IvParameterSpec? = null
    private var videoScratch = ByteArray(0)

    fun handlePairSetup(requestBody: ByteArray): ByteArray {
        synchronized(lock) {
            if (!pinRequiredEnabled) {
                return serverPublicKey.copyOf()
            }
            val items = Tlv8.decode(requestBody)
            val method = items.firstOrNull { it.type == Tlv8.METHOD }
                ?.value?.firstOrNull()?.toInt()?.and(0xFF) ?: 0
            if (method != 0) throw SecurityException("Unsupported pair-setup method")
            val state = items.firstOrNull { it.type == Tlv8.STATE }
                ?.value?.firstOrNull()?.toInt()?.and(0xFF) ?: 1
            return when (state) {
                1, 2 -> pairSetupStart()
                3 -> pairSetupFinish(items)
                else -> throw SecurityException("Unsupported pair-setup state")
            }
        }
    }

    fun handlePairVerify(requestBody: ByteArray): ByteArray = synchronized(lock) {
        if (requestBody.size < 68) throw SecurityException("Truncated pair-verify request")
        when (requestBody[0].toInt() and 0xFF) {
            0x01 -> pairVerifyStageOne(requestBody)
            0x00 -> pairVerifyStageTwo(requestBody)
            else -> throw SecurityException("Unsupported pair-verify stage")
        }
    }

    fun handleFairPlaySetup(requestBody: ByteArray): ByteArray = synchronized(lock) {
        if (requestBody.size < 5) throw SecurityException("Truncated FairPlay request")
        if (!isFply(requestBody)) throw SecurityException("Invalid FairPlay header")
        // Apple sends a 16-byte mode request followed by a 164-byte trailer.
        // Dispatch by size as RPiPlay/AirPlay receivers do; byte 4 is not a
        // reliable stage discriminator across iOS versions.
        when {
            requestBody.size == 16 -> fairPlayModeResponse(requestBody)
            requestBody.size >= 164 -> fairPlayTrailer(requestBody)
            (requestBody[4].toInt() and 0xFF) == 0x01 -> fairPlayModeResponse(requestBody)
            (requestBody[4].toInt() and 0xFF) == 0x03 && requestBody.size >= 20 ->
                fairPlayTrailer(requestBody)
            else -> throw SecurityException(
                "Unsupported FairPlay request length " + requestBody.size
            )
        }
    }

    fun initMirroringStreamCipher(
        streamConnectionId: ULong,
        ekey: ByteArray?,
        eiv: ByteArray?
    ) = synchronized(lock) {
        val secret = sharedSecret ?: throw SecurityException("Pair-verify has not completed")
        val connectionBytes = ByteBuffer.allocate(8)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putLong(streamConnectionId.toLong())
            .array()
        val keyMessage = fairPlayKeyMessage ?: SHARED_FAIRPLAY_KEY_MESSAGE
        val effectiveEkey = ekey ?: pendingEkey ?: SHARED_EKEY
        if (keyMessage != null && effectiveEkey != null && effectiveEkey.size >= 72) {
            val fairPlayKey = FairPlayNative.decrypt(keyMessage, effectiveEkey)
                ?: throw SecurityException("FairPlay decryption failed")
            val eAesKey = sha512(fairPlayKey + secret)
            val keySeed = eAesKey.copyOfRange(0, 16)
            val id = streamConnectionId.toString()
            val key = sha512(
                ("AirPlayStreamKey" + id).toByteArray(Charsets.US_ASCII) + keySeed
            ).copyOfRange(0, 16)
            val iv = sha512(
                ("AirPlayStreamIV" + id).toByteArray(Charsets.US_ASCII) + keySeed
            ).copyOfRange(0, 16)
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            val keySpec = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            videoCipher = cipher
            videoKey = keySpec
            videoIv = ivSpec
        } else {
            val derivedKey = sha512(
                "AirPlayStreamKey".toByteArray(Charsets.US_ASCII) + connectionBytes + secret
            ).copyOfRange(0, 16)
            val derivedIv = sha512(
                "AirPlayStreamIV".toByteArray(Charsets.US_ASCII) + connectionBytes + secret
            ).copyOfRange(0, 16)
            val key = if (effectiveEkey != null && effectiveEkey.size == 16) effectiveEkey else derivedKey
            val iv = if (eiv != null && eiv.size == 16) eiv else derivedIv
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            val keySpec = SecretKeySpec(key, "AES")
            val ivSpec = IvParameterSpec(iv)
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, ivSpec)
            videoCipher = cipher
            videoKey = keySpec
            videoIv = ivSpec
        }
        Unit
    }

    fun decryptVideoInPlace(data: ByteArray, offset: Int, length: Int) = synchronized(lock) {
        if (length <= 0) return@synchronized
        val cipher = videoCipher ?: throw SecurityException("Video cipher is not initialized")
        if (videoScratch.size < length) videoScratch = ByteArray(length)
        val written = cipher.update(data, offset, length, videoScratch, 0)
        if (written != length) throw IllegalStateException("Unexpected AES-CTR output length")
        System.arraycopy(videoScratch, 0, data, offset, length)
    }

    fun setPinRequired(required: Boolean) {
        pinRequiredEnabled = required
    }

    fun setClientContext(clientName: String, clientAddress: String) {
        this.clientName = clientName.ifBlank { "AirPlay sender" }
        this.clientAddress = clientAddress.ifBlank { "unknown" }
    }

    fun cryptoDiagnostics(): String =
        "keymsg=" + (fairPlayKeyMessage?.size ?: SHARED_FAIRPLAY_KEY_MESSAGE?.size ?: 0) +
            " sharedEkey=" + (SHARED_EKEY?.size ?: 0) +
            " videoKey=" + (videoKey != null)

    fun setPendingStreamKeys(ekey: ByteArray?, eiv: ByteArray?) {
        synchronized(lock) {
            if (ekey != null) { pendingEkey = ekey.copyOf(); SHARED_EKEY = pendingEkey }
            if (eiv != null) pendingEiv = eiv.copyOf()
        }
    }

    fun close() = synchronized(lock) {
        pairSetupServer = null
        pairSetupChallenge = null
        pairSetupSalt = null
        pairVerifyCipher = null
        fairPlayKeyMessage = null
        videoCipher = null
        videoScratch = ByteArray(0)
    }

    private fun pairSetupStart(): ByteArray {
        val challenge = sessionManager.requestPinChallenge(clientName, clientAddress)
        val salt = ByteArray(16).also { random.nextBytes(it) }
        val verifierGenerator = SRP6VerifierGenerator()
        verifierGenerator.init(SRP6StandardGroups.rfc5054_3072, SHA512Digest())
        val verifier = verifierGenerator.generateVerifier(
            salt,
            PAIR_SETUP_USERNAME.toByteArray(Charsets.US_ASCII),
            challenge.code.value.toByteArray(Charsets.US_ASCII)
        )
        val server = SRP6Server()
        server.init(SRP6StandardGroups.rfc5054_3072, verifier, SHA512Digest(), random)
        val publicB = server.generateServerCredentials()
        pairSetupServer = server
        pairSetupChallenge = challenge
        pairSetupSalt = salt
        return Tlv8.encode(
            listOf(
                Tlv8.Item(Tlv8.METHOD, byteArrayOf(0x00)),
                Tlv8.Item(Tlv8.STATE, byteArrayOf(0x02)),
                Tlv8.Item(Tlv8.SALT, salt),
                Tlv8.Item(Tlv8.PUBLIC_KEY, toFixedLength(publicB, SRP_KEY_BYTES))
            )
        )
    }

    private fun pairSetupFinish(items: List<Tlv8.Item>): ByteArray {
        val server = pairSetupServer ?: throw SecurityException("Pair-setup was not started")
        val challenge = pairSetupChallenge ?: throw SecurityException("No PIN challenge")
        if (System.currentTimeMillis() > challenge.expiresAtEpochMs) {
            throw SecurityException("PIN challenge expired")
        }
        val clientPublic = items.firstOrNull { it.type == Tlv8.PUBLIC_KEY }?.value
            ?: throw SecurityException("Missing SRP client public key")
        val clientProof = items.firstOrNull { it.type == Tlv8.PROOF }?.value
            ?: throw SecurityException("Missing SRP client proof")

        val secret = try {
            server.calculateSecret(BigInteger(1, clientPublic))
        } catch (error: Exception) {
            throw SecurityException("SRP secret calculation failed", error)
        }
        val proofValid = try {
            server.verifyClientEvidenceMessage(BigInteger(1, clientProof))
        } catch (error: Exception) {
            throw SecurityException("SRP proof validation failed", error)
        }
        if (!proofValid) throw SecurityException("Invalid SRP proof")
        val serverProof = server.calculateServerEvidenceMessage()
        sessionManager.completePinChallenge(challenge.id)
        pairSetupServer = null
        pairSetupChallenge = null
        pairSetupSalt = null
        return Tlv8.encode(
            listOf(
                Tlv8.Item(Tlv8.METHOD, byteArrayOf(0x00)),
                Tlv8.Item(Tlv8.STATE, byteArrayOf(0x04)),
                Tlv8.Item(Tlv8.PROOF, toFixedLength(serverProof, SHA512_BYTES))
            )
        )
    }

    private fun pairVerifyStageOne(requestBody: ByteArray): ByteArray {
        val clientX = requestBody.copyOfRange(4, 36)
        val clientEd = requestBody.copyOfRange(36, 68)
        val serverPrivate = X25519PrivateKeyParameters(random)
        val serverPublic = serverPrivate.generatePublicKey().encoded
        val agreement = X25519Agreement()
        agreement.init(serverPrivate)
        val secret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(clientX, 0), secret, 0)

        val aesKey = sha512(PAIR_VERIFY_KEY_PREFIX + secret).copyOfRange(0, 16)
        val aesIv = sha512(PAIR_VERIFY_IV_PREFIX + secret).copyOfRange(0, 16)
        val signature = signEd25519(serverPublic + clientX)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), IvParameterSpec(aesIv))
        val encryptedSignature = cipher.update(signature)
        if (encryptedSignature.size != 64) throw IllegalStateException("AES stage encryption failed")

        verifyStage = 1
        clientX25519Public = clientX
        serverX25519Private = serverPrivate
        serverX25519Public = serverPublic
        clientEd25519Public = Ed25519PublicKeyParameters(clientEd, 0)
        sharedSecret = secret
        pairVerifyCipher = cipher
        return serverPublic + encryptedSignature
    }

    private fun pairVerifyStageTwo(requestBody: ByteArray): ByteArray {
        if (verifyStage != 1) throw SecurityException("Pair-verify stage 1 has not completed")
        val encryptedSignature = requestBody.copyOfRange(4, 68)
        val cipher = pairVerifyCipher ?: throw SecurityException("Missing pair-verify cipher")
        val decrypted = cipher.update(encryptedSignature)
        if (decrypted.size != 64) throw SecurityException("Invalid client signature size")
        val serverPublic = serverX25519Public ?: throw SecurityException("Missing server key")
        val clientPublic = clientX25519Public ?: throw SecurityException("Missing client key")
        val clientKey = clientEd25519Public ?: throw SecurityException("Missing client identity")
        if (!verifyEd25519(clientPublic + serverPublic, decrypted, clientKey)) {
            throw SecurityException("Pair-verify client signature mismatch")
        }
        verifyStage = 2
        pairVerifyCipher = null
        return ByteArray(0)
    }

    private fun fairPlayModeResponse(requestBody: ByteArray): ByteArray {
        val mode = if (requestBody.size > 14) requestBody[14].toInt() and 0xFF else 0
        return FAIRPLAY_MODE_RESPONSES[mode.coerceIn(0, 3)].copyOf()
    }

    private fun fairPlayTrailer(requestBody: ByteArray): ByteArray {
        if (requestBody.size < 20) throw SecurityException("FairPlay stage 2 request is too short")
        val response = ByteArray(32)
        fairPlayKeyMessage = requestBody.copyOf()
        SHARED_FAIRPLAY_KEY_MESSAGE = fairPlayKeyMessage
        val header = byteArrayOf(
            0x46, 0x50, 0x4C, 0x59, 0x03, 0x01,
            0x04, 0x00, 0x00, 0x00, 0x00, 0x14
        )
        System.arraycopy(header, 0, response, 0, header.size)
        System.arraycopy(requestBody, requestBody.size - 20, response, 12, 20)
        return response
    }

    private fun signEd25519(message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, serverEdPrivate)
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    private fun verifyEd25519(
        message: ByteArray,
        signature: ByteArray,
        publicKey: Ed25519PublicKeyParameters
    ): Boolean {
        val signer = Ed25519Signer()
        signer.init(false, publicKey)
        signer.update(message, 0, message.size)
        return signer.verifySignature(signature)
    }

    private fun sha512(input: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-512").digest(input)

    private fun toFixedLength(value: BigInteger, size: Int): ByteArray {
        val bytes = value.toByteArray()
        val unsigned = if (bytes.size > size && bytes[0] == 0.toByte()) {
            bytes.copyOfRange(1, bytes.size)
        } else {
            bytes
        }
        if (unsigned.size > size) throw IllegalArgumentException("Integer does not fit")
        val output = ByteArray(size)
        System.arraycopy(unsigned, 0, output, size - unsigned.size, unsigned.size)
        return output
    }

    private fun isFply(bytes: ByteArray): Boolean =
        bytes.size >= 4 &&
            bytes[0] == 0x46.toByte() &&
            bytes[1] == 0x50.toByte() &&
            bytes[2] == 0x4C.toByte() &&
            bytes[3] == 0x59.toByte()

    private companion object {
        @Volatile var SHARED_FAIRPLAY_KEY_MESSAGE: ByteArray? = null
        @Volatile var SHARED_EKEY: ByteArray? = null
        val FAIRPLAY_MODE_RESPONSES = arrayOf(
            fairPlayHex("46504c59030102000000008202000f9f3f9e0a2521dbdf312ab2bfb29e8d232b6376a8c818701d22ae93d82737feaf9db4fdf41c2dba9d1f49caaabf6591ac1f7bc6f7e0663d21afe01565953eab81f418ceed095adb7c3d0e254909a79831d49c3982973434facb42c63a1cd911a6fe941a8a6d4a743b46c3a7649e44c78955e49d8155009549c4e2f7a3f6d5ba"),
            fairPlayHex("46504c5903010200000000820201cf32a25714b2524f8aa0ad7af164e37bcf4424e200047efc0ad67afcd95ded1c2730bb591b962ed63a9c4ded88ba8fc78de64d91ccfd5c7b56da88e31f5cceafc7431995a01665a54e1939d25b94db64b9e45d8d063e1e6af07e9656162b0efa404275ea5a44d9591c7256b9fbe6513898b80227721988571650942ad946688a"),
            fairPlayHex("46504c5903010200000000820202c169a352eeed35b18cdd9c58d64f16c1519a89eb5317bd0d4336cd68f638ff9d016a5b52b7fa9216b2b65482c78444118121a2c7fed83db7119e9182aad7d18c7063e2a457555910af9e0efc76347d164043807f581ee4fbe42ca9dedc1b5eb2a3aa3d2ecd59e7eee70b3629f22afd161d877353ddb99adc8e07006e56f850ce"),
            fairPlayHex("46504c59030102000000008202039001e1727e0f57f9f5880db104a6257a23f5cfff1abbe1e93045251afb97eb9fc0011ebe0f3a81df5b691d76acb2f7a5c708e3d328f56bb39dbde5f29c8a17f481487e3ae863c678325422e6f78e166d18aa7fd636258bce28726f661f738893ce44311e4be6c0535193e5ef72e8686233729c227d820c999445d89246c8c359")
        )
        const val SRP_KEY_BYTES = 384
        const val SHA512_BYTES = 64
        const val FAIRPLAY_MODE_RESPONSE_BYTES = 142
        const val PAIR_SETUP_USERNAME = "Pair-Setup"
        val PAIR_VERIFY_KEY_PREFIX = "Pair-Verify-AES-Key".toByteArray(Charsets.US_ASCII)
        val PAIR_VERIFY_IV_PREFIX = "Pair-Verify-AES-IV".toByteArray(Charsets.US_ASCII)
    }
}
private fun fairPlayHex(value: String): ByteArray =
    ByteArray(value.length / 2) { index ->
        value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }