package com.airscreen.tv.protocols.airplay.crypto

object FairPlayNative {
    init {
        System.loadLibrary("fairplay")
    }

    external fun decrypt(keyMessage: ByteArray, ekey: ByteArray): ByteArray?
}
