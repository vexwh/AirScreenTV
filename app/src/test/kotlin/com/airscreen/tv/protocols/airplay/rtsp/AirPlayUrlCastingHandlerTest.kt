package com.airscreen.tv.protocols.airplay.rtsp

import com.airscreen.tv.protocols.airplay.plist.AirPlayPlistCodec
import com.airscreen.tv.support.FakeCastUrlController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayUrlCastingHandlerTest {

    @Test
    fun binaryPlistPlayParsesLocationAndStartPosition() = runBlocking {
        val controller = FakeCastUrlController()
        val handler = AirPlayUrlCastingHandler(controller)
        val body = AirPlayPlistCodec.encodeBinary(
            mapOf(
                "Content-Location" to "http://example.test/video.mp4",
                "Start-Position" to 2.5,
                "Title" to "Example"
            )
        )
        val response = handler.handle("POST", "/play", emptyMap(), body)
        assertNotNull(response)
        assertEquals(200, response?.statusCode)
        assertEquals("http://example.test/video.mp4", controller.lastUrl)
        assertEquals(2_500L, controller.lastStartPositionMs)
        assertEquals("Example", controller.lastTitle)
    }

    @Test
    fun textPlayScrubRatePhotoAndPlaybackInfo() = runBlocking {
        val controller = FakeCastUrlController()
        val handler = AirPlayUrlCastingHandler(controller)

        val play = handler.handle(
            "POST",
            "/play",
            emptyMap(),
            "Content-Location: http://example.test/audio.mp3\r\nStart-Position: 1.5\r\n"
                .toByteArray()
        )
        assertEquals(200, play?.statusCode)
        assertEquals("http://example.test/audio.mp3", controller.lastUrl)
        assertEquals(1_500L, controller.lastStartPositionMs)

        val scrub = handler.handle(
            "GET",
            "/scrub?position=3.5",
            emptyMap(),
            ByteArray(0)
        )
        assertNotNull(scrub)
        assertEquals(200, scrub?.statusCode)
        assertEquals("text/parameters", scrub?.contentType)
        assertEquals(3_500L, controller.lastSeekPositionMs)
        val scrubBody = String(scrub!!.body)
        assertTrue(scrubBody.contains("duration: 100.000\r\n"))
        assertTrue(scrubBody.contains("position: 3.500\r\n"))

        val pause = handler.handle("POST", "/rate?value=0", emptyMap(), ByteArray(0))
        assertEquals(200, pause?.statusCode)
        assertEquals(1, controller.pauseCount)
        val resume = handler.handle("POST", "/rate?value=1", emptyMap(), ByteArray(0))
        assertEquals(200, resume?.statusCode)
        assertEquals(1, controller.playCount)

        val info = handler.handle("GET", "/playback-info", emptyMap(), ByteArray(0))
        assertNotNull(info)
        assertEquals("text/xml; charset=utf-8", info?.contentType)
        val xml = String(info!!.body)
        assertTrue(xml.contains("<key>duration</key>"))
        assertTrue(xml.contains("<key>rate</key>"))
        val decoded = AirPlayPlistCodec.decode(info.body) as Map<*, *>
        assertEquals(1.0, decoded["rate"])
        assertEquals(true, decoded["readyToPlay"])
        assertEquals(false, decoded["playbackBufferEmpty"])

        val photoBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        val photo = handler.handle("PUT", "/photo", emptyMap(), photoBytes)
        assertEquals(200, photo?.statusCode)
        assertTrue(controller.photoBytes?.contentEquals(photoBytes) == true)

        val stop = handler.handle("POST", "/stop", emptyMap(), ByteArray(0))
        assertEquals(200, stop?.statusCode)
        assertEquals(1, controller.stopCount)
    }
}