package com.hereliesaz.guillotine.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Tests the handoff install trust gate: [AzpHandoffInstaller.install] must apply the same
 * [AzpPackage.verifyTrust] + [AzpPublisherPins] protection [AzpModelInstall] already had, so a package
 * handed over by a store app (azphalt `spec/store-app.md`) can't be used to silently install an
 * untrusted or hijacked update — the store app's own check is not evidence, per spec.
 */
class AzpHandoffInstallerTest {

    @get:Rule val tmp = TemporaryFolder()

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

    @Test fun installsTrustedSignedPackage() {
        val author = ed25519Key()
        val id = "com.hereliesaz.trusted"
        val bytes = signedPackage(id, author)
        val result = AzpHandoffInstaller.install(
            bytes, tmp.newFolder("ext").absolutePath, trustedKeys = setOf(b64(author.public.encoded)),
        )
        val ok = result as? AzpHandoffInstaller.InstallResult.Success ?: error("expected Success, got $result")
        assertEquals(id, ok.id)
        assertTrue(ok.signed)
        assertTrue(ok.signatureValid)
    }

    @Test fun unsignedPackageInstallsWithoutExtraApproval() {
        // Unsigned has no provenance claim to warn about — it's exactly as trustworthy as every
        // install was before the trust gate existed, so it must not require allowUntrusted.
        val id = "com.hereliesaz.unsigned"
        val bytes = unsignedPackage(id)
        val dir = tmp.newFolder("ext").absolutePath

        val result = AzpHandoffInstaller.install(bytes, dir)
        val ok = result as? AzpHandoffInstaller.InstallResult.Success ?: error("expected Success, got $result")
        assertEquals(false, ok.signed)
    }

    @Test fun signedButUntrustedSignerRequiresExplicitApproval() {
        val author = ed25519Key()
        val id = "com.hereliesaz.untrusted-signer"
        val bytes = signedPackage(id, author)
        val dir = tmp.newFolder("ext").absolutePath

        // trustedKeys deliberately empty: a valid signature from an unrecognized publisher still needs
        // an explicit "install anyway".
        val first = AzpHandoffInstaller.install(bytes, dir)
        assertTrue(first is AzpHandoffInstaller.InstallResult.Untrusted)

        val second = AzpHandoffInstaller.install(bytes, dir, allowUntrusted = true)
        assertTrue(second is AzpHandoffInstaller.InstallResult.Success)
    }

    @Test fun rejectsTamperedPackage() {
        val id = "com.hereliesaz.tampered"
        val code = "x".encodeToByteArray()
        val wrongDigest = AzpPackage.digest("different".encodeToByteArray())
        val bytes = zip(mapOf("manifest.json" to manifest(id, mapOf("code/main.js" to wrongDigest)), "code/main.js" to code))
        val result = AzpHandoffInstaller.install(bytes, tmp.newFolder("ext").absolutePath, allowUntrusted = true)
        assertTrue(result is AzpHandoffInstaller.InstallResult.Failure)
    }

    @Test fun publisherChangeIsRefusedWithoutExplicitApproval() {
        val original = ed25519Key()
        val hijacker = ed25519Key()
        val id = "com.hereliesaz.pinned"
        val pins = AzpPublisherPins(tmp.newFile("pins.json"))
        val dir = tmp.newFolder("ext").absolutePath

        val installed = AzpHandoffInstaller.install(
            signedPackage(id, original), dir, trustedKeys = setOf(b64(original.public.encoded)), pins = pins,
        )
        assertTrue(installed is AzpHandoffInstaller.InstallResult.Success)
        assertEquals(b64(original.public.encoded), pins.keyFor(id))

        // A later "update" signed by a different key — same publisher pinning protection the
        // model-install path already had, now also covering the handoff install path.
        val hijacked = AzpHandoffInstaller.install(
            signedPackage(id, hijacker), dir, trustedKeys = setOf(b64(hijacker.public.encoded)), pins = pins,
        )
        val changed = hijacked as? AzpHandoffInstaller.InstallResult.PublisherChanged
            ?: error("expected PublisherChanged, got $hijacked")
        assertEquals(id, changed.packageId)
        assertEquals(b64(original.public.encoded), changed.pinnedKey)
        assertEquals(b64(hijacker.public.encoded), changed.newSignerKey)
        // Pin must NOT have moved to the hijacker's key.
        assertEquals(b64(original.public.encoded), pins.keyFor(id))

        // Explicit approval lets the rotation through and re-pins.
        val approved = AzpHandoffInstaller.install(
            signedPackage(id, hijacker), dir, trustedKeys = setOf(b64(hijacker.public.encoded)),
            pins = pins, allowPublisherChange = true,
        )
        assertTrue(approved is AzpHandoffInstaller.InstallResult.Success)
        assertEquals(b64(hijacker.public.encoded), pins.keyFor(id))
    }

    // ---- host scoping (azphalt spec/web-handoff.md § Host obligations, 5) ----

    /** As [manifest], but declaring `targetApps`. */
    private fun scopedPackage(id: String, targetApps: List<String>): ByteArray {
        val code = "export function f(){}".encodeToByteArray()
        val apps = targetApps.joinToString(",") { "\"$it\"" }
        val manifestBytes = """
            {"azphalt":"0.1","id":"$id","name":"Scoped","version":"1.0.0","kind":"code","license":"MIT",
             "compat":">=0.1","entry":"code/main.js","runtime":"js","capabilities":["canvas"],
             "targetApps":[$apps],
             "files":{"code/main.js":"${AzpPackage.digest(code)}"}}
        """.trimIndent().encodeToByteArray()
        return zip(mapOf("manifest.json" to manifestBytes, "code/main.js" to code))
    }

    @Test fun packageScopedToAnotherHostIsRefused() {
        // A deep link names a package with nothing in between — no store app filtering on the `app` browse
        // extra — so the host has to enforce targetApps itself. The spec makes refusing a MUST.
        val dir = tmp.newFolder("ext")
        val result = AzpHandoffInstaller.install(
            scopedPackage("com.example.other", listOf("com.example.othereditor")),
            dir.absolutePath,
            allowUntrusted = true,
            hostAppId = "com.hereliesaz.guillotine",
        )
        val wrong = result as? AzpHandoffInstaller.InstallResult.WrongHost
            ?: error("expected WrongHost, got $result")
        assertEquals("com.example.other", wrong.packageId)
        assertEquals(listOf("com.example.othereditor"), wrong.targetApps)
        // Refused means nothing on disk — not "installed but hidden".
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }

    @Test fun packageListingThisHostIsInstalled() {
        val dir = tmp.newFolder("ext")
        val result = AzpHandoffInstaller.install(
            scopedPackage("com.example.scoped", listOf("com.other.editor", "com.hereliesaz.guillotine")),
            dir.absolutePath,
            allowUntrusted = true,
            hostAppId = "com.hereliesaz.guillotine",
        )
        assertTrue("expected Success, got $result", result is AzpHandoffInstaller.InstallResult.Success)
    }

    @Test fun emptyTargetAppsIsGlobal() {
        val dir = tmp.newFolder("ext")
        val result = AzpHandoffInstaller.install(
            unsignedPackage("com.example.global"), dir.absolutePath,
            allowUntrusted = true, hostAppId = "com.hereliesaz.guillotine",
        )
        assertTrue("expected Success, got $result", result is AzpHandoffInstaller.InstallResult.Success)
    }

    @Test fun blankHostIdSkipsScoping() {
        // Callers with no host identity (tests, tooling) must not be silently refused everything.
        val dir = tmp.newFolder("ext")
        val result = AzpHandoffInstaller.install(
            scopedPackage("com.example.other2", listOf("com.example.othereditor")),
            dir.absolutePath, allowUntrusted = true,
        )
        assertTrue("expected Success, got $result", result is AzpHandoffInstaller.InstallResult.Success)
    }

    @Test fun wrongHostIsRefusedBeforeAnyTrustPrompt() {
        // Ordering matters: a package that will never run here must not first ask the user to vouch for
        // its publisher. Signed by an untrusted key AND scoped elsewhere -> WrongHost, not Untrusted.
        val stranger = ed25519Key()
        val code = "export function f(){}".encodeToByteArray()
        val manifestBytes = """
            {"azphalt":"0.1","id":"com.example.both","name":"Both","version":"1.0.0","kind":"code",
             "license":"MIT","compat":">=0.1","entry":"code/main.js","runtime":"js","capabilities":["canvas"],
             "targetApps":["com.example.othereditor"],
             "files":{"code/main.js":"${AzpPackage.digest(code)}"}}
        """.trimIndent().encodeToByteArray()
        val sig = """{"alg":"ed25519","publicKey":"${b64(stranger.public.encoded)}","signature":"${b64(sign(stranger, manifestBytes))}"}"""
        val bytes = zip(
            mapOf(
                "manifest.json" to manifestBytes,
                "code/main.js" to code,
                "signature.json" to sig.encodeToByteArray(),
            ),
        )
        val result = AzpHandoffInstaller.install(
            bytes, tmp.newFolder("ext").absolutePath, hostAppId = "com.hereliesaz.guillotine",
        )
        assertTrue("expected WrongHost, got $result", result is AzpHandoffInstaller.InstallResult.WrongHost)
    }
}
