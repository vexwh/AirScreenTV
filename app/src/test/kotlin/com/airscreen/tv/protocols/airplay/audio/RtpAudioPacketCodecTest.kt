package com.airscreen.tv.protocols.airplay.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class RtpAudioPacketCodecTest {

    @Test
    fun parsesBigEndianRtpHeaderAndPayload() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val packet = ByteArray(12 + payload.size)
        packet[0] = 0x80.toByte()
        packet[1] = 0x60
        packet[2] = 0x00
        packet[3] = 0x2A
        packet[4] = 0x00
        packet[5] = 0x00
        packet[6] = 0x11
        packet[7] = 0x22
        packet[8] = 0x33
        packet[9] = 0x44
        packet[10] = 0x55
        packet[11] = 0x66
        System.arraycopy(payload, 0, packet, 12, payload.size)

        val parsed = RtpAudioPacketCodec.parse(packet)
        assertNotNull(parsed)
        assertEquals(42, parsed?.sequenceNumber)
        assertEquals(0x1122L, parsed?.timestamp)
        assertEquals(96, parsed?.payloadType)
        assertEquals(0x33445566L, parsed?.ssrc)
        assertArrayEquals(payload, parsed?.payload)
    }

    @Test
    fun rejectsTruncatedOrInvalidRtp() {
        assertNull(RtpAudioPacketCodec.parse(byteArrayOf(0x80.toByte())))
        assertNull(RtpAudioPacketCodec.parse(ByteArray(20)))
    }

    @Test
    fun volumeMappingCoversDbRange() {
        assertEquals(0f, AudioVolume.gainFromDb(-30f), 0.0001f)
        assertEquals(0.5f, AudioVolume.gainFromDb(-15f), 0.0001f)
        assertEquals(1f, AudioVolume.gainFromDb(0f), 0.0001f)
        assertEquals(0f, AudioVolume.gainFromDb(-100f), 0.0001f)
    }
}
