package com.airscreen.tv.protocols.airplay.crypto

object Tlv8 {
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val STATE = 0x06
    const val ERROR = 0x07
    const val SIGNATURE = 0x0A
    const val FLAGS = 0x13

    data class Item(val type: Int, val value: ByteArray)

    fun decode(bytes: ByteArray): List<Item> {
        val result = ArrayList<Item>()
        var offset = 0
        var pendingType = -1
        val pending = ArrayList<Byte>()
        while (offset < bytes.size) {
            if (offset + 2 > bytes.size) throw SecurityException("Truncated TLV8")
            val type = bytes[offset].toInt() and 0xFF
            val length = bytes[offset + 1].toInt() and 0xFF
            offset += 2
            if (offset + length > bytes.size) throw SecurityException("Truncated TLV8 value")
            if (pendingType != type && pending.isNotEmpty()) {
                result += Item(pendingType, pending.toByteArray())
                pending.clear()
            }
            pendingType = type
            for (index in 0 until length) pending.add(bytes[offset + index])
            offset += length
            if (length < 255) {
                result += Item(type, pending.toByteArray())
                pending.clear()
                pendingType = -1
            }
        }
        if (pending.isNotEmpty()) result += Item(pendingType, pending.toByteArray())
        return result
    }

    fun encode(items: List<Item>): ByteArray {
        val output = ArrayList<Byte>()
        for (item in items) {
            if (item.value.isEmpty()) {
                output.add(item.type.toByte())
                output.add(0)
                continue
            }
            var offset = 0
            while (offset < item.value.size) {
                val length = minOf(255, item.value.size - offset)
                output.add(item.type.toByte())
                output.add(length.toByte())
                for (index in 0 until length) output.add(item.value[offset + index])
                offset += length
            }
        }
        return output.toByteArray()
    }
}
