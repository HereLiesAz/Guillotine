package com.hereliesaz.guillotine.desktop.platform

import com.hereliesaz.guillotine.ai.gen.GenHttp
import com.hereliesaz.guillotine.ai.gen.GenSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [GenSink] backed by the desktop app data dir — the desktop analogue of `AndroidGenSink`. Generated
 * media (downloaded from a provider URL, or returned inline as bytes) is written to a local file whose
 * `file:` uri then flows through the same import path as user media.
 */
class DesktopGenSink : GenSink {

    private val dir = File(DesktopStorage.dataDir, "gen").apply { mkdirs() }

    override suspend fun saveUrl(url: String, ext: String): String = withContext(Dispatchers.IO) {
        write(GenHttp.getBytes(url), ext)
    }

    override suspend fun saveBytes(bytes: ByteArray, ext: String): String = withContext(Dispatchers.IO) {
        write(bytes, ext)
    }

    private fun write(bytes: ByteArray, ext: String): String {
        val file = File(dir, "gen_${System.currentTimeMillis()}.$ext")
        file.outputStream().use { it.write(bytes) }
        return file.toURI().toString()
    }
}
