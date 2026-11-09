package com.airscreen.tv.discovery.mdns

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MdnsTxtRecordsTest {

    private val uuid = "33dc7acc-51af-40ba-a304-002e673dde6e"
    private val hardwareId = "AA:BB:CC:DD:EE:FF"
    private val publicKey = "ab".repeat(32)

    @Test
    fun airplayMapMatchesRequiredTxtRecord() {
        val txt = MdnsTxtRecords.airplay(
            deviceUuid = uuid,
            hardwareId = hardwareId,
            publicKeyHex = publicKey
        )
        assertEquals(8, txt.size)
        assertEquals(hardwareId, txt["deviceid"])
        assertEquals("0x5A7FFFF7,0x1E", txt["features"])
        assertEquals("0x4", txt["flags"])
        assertEquals("AppleTV6,2", txt["model"])
        assertEquals(uuid, txt["pi"])
        assertEquals(publicKey, txt["pk"])
        assertEquals("220.68", txt["srcvers"])
        assertEquals("2", txt["vv"])
        assertTrue(txt["pk"]?.matches(Regex("[0-9a-f]{64}")) == true)
    }

    @Test
    fun raopMapMatchesRequiredTxtRecordAndTracksPin() {
        val open = MdnsTxtRecords.raop(
            pinRequired = false,
            hardwareId = hardwareId,
            publicKeyHex = publicKey
        )
        assertEquals(16, open.size)
        assertEquals("2", open["ch"])
        assertEquals("0,1,2,3", open["cn"])
        assertEquals("true", open["da"])
        assertEquals("0,3,5", open["et"])
        assertEquals("0x5A7FFFF7,0x1E", open["ft"])
        assertEquals("0,1,2", open["md"])
        assertEquals(publicKey, open["pk"])
        assertEquals("false", open["pw"])
        assertEquals("0x4", open["sf"])
        assertEquals("false", open["sm"])
        assertEquals("44100", open["sr"])
        assertEquals("16", open["ss"])
        assertEquals("UDP", open["tp"])
        assertEquals("65537", open["vn"])
        assertEquals("220.68", open["vs"])
        assertEquals("AppleTV6,2", open["am"])

        val secured = MdnsTxtRecords.raop(
            pinRequired = true,
            hardwareId = hardwareId,
            publicKeyHex = publicKey
        )
        assertEquals("true", secured["pw"])
    }

}