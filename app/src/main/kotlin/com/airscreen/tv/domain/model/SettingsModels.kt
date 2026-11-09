package com.airscreen.tv.domain.model

enum class MaxResolution(val width: Int, val height: Int) {
    AUTO(0, 0),
    HD_720P(1280, 720),
    FHD_1080P(1920, 1080),
    UHD_4K(3840, 2160)
}

enum class FpsCap(val fps: Int) {
    FPS_30(30),
    FPS_60(60),
    FPS_120(120)
}

data class AppSettings(
    val deviceName: String = DEFAULT_DEVICE_NAME,
    val maxResolution: MaxResolution = MaxResolution.FHD_1080P,
    val fpsCap: FpsCap = FpsCap.FPS_60,
    val hardwareDecodingEnabled: Boolean = true,
    val pinSecurityEnabled: Boolean = true,
    val autoStartOnBoot: Boolean = true
) {
    companion object {
        const val DEFAULT_DEVICE_NAME = "AirScreen TV"
        const val MAX_DEVICE_NAME_BYTES = 63
    }
}