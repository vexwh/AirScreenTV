package com.airscreen.tv.media.decoder

import android.graphics.ImageFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import com.airscreen.tv.domain.model.SurfaceSlotId
import java.io.Closeable
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SurfaceSlotRegistry @Inject constructor() {
    private val lock = Any()
    private val leases = LinkedHashMap<SurfaceSlotId, SurfaceLease>()
    private val handlerThread = HandlerThread("AirPlaySurfaces").apply { start() }
    private val handler = Handler(handlerThread.looper)

    fun acquire(slot: SurfaceSlotId, width: Int, height: Int): SurfaceLease {
        val safeWidth = width.coerceIn(16, 4096)
        val safeHeight = height.coerceIn(16, 4096)
        synchronized(lock) {
            leases.remove(slot)?.close()
            val reader = ImageReader.newInstance(
                safeWidth,
                safeHeight,
                ImageFormat.PRIVATE,
                2
            )
            reader.setOnImageAvailableListener(
                { imageReader ->
                    try {
                        imageReader.acquireLatestImage()?.close()
                    } catch (error: Throwable) {
                        Log.d(TAG, "ImageReader drain failed", error)
                    }
                },
                handler
            )
            val lease = SurfaceLease(
                slot = slot,
                surface = reader.surface,
                reader = reader,
                onClose = { released ->
                    synchronized(lock) {
                        if (leases[released.slot] === released) leases.remove(released.slot)
                    }
                }
            )
            leases[slot] = lease
            return lease
        }
    }

    fun release(slot: SurfaceSlotId) {
        synchronized(lock) {
            leases.remove(slot)?.close()
        }
    }

    fun closeAll() {
        synchronized(lock) {
            leases.values.toList().forEach { it.close() }
            leases.clear()
        }
    }

    private companion object {
        const val TAG = "AirPlaySurfaces"
    }
}

class SurfaceLease(
    val slot: SurfaceSlotId,
    val surface: Surface,
    private val reader: ImageReader,
    private val onClose: (SurfaceLease) -> Unit
) : Closeable {
    private var closed = false

    override fun close() {
        synchronized(this) {
            if (closed) return
            closed = true
        }
        try {
            reader.setOnImageAvailableListener(null, null)
            reader.close()
        } catch (error: Throwable) {
            Log.d("AirPlaySurfaces", "Unable to close ImageReader", error)
        }
        try {
            surface.release()
        } catch (error: Throwable) {
            Log.d("AirPlaySurfaces", "Unable to release Surface", error)
        }
        onClose(this)
    }
}
