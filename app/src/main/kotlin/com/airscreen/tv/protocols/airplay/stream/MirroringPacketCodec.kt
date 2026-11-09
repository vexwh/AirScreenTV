package com.airscreen.tv.protocols.airplay.stream

import com.airscreen.tv.domain.model.VideoCodec
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class MirroringPacketHeader(
    val payloadSize: Int,
    val payloadType: Int,
    val payloadOption: Int,
    val ntpTimestamp: Long,
    val width: Float,
    val height: Float
)

data class ParsedVideoCodecConfig(
    val codec: VideoCodec,
    val sps: ByteArray,
    val pps: ByteArray,
    val vps: ByteArray?
)

object MirroringPacketCodec {
    const val HEADER_SIZE = 128
    const val MAX_PAYLOAD_SIZE = 16 * 1024 * 1024
    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    fun parseHeader(header: ByteArray): MirroringPacketHeader {
        require(header.size >= HEADER_SIZE) { "Mirroring header must be 128 bytes" }
        val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        return MirroringPacketHeader(
            payloadSize = buffer.getInt(0),
            // RPiPlay masks the packet type to its low byte; video packets
            // arrive as 0x1000 and would otherwise be ignored.
            payloadType = buffer.getShort(4).toInt() and 0xFF,
            payloadOption = buffer.getShort(6).toInt() and 0xFFFF,
            ntpTimestamp = buffer.getLong(8),
            width = buffer.getFloat(16),
            height = buffer.getFloat(20)
        )
    }

    fun avccToAnnexBInPlace(data: ByteArray, offset: Int, length: Int): Boolean {
        if (length < 4) return false
        val end = offset + length
        var cursor = offset
        var nalCount = 0
        while (cursor + 4 <= end) {
            val nalLength = readIntBigEndian(data, cursor)
            if (nalLength <= 0) {
                // Some iOS builds pad the tail with zero bytes.
                if ((cursor until end).all { data[it] == 0.toByte() }) break
                return false
            }
            if (cursor + 4 + nalLength > end) return false
            data[cursor] = 0
            data[cursor + 1] = 0
            data[cursor + 2] = 0
            data[cursor + 3] = 1
            cursor += 4 + nalLength
            nalCount++
        }
        val tailIsZero = (cursor until end).all { data[it] == 0.toByte() }
        return nalCount > 0 && (cursor == end || tailIsZero)
    }

    fun containsKeyFrame(data: ByteArray, offset: Int, length: Int, codec: VideoCodec): Boolean {
        val end = offset + length
        var cursor = offset
        while (cursor + 4 <= end) {
            val nalLength = readIntBigEndian(data, cursor)
            if (nalLength <= 0 || cursor + 4 + nalLength > end) return false
            val nalHeader = data[cursor + 4].toInt() and 0xFF
            if (codec == VideoCodec.H264) {
                if (nalHeader and 0x1F == 5) return true
            } else {
                val nalType = (nalHeader ushr 1) and 0x3F
                if (nalType in 16..23) return true
            }
            cursor += 4 + nalLength
        }
        return false
    }

    fun parseCodecConfig(payload: ByteArray): ParsedVideoCodecConfig {
        val avcError: Throwable?
        try {
            return parseAvcC(payload)
        } catch (error: Throwable) {
            avcError = error
        }
        try {
            return parseHvcC(payload)
        } catch (error: Throwable) {
            val combined = IllegalStateException("Unsupported video codec configuration", avcError)
            combined.addSuppressed(error)
            throw combined
        }
    }

    fun ntpToMicros(ntpTimestamp: Long): Long {
        val seconds = (ntpTimestamp ushr 32) and 0xFFFFFFFFL
        val fraction = ntpTimestamp and 0xFFFFFFFFL
        val fractionMicros = (fraction * 1_000_000L) ushr 32
        return seconds * 1_000_000L + fractionMicros
    }

    fun relativeMicros(ntpTimestamp: Long, firstTimestamp: Long): Long {
        if (ntpTimestamp == 0L) return 0L
        if (firstTimestamp == 0L) return 0L
        return (ntpToMicros(ntpTimestamp) - ntpToMicros(firstTimestamp)).coerceAtLeast(0L)
    }

    private fun parseAvcC(payload: ByteArray): ParsedVideoCodecConfig {
        if (payload.size < 8 || payload[0].toInt() != 1) {
            throw IllegalArgumentException("Not an avcC record")
        }
        val numSps = payload[5].toInt() and 0x1F
        if (numSps <= 0) throw IllegalArgumentException("avcC has no SPS")
        var cursor = 6
        var sps: ByteArray? = null
        repeat(numSps) {
            val length = readUInt16(payload, cursor)
            cursor += 2
            if (length <= 0 || cursor + length > payload.size) {
                throw IllegalArgumentException("Truncated avcC SPS")
            }
            if (sps == null) sps = payload.copyOfRange(cursor, cursor + length)
            cursor += length
        }
        if (cursor >= payload.size) throw IllegalArgumentException("avcC has no PPS count")
        val numPps = payload[cursor].toInt() and 0xFF
        cursor++
        if (numPps <= 0) throw IllegalArgumentException("avcC has no PPS")
        var pps: ByteArray? = null
        repeat(numPps) {
            val length = readUInt16(payload, cursor)
            cursor += 2
            if (length <= 0 || cursor + length > payload.size) {
                throw IllegalArgumentException("Truncated avcC PPS")
            }
            if (pps == null) pps = payload.copyOfRange(cursor, cursor + length)
            cursor += length
        }
        return ParsedVideoCodecConfig(
            codec = VideoCodec.H264,
            sps = START_CODE + (sps ?: throw IllegalArgumentException("Missing SPS")),
            pps = START_CODE + (pps ?: throw IllegalArgumentException("Missing PPS")),
            vps = null
        )
    }

    private fun parseHvcC(payload: ByteArray): ParsedVideoCodecConfig {
        if (payload.size < 23 || payload[0].toInt() != 1) {
            throw IllegalArgumentException("Not an hvcC record")
        }
        val numArrays = payload[22].toInt() and 0xFF
        if (numArrays <= 0) throw IllegalArgumentException("hvcC has no parameter sets")
        var cursor = 23
        var vps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        repeat(numArrays) {
            if (cursor + 3 > payload.size) throw IllegalArgumentException("Truncated hvcC array")
            val nalType = payload[cursor].toInt() and 0x3F
            val numNalus = readUInt16(payload, cursor + 1)
            cursor += 3
            repeat(numNalus) {
                if (cursor + 2 > payload.size) throw IllegalArgumentException("Truncated hvcC NAL")
                val length = readUInt16(payload, cursor)
                cursor += 2
                if (length <= 0 || cursor + length > payload.size) {
                    throw IllegalArgumentException("Truncated hvcC NAL data")
                }
                val nal = payload.copyOfRange(cursor, cursor + length)
                cursor += length
                when (nalType) {
                    32 -> if (vps == null) vps = nal
                    33 -> if (sps == null) sps = nal
                    34 -> if (pps == null) pps = nal
                }
            }
        }
        val actualSps = sps ?: throw IllegalArgumentException("hvcC has no SPS")
        val actualPps = pps ?: throw IllegalArgumentException("hvcC has no PPS")
        return ParsedVideoCodecConfig(
            codec = VideoCodec.HEVC,
            sps = START_CODE + actualSps,
            pps = START_CODE + actualPps,
            vps = vps?.let { START_CODE + it }
        )
    }

    private fun readIntBigEndian(data: ByteArray, offset: Int): Int {
        if (offset + 4 > data.size) return -1
        return ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int {
        if (offset + 2 > data.size) throw IllegalArgumentException("Truncated parameter set")
        return ((data[offset].toInt() and 0xFF) shl 8) or
            (data[offset + 1].toInt() and 0xFF)
    }
}