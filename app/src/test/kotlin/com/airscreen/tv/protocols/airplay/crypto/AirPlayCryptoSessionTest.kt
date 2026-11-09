package com.airscreen.tv.protocols.airplay.crypto

import com.airscreen.tv.core.session.SessionManagerImpl
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.agreement.srp.SRP6Client
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayCryptoSessionTest {

    private val random = SecureRandom()

    @Test
    fun pairVerifyTwoStageHandshakeSucceeds() {
        val server = newServer()
        val client = newClient()
        val session = AirPlayCryptoSession(server.keyPair, SessionManagerImpl(), false)

        val stageOne = session.handlePairVerify(stageOneRequest(client))
        assertEquals(96, stageOne.size)
        val serverX = stageOne.copyOfRange(0, 32)
        val encryptedServerSignature = stageOne.copyOfRange(32, 96)

        val clientCipher = client.cipherFor(serverX)
        val serverSignature = clientCipher.update(encryptedServerSignature)
        assertEquals(64, serverSignature.size)
        assertTrue(
            verifyEd25519(
                serverX + client.xPublic,
                serverSignature,
                server.keyPair.public as Ed25519PublicKeyParameters
            )
        )

        val clientSignature = signEd25519(
            client.xPublic + serverX,
            client.edPrivate
        )
        val encryptedClientSignature = clientCipher.update(clientSignature)
        val stageTwoRequest = ByteArray(68)
        stageTwoRequest[0] = 0
        System.arraycopy(encryptedClientSignature, 0, stageTwoRequest, 4, 64)

        assertArrayEquals(ByteArray(0), session.handlePairVerify(stageTwoRequest))
    }

    @Test
    fun pairVerifyRejectsTamperedStageTwo() {
        val server = newServer()
        val client = newClient()
        val session = AirPlayCryptoSession(server.keyPair, SessionManagerImpl(), false)
        val stageOne = session.handlePairVerify(stageOneRequest(client))
        val clientCipher = client.cipherFor(stageOne.copyOfRange(0, 32))
        clientCipher.update(stageOne.copyOfRange(32, 96))

        val tampered = ByteArray(68)
        tampered[0] = 0
        for (index in 4 until 68) tampered[index] = 0x42
        assertThrows(SecurityException::class.java) {
            session.handlePairVerify(tampered)
        }
    }

    @Test
    fun pinDisabledPairSetupReturnsServerPublicKey() {
        val server = newServer()
        val session = AirPlayCryptoSession(server.keyPair, SessionManagerImpl(), false)
        val response = session.handlePairSetup(byteArrayOf(1))
        assertArrayEquals(
            (server.keyPair.public as Ed25519PublicKeyParameters).encoded,
            response
        )
    }

    @Test
    fun pinEnabledPairSetupStartsSrpChallenge() {
        val server = newServer()
        val manager = SessionManagerImpl()
        val session = AirPlayCryptoSession(server.keyPair, manager, true)
        val request = Tlv8.encode(
            listOf(
                Tlv8.Item(Tlv8.METHOD, byteArrayOf(0)),
                Tlv8.Item(Tlv8.STATE, byteArrayOf(1))
            )
        )
        val response = Tlv8.decode(session.handlePairSetup(request))
        val state = response.first { it.type == Tlv8.STATE }.value.first().toInt() and 0xFF
        val salt = response.first { it.type == Tlv8.SALT }.value
        val publicKey = response.first { it.type == Tlv8.PUBLIC_KEY }.value
        assertEquals(2, state)
        assertEquals(16, salt.size)
        assertEquals(384, publicKey.size)
        assertEquals(1, manager.pinChallenges.value.size)
    }

    @Test
    fun fairPlayStageOneAndTwoHaveRequiredFraming() {
        val server = newServer()
        val session = AirPlayCryptoSession(server.keyPair, SessionManagerImpl(), false)
        val stageOneRequest = ByteArray(16)
        stageOneRequest[0] = 0x46
        stageOneRequest[1] = 0x50
        stageOneRequest[2] = 0x4C
        stageOneRequest[3] = 0x59
        stageOneRequest[4] = 1
        stageOneRequest[14] = 3
        val stageOne = session.handleFairPlaySetup(stageOneRequest)
        assertEquals(142, stageOne.size)
        assertEquals(0x46, stageOne[0].toInt() and 0xFF)
        assertEquals(0x50, stageOne[1].toInt() and 0xFF)
        assertEquals(0x4C, stageOne[2].toInt() and 0xFF)
        assertEquals(0x59, stageOne[3].toInt() and 0xFF)
        assertEquals(3, stageOne[4].toInt() and 0xFF)
        assertEquals(3, stageOne[13].toInt() and 0xFF)

        val stageTwoRequest = ByteArray(40)
        stageTwoRequest[0] = 0x46
        stageTwoRequest[1] = 0x50
        stageTwoRequest[2] = 0x4C
        stageTwoRequest[3] = 0x59
        stageTwoRequest[4] = 3
        for (index in 20 until 40) stageTwoRequest[index] = index.toByte()
        val stageTwo = session.handleFairPlaySetup(stageTwoRequest)
        assertEquals(32, stageTwo.size)
        assertEquals(0x46, stageTwo[0].toInt() and 0xFF)
        assertEquals(3, stageTwo[4].toInt() and 0xFF)
        assertArrayEquals(stageTwoRequest.copyOfRange(20, 40), stageTwo.copyOfRange(12, 32))
    }

    @Test
    fun streamCipherDecryptsInPlaceAfterHandshake() {
        val server = newServer()
        val client = newClient()
        val session = AirPlayCryptoSession(server.keyPair, SessionManagerImpl(), false)
        val stageOne = session.handlePairVerify(stageOneRequest(client))
        val serverX = stageOne.copyOfRange(0, 32)
        val clientCipher = client.cipherFor(serverX)
        clientCipher.update(stageOne.copyOfRange(32, 96))
        val clientSignature = signEd25519(client.xPublic + serverX, client.edPrivate)
        val stageTwoRequest = ByteArray(68)
        stageTwoRequest[0] = 0
        System.arraycopy(clientCipher.update(clientSignature), 0, stageTwoRequest, 4, 64)
        session.handlePairVerify(stageTwoRequest)

        val streamId = 0x1122334455667788UL
        session.initMirroringStreamCipher(streamId, null, null)
        val clientStreamCipher = client.streamCipher(serverX, streamId)
        val plaintext = ByteArray(257) { index -> (index * 31).toByte() }
        val ciphertext = clientStreamCipher.update(plaintext)
        val inPlace = ciphertext.copyOf()
        session.decryptVideoInPlace(inPlace, 0, inPlace.size)
        assertArrayEquals(plaintext, inPlace)
    }


    @Test
    fun pinEnabledPairSetupValidatesSrpProof() {
        val server = newServer()
        val manager = SessionManagerImpl()
        val session = AirPlayCryptoSession(server.keyPair, manager, true)
        val startRequest = Tlv8.encode(
            listOf(
                Tlv8.Item(Tlv8.METHOD, byteArrayOf(0)),
                Tlv8.Item(Tlv8.STATE, byteArrayOf(1))
            )
        )
        val startResponse = Tlv8.decode(session.handlePairSetup(startRequest))
        val salt = startResponse.first { it.type == Tlv8.SALT }.value
        val serverPublic = startResponse.first { it.type == Tlv8.PUBLIC_KEY }.value
        val challenge = manager.pinChallenges.value.single()

        val client = SRP6Client()
        client.init(SRP6StandardGroups.rfc5054_3072, SHA512Digest(), random)
        val clientPublic = client.generateClientCredentials(
            salt,
            "Pair-Setup".toByteArray(Charsets.US_ASCII),
            challenge.code.value.toByteArray(Charsets.US_ASCII)
        )
        client.calculateSecret(java.math.BigInteger(1, serverPublic))
        val clientProof = client.calculateClientEvidenceMessage()
        val finishRequest = Tlv8.encode(
            listOf(
                Tlv8.Item(Tlv8.METHOD, byteArrayOf(0)),
                Tlv8.Item(Tlv8.STATE, byteArrayOf(3)),
                Tlv8.Item(Tlv8.PUBLIC_KEY, clientPublic.toByteArray()),
                Tlv8.Item(Tlv8.PROOF, clientProof.toByteArray())
            )
        )
        val finishResponse = Tlv8.decode(session.handlePairSetup(finishRequest))
        val serverProof = finishResponse.first { it.type == Tlv8.PROOF }.value
        assertTrue(client.verifyServerEvidenceMessage(java.math.BigInteger(1, serverProof)))
        assertTrue(manager.pinChallenges.value.isEmpty())
    }

    private fun newServer(): ServerMaterial {
        val privateKey = Ed25519PrivateKeyParameters(random)
        return ServerMaterial(
            keyPair = AsymmetricCipherKeyPair(privateKey.generatePublicKey(), privateKey)
        )
    }

    private fun newClient(): ClientMaterial {
        val xPrivate = X25519PrivateKeyParameters(random)
        val edPrivate = Ed25519PrivateKeyParameters(random)
        return ClientMaterial(
            xPrivate = xPrivate,
            xPublic = xPrivate.generatePublicKey().encoded,
            edPrivate = edPrivate,
            edPublic = edPrivate.generatePublicKey().encoded
        )
    }

    private fun stageOneRequest(client: ClientMaterial): ByteArray {
        val request = ByteArray(68)
        request[0] = 1
        System.arraycopy(client.xPublic, 0, request, 4, 32)
        System.arraycopy(client.edPublic, 0, request, 36, 32)
        return request
    }

    private fun ClientMaterial.cipherFor(serverX: ByteArray): Cipher {
        val agreement = X25519Agreement()
        agreement.init(xPrivate)
        val secret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(serverX, 0), secret, 0)
        val key = sha512("Pair-Verify-AES-Key".toByteArray() + secret).copyOfRange(0, 16)
        val iv = sha512("Pair-Verify-AES-IV".toByteArray() + secret).copyOfRange(0, 16)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher
    }

    private fun ClientMaterial.streamCipher(serverX: ByteArray, streamId: ULong): Cipher {
        val agreement = X25519Agreement()
        agreement.init(xPrivate)
        val secret = ByteArray(agreement.agreementSize)
        agreement.calculateAgreement(X25519PublicKeyParameters(serverX, 0), secret, 0)
        val id = java.nio.ByteBuffer.allocate(8)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .putLong(streamId.toLong())
            .array()
        val key = sha512("AirPlayStreamKey".toByteArray() + id + secret).copyOfRange(0, 16)
        val iv = sha512("AirPlayStreamIV".toByteArray() + id + secret).copyOfRange(0, 16)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher
    }

    private fun signEd25519(
        message: ByteArray,
        privateKey: Ed25519PrivateKeyParameters
    ): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, privateKey)
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

    private data class ServerMaterial(val keyPair: AsymmetricCipherKeyPair)

    private data class ClientMaterial(
        val xPrivate: X25519PrivateKeyParameters,
        val xPublic: ByteArray,
        val edPrivate: Ed25519PrivateKeyParameters,
        val edPublic: ByteArray
    )
}