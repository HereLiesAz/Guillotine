package com.hereliesaz.guillotine.azphalt

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * The GitHub Actions sandbox for azphalt `kind: "llm"` packages (azphalt `spec/llm.md` § Setup, § Sandbox,
 * § `github-actions-runner`). A Kotlin port of the reference `@azphalt/llm-host` (`install.ts`, `run.ts`,
 * `github.ts`), speaking the same protocol as the reference runner the packages ship.
 *
 * - [install] verifies the package, makes sure the sandbox is a **private** repository (creating it when
 *   asked), commits the payload to `llm/<package id>/` and the runner to
 *   `.github/workflows/azphalt-llm-<package id>.yml` in one commit, stores each `setup.secrets` input as an
 *   Actions secret sealed with [AzpSealedBox], and runs setup once.
 * - [run] dispatches the runner with one `task` input, follows its check run's `<seq>\t<message>` lines,
 *   and reads `result.json` from the `azphalt-llm-result` artifact, bounded, as untrusted model output.
 *
 * Nothing here runs on the device except these REST calls: setup and the model run in the sandbox.
 * The token is sent only to [GitHub.apiBase].
 */
object AzpLlmSandbox {

    /** Reference bounds from spec § github-actions-runner. */
    const val MAX_ARTIFACT_BYTES = 4 * 1024 * 1024
    const val MAX_RESULT_BYTES = 2 * 1024 * 1024

    /** Payload path of the runner workflow; first-party packages ship it here. */
    const val WORKFLOW_PATH = "setup/workflow.yml"

    // ---- GitHub REST ---------------------------------------------------------------------------------

    class GitHubException(val status: Int, message: String) : Exception(message)

    /** One HTTP exchange; injectable so the protocol can be tested without a network. */
    fun interface Transport {
        fun exchange(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Response
    }

    class Response(val status: Int, val body: ByteArray)

    class GitHub(
        private val token: String,
        val apiBase: String = "https://api.github.com",
        private val transport: Transport = HttpTransport,
    ) {
        private fun headers(json: Boolean) = buildMap {
            put("Authorization", "Bearer $token")
            put("Accept", "application/vnd.github+json")
            put("X-GitHub-Api-Version", "2022-11-28")
            if (json) put("Content-Type", "application/json")
        }

        /** One JSON call; throws [GitHubException] on any non-2xx answer. Returns null for an empty body. */
        fun call(method: String, path: String, body: JSONObject? = null): Any? {
            val res = transport.exchange(method, apiBase + path, headers(body != null), body?.toString()?.toByteArray())
            if (res.status !in 200..299) throw GitHubException(res.status, "$method $path: HTTP ${res.status}")
            val text = res.body.decodeToString().trim()
            return when {
                text.isEmpty() -> null
                text.startsWith("[") -> JSONArray(text)
                else -> JSONObject(text)
            }
        }

        fun obj(method: String, path: String, body: JSONObject? = null): JSONObject =
            call(method, path, body) as? JSONObject ?: JSONObject()

        /** A binary download (an artifact archive), refusing anything over [maxBytes]. */
        fun bytes(path: String, maxBytes: Int): ByteArray {
            val res = transport.exchange("GET", apiBase + path, headers(false), null)
            if (res.status !in 200..299) throw GitHubException(res.status, "GET $path: HTTP ${res.status}")
            if (res.body.size > maxBytes) throw IllegalStateException("download is ${res.body.size} bytes; the limit is $maxBytes")
            return res.body
        }
    }

    /**
     * OkHttp transport. GitHub answers an artifact download with a redirect to signed storage; OkHttp
     * follows it and drops the Authorization header on the cross-host hop, which is what the signed URL
     * expects. Bodies are read with a bound so a hostile response can't exhaust memory.
     */
    object HttpTransport : Transport {
        private val client by lazy {
            okhttp3.OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .build()
        }

        override fun exchange(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Response {
            val requestBody = when {
                body != null -> body.toRequestBody()
                method == "GET" -> null
                else -> ByteArray(0).toRequestBody()
            }
            val request = okhttp3.Request.Builder().url(url).method(method, requestBody).apply {
                headers.forEach { (k, v) -> header(k, v) }
            }.build()
            client.newCall(request).execute().use { res ->
                val source = res.body.source()
                source.request((MAX_ARTIFACT_BYTES + 1).toLong())
                val bytes = source.buffer.readByteArray(minOf(source.buffer.size, (MAX_ARTIFACT_BYTES + 1).toLong()))
                return Response(res.code, bytes)
            }
        }
    }

    // ---- install -------------------------------------------------------------------------------------

    /** Where an installed package lives; returned by [install], persisted by the host. */
    data class Install(
        val owner: String,
        val repo: String,
        val branch: String,
        val packageId: String,
        val version: String,
        /** The runner workflow's file name under `.github/workflows/`. */
        val workflowFile: String,
    ) {
        fun toJson(): String = JSONObject()
            .put("owner", owner).put("repo", repo).put("branch", branch)
            .put("packageId", packageId).put("version", version).put("workflowFile", workflowFile)
            .toString()

        companion object {
            fun fromJson(json: String): Install? = runCatching {
                val o = JSONObject(json)
                Install(
                    o.getString("owner"), o.getString("repo"), o.getString("branch"),
                    o.getString("packageId"), o.getString("version"), o.getString("workflowFile"),
                )
            }.getOrNull()
        }
    }

    data class InstallResult(val install: Install, val setup: Result?)

    /**
     * Install [azp] into the private sandbox `owner/repo`. The token needs what the package's
     * `setup.requires.githubToken` declares. Throws with a readable message on any refusal.
     */
    suspend fun install(
        github: GitHub,
        owner: String,
        repo: String,
        azp: ByteArray,
        inputs: Map<String, String> = emptyMap(),
        createIfMissing: Boolean = true,
        runSetup: Boolean = true,
        onProgress: (Int, String) -> Unit = { _, _ -> },
    ): InstallResult = withContext(Dispatchers.IO) {
        val loaded = AzpPackage.load(azp) // integrity; throws on a bad package
        val manifest = loaded.manifest
        val endpoint = (AzpLlm.parse(manifest) as? AzpLlm.Parsed.Ok)?.endpoint
            ?: throw IllegalArgumentException((AzpLlm.parse(manifest) as AzpLlm.Parsed.Unsupported).reason)
        if (!endpoint.supportsRunner) throw IllegalArgumentException("${manifest.name} doesn't run in a sandbox")
        for (input in endpoint.inputs) {
            if (!input.optional && inputs[input.id].isNullOrBlank()) throw IllegalArgumentException("input “${input.id}” is required")
        }
        val workflow = loaded.payload[WORKFLOW_PATH]
            ?: throw IllegalArgumentException("the package has no runner workflow at $WORKFLOW_PATH")

        val branch = ensurePrivateRepo(github, owner, repo, createIfMissing)
        val dir = "llm/${manifest.id}"
        val workflowFile = "azphalt-llm-${manifest.id}.yml"
        val files = LinkedHashMap<String, ByteArray>()
        files["$dir/manifest.json"] = loaded.manifestBytes ?: error("manifest bytes missing")
        loaded.payload.forEach { (path, bytes) -> files["$dir/$path"] = bytes }
        files[".github/workflows/$workflowFile"] = workflow
        commitFiles(github, owner, repo, branch, files, "Install ${manifest.id} ${manifest.version}")

        val secrets = endpoint.secrets.filter { !inputs[it.input].isNullOrBlank() }
        if (secrets.isNotEmpty()) {
            val pk = github.obj("GET", "/repos/${enc(owner)}/${enc(repo)}/actions/secrets/public-key")
            val recipient = Base64.getDecoder().decode(pk.getString("key"))
            for (s in secrets) {
                val sealed = AzpSealedBox.seal(inputs.getValue(s.input).toByteArray(), recipient)
                github.call(
                    "PUT", "/repos/${enc(owner)}/${enc(repo)}/actions/secrets/${enc(s.name)}",
                    JSONObject().put("encrypted_value", Base64.getEncoder().encodeToString(sealed)).put("key_id", pk.getString("key_id")),
                )
            }
        }

        val install = Install(owner, repo, branch, manifest.id, manifest.version, workflowFile)
        if (!runSetup) return@withContext InstallResult(install, null)
        val setup = run(github, install, Task(op = "setup"), onProgress = onProgress)
        InstallResult(install, setup)
    }

    private fun ensurePrivateRepo(github: GitHub, owner: String, repo: String, createIfMissing: Boolean): String {
        try {
            val existing = github.obj("GET", "/repos/${enc(owner)}/${enc(repo)}")
            if (!existing.optBoolean("private", false)) {
                throw IllegalStateException("$owner/$repo is public; an llm sandbox must be a private repository")
            }
            return existing.optString("default_branch", "main")
        } catch (e: GitHubException) {
            if (e.status != 404 || !createIfMissing) throw e
        }
        val login = github.obj("GET", "/user").optString("login")
        val path = if (login.equals(owner, ignoreCase = true)) "/user/repos" else "/orgs/${enc(owner)}/repos"
        val created = github.obj(
            "POST", path,
            JSONObject().put("name", repo).put("private", true).put("auto_init", true)
                .put("description", "azphalt llm sandbox. Holds nothing but llm packages and their runs."),
        )
        return created.optString("default_branch").ifBlank { "main" }
    }

    private fun commitFiles(github: GitHub, owner: String, repo: String, branch: String, files: Map<String, ByteArray>, message: String) {
        val base = "/repos/${enc(owner)}/${enc(repo)}/git"
        val ref = github.obj("GET", "$base/ref/heads/${enc(branch)}").getJSONObject("object").getString("sha")
        val parentTree = github.obj("GET", "$base/commits/$ref").getJSONObject("tree").getString("sha")
        val tree = JSONArray()
        for ((path, bytes) in files) {
            val blob = github.obj(
                "POST", "$base/blobs",
                JSONObject().put("content", Base64.getEncoder().encodeToString(bytes)).put("encoding", "base64"),
            )
            tree.put(
                JSONObject().put("path", path).put("mode", if (path.endsWith(".sh")) "100755" else "100644")
                    .put("type", "blob").put("sha", blob.getString("sha")),
            )
        }
        val newTree = github.obj("POST", "$base/trees", JSONObject().put("base_tree", parentTree).put("tree", tree))
        val commit = github.obj(
            "POST", "$base/commits",
            JSONObject().put("message", message).put("tree", newTree.getString("sha")).put("parents", JSONArray().put(ref)),
        )
        github.call("PATCH", "$base/refs/heads/${enc(branch)}", JSONObject().put("sha", commit.getString("sha")))
    }

    // ---- run -----------------------------------------------------------------------------------------

    /** The dispatch `task` (spec § github-actions-runner). Messages are `system`/`user`/`assistant`. */
    data class Task(
        val correlationId: String = randomId(),
        val op: String = "generate",
        val messages: List<AzpLlmDelimiters.Message> = emptyList(),
        val sessionKey: String? = null,
        val turn: Int = 0,
        val model: String? = null,
        val maxTokens: Int? = null,
        val temperature: Double? = null,
    ) {
        fun toJson(): String = JSONObject().apply {
            put("correlationId", correlationId)
            put("op", op)
            if (messages.isNotEmpty()) {
                put("messages", JSONArray().apply { messages.forEach { put(JSONObject().put("role", it.role).put("content", it.content)) } })
            }
            sessionKey?.let { put("sessionKey", it); put("turn", turn) }
            model?.let { put("model", it) }
            maxTokens?.let { put("maxTokens", it) }
            temperature?.let { put("temperature", it) }
        }.toString()
    }

    /** `result.json`, validated. Every field is untrusted model output. */
    data class Result(
        val status: String,
        val message: String,
        val text: String? = null,
        val patch: String? = null,
        val branch: String? = null,
        val inputTokens: Int? = null,
        val outputTokens: Int? = null,
    ) {
        val completed: Boolean get() = status == "completed"
    }

    /**
     * Dispatch (unless [resume]), follow, and return the run's result. [onProgress] gets each new
     * progress line once, in `seq` order.
     */
    suspend fun run(
        github: GitHub,
        install: Install,
        task: Task,
        onProgress: (Int, String) -> Unit = { _, _ -> },
        resume: Boolean = false,
        pollMs: Long = 5_000,
        timeoutMs: Long = 45 * 60 * 1000L,
    ): Result = withContext(Dispatchers.IO) {
        val deadline = System.currentTimeMillis() + timeoutMs
        val tags = task.sessionKey?.let { AzpLlmDelimiters.sessionTags(it, task.turn) }.orEmpty()
        if (!resume) dispatch(github, install, task)

        var run: JSONObject?
        var lastSeq = 0
        while (true) {
            if (System.currentTimeMillis() > deadline) throw IllegalStateException("run ${task.correlationId} did not finish in time")
            run = findRun(github, install, task.correlationId)
            if (run != null) {
                val checks = github.obj(
                    "GET",
                    "${repoPath(install)}/commits/${run.getString("head_sha")}/check-runs?check_name=${enc(task.correlationId)}",
                )
                val text = checks.optJSONArray("check_runs")?.optJSONObject(0)?.optJSONObject("output")?.optString("text")
                for ((seq, message) in parseProgress(text)) {
                    if (seq > lastSeq) {
                        lastSeq = seq
                        onProgress(seq, message)
                    }
                }
                if (run.optString("status") == "completed") break
            }
            delay(pollMs)
        }

        val result = readResult(github, install, run!!)
        // § Output check: model output carrying any session tag is rejected, whoever translated.
        if (tags.isNotEmpty() && listOfNotNull(result.text, result.patch).any { AzpLlmDelimiters.containsSessionTag(it, tags) }) {
            return@withContext Result("failed", "output contained a session tag; rejected")
        }
        result
    }

    /** The run whose `run-name` is [correlationId], if it has started. */
    fun findRun(github: GitHub, install: Install, correlationId: String): JSONObject? {
        val runs = github.obj(
            "GET",
            "${repoPath(install)}/actions/workflows/${enc(install.workflowFile)}/runs?event=workflow_dispatch&per_page=30",
        ).optJSONArray("workflow_runs") ?: return null
        for (i in 0 until runs.length()) {
            val r = runs.getJSONObject(i)
            if (r.optString("display_title") == correlationId) return r
        }
        return null
    }

    private suspend fun dispatch(github: GitHub, install: Install, task: Task) {
        // A just-committed workflow takes a few seconds to register; until then dispatch answers 404/422.
        var attempt = 0
        while (true) {
            try {
                github.call(
                    "POST", "${repoPath(install)}/actions/workflows/${enc(install.workflowFile)}/dispatches",
                    JSONObject().put("ref", install.branch).put("inputs", JSONObject().put("task", task.toJson())),
                )
                return
            } catch (e: GitHubException) {
                if ((e.status != 404 && e.status != 422) || attempt >= 5) throw e
                attempt++
                delay(3_000L * attempt)
            }
        }
    }

    internal fun parseProgress(text: String?): List<Pair<Int, String>> =
        text.orEmpty().split('\n').mapNotNull { line ->
            val m = Regex("""^(\d+)\t(.*)$""").find(line) ?: return@mapNotNull null
            m.groupValues[1].toIntOrNull()?.let { it to m.groupValues[2] }
        }

    /** Validate an untrusted `result.json`. Unknown fields are dropped; wrong types fail the result. */
    fun parseResult(raw: ByteArray): Result {
        if (raw.size > MAX_RESULT_BYTES) return Result("failed", "result.json exceeds 2 MB")
        val r = runCatching { JSONObject(raw.decodeToString()) }.getOrNull() ?: return Result("failed", "result.json is not JSON")
        val status = r.opt("status")
        val message = r.opt("message")
        if ((status != "completed" && status != "failed") || message !is String) return Result("failed", "result.json is malformed")
        val strings = HashMap<String, String>()
        for (k in listOf("text", "patch", "branch")) {
            if (!r.has(k) || r.isNull(k)) continue
            strings[k] = r.get(k) as? String ?: return Result("failed", "result.json $k is not a string")
        }
        val ints = HashMap<String, Int>()
        for (k in listOf("inputTokens", "outputTokens")) {
            if (!r.has(k) || r.isNull(k)) continue
            val v = r.get(k)
            ints[k] = (v as? Int) ?: return Result("failed", "result.json $k is not an integer")
        }
        return Result(status as String, message, strings["text"], strings["patch"], strings["branch"], ints["inputTokens"], ints["outputTokens"])
    }

    private fun readResult(github: GitHub, install: Install, run: JSONObject): Result {
        val artifacts = github.obj("GET", "${repoPath(install)}/actions/runs/${run.get("id")}/artifacts").optJSONArray("artifacts")
        var artifact: JSONObject? = null
        for (i in 0 until (artifacts?.length() ?: 0)) {
            val a = artifacts!!.getJSONObject(i)
            if (a.optString("name") == "azphalt-llm-result" && !a.optBoolean("expired")) artifact = a
        }
        if (artifact == null) {
            return Result("failed", "the run ended ${run.optString("conclusion").ifBlank { "without a conclusion" }} and left no result")
        }
        if (artifact.optLong("size_in_bytes") > MAX_ARTIFACT_BYTES) return Result("failed", "result artifact exceeds 4 MB")
        val zip = github.bytes("${repoPath(install)}/actions/artifacts/${artifact.get("id")}/zip", MAX_ARTIFACT_BYTES)
        val raw = runCatching { resultJsonFromZip(zip) }.getOrElse { return Result("failed", "result artifact is not a readable zip") }
            ?: return Result("failed", "result artifact holds no result.json (or one over 2 MB)")
        return parseResult(raw)
    }

    /** Inflates nothing but `result.json`, and nothing over the bound (null when absent or too big). */
    internal fun resultJsonFromZip(zip: ByteArray): ByteArray? {
        ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
            var e = zin.nextEntry
            while (e != null) {
                if (!e.isDirectory && e.name == "result.json") {
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = zin.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > MAX_RESULT_BYTES) return null
                    }
                    return out.toByteArray()
                }
                e = zin.nextEntry
            }
        }
        return null
    }

    // ---- text jobs -----------------------------------------------------------------------------------

    /**
     * One plain-text completion run in the sandbox, for background jobs (vocabulary expansion and the
     * like). [prompt] is host-authored; a fresh session key still arms the output check. Null on failure.
     */
    suspend fun complete(github: GitHub, install: Install, prompt: String, maxTokens: Int? = null): String? {
        val key = AzpLlmDelimiters.newSessionKey()
        val result = run(
            github, install,
            Task(messages = listOf(AzpLlmDelimiters.Message("user", prompt)), sessionKey = key, turn = 0, maxTokens = maxTokens),
        )
        return result.text?.takeIf { result.completed && it.isNotBlank() }
    }

    private val rng = SecureRandom()

    private fun randomId(): String =
        "azphalt-llm-" + ByteArray(8).also(rng::nextBytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun repoPath(i: Install) = "/repos/${enc(i.owner)}/${enc(i.repo)}"

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
}
