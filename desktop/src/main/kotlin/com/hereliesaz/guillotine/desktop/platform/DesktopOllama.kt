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
    const val ROUTER_MODEL = "qwen3:0.6b"

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
