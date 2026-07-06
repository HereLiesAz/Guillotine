package com.hereliesaz.guillotine.ai.gen

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** [GenSink] backed by the app cache dir — mirrors `ImageGen.Leonardo.download`. */
class AndroidGenSink(private val context: Context) : GenSink {

    override suspend fun saveUrl(url: String, ext: String): String = withContext(Dispatchers.IO) {
        val bytes = GenHttp.getBytes(url)
        write(bytes, ext)
    }

    override suspend fun saveBytes(bytes: ByteArray, ext: String): String = withContext(Dispatchers.IO) {
        write(bytes, ext)
    }

    private fun write(bytes: ByteArray, ext: String): String {
        val file = File(context.cacheDir, "gen_${System.currentTimeMillis()}.$ext")
        file.outputStream().use { it.write(bytes) }
        return Uri.fromFile(file).toString()
    }
}
