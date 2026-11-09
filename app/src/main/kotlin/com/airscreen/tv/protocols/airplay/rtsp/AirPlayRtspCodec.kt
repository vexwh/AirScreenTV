package com.airscreen.tv.protocols.airplay.rtsp

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufUtil
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.ByteToMessageDecoder
import io.netty.handler.codec.MessageToByteEncoder

data class AirPlayRequest(
    val method: String,
    val uri: String,
    val version: String,
    val headers: Map<String, String>,
    val body: ByteArray
) {
    fun header(name: String): String? = headers[name.lowercase()]
}

data class AirPlayResponse(
    val version: String,
    val statusCode: Int,
    val reason: String,
    val headers: MutableMap<String, String> = LinkedHashMap(),
    val body: ByteArray = ByteArray(0),
    val contentType: String? = null
)

class AirPlayRequestDecoder : ByteToMessageDecoder() {
    override fun decode(
        ctx: ChannelHandlerContext,
        input: ByteBuf,
        out: MutableList<Any>
    ) {
        while (true) {
            val headerEnd = findHeaderEnd(input)
            if (headerEnd < 0) {
                if (input.readableBytes() > MAX_HEADER_BYTES) {
                    throw IllegalArgumentException("AirPlay headers exceed limit")
                }
                return
            }
            val headerLength = headerEnd - input.readerIndex()
            val headerText = input.toString(
                input.readerIndex(),
                headerLength,
                Charsets.US_ASCII
            )
            val lines = headerText.split("\r\n")
            if (lines.isEmpty()) throw IllegalArgumentException("Missing request line")
            val requestLine = lines[0].split(Regex("\\s+"), limit = 3)
            if (requestLine.size < 3) throw IllegalArgumentException("Malformed request line")
            val method = requestLine[0]
            val uri = requestLine[1]
            val version = requestLine[2]
            val headers = LinkedHashMap<String, String>()
            for (index in 1 until lines.size) {
                val line = lines[index]
                if (line.isEmpty()) continue
                val separator = line.indexOf(':')
                if (separator <= 0) continue
                val name = line.substring(0, separator).trim().lowercase()
                val value = line.substring(separator + 1).trim()
                headers[name] = if (headers.containsKey(name)) {
                    headers[name] + ", " + value
                } else {
                    value
                }
            }
            val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
            if (contentLength < 0 || contentLength > MAX_BODY_BYTES) {
                throw IllegalArgumentException("AirPlay body exceeds limit")
            }
            val totalLength = headerLength + 4 + contentLength
            if (input.readableBytes() < totalLength) return
            input.skipBytes(headerLength + 4)
            val body = ByteArray(contentLength)
            if (contentLength > 0) input.readBytes(body)
            out.add(AirPlayRequest(method, uri, version, headers, body))
        }
    }

    private fun findHeaderEnd(input: ByteBuf): Int {
        val start = input.readerIndex()
        val end = input.writerIndex() - 3
        if (end <= start) return -1
        for (index in start until end) {
            if (input.getByte(index) == 13.toByte() &&
                input.getByte(index + 1) == 10.toByte() &&
                input.getByte(index + 2) == 13.toByte() &&
                input.getByte(index + 3) == 10.toByte()
            ) {
                return index
            }
        }
        return -1
    }

    private companion object {
        const val MAX_HEADER_BYTES = 64 * 1024
        const val MAX_BODY_BYTES = 16 * 1024 * 1024
    }
}

class AirPlayResponseEncoder : MessageToByteEncoder<AirPlayResponse>() {
    override fun encode(
        ctx: ChannelHandlerContext,
        message: AirPlayResponse,
        out: ByteBuf
    ) {
        val version = message.version.ifBlank { "HTTP/1.1" }
        val headers = LinkedHashMap(message.headers)
        headers.putIfAbsent("Server", "AirTunes/220.68")
        headers.putIfAbsent("Audio-Jack-Status", "connected; type=digital")
        headers["Content-Length"] = message.body.size.toString()
        if (message.contentType != null && message.body.isNotEmpty()) {
            headers.putIfAbsent("Content-Type", message.contentType)
        }
        val headerText = buildString {
            append(version).append(' ').append(message.statusCode).append(' ')
                .append(message.reason).append("\r\n")
            for ((name, value) in headers) {
                append(name).append(": ").append(value).append("\r\n")
            }
            append("\r\n")
        }
        ByteBufUtil.writeAscii(out, headerText)
        if (message.body.isNotEmpty()) out.writeBytes(message.body)
    }
}
