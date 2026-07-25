package com.hereliesaz.guillotine.azphalt

import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests the store's install trust gate: [AzphaltStoreState.install] must apply the same
 * [AzpPackage.verifyTrust] + [AzpPublisherPins] protection [AzpModelInstall] already had, so the
 * "shader/LUT/code" store path can't be used to silently install an untrusted or hijacked update.
 */
class AzphaltStoreStateTest {

    @get:Rule val tmp = TemporaryFolder()

    private var server: HttpServer? = null
    private var body: ByteArray = ByteArray(0)

    @After fun stopServer() { server?.stop(0) }

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zout ->
            for ((name, data) in entries) {
                zout.putNextEntry(ZipEntry(name)); zout.write(data); zout.closeEntry()
            }
        }
        return bos.toByteArray()
    }

    private fun manifest(id: String, files: Map<String, String>): ByteArray {
        val filesJson = files.entries.joinToString(",") { "\"${it.key}\":\"${it.value}\"" }
        return """
            {"azphalt":"0.1","id":"$id","name":"Test","version":"1.0.0","kind":"code","license":"MIT",
             "compat":">=0.1","entry":"code/main.js","runtime":"js","capabilities":["canvas"],
             "files":{$filesJson}}
        """.trimIndent().encodeToByteArray()
    }

    private fun ed25519Key(): KeyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    private fun sign(kp: KeyPair, message: ByteArray): ByteArray =
        Signature.getInstance("Ed25519").run { initSign(kp.private); update(message); sign() }

    private fun unsignedPackage(id: String): ByteArray {
        val code = "export function f(){}".encodeToByteArray()
        return zip(
            mapOf(
                "manifest.json" to manifest(id, mapOf("code/main.js" to AzpPackage.digest(code))),
                "code/main.js" to code,
            ),
        )
    }

    private fun signedPackage(id: String, author: KeyPair): ByteArray {
        val code = "export function f(){}".encodeToByteArray()
        val manifestBytes = manifest(id, mapOf("code/main.js" to AzpPackage.digest(code)))
        val sig = """{"alg":"ed25519","publicKey":"${b64(author.public.encoded)}","signature":"${b64(sign(author, manifestBytes))}"}"""
        return zip(
            mapOf(
                "manifest.json" to manifestBytes,
                "code/main.js" to code,
                "signature.json" to sig.encodeToByteArray(),
            ),
        )
    }

    /** A repository whose /packages/.../download always serves the currently-set [body]. */
    private fun repository(): AzphaltRepository {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/packages") { ex ->
            val bytes = body
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return AzphaltRepository(baseUrl = "http://127.0.0.1:${s.address.port}")
    }

    private fun plugin(id: String, version: String = "1.0.0") = AzphaltPlugin(
        id = id, name = "Test Plugin", description = "", author = "Az", version = version,
        kind = "code", category = "layer-effects", tags = emptyList(), priceLabel = "Free",
        rating = null, downloads = 0, previewImageUrl = null,
    )

    @Test fun installsTrustedSignedPackage() {
        val author = ed25519Key()
        val id = "com.hereliesaz.trusted"
        body = signedPackage(id, author)
        val state = AzphaltStoreState(repository())
        val result = state.install(plugin(id), tmp.newFolder("ext").absolutePath, trustedKeys = setOf(b64(author.public.encoded)))
        val ok = result as? AzphaltStoreState.InstallResult.Success ?: error("expected Success, got $result")
        assertTrue(ok.signed)
        assertTrue(ok.signatureValid)
    }

    @Test fun unsignedPackageRequiresExplicitApproval() {
        val id = "com.hereliesaz.unsigned"
        body = unsignedPackage(id)
        val state = AzphaltStoreState(repository())
        val dir = tmp.newFolder("ext").absolutePath

        val first = state.install(plugin(id), dir)
        assertTrue(first is AzphaltStoreState.InstallResult.Untrusted)

        val second = state.install(plugin(id), dir, allowUntrusted = true)
        val ok = second as? AzphaltStoreState.InstallResult.Success ?: error("expected Success, got $second")
        assertEquals(false, ok.signed)
    }

    @Test fun signedButUntrustedSignerRequiresExplicitApproval() {
        val author = ed25519Key()
        val id = "com.hereliesaz.untrusted-signer"
        body = signedPackage(id, author)
        val state = AzphaltStoreState(repository())
        val dir = tmp.newFolder("ext").absolutePath

        // trustedKeys deliberately empty: a valid signature from an unrecognized publisher still needs
        // the explicit "install anyway" the untrusted-store-install-skips-verifyTrust bug used to skip.
        val first = state.install(plugin(id), dir)
        assertTrue(first is AzphaltStoreState.InstallResult.Untrusted)

        val second = state.install(plugin(id), dir, allowUntrusted = true)
        assertTrue(second is AzphaltStoreState.InstallResult.Success)
    }

    @Test fun rejectsTamperedPackage() {
        val id = "com.hereliesaz.tampered"
        val code = "x".encodeToByteArray()
        val wrongDigest = AzpPackage.digest("different".encodeToByteArray())
        body = zip(mapOf("manifest.json" to manifest(id, mapOf("code/main.js" to wrongDigest)), "code/main.js" to code))
        val state = AzphaltStoreState(repository())
        val result = state.install(plugin(id), tmp.newFolder("ext").absolutePath, allowUntrusted = true)
        assertTrue(result is AzphaltStoreState.InstallResult.Failure)
    }

    @Test fun publisherChangeIsRefusedWithoutExplicitApproval() {
        val original = ed25519Key()
        val hijacker = ed25519Key()
        val id = "com.hereliesaz.pinned"
        val pins = AzpPublisherPins(tmp.newFile("pins.json"))
        val dir = tmp.newFolder("ext").absolutePath
        val state = AzphaltStoreState(repository())

        body = signedPackage(id, original)
        val installed = state.install(
            plugin(id), dir, trustedKeys = setOf(b64(original.public.encoded)), pins = pins,
        )
        assertTrue(installed is AzphaltStoreState.InstallResult.Success)
        assertEquals(b64(original.public.encoded), pins.keyFor(id))

        // A later "update" signed by a different key — same publisher pinning protection the
        // model-install path already had, now also covering the store's install path.
        body = signedPackage(id, hijacker)
        val hijacked = state.install(
            plugin(id, version = "2.0.0"), dir, trustedKeys = setOf(b64(hijacker.public.encoded)), pins = pins,
        )
        val changed = hijacked as? AzphaltStoreState.InstallResult.PublisherChanged
            ?: error("expected PublisherChanged, got $hijacked")
        assertEquals(id, changed.packageId)
        assertEquals(b64(original.public.encoded), changed.pinnedKey)
        assertEquals(b64(hijacker.public.encoded), changed.newSignerKey)
        // Pin must NOT have moved to the hijacker's key.
        assertEquals(b64(original.public.encoded), pins.keyFor(id))

        // Explicit approval lets the rotation through and re-pins.
        val approved = state.install(
            plugin(id, version = "2.0.0"), dir, trustedKeys = setOf(b64(hijacker.public.encoded)),
            pins = pins, allowPublisherChange = true,
        )
        assertTrue(approved is AzphaltStoreState.InstallResult.Success)
        assertEquals(b64(hijacker.public.encoded), pins.keyFor(id))
    }
}
