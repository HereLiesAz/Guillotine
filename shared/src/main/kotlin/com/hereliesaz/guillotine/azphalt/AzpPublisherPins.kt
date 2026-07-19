package com.hereliesaz.guillotine.azphalt

import org.json.JSONObject
import java.io.File

/**
 * Trust-on-first-use publisher pins: plugin id → the base64 SPKI Ed25519 public key that signed the
 * first version installed. On any later install/update of the same id, [AzpModelInstall] requires the
 * new package to be signed by the same key (or the caller to explicitly approve a publisher change).
 *
 * This is what makes "only the repo owner can push an update" enforceable: a package's id
 * (`com.hereliesaz.azphalt.whisper`) is just a string anyone can put in a manifest, but only the holder
 * of the pinned private key can produce a package whose signature verifies against the pinned public
 * key. A third party who republishes under someone else's id is rejected as a publisher change rather
 * than silently accepted as an update.
 *
 * Backed by a small JSON file the host owns (`id` → base64 SPKI). Pure `java.io`, so `:app` and
 * `:desktop` share it — each just hands in a file under its own data dir.
 */
class AzpPublisherPins(private val file: File) {

    private fun readAll(): LinkedHashMap<String, String> {
        if (!file.isFile) return LinkedHashMap()
        return runCatching {
            val o = JSONObject(file.readText())
            val m = LinkedHashMap<String, String>()
            for (k in o.keys()) o.optString(k).takeIf { it.isNotBlank() }?.let { m[k] = it }
            m
        }.getOrDefault(LinkedHashMap())
    }

    /** The pinned publisher key for [packageId], or null if this id has never been installed. */
    fun keyFor(packageId: String): String? = readAll()[packageId]

    /** Record [publicKey] as the publisher of [packageId] (first install, or a caller-approved rotation). */
    fun pin(packageId: String, publicKey: String) {
        val m = readAll()
        if (m[packageId] == publicKey) return
        m[packageId] = publicKey
        file.parentFile?.mkdirs()
        val o = JSONObject()
        for ((k, v) in m) o.put(k, v)
        file.writeText(o.toString(2))
    }
}
