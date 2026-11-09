package com.airscreen.tv.protocols.airplay.plist

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AirPlayPlistCodecTest {

    @Test
    fun binaryRoundTripSupportsNestedInfoShape() {
        val value = linkedMapOf<String, Any?>(
            "name" to "AirScreen TV",
            "statusFlags" to 68L,
            "vv" to 2L,
            "enabled" to true,
            "refreshRate" to 60.0,
            "data" to byteArrayOf(1, 2, 3, 4),
            "displays" to listOf(
                linkedMapOf<String, Any?>(
                    "width" to 3840L,
                    "height" to 2160L,
                    "maxFPS" to 60L,
                    "overscanned" to false,
                    "uuid" to "33dc7acc-51af-40ba-a304-002e673dde6e"
                )
            )
        )
        val encoded = AirPlayPlistCodec.encodeBinary(value)
        assertTrue(AirPlayPlistCodec.isBinary(encoded))
        assertTrue(String(encoded.copyOfRange(0, 8), Charsets.US_ASCII) == "bplist00")

        val decoded = AirPlayPlistCodec.decode(encoded) as Map<*, *>
        assertEquals("AirScreen TV", decoded["name"])
        assertEquals(68L, decoded["statusFlags"])
        assertEquals(2L, decoded["vv"])
        assertEquals(true, decoded["enabled"])
        assertEquals(60.0, decoded["refreshRate"])
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), decoded["data"] as ByteArray)
        val displays = decoded["displays"] as List<*>
        val display = displays.first() as Map<*, *>
        assertEquals(3840L, display["width"])
        assertEquals(2160L, display["height"])
        assertEquals(60L, display["maxFPS"])
        assertEquals(false, display["overscanned"])
        assertEquals("33dc7acc-51af-40ba-a304-002e673dde6e", display["uuid"])
    }

    @Test
    fun xmlRoundTripSupportsDoctypeAndNestedStructures() {
        val value = linkedMapOf<String, Any?>(
            "name" to "AirScreen TV",
            "features" to 0x5A7FFFF7L,
            "active" to true,
            "streams" to listOf(
                linkedMapOf<String, Any?>("type" to 110L, "dataPort" to 7100L)
            )
        )
        val encoded = AirPlayPlistCodec.encodeXml(value)
        val xml = String(encoded, Charsets.UTF_8)
        assertTrue(xml.contains("<!DOCTYPE plist"))
        val decoded = AirPlayPlistCodec.decode(encoded) as Map<*, *>
        assertEquals("AirScreen TV", decoded["name"])
        assertEquals(0x5A7FFFF7L, decoded["features"])
        assertEquals(true, decoded["active"])
        val streams = decoded["streams"] as List<*>
        assertEquals(110L, (streams.first() as Map<*, *>)["type"])
    }

    @Test
    fun xmlRoundTripPreservesUnicodeAndData() {
        val value = linkedMapOf<String, Any?>(
            "title" to "Cafe \u00e9\u00e0\u00fc",
            "blob" to byteArrayOf(0, 1, 2, 3, 127, -1)
        )
        val encoded = AirPlayPlistCodec.encodeXml(value)
        val decoded = AirPlayPlistCodec.decode(encoded) as Map<*, *>
        assertEquals("Cafe \u00e9\u00e0\u00fc", decoded["title"])
        assertArrayEquals(byteArrayOf(0, 1, 2, 3, 127, -1), decoded["blob"] as ByteArray)
    }

    @Test
    fun binaryRoundTripSupportsLargeArrayAndLongStrings() {
        val list = (0 until 40).map { index -> "item-" + index }
        val value = linkedMapOf<String, Any?>(
            "items" to list,
            "long" to "x".repeat(300)
        )
        val decoded = AirPlayPlistCodec.decode(AirPlayPlistCodec.encodeBinary(value)) as Map<*, *>
        val items = decoded["items"] as List<*>
        assertEquals(40, items.size)
        assertEquals("item-39", items.last())
        assertEquals(300, (decoded["long"] as String).length)
    }

    @Test
    fun rejectsUnknownPayload() {
        try {
            AirPlayPlistCodec.decode(byteArrayOf(1, 2, 3))
            throw AssertionError("Expected an exception")
        } catch (_: IllegalArgumentException) {
            assertNotNull("rejected")
        }
    }
}
