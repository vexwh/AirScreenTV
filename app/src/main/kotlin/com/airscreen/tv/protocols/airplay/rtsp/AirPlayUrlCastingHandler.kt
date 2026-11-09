package com.airscreen.tv.protocols.airplay.rtsp

import com.airscreen.tv.domain.contract.CastUrlController
import com.airscreen.tv.domain.model.CastPlaybackState
import com.airscreen.tv.protocols.airplay.plist.AirPlayPlistCodec
import java.net.URLDecoder
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

data class AirPlayCastingResponse(
    val statusCode: Int,
    val body: ByteArray = ByteArray(0),
    val contentType: String? = null,
    val headers: Map<String, String> = emptyMap()
)

@Singleton
class AirPlayUrlCastingHandler @Inject constructor(
    private val castUrlController: CastUrlController
) {
    suspend fun handle(
        method: String,
        uri: String,
        headers: Map<String, String>,
        body: ByteArray
    ): AirPlayCastingResponse? {
        val path = uri.substringBefore('?')
        val query = parseQuery(uri.substringAfter('?', ""))
        val normalized = method.uppercase(Locale.US)
        return when (path) {
            "/play" -> if (normalized == "POST") handlePlay(headers, body) else null
            "/scrub" -> if (normalized == "GET" || normalized == "POST") {
                handleScrub(normalized, query, headers, body)
            } else {
                null
            }
            "/rate" -> if (normalized == "POST") handleRate(query, body) else null
            "/stop" -> if (normalized == "POST") {
                castUrlController.stop()
                AirPlayCastingResponse(200)
            } else {
                null
            }
            "/photo" -> if (normalized == "PUT") {
                castUrlController.displayPhoto(body)
                AirPlayCastingResponse(200)
            } else {
                null
            }
            "/playback-info" -> if (normalized == "GET") playbackInfo() else null
            else -> null
        }
    }

    private suspend fun handlePlay(
        headers: Map<String, String>,
        body: ByteArray
    ): AirPlayCastingResponse {
        val parsed = parsePlay(body, headers)
        val url = parsed.url ?: return AirPlayCastingResponse(
            400,
            "Missing Content-Location".toByteArray(),
            "text/plain"
        )
        val start = parsed.startPosition
        if (start > 0.0 && start < 1.0) {
            castUrlController.loadMedia(url, parsed.title, parsed.mimeType, 0L)
            val progress = withTimeoutOrNull(3_000L) {
                castUrlController.playbackProgress.first { it.durationMs > 0L }
            }
            if (progress != null) {
                castUrlController.seekTo((progress.durationMs * start).toLong())
            }
        } else {
            castUrlController.loadMedia(
                url,
                parsed.title,
                parsed.mimeType,
                (start * 1000.0).toLong().coerceAtLeast(0L)
            )
        }
        return AirPlayCastingResponse(200)
    }

    private suspend fun handleScrub(
        method: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        body: ByteArray
    ): AirPlayCastingResponse {
        val parsed = parseNumeric("position", query, headers, body)
        if (parsed != null) {
            castUrlController.seekTo((parsed * 1000.0).toLong().coerceAtLeast(0L))
        }
        if (method == "POST") return AirPlayCastingResponse(200)
        val progress = castUrlController.playbackProgress.value
        val text = String.format(
            Locale.US,
            "duration: %.3f\r\nposition: %.3f\r\n",
            progress.durationMs / 1000.0,
            progress.positionMs / 1000.0
        )
        return AirPlayCastingResponse(
            200,
            text.toByteArray(Charsets.UTF_8),
            "text/parameters"
        )
    }

    private suspend fun handleRate(
        query: Map<String, String>,
        body: ByteArray
    ): AirPlayCastingResponse {
        val value = query["value"]?.toDoubleOrNull()
            ?: parseNumeric("rate", emptyMap(), emptyMap(), body)
        if (value == null) return AirPlayCastingResponse(400, "Missing rate".toByteArray(), "text/plain")
        if (value == 0.0) castUrlController.pause() else castUrlController.play()
        return AirPlayCastingResponse(200)
    }

    private fun playbackInfo(): AirPlayCastingResponse {
        val state = castUrlController.playbackState.value
        val progress = castUrlController.playbackProgress.value
        val info = linkedMapOf<String, Any?>(
            "duration" to progress.durationMs / 1000.0,
            "position" to progress.positionMs / 1000.0,
            "rate" to if (state == CastPlaybackState.Playing) 1.0 else 0.0,
            "readyToPlay" to true,
            "playbackBufferEmpty" to false
        )
        return AirPlayCastingResponse(
            200,
            AirPlayPlistCodec.encodeXml(info),
            "text/xml; charset=utf-8"
        )
    }

    private fun parsePlay(body: ByteArray, headers: Map<String, String>): ParsedPlay {
        val map = decodePlist(body)
        val text = if (map == null) String(body, Charsets.UTF_8) else ""
        val url = stringValue(map, "Content-Location")
            ?: headers["content-location"]
            ?: textValue(text, "Content-Location")
        val mime = stringValue(map, "Content-Type")
        val title = stringValue(map, "Title") ?: stringValue(map, "title")
        val start = doubleValue(map, "Start-Position")
            ?: headers["start-position"]?.toDoubleOrNull()
            ?: textValue(text, "Start-Position")?.toDoubleOrNull()
            ?: 0.0
        return ParsedPlay(url, mime, title, start.coerceAtLeast(0.0))
    }

    private fun parseNumeric(
        key: String,
        query: Map<String, String>,
        headers: Map<String, String>,
        body: ByteArray
    ): Double? {
        query[key]?.toDoubleOrNull()?.let { return it }
        val map = decodePlist(body)
        doubleValue(map, key)?.let { return it }
        val text = String(body, Charsets.UTF_8)
        textValue(text, key)?.toDoubleOrNull()?.let { return it }
        headers[key]?.toDoubleOrNull()?.let { return it }
        return null
    }

    private fun decodePlist(body: ByteArray): Map<*, *>? {
        if (body.isEmpty()) return null
        if (!body.startsWith("bplist".toByteArray()) && body.first() != '<'.code.toByte()) {
            return null
        }
        return try {
            AirPlayPlistCodec.decode(body) as? Map<*, *>
        } catch (_: Throwable) {
            null
        }
    }

    private fun stringValue(map: Map<*, *>?, key: String): String? {
        if (map == null) return null
        for ((rawKey, value) in map) {
            if (rawKey?.toString().equals(key, true) && value != null) return value.toString()
        }
        return null
    }

    private fun doubleValue(map: Map<*, *>?, key: String): Double? {
        val raw = stringValue(map, key) ?: return null
        return raw.toDoubleOrNull()
    }

    private fun textValue(text: String, key: String): String? {
        val match = Regex(
            "(?im)^\\s*" + Regex.escape(key) + "\\s*:\\s*(.+?)\\s*$"
        ).find(text) ?: return null
        return match.groupValues[1].trim()
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val key = URLDecoder.decode(part.substringBefore('='), Charsets.UTF_8.name())
            val value = URLDecoder.decode(part.substringAfter('=', ""), Charsets.UTF_8.name())
            key to value
        }.toMap()
    }

    private data class ParsedPlay(
        val url: String?,
        val mimeType: String?,
        val title: String?,
        val startPosition: Double
    )

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        for (index in prefix.indices) {
            if (this[index] != prefix[index]) return false
        }
        return true
    }
}
