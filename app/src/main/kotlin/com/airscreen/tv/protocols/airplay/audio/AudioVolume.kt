package com.airscreen.tv.protocols.airplay.audio

object AudioVolume {
    const val MIN_DB = -30.0f
    const val MAX_DB = 0.0f

    fun gainFromDb(db: Float): Float {
        val clamped = db.coerceIn(MIN_DB, MAX_DB)
        return ((clamped - MIN_DB) / (MAX_DB - MIN_DB)).coerceIn(0f, 1f)
    }
}
