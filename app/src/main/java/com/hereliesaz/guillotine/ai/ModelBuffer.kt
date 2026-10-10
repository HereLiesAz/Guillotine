package com.hereliesaz.guillotine.ai

import java.io.File
import java.nio.ByteBuffer

/**
 * Reads an on-disk model into a direct [ByteBuffer] for MediaPipe's `setModelAssetBuffer`
 * (`setModelAssetPath` only reads APK assets). Vision models are installed from the azphalt store,
 * not bundled, so a blank or missing [path] yields null and the caller degrades to "unavailable".
 */
internal object ModelBuffer {
    fun load(path: String?): ByteBuffer? {
        val f = path?.trim()?.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile } ?: return null
        val bytes = f.readBytes()
        return ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); rewind() }
    }
}
