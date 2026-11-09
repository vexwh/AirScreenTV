package com.airscreen.tv.protocols.airplay.plist

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.LinkedHashMap
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

object AirPlayPlistCodec {

    fun decode(bytes: ByteArray): Any? {
        if (bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(BINARY_MAGIC)) {
            return BinaryPlist.decode(bytes)
        }
        val first = bytes.firstOrNull { byte ->
            byte.toInt().toChar().let { !it.isWhitespace() }
        }
        if (first == '<'.code.toByte() || String(bytes, Charsets.ISO_8859_1).contains("<plist")) {
            return XmlPlist.decode(bytes)
        }
        throw IllegalArgumentException("Unsupported plist payload")
    }

    fun encodeBinary(value: Any?): ByteArray = BinaryPlist.encode(value)

    fun encodeXml(value: Any?): ByteArray = XmlPlist.encode(value)

    fun encode(value: Any?, binary: Boolean): ByteArray =
        if (binary) encodeBinary(value) else encodeXml(value)

    fun isBinary(bytes: ByteArray): Boolean =
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(BINARY_MAGIC)

    private val BINARY_MAGIC = "bplist00".toByteArray(Charsets.US_ASCII)

    private object BinaryPlist {
        private const val MAX_DEPTH = 64

        fun decode(data: ByteArray): Any? {
            if (data.size < 8 + 32) throw IllegalArgumentException("Truncated binary plist")
            val trailer = data.size - 32
            val offsetIntSize = data[trailer + 6].toInt() and 0xFF
            val objectRefSize = data[trailer + 7].toInt() and 0xFF
            val numObjects = readUInt(data, trailer + 8, 8)
            val topObject = readUInt(data, trailer + 16, 8).toInt()
            val offsetTableOffset = readUInt(data, trailer + 24, 8).toInt()
            if (offsetIntSize !in 1..8 || objectRefSize !in 1..8) {
                throw IllegalArgumentException("Invalid binary plist trailer")
            }
            if (numObjects <= 0 || numObjects > data.size) {
                throw IllegalArgumentException("Invalid binary plist object count")
            }
            if (offsetTableOffset < 8 || offsetTableOffset + numObjects * offsetIntSize > trailer) {
                throw IllegalArgumentException("Invalid binary plist offset table")
            }
            val offsets = IntArray(numObjects.toInt()) { index ->
                readUInt(data, offsetTableOffset + index * offsetIntSize, offsetIntSize).toInt()
            }
            if (topObject < 0 || topObject >= offsets.size) {
                throw IllegalArgumentException("Invalid binary plist root object")
            }
            val reader = BinaryReader(data, offsets, objectRefSize)
            return reader.parse(topObject, 0, mutableSetOf())
        }

        fun encode(value: Any?): ByteArray {
            val nodes = ArrayList<Any?>()
            val children = ArrayList<List<Int>>()
            flatten(value, nodes, children, 0)
            val refSize = minimalBytes(nodes.size - 1)
            val encodedObjects = ArrayList<ByteArray>(nodes.size)
            for (index in nodes.indices) {
                encodedObjects += encodeNode(nodes[index], children[index], refSize)
            }
            var total = 8
            for (bytes in encodedObjects) total += bytes.size
            val offsetSize = minimalBytes(total)
            val output = ArrayList<Byte>(total + nodes.size * offsetSize + 32)
            for (byte in BINARY_MAGIC) output.add(byte)
            val offsets = IntArray(encodedObjects.size)
            for (index in encodedObjects.indices) {
                offsets[index] = output.size
                encodedObjects[index].forEach { output.add(it) }
            }
            val offsetTableOffset = output.size
            for (offset in offsets) writeUInt(output, offset.toLong(), offsetSize)
            repeat(6) { output.add(0.toByte()) }
            output.add(offsetSize.toByte())
            output.add(refSize.toByte())
            writeUInt(output, nodes.size.toLong(), 8)
            writeUInt(output, 0L, 8)
            writeUInt(output, offsetTableOffset.toLong(), 8)
            return output.toByteArray()
        }

        private fun flatten(
            value: Any?,
            nodes: MutableList<Any?>,
            children: MutableList<List<Int>>,
            depth: Int
        ): Int {
            if (depth > MAX_DEPTH) throw IllegalArgumentException("Plist nesting is too deep")
            val index = nodes.size
            nodes.add(value)
            children.add(emptyList())
            when (value) {
                is Map<*, *> -> {
                    val refs = ArrayList<Int>(value.size * 2)
                    val entries = value.entries.toList()
                    for (entry in entries) {
                        refs += flatten(entry.key, nodes, children, depth + 1)
                    }
                    for (entry in entries) {
                        refs += flatten(entry.value, nodes, children, depth + 1)
                    }
                    children[index] = refs
                }
                is List<*> -> {
                    val refs = ArrayList<Int>(value.size)
                    for (childValue in value) {
                        refs += flatten(childValue, nodes, children, depth + 1)
                    }
                    children[index] = refs
                }
            }
            return index
        }

        private fun encodeNode(value: Any?, childIndices: List<Int>, refSize: Int): ByteArray {
            val output = ArrayList<Byte>()
            when (value) {
                null -> output.add(0x00.toByte())
                is Boolean -> output.add(if (value) 0x09.toByte() else 0x08.toByte())
                is Byte, is Short, is Int, is Long -> {
                    val longValue = (value as Number).toLong()
                    val encoded = encodeInteger(longValue)
                    output.addAll(encoded.toList())
                }
                is Float -> {
                    output.add(0x23.toByte())
                    writeLongBytes(output, java.lang.Double.doubleToLongBits(value.toDouble()), 8)
                }
                is Double -> {
                    output.add(0x23)
                    writeLongBytes(output, java.lang.Double.doubleToLongBits(value), 8)
                }
                is String -> {
                    val ascii = value.all { it.code < 0x80 }
                    val bytes = if (ascii) {
                        value.toByteArray(Charsets.US_ASCII)
                    } else {
                        value.toByteArray(Charsets.UTF_16BE)
                    }
                    output.add(if (ascii) 0x50.toByte() else 0x60.toByte())
                    appendLength(output, bytes.size / if (ascii) 1 else 2)
                    bytes.forEach { output.add(it) }
                }
                is ByteArray -> {
                    output.add(0x40.toByte())
                    appendLength(output, value.size)
                    value.forEach { output.add(it) }
                }
                is Map<*, *> -> {
                    output.add(0xD0.toByte())
                    appendLength(output, childIndices.size / 2)
                    val keyCount = childIndices.size / 2
                    for (index in 0 until keyCount) {
                        writeUInt(output, childIndices[index].toLong(), refSize)
                    }
                    for (index in 0 until keyCount) {
                        writeUInt(output, childIndices[keyCount + index].toLong(), refSize)
                    }
                }
                is List<*> -> {
                    output.add(0xA0.toByte())
                    appendLength(output, childIndices.size)
                    for (childIndex in childIndices) {
                        writeUInt(output, childIndex.toLong(), refSize)
                    }
                }
                else -> throw IllegalArgumentException(
                    "Unsupported plist value type: " + value.javaClass.name
                )
            }
            return output.toByteArray()
        }

        private fun appendLength(output: MutableList<Byte>, length: Int) {
            if (length < 15) {
                val last = output.removeAt(output.lastIndex)
                output.add((last.toInt() or length).toByte())
            } else {
                val last = output.removeAt(output.lastIndex)
                output.add((last.toInt() or 0x0F).toByte())
                val encoded = encodeInteger(length.toLong())
                encoded.forEach { output.add(it) }
            }
        }

        private fun encodeInteger(value: Long): ByteArray {
            val size = when {
                value in Byte.MIN_VALUE..Byte.MAX_VALUE -> 1
                value in Short.MIN_VALUE..Short.MAX_VALUE -> 2
                value in Int.MIN_VALUE..Int.MAX_VALUE -> 4
                else -> 8
            }
            val output = ArrayList<Byte>()
            output.add((0x10 or sizeCode(size)).toByte())
            writeLongBytes(output, value, size)
            return output.toByteArray()
        }

        private fun sizeCode(size: Int): Int = when (size) {
            1 -> 0
            2 -> 1
            4 -> 2
            8 -> 3
            else -> throw IllegalArgumentException("Unsupported integer size")
        }

        private class BinaryReader(
            private val data: ByteArray,
            private val offsets: IntArray,
            private val objectRefSize: Int
        ) {
            fun parse(index: Int, depth: Int, stack: MutableSet<Int>): Any? {
                if (depth > MAX_DEPTH) throw IllegalArgumentException("Plist nesting is too deep")
                if (index !in offsets.indices) throw IllegalArgumentException("Bad object reference")
                if (!stack.add(index)) throw IllegalArgumentException("Cyclic plist object graph")
                try {
                    val offset = offsets[index]
                    if (offset < 0 || offset >= data.size) throw IllegalArgumentException("Bad object offset")
                    val marker = data[offset].toInt() and 0xFF
                    val family = marker ushr 4
                    val low = marker and 0x0F
                    return when (family) {
                        0x0 -> when (marker) {
                            0x00 -> null
                            0x08 -> false
                            0x09 -> true
                            else -> null
                        }
                        0x1 -> {
                            val size = 1 shl low
                            if (size > 8) {
                                BigInteger(1, data.copyOfRange(offset + 1, offset + 1 + size)).toLong()
                            } else {
                                readSigned(data, offset + 1, size)
                            }
                        }
                        0x2 -> {
                            if (low == 2) {
                                java.lang.Float.intBitsToFloat(
                                    readInt(data, offset + 1, 4).toInt()
                                ).toDouble()
                            } else {
                                java.lang.Double.longBitsToDouble(readLong(data, offset + 1, 8))
                            }
                        }
                        0x3 -> java.lang.Double.longBitsToDouble(readLong(data, offset + 1, 8))
                        0x4 -> {
                            val length = readLength(offset, low)
                            data.copyOfRange(offset + length.second, offset + length.second + length.first)
                        }
                        0x5 -> {
                            val length = readLength(offset, low)
                            String(
                                data,
                                offset + length.second,
                                length.first,
                                StandardCharsets.US_ASCII
                            )
                        }
                        0x6 -> {
                            val length = readLength(offset, low)
                            String(
                                data,
                                offset + length.second,
                                length.first * 2,
                                StandardCharsets.UTF_16BE
                            )
                        }
                        0x8 -> {
                            val size = 1 shl low
                            readUInt(data, offset + 1, size)
                        }
                        0xA -> {
                            val length = readLength(offset, low)
                            val result = ArrayList<Any?>(length.first)
                            var refOffset = offset + length.second
                            repeat(length.first) {
                                val child = readUInt(data, refOffset, objectRefSize).toInt()
                                result += parse(child, depth + 1, stack)
                                refOffset += objectRefSize
                            }
                            result
                        }
                        0xB, 0xC -> {
                            val length = readLength(offset, low)
                            val result = ArrayList<Any?>(length.first)
                            var refOffset = offset + length.second
                            repeat(length.first) {
                                val child = readUInt(data, refOffset, objectRefSize).toInt()
                                result += parse(child, depth + 1, stack)
                                refOffset += objectRefSize
                            }
                            result
                        }
                        0xD -> {
                            val length = readLength(offset, low)
                            val result = LinkedHashMap<String, Any?>(length.first)
                            var keyOffset = offset + length.second
                            val valueOffset = keyOffset + length.first * objectRefSize
                            repeat(length.first) { index ->
                                val keyObject = parse(
                                    readUInt(data, keyOffset, objectRefSize).toInt(),
                                    depth + 1,
                                    stack
                                )
                                val childValue = parse(
                                    readUInt(
                                        data,
                                        valueOffset + index * objectRefSize,
                                        objectRefSize
                                    ).toInt(),
                                    depth + 1,
                                    stack
                                )
                                result[keyObject?.toString() ?: ""] = childValue
                                keyOffset += objectRefSize
                            }
                            result
                        }
                        else -> throw IllegalArgumentException("Unsupported plist marker")
                    }
                } finally {
                    stack.remove(index)
                }
            }

            private fun readLength(offset: Int, low: Int): Pair<Int, Int> {
                return if (low == 0x0F) {
                    val marker = data[offset + 1].toInt() and 0xFF
                    val size = 1 shl (marker and 0x0F)
                    val length = readUInt(data, offset + 2, size).toInt()
                    Pair(length, 2 + size)
                } else {
                    Pair(low, 1)
                }
            }
        }
    }

    private object XmlPlist {
        fun decode(bytes: ByteArray): Any? {
            val factory = DocumentBuilderFactory.newInstance()
            factory.isNamespaceAware = false
            runCatching {
                factory.setFeature("http://xml.org/sax/features/external-general-entities", false)
            }
            runCatching {
                factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            runCatching {
                factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }
            runCatching { factory.isXIncludeAware = false }
            runCatching { factory.isExpandEntityReferences = false }
            val document = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
            val root = document.documentElement ?: throw IllegalArgumentException("Missing plist root")
            val plist = if (root.tagName.equals("plist", ignoreCase = true)) {
                firstElementChild(root) ?: throw IllegalArgumentException("Empty plist")
            } else {
                root
            }
            return parseElement(plist)
        }

        fun encode(value: Any?): ByteArray {
            val builder = StringBuilder()
            builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            builder.append("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" ")
            builder.append("\"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
            builder.append("<plist version=\"1.0\">\n")
            appendValue(builder, value, 0)
            builder.append("\n</plist>\n")
            return builder.toString().toByteArray(Charsets.UTF_8)
        }

        private fun parseElement(element: Element): Any? {
            return when (element.tagName.lowercase()) {
                "dict" -> {
                    val result = LinkedHashMap<String, Any?>()
                    var child = element.firstChild
                    while (child != null) {
                        if (child is Element && child.tagName.equals("key", true)) {
                            val key = child.textContent ?: ""
                            var valueNode = child.nextSibling
                            while (valueNode != null && valueNode !is Element) {
                                valueNode = valueNode.nextSibling
                            }
                            result[key] = if (valueNode is Element) {
                                parseElement(valueNode)
                            } else {
                                null
                            }
                            child = valueNode ?: child
                        }
                        child = child.nextSibling
                    }
                    result
                }
                "array" -> {
                    val result = ArrayList<Any?>()
                    var child = element.firstChild
                    while (child != null) {
                        if (child is Element) result += parseElement(child)
                        child = child.nextSibling
                    }
                    result
                }
                "string" -> element.textContent ?: ""
                "integer" -> parseLong(element.textContent ?: "0")
                "real" -> (element.textContent ?: "0").trim().toDoubleOrNull() ?: 0.0
                "true" -> true
                "false" -> false
                "data" -> {
                    val text = (element.textContent ?: "").filterNot { it.isWhitespace() }
                    Base64.getMimeDecoder().decode(text)
                }
                "date" -> element.textContent ?: ""
                else -> element.textContent ?: ""
            }
        }

        private fun appendValue(builder: StringBuilder, value: Any?, indent: Int) {
            val pad = "    ".repeat(indent)
            val childPad = "    ".repeat(indent + 1)
            when (value) {
                is Map<*, *> -> {
                    builder.append(pad).append("<dict>")
                    for ((key, childValue) in value) {
                        builder.append("\n").append(childPad).append("<key>")
                            .append(escape(key?.toString() ?: ""))
                            .append("</key>")
                        builder.append("\n")
                        appendValue(builder, childValue, indent + 1)
                    }
                    builder.append("\n").append(pad).append("</dict>")
                }
                is List<*> -> {
                    builder.append(pad).append("<array>")
                    for (childValue in value) {
                        builder.append("\n")
                        appendValue(builder, childValue, indent + 1)
                    }
                    builder.append("\n").append(pad).append("</array>")
                }
                is Boolean -> builder.append(pad).append(if (value) "<true/>" else "<false/>")
                is ByteArray -> builder.append(pad).append("<data>")
                    .append(Base64.getEncoder().encodeToString(value))
                    .append("</data>")
                is Double, is Float -> builder.append(pad).append("<real>")
                    .append((value as Number).toDouble().toString()).append("</real>")
                is Byte, is Short, is Int, is Long -> builder.append(pad)
                    .append("<integer>").append((value as Number).toLong()).append("</integer>")
                null -> builder.append(pad).append("<string></string>")
                else -> builder.append(pad).append("<string>")
                    .append(escape(value.toString())).append("</string>")
            }
        }

        private fun firstElementChild(element: Element): Element? {
            var child: Node? = element.firstChild
            while (child != null) {
                if (child is Element) return child
                child = child.nextSibling
            }
            return null
        }

        private fun parseLong(raw: String): Long {
            val value = raw.trim()
            return when {
                value.startsWith("0x", ignoreCase = true) -> value.substring(2).toLongOrNull(16) ?: 0L
                value.startsWith("-0x", ignoreCase = true) ->
                    -(value.substring(3).toLongOrNull(16) ?: 0L)
                else -> value.toLongOrNull() ?: 0L
            }
        }

        private fun escape(value: String): String = buildString(value.length) {
            for (character in value) {
                when (character) {
                    '&' -> append("&amp;")
                    '<' -> append("&lt;")
                    '>' -> append("&gt;")
                    '"' -> append("&quot;")
                    '\'' -> append("&apos;")
                    else -> append(character)
                }
            }
        }
    }

    private fun minimalBytes(value: Int): Int {
        val safe = value.coerceAtLeast(0)
        return when {
            safe <= 0xFF -> 1
            safe <= 0xFFFF -> 2
            safe <= 0xFFFFFF -> 3
            else -> 4
        }
    }

    private fun writeUInt(output: MutableList<Byte>, value: Long, size: Int) {
        for (shift in size - 1 downTo 0) {
            output.add(((value ushr (shift * 8)) and 0xFF).toByte())
        }
    }

    private fun writeLongBytes(output: MutableList<Byte>, value: Long, size: Int) {
        for (shift in size - 1 downTo 0) {
            output.add(((value ushr (shift * 8)) and 0xFF).toByte())
        }
    }

    private fun readUInt(data: ByteArray, offset: Int, size: Int): Long {
        if (offset < 0 || size !in 1..8 || offset + size > data.size) {
            throw IllegalArgumentException("Truncated plist integer")
        }
        var result = 0L
        for (index in 0 until size) {
            result = (result shl 8) or (data[offset + index].toLong() and 0xFF)
        }
        return result
    }

    private fun readSigned(data: ByteArray, offset: Int, size: Int): Long {
        val unsigned = readUInt(data, offset, size)
        if (size == 8) return unsigned
        val shift = 64 - size * 8
        return (unsigned shl shift) shr shift
    }

    private fun readInt(data: ByteArray, offset: Int, size: Int): Long =
        readUInt(data, offset, size)

    private fun readLong(data: ByteArray, offset: Int, size: Int): Long =
        readUInt(data, offset, size)
}