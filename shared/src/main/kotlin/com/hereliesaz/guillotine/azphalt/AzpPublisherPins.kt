package com.hereliesaz.guillotine.azphalt

import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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

    // Installs run on background (Dispatchers.IO) coroutines, so two of them can read-modify-write the
    // pins file concurrently. Serialize all access so a pin can't be lost to a lost-update race.
    private val lock = Any()

    private fun readAll(): LinkedHashMap<String, String> {
        if (!file.isFile || file.length() == 0L) return LinkedHashMap()
        return try {
            val o = JSONObject(file.readText())
            val m = LinkedHashMap<String, String>()
            for (k in o.keys()) o.optString(k).takeIf { it.isNotBlank() }?.let { m[k] = it }
            m
        } catch (e: Exception) {
            // A present-but-unparseable file must fail loud, never read as empty: an empty read would let
            // pin() overwrite the file and silently discard every other publisher's pin — a security hole.
            throw IllegalStateException("azp: publisher pins file is unreadable: ${file.absolutePath}", e)
        }
    }

    /** The pinned publisher key for [packageId], or null if this id has never been installed. */
    fun keyFor(packageId: String): String? = synchronized(lock) { readAll()[packageId] }

    /** Record [publicKey] as the publisher of [packageId] (first install, or a caller-approved rotation). */
    fun pin(packageId: String, publicKey: String) = synchronized(lock) {
        val m = readAll()
        if (m[packageId] != publicKey) {
            m[packageId] = publicKey
            val o = JSONObject()
            for ((k, v) in m) o.put(k, v)
            file.parentFile?.mkdirs()
            // Write to a temp file and move it into place so a crash mid-write can never truncate the
            // live file (it would only leave a stray .tmp). Files.move with ATOMIC_MOVE is robust where
            // File.renameTo isn't (e.g. Windows won't rename onto an existing file); fall back through a
            // plain replace, then a direct write, if a filesystem rejects the atomic move.
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(o.toString(2))
            try {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: Exception) {
                try {
                    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                } catch (_: Exception) {
                    file.writeText(o.toString(2))
                    tmp.delete()
                }
            }
        }
    }
}
