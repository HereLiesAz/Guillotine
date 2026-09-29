package com.hereliesaz.guillotine.desktop.platform

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Small desktop integration layer for Ollama.
 *
 * Guillotine does not bundle Ollama. If it is installed, we can inspect installed models, pull a
 * recommended model, and make sure its local HTTP server is available before an agent turn.
 */
object DesktopOllama {
    /** Dedicated desktop routing model. It is never used as the editing planner. */
    const val ROUTER_MODEL = "qwen3.5:0.8b"

    /** Local Ollama tags that take images (Qwen 3.5 and Gemma 4 families). */
    fun isMultimodal(tag: String): Boolean =
        tag.startsWith("qwen3.5", ignoreCase = true) || tag.startsWith("gemma4", ignoreCase = true)

    /**
     * Ask the local multimodal [model] about one image ([jpegBase64]) via Ollama's `/api/generate`.
     * Localhost only: the frame never leaves the machine. Throws with a readable message on failure.
     */
    fun describeImage(model: String, jpegBase64: String, prompt: String, timeoutMs: Int = 120_000): String {
        check(ensureRunning()) { "Ollama isn't running and couldn't be started." }
        val body = org.json.JSONObject()
            .put("model", model)
            .put("prompt", prompt)
            .put("images", org.json.JSONArray().put(jpegBase64))
            .put("stream", false)
            .toString().toByteArray()
        val conn = (URL("http://127.0.0.1:11434/api/generate").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = 5_000
            readTimeout = timeoutMs
            doOutput = true
        }
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
            check(code in 200..299) { "Ollama answered HTTP $code: ${text.take(200)}" }
            return org.json.JSONObject(text).optString("response").trim()
        } finally {
            conn.disconnect()
        }
    }

    data class Status(
        val executableAvailable: Boolean,
        val serverRunning: Boolean,
        val version: String?,
        val installedModels: Set<String>,
    )

    fun status(): Status {
        val version = commandOutput(1_500, "ollama", "--version")?.lineSequence()?.firstOrNull()?.trim()
        val available = version != null
        val running = serverReady()
        val installed = if (available && running) listModels() else emptySet()
        return Status(available, running, version, installed)
    }

    fun ensureRunning(timeoutMs: Long = 6_000L): Boolean {
        if (serverReady()) return true
        val version = commandOutput(1_500, "ollama", "--version") ?: return false
        if (version.isBlank()) return false

        runCatching {
            ProcessBuilder("ollama", "serve")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
        }.getOrElse { return false }

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            if (serverReady()) return true
            Thread.sleep(200)
        }
        return serverReady()
    }

    fun listModels(): Set<String> {
        val text = commandOutput(3_000, "ollama", "list") ?: return emptySet()
        return text.lineSequence()
            .drop(1)
            .mapNotNull { line -> line.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.isNotBlank() } }
            .toSet()
    }

    fun pull(model: String, onLine: (String) -> Unit = {}): Result<Unit> = runCatching {
        require(ensureRunning()) {
            "Ollama is not installed or its local server could not start. Install Ollama first."
        }
        val process = ProcessBuilder("ollama", "pull", model)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line -> if (line.isNotBlank()) onLine(line.trim()) }
        }
        if (!process.waitFor(60, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            error("Timed out while pulling $model.")
        }
        if (process.exitValue() != 0) error("Ollama failed to pull $model.")
    }

    private fun serverReady(): Boolean = runCatching {
        val conn = (URL("http://127.0.0.1:11434/api/tags").openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 300
            readTimeout = 500
        }
        val ok = conn.responseCode in 200..299
        conn.disconnect()
        ok
    }.getOrDefault(false)

    private fun commandOutput(timeoutMs: Long, vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        process.inputStream.bufferedReader().use { it.readText() }
            .takeIf { process.exitValue() == 0 }
    }.getOrNull()
}
