package com.airscreen.tv.protocols.airplay.stream

import com.airscreen.tv.domain.model.VideoCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MirroringPacketCodecTest {

    @Test
    fun parsesLittleEndianMirroringHeader() {
        val header = ByteArray(128)
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(4096)
            putShort(1)
            putShort(7)
            putLong(0x123456789ABCDEF0L)
            putFloat(3840f)
            putFloat(2160f)
        }
        val parsed = MirroringPacketCodec.parseHeader(header)
        assertEquals(4096, parsed.payloadSize)
        assertEquals(1, parsed.payloadType)
        assertEquals(7, parsed.payloadOption)
        assertEquals(0x123456789ABCDEF0L, parsed.ntpTimestamp)
        assertEquals(3840f, parsed.width)
        assertEquals(2160f, parsed.height)
    }

    @Test
    fun convertsAvccToAnnexBInPlaceAndDetectsIdr() {
        val idr = byteArrayOf(0x65, 0x11, 0x22)
        val slice = byteArrayOf(0x41, 0x33)
        val buffer = ByteArray(4 + idr.size + 4 + slice.size)
        var offset = 0
        ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(idr.size)
            put(idr)
            putInt(slice.size)
            put(slice)
        }
        assertEquals(4 + idr.size, offset + 4 + idr.size)
        assertTrue(MirroringPacketCodec.avccToAnnexBInPlace(buffer, 0, buffer.size))
        assertEquals(0, buffer[0].toInt())
        assertEquals(0, buffer[1].toInt())
        assertEquals(0, buffer[2].toInt())
        assertEquals(1, buffer[3].toInt())
        assertEquals(0x65, buffer[4].toInt() and 0xFF)
        assertEquals(0, buffer[4 + idr.size].toInt())
        assertEquals(1, buffer[7 + idr.size].toInt())
        assertTrue(MirroringPacketCodec.containsKeyFrame(buffer, 0, buffer.size, VideoCodec.H264))
    }

    @Test
    fun rejectsMalformedAvcc() {
        val buffer = byteArrayOf(0, 0, 0, 8, 0x65, 1, 2)
        assertFalse(MirroringPacketCodec.avccToAnnexBInPlace(buffer, 0, buffer.size))
    }

    @Test
    fun parsesAvcCToAnnexBParameterSets() {
        val sps = byteArrayOf(0x67, 0x42, 0x00, 0x1F)
        val pps = byteArrayOf(0x68, 0xCE.toByte(), 0x3C)
        val record = ByteArray(6 + 2 + sps.size + 1 + 2 + pps.size)
        var cursor = 0
        record[cursor++] = 1
        record[cursor++] = 0x64
        record[cursor++] = 0x00
        record[cursor++] = 0x1F
        record[cursor++] = 0xFF.toByte()
        record[cursor++] = 0xE1.toByte()
        record[cursor++] = 0
        record[cursor++] = sps.size.toByte()
        for (byte in sps) record[cursor++] = byte
        record[cursor++] = 1
        record[cursor++] = 0
        record[cursor++] = pps.size.toByte()
        for (byte in pps) record[cursor++] = byte

        val parsed = MirroringPacketCodec.parseCodecConfig(record)
        assertEquals(VideoCodec.H264, parsed.codec)
        assertEquals(4 + sps.size, parsed.sps.size)
        assertEquals(4 + pps.size, parsed.pps.size)
        assertEquals(0x67, parsed.sps[4].toInt() and 0xFF)
        assertEquals(0x68, parsed.pps[4].toInt() and 0xFF)
    }

    @Test
    fun convertsNtpFixedPointToMicros() {
        val ntp = (1234L shl 32) or 0x80000000L
        val micros = MirroringPacketCodec.ntpToMicros(ntp)
        assertTrue(micros in 1234000000L..1234500000L)
        assertEquals(0L, MirroringPacketCodec.relativeMicros(ntp, ntp))
    }

    @Test
    fun detectsHevcIrapFrames() {
        val nal = byteArrayOf(0x26, 0x01, 0x02)
        val buffer = ByteArray(4 + nal.size)
        ByteBuffer.wrap(buffer).order(ByteOrder.BIG_ENDIAN).putInt(nal.size).put(nal)
        assertTrue(MirroringPacketCodec.avccToAnnexBInPlace(buffer, 0, buffer.size))
        assertTrue(MirroringPacketCodec.containsKeyFrame(buffer, 0, buffer.size, VideoCodec.HEVC))
    }
}