package com.hereliesaz.guillotine.desktop.platform

import com.hereliesaz.guillotine.ai.agent.DeviceModelProfile
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Permission-free desktop hardware facts used only for local model recommendations.
 *
 * We intentionally keep this conservative: total RAM, free storage, logical CPU count, OS/arch, and
 * a best-effort accelerator label. Recommendation math never assumes a GPU exists just because one
 * cannot be identified.
 */
object DesktopDeviceModelProfile {

    fun read(): DeviceModelProfile {
        DesktopStorage.dataDir.mkdirs()
        val bean = ManagementFactory.getOperatingSystemMXBean()
        val totalRam = (bean as? com.sun.management.OperatingSystemMXBean)?.totalMemorySize ?: 0L
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val arch = System.getProperty("os.arch").orEmpty()
        val osName = System.getProperty("os.name").orEmpty()
        val osVersion = System.getProperty("os.version").orEmpty()
        val freeStorage = runCatching {
            Files.getFileStore(DesktopStorage.dataDir.toPath()).usableSpace
        }.getOrDefault(0L)
        val is64 = arch.contains("64", ignoreCase = true) ||
            arch.equals("aarch64", ignoreCase = true) ||
            arch.equals("arm64", ignoreCase = true)

        return DeviceModelProfile(
            deviceLabel = buildString {
                append(osName.ifBlank { "Desktop" })
                if (arch.isNotBlank()) append(" ").append(arch)
            },
            totalRamBytes = totalRam,
            freeStorageBytes = freeStorage,
            cpuCores = cores,
            is64Bit = is64,
            lowRam = false,
            osLabel = listOf(osName, osVersion).filter { it.isNotBlank() }.joinToString(" "),
            primaryAbi = arch,
            acceleratorLabel = detectAccelerator(osName, arch),
        )
    }

    private fun detectAccelerator(osName: String, arch: String): String {
        // NVIDIA exposes both GPU name and VRAM through a stable CLI when the driver is installed.
        runCommand(
            "nvidia-smi",
            "--query-gpu=name,memory.total",
            "--format=csv,noheader,nounits",
        )?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }?.let { line ->
            val parts = line.split(',').map { it.trim() }
            val name = parts.firstOrNull().orEmpty()
            val mib = parts.getOrNull(1)?.toLongOrNull()
            return if (mib != null) "$name · ${formatVram(mib)} VRAM" else name
        }

        // Apple Silicon uses unified memory, so reporting a fake dedicated-VRAM number would be wrong.
        if (
            osName.lowercase(Locale.ROOT).contains("mac") &&
            (arch.equals("aarch64", true) || arch.equals("arm64", true))
        ) {
            return "Apple Silicon unified GPU"
        }

        // Best-effort labels only. Lack of a result is not treated as "no GPU".
        if (osName.lowercase(Locale.ROOT).contains("windows")) {
            runCommand(
                "powershell",
                "-NoProfile",
                "-Command",
                "(Get-CimInstance Win32_VideoController | Select-Object -First 1 -ExpandProperty Name)",
            )?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        } else {
            runCommand("lspci")?.lineSequence()
                ?.firstOrNull {
                    val l = it.lowercase(Locale.ROOT)
                    "vga compatible controller" in l || "3d controller" in l
                }
                ?.substringAfter(": ")
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }

        return ""
    }

    private fun runCommand(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command)
            .redirectErrorStream(true)
            .start()
        if (!process.waitFor(1200, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        process.inputStream.bufferedReader().use { it.readText() }
            .takeIf { process.exitValue() == 0 }
    }.getOrNull()

    private fun formatVram(mib: Long): String {
        val gib = mib / 1024.0
        return if (gib >= 10.0) "%.0f GB".format(gib) else "%.1f GB".format(gib)
    }
}
