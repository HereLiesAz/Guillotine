package com.hereliesaz.guillotine.azphalt

import kotlinx.coroutines.runBlocking
import org.bouncycastle.math.ec.rfc7748.X25519
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The `github-actions-runner` protocol and the sandbox install, against a scripted GitHub (no network):
 * what gets committed where, that secrets are sealed rather than sent in the clear, that progress lines
 * arrive once and in order, and that the result is bounded and output-checked.
 */
class AzpLlmSandboxTest {

    private class Call(val method: String, val url: String, val body: String?)

    /** A scripted GitHub: [routes] match "METHOD path-prefix" and return (status, body). */
    private class FakeGitHub(val routes: (String, String, String?) -> AzpLlmSandbox.Response?) : AzpLlmSandbox.Transport {
        val calls = ArrayList<Call>()
        override fun exchange(method: String, url: String, headers: Map<String, String>, body: ByteArray?): AzpLlmSandbox.Response {
            val path = url.removePrefix("https://api.github.com")
            calls += Call(method, path, body?.decodeToString())
            assertEquals("Bearer tok", headers["Authorization"])
            return routes(method, path, body?.decodeToString()) ?: error("unrouted $method $path")
        }
    }

    private fun ok(json: String) = AzpLlmSandbox.Response(200, json.toByteArray())

    private fun zip(entries: Map<String, ByteArray>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> entries.forEach { (n, d) -> z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }
        return bos.toByteArray()
    }

    private val install = AzpLlmSandbox.Install("me", "sb", "main", "com.x.llm", "1.0.0", "azphalt-llm-com.x.llm.yml")

    private fun runRoutes(resultJson: String, correlation: () -> String) = { method: String, path: String, _: String? ->
        when {
            method == "POST" && path.endsWith("/dispatches") -> AzpLlmSandbox.Response(204, ByteArray(0))
            path.contains("/runs?event=workflow_dispatch") ->
                ok("""{"workflow_runs":[{"id":7,"display_title":"${correlation()}","head_sha":"abc","status":"completed","conclusion":"success"}]}""")
            path.contains("/commits/abc/check-runs") ->
                ok("""{"check_runs":[{"output":{"text":"1\tstarting\n2\tthinking\n2\tdup\n3\tdone"}}]}""")
            path.endsWith("/runs/7/artifacts") ->
                ok("""{"artifacts":[{"id":9,"name":"azphalt-llm-result","size_in_bytes":100,"expired":false}]}""")
            path.endsWith("/artifacts/9/zip") ->
                AzpLlmSandbox.Response(200, zip(mapOf("result.json" to resultJson.toByteArray())))
            else -> null
        }
    }

    @Test fun runDispatchesFollowsAndReadsTheResult() = runBlocking {
        val task = AzpLlmSandbox.Task(messages = listOf(AzpLlmDelimiters.Message("user", "hi")))
        val gh = FakeGitHub(runRoutes("""{"status":"completed","message":"ok","text":"hello","outputTokens":3}""") { task.correlationId })
        val progress = ArrayList<String>()
        val r = AzpLlmSandbox.run(AzpLlmSandbox.GitHub("tok", transport = gh), install, task, { s, m -> progress += "$s:$m" }, pollMs = 1)
        assertEquals("completed", r.status)
        assertEquals("hello", r.text)
        assertEquals(3, r.outputTokens)
        assertEquals(listOf("1:starting", "2:thinking", "3:done"), progress)
        val dispatch = gh.calls.first { it.url.endsWith("/dispatches") }
        val sent = JSONObject(JSONObject(dispatch.body!!).getJSONObject("inputs").getString("task"))
        assertEquals(task.correlationId, sent.getString("correlationId"))
        assertEquals("main", JSONObject(dispatch.body).getString("ref"))
    }

    @Test fun outputCarryingASessionTagIsRejected() = runBlocking {
        val key = AzpLlmDelimiters.newSessionKey()
        val tag = AzpLlmDelimiters.turnTag(key, 0)
        val task = AzpLlmSandbox.Task(messages = listOf(AzpLlmDelimiters.Message("user", "x")), sessionKey = key)
        val gh = FakeGitHub(runRoutes("""{"status":"completed","message":"ok","text":"leak $tag"}""") { task.correlationId })
        val r = AzpLlmSandbox.run(AzpLlmSandbox.GitHub("tok", transport = gh), install, task, pollMs = 1)
        assertEquals("failed", r.status)
        assertTrue(r.message.contains("session tag"))
    }

    @Test fun malformedResultsFail() {
        assertEquals("failed", AzpLlmSandbox.parseResult("nope".toByteArray()).status)
        assertEquals("failed", AzpLlmSandbox.parseResult("""{"status":"done","message":"x"}""".toByteArray()).status)
        assertEquals("failed", AzpLlmSandbox.parseResult("""{"status":"completed","message":"x","text":5}""".toByteArray()).status)
        assertEquals("failed", AzpLlmSandbox.parseResult(ByteArray(AzpLlmSandbox.MAX_RESULT_BYTES + 1)).status)
        assertEquals("x", AzpLlmSandbox.parseResult("""{"status":"completed","message":"m","text":"x","extra":1}""".toByteArray()).text)
    }

    @Test fun resultZipInflatesOnlyResultJson() {
        val z = zip(mapOf("other.txt" to "a".toByteArray(), "result.json" to "{}".toByteArray()))
        assertArrayEquals("{}".toByteArray(), AzpLlmSandbox.resultJsonFromZip(z))
        assertEquals(null, AzpLlmSandbox.resultJsonFromZip(zip(mapOf("x" to ByteArray(1)))))
    }

    private fun llmAzp(): ByteArray {
        val workflow = "name: runner\n".toByteArray()
        val script = "#!/bin/sh\n".toByteArray()
        val payload = mapOf("setup/workflow.yml" to workflow, "setup/setup.sh" to script)
        val d = payload.mapValues { AzpPackage.digest(it.value) }
        val manifest = """
            {"azphalt":"0.1","id":"com.x.llm","name":"X","version":"1.0.0","kind":"llm","license":"MIT","compat":">=0.1",
             "llm":{"tier":"endpoint",
               "inputs":[{"id":"providerKey","type":"promptString","password":true,"optional":true}],
               "setup":{"sandbox":"github-actions","script":"setup/setup.sh","secrets":[{"name":"PROVIDER_KEY","input":"providerKey"}]},
               "endpoint":{"protocols":["github-actions-runner"],"defaultModel":"m","auth":"optional-bearer","authInput":"providerKey"},
               "dataHandling":{"prompts":"logged"}},
             "files":{"setup/workflow.yml":"${d["setup/workflow.yml"]}","setup/setup.sh":"${d["setup/setup.sh"]}"}}
        """.trimIndent()
        return zip(payload + ("manifest.json" to manifest.toByteArray()))
    }

    @Test fun installCommitsOnceAndSealsSecrets() = runBlocking {
        val recipientSk = ByteArray(32) { it.toByte() }
        val recipientPk = ByteArray(32).also { X25519.scalarMultBase(recipientSk, 0, it, 0) }
        var blobs = 0
        val gh = FakeGitHub { method, path, _ ->
            when {
                method == "GET" && path == "/repos/me/sb" -> AzpLlmSandbox.Response(404, ByteArray(0))
                method == "GET" && path == "/user" -> ok("""{"login":"me"}""")
                method == "POST" && path == "/user/repos" -> ok("""{"default_branch":"main"}""")
                path.endsWith("/git/ref/heads/main") -> ok("""{"object":{"sha":"p1"}}""")
                path.endsWith("/git/commits/p1") -> ok("""{"tree":{"sha":"t0"}}""")
                path.endsWith("/git/blobs") -> ok("""{"sha":"b${blobs++}"}""")
                path.endsWith("/git/trees") -> ok("""{"sha":"t1"}""")
                path.endsWith("/git/commits") -> ok("""{"sha":"c1"}""")
                method == "PATCH" && path.endsWith("/git/refs/heads/main") -> ok("{}")
                path.endsWith("/actions/secrets/public-key") ->
                    ok("""{"key":"${Base64.getEncoder().encodeToString(recipientPk)}","key_id":"k1"}""")
                method == "PUT" && path.endsWith("/actions/secrets/PROVIDER_KEY") -> AzpLlmSandbox.Response(201, ByteArray(0))
                else -> null
            }
        }
        val r = AzpLlmSandbox.install(
            AzpLlmSandbox.GitHub("tok", transport = gh), "me", "sb", llmAzp(),
            inputs = mapOf("providerKey" to "s3cret"), runSetup = false,
        )
        assertEquals(install, r.install)

        val create = JSONObject(gh.calls.first { it.url == "/user/repos" }.body!!)
        assertTrue(create.getBoolean("private"))

        val tree = JSONObject(gh.calls.first { it.url.endsWith("/git/trees") }.body!!).getJSONArray("tree")
        val paths = (0 until tree.length()).map { tree.getJSONObject(it).getString("path") }.toSet()
        assertEquals(
            setOf(
                "llm/com.x.llm/manifest.json", "llm/com.x.llm/setup/workflow.yml", "llm/com.x.llm/setup/setup.sh",
                ".github/workflows/azphalt-llm-com.x.llm.yml",
            ),
            paths,
        )
        assertEquals(1, gh.calls.count { it.url.endsWith("/git/commits") && it.method == "POST" })

        val secret = gh.calls.first { it.method == "PUT" }
        assertTrue(!secret.body!!.contains("s3cret"))
        val sealed = Base64.getDecoder().decode(JSONObject(secret.body).getString("encrypted_value"))
        assertArrayEquals("s3cret".toByteArray(), AzpSealedBox.open(sealed, recipientPk, recipientSk))
    }

    @Test fun publicSandboxIsRefused() {
        val gh = FakeGitHub { method, path, _ ->
            if (method == "GET" && path == "/repos/me/sb") ok("""{"private":false,"default_branch":"main"}""") else null
        }
        val e = runCatching {
            runBlocking { AzpLlmSandbox.install(AzpLlmSandbox.GitHub("tok", transport = gh), "me", "sb", llmAzp(), runSetup = false) }
        }.exceptionOrNull()
        assertTrue(e?.message.orEmpty().contains("public"))
    }
}
