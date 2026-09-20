package com.hereliesaz.guillotine.ai.agent

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.StatFs

/**
 * Reads only permission-free local hardware facts used for model recommendations.
 * Nothing here is uploaded or persisted; Settings/Onboarding compute the profile on-device.
 */
object AndroidDeviceModelProfile {
    fun read(context: Context): DeviceModelProfile {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memory = ActivityManager.MemoryInfo()
        val totalRam = runCatching {
            am?.getMemoryInfo(memory)
            memory.totalMem
        }.getOrDefault(0L)

        val storageRoot = app.getExternalFilesDir(null) ?: app.filesDir
        val freeStorage = runCatching { StatFs(storageRoot.absolutePath).availableBytes }.getOrDefault(0L)
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val is64 = runCatching { Process.is64Bit() }.getOrElse {
            Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()
        }
        val lowRam = runCatching { am?.isLowRamDevice == true }.getOrDefault(false)
        val maker = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val label = listOf(maker, model)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "Android device" }

        return DeviceModelProfile(
            deviceLabel = label,
            totalRamBytes = totalRam,
            freeStorageBytes = freeStorage,
            cpuCores = cores,
            is64Bit = is64,
            lowRam = lowRam,
            osLabel = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            primaryAbi = Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
        )
    }
}
