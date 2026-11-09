package com.airscreen.tv.discovery.mdns

internal object MdnsTxtRecords {
    const val AIRPLAY_FEATURES = "0x5A7FFFF7,0x1E"
    const val AIRPLAY_FLAGS = "0x4"
    const val APPLE_TV_MODEL = "AppleTV6,2"
    const val AIRPLAY_VERSION = "220.68"

    fun airplay(
        deviceUuid: String,
        hardwareId: String,
        publicKeyHex: String
    ): Map<String, String> = mapOf(
        "deviceid" to hardwareId,
        "features" to AIRPLAY_FEATURES,
        "flags" to AIRPLAY_FLAGS,
        "model" to APPLE_TV_MODEL,
        "pi" to deviceUuid,
        "pk" to publicKeyHex,
        "srcvers" to AIRPLAY_VERSION,
        "vv" to "2"
    )

    fun raop(
        pinRequired: Boolean,
        hardwareId: String,
        publicKeyHex: String
    ): Map<String, String> = mapOf(
        "ch" to "2",
        "cn" to "0,1,2,3",
        "da" to "true",
        "et" to "0,3,5",
        "ft" to AIRPLAY_FEATURES,
        "md" to "0,1,2",
        "pk" to publicKeyHex,
        "pw" to if (pinRequired) "true" else "false",
        "sf" to AIRPLAY_FLAGS,
        "sm" to "false",
        "sr" to "44100",
        "ss" to "16",
        "tp" to "UDP",
        "vn" to "65537",
        "vs" to AIRPLAY_VERSION,
        "am" to APPLE_TV_MODEL
    )

}