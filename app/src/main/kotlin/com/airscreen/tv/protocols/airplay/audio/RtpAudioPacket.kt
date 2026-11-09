package com.airscreen.tv.protocols.airplay.audio

data class RtpAudioPacket(
    val sequenceNumber: Int,
    val timestamp: Long,
    val payloadType: Int,
    val ssrc: Long,
    val payload: ByteArray
)

object RtpAudioPacketCodec {
    fun parse(packet: ByteArray): RtpAudioPacket? {
        if (packet.size < 12) return null
        val first = packet[0].toInt() and 0xFF
        val version = (first ushr 6) and 0x03
        if (version != 2) return null
        val padding = (first and 0x20) != 0
        val extension = (first and 0x10) != 0
        val csrcCount = first and 0x0F
        var headerLength = 12 + csrcCount * 4
        if (headerLength > packet.size) return null
        if (extension) {
            if (headerLength + 4 > packet.size) return null
            val extensionWords = readUInt16(packet, headerLength + 2)
            headerLength += 4 + extensionWords * 4
            if (headerLength > packet.size) return null
        }
        var payloadEnd = packet.size
        if (padding) {
            val paddingLength = packet[packet.size - 1].toInt() and 0xFF
            if (paddingLength <= 0 || paddingLength > payloadEnd - headerLength) return null
            payloadEnd -= paddingLength
        }
        if (headerLength >= payloadEnd) return null
        return RtpAudioPacket(
            sequenceNumber = readUInt16(packet, 2),
            timestamp = readUInt32(packet, 4),
            payloadType = packet[1].toInt() and 0x7F,
            ssrc = readUInt32(packet, 8),
            payload = packet.copyOfRange(headerLength, payloadEnd)
        )
    }

    private fun readUInt16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or
            (data[offset + 1].toInt() and 0xFF)

    private fun readUInt32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)
}
