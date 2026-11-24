package io.nativeforge.core.remote

import io.nativeforge.core.build.diagnostics.Diagnostic
import io.nativeforge.core.build.model.BuildArtifact
import io.nativeforge.core.build.model.BuildEvent
import io.nativeforge.core.common.Logger
import io.nativeforge.core.common.Outcome
import io.nativeforge.core.common.process.OutputStream
import io.nativeforge.core.project.model.BuildSystemKind
import io.nativeforge.core.project.model.Language
import io.nativeforge.core.project.model.Project
import io.nativeforge.core.project.template.ProjectOptions
import io.nativeforge.core.project.template.ProjectTemplate
import io.nativeforge.core.remote.http.HttpHandler
import io.nativeforge.core.remote.http.HttpReply
import io.nativeforge.core.remote.http.HttpRequest
import io.nativeforge.core.remote.http.HttpResponse
import io.nativeforge.core.remote.http.HttpServer
import io.nativeforge.core.remote.http.HttpServerOptions
import io.nativeforge.core.remote.http.Respond
import io.nativeforge.core.remote.http.RespondStream
import io.nativeforge.core.remote.http.Upgrade
import io.nativeforge.core.remote.http.WebSocket
import io.nativeforge.core.remote.http.WebSocketListener
import io.nativeforge.core.remote.security.AccessControl
import io.nativeforge.core.remote.security.NetworkPolicy
import io.nativeforge.core.remote.security.PairingResult
import io.nativeforge.core.remote.security.TrustedBrowser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** Server settings the user can change. */
data class RemoteServerConfig(
    /** Stop after this long without anything done from a browser; 0 never stops. */
    val idleTimeoutMillis: Long = TimeUnit.MINUTES.toMillis(30),
    /** Refuse peers outside local networks (see [NetworkPolicy.isLocalPeer]). */
    val localNetworksOnly: Boolean = true,
)

/** What the app shows about a running server. */
data class RemoteServerStatus(
    val port: Int,
    val connectedBrowsers: Int,
    val buildRunning: Boolean,
    val lastActivityMillis: Long,
)

/**
 * The web IDE's server: static files, a JSON API over HTTPS and two WebSocket channels
 * (`/ws` for events, build output and clangd; `/ws/terminal` for terminal bytes).
 *
 * Security model (see docs/SECURITY.md, "Remote access"):
 * * Only the pairing endpoint and the page's own static files answer without a session.
 *   Everything else needs the `__Host-nf_session` cookie (HttpOnly, Secure, SameSite=Strict)
 *   of a browser paired with a one-time code ([AccessControl]).
 * * Requests must carry an allowed Host (DNS rebinding) and, when they change state or open a
 *   WebSocket, an Origin equal to the server's own (CSRF, cross-site WebSockets).
 * * File access is confined to project folders ([ProjectFiles]).
 * * Strict CSP and the usual hardening headers on every response.
 */
class RemoteServer(
    private val backend: RemoteBackend,
    private val access: AccessControl,
    private val logger: Logger,
    private val config: RemoteServerConfig = RemoteServerConfig(),
    private val assets: StaticAssets = StaticAssets(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainSockets = CopyOnWriteArraySet<WebSocket>()
    private val terminals = TerminalHub(backend.ptyFactory, logger)
    private val intel = CodeIntelHub(backend, logger, ::broadcast)
    private val lastActivity = AtomicLong(clock())
    private var http: HttpServer? = null
    private var scheduler: ScheduledExecutorService? = null
    private var previousOnChanged: () -> Unit = {}

    @Volatile private var buildJob: Job? = null
    @Volatile private var buildProject: String? = null
    private val buildLog = ArrayDeque<JsonObject>()
    private val buildDiagnostics = ArrayList<JsonObject>()
    @Volatile private var lastBuild: JsonObject? = null
    private val artifacts = java.util.concurrent.ConcurrentHashMap<String, BuildArtifact>()

    /** Called (on a background thread) when the server stops itself after [RemoteServerConfig.idleTimeoutMillis]. */
    @Volatile var onIdle: () -> Unit = {}

    /** Called whenever [status] may have changed (browsers connect, builds start or finish). */
    @Volatile var onStatusChanged: () -> Unit = {}

    val status: RemoteServerStatus
        get() = RemoteServerStatus(http?.port ?: 0, mainSockets.size, buildJob?.isActive == true, lastActivity.get())

    /** Starts serving on [serverSocket] (an SSLServerSocket from [io.nativeforge.core.remote.security.TlsIdentity]). */
    fun start(serverSocket: ServerSocket, options: HttpServerOptions = HttpServerOptions()) {
        check(http == null) { "Already started" }
        val server = HttpServer(
            serverSocket = serverSocket,
            handler = HttpHandler(::route),
            logger = logger,
            options = options,
            acceptPeer = { !config.localNetworksOnly || NetworkPolicy.isLocalPeer(it) },
            decorate = ::securityHeaders,
        )
        http = server
        server.start()
        previousOnChanged = access.onChanged
        access.onChanged = { previousOnChanged(); dropRevokedSockets() }
        scheduler = Executors.newSingleThreadScheduledExecutor { Thread(it, "nf-remote-timer").apply { isDaemon = true } }.also {
            it.scheduleWithFixedDelay(::tick, 30, 30, TimeUnit.SECONDS)
        }
        logger.info(TAG, "Remote access listening on port ${server.port}")
    }

    fun stop() {
        scheduler?.shutdownNow()
        scheduler = null
        buildJob?.cancel()
        mainSockets.forEach { runCatching { it.close(1001, "Server stopped") } }
        mainSockets.clear()
        terminals.closeAll()
        intel.stop()
        http?.stop()
        http = null
        scope.cancel()
        access.onChanged = previousOnChanged
    }

    /** Pushes the app's current theme to every browser (call when the user changes it). */
    fun themeChanged() = broadcast(Json.obj("t" to "theme", "theme" to themeJson()))

    private fun touch() = lastActivity.set(clock())

    private fun tick() {
        mainSockets.forEach { socket -> runCatching { socket.ping() }.onFailure { mainSockets.remove(socket) } }
        terminals.list().forEach { session -> session.clients.forEach { runCatching { it.ping() } } }
        if (buildJob?.isActive == true) touch()
        val idle = config.idleTimeoutMillis
        if (idle > 0 && clock() - lastActivity.get() > idle) {
            logger.info(TAG, "Stopping remote access after ${idle / 60000} idle minutes")
            onIdle()
        }
    }

    // ---------------------------------------------------------------- routing

    private fun route(request: HttpRequest): HttpReply {
        if (!NetworkPolicy.isAllowedHost(request.header("host"))) {
            return Respond(HttpResponse.error(421, "Open this page by the address shown in the app"))
        }
        val changesState = request.method != "GET" && request.method != "HEAD"
        if ((changesState || request.isWebSocketUpgrade) && !NetworkPolicy.isSameOrigin(request.header("origin"), request.header("host"))) {
            return Respond(HttpResponse.error(403, "Cross-origin request refused"))
        }

        // Public: the page and its static files (code only, no data), and pairing.
        when {
            request.method == "GET" && (request.path == "/" || request.path == "/index.html") -> return Respond(assets.index(request))
            request.path.startsWith("/a/") -> return Respond(assets.serve(request) ?: HttpResponse.error(404, "Not found"))
            request.path == "/api/hello" -> return Respond(hello(request))
            request.path == "/api/pair" && request.method == "POST" -> return Respond(pair(request))
            request.path == "/favicon.ico" -> return Respond(HttpResponse.empty(404))
        }

        val token = request.cookie(COOKIE)
        val browser = access.authenticate(token) ?: return Respond(HttpResponse.error(401, "Pair this browser with the code shown in the app"))
        touch()
        return try {
            authenticated(request, token!!, browser)
        } catch (e: FileAccessException) {
            Respond(HttpResponse.error(e.status, e.message ?: "File error"))
        } catch (e: IllegalArgumentException) {
            Respond(HttpResponse.error(400, e.message ?: "Bad request"))
        } catch (e: IllegalStateException) {
            Respond(HttpResponse.error(409, e.message ?: "Conflict"))
        }
    }

    private fun authenticated(request: HttpRequest, token: String, browser: TrustedBrowser): HttpReply {
        val get = request.method == "GET"
        return when (request.path) {
            "/ws" -> if (request.isWebSocketUpgrade) Upgrade(MainChannel(token)) else Respond(HttpResponse.error(400, "WebSocket expected"))
            "/ws/terminal" -> {
                val session = terminals[request.query["id"]] ?: return Respond(HttpResponse.error(404, "No such terminal"))
                if (request.isWebSocketUpgrade) Upgrade(TerminalChannel(session, token)) else Respond(HttpResponse.error(400, "WebSocket expected"))
            }
            "/api/logout" -> { access.logout(token); Respond(HttpResponse.json("{}").header("Set-Cookie", clearCookie())) }
            "/api/bootstrap" -> Respond(HttpResponse.json(bootstrap(browser).toString()))
            "/api/theme" -> Respond(HttpResponse.json(themeJson().toString()))
            "/api/projects" -> if (get) Respond(HttpResponse.json(projectsJson().toString())) else Respond(createProject(request))
            "/api/projects/remove" -> Respond(removeProject(request))
            "/api/tree" -> Respond(HttpResponse.json(treeJson(project(request.query["project"])).toString()))
            "/api/file" -> when (request.method) {
                "GET" -> Respond(readFile(request))
                "PUT" -> Respond(writeFile(request))
                else -> Respond(HttpResponse.error(405, "Method not allowed"))
            }
            "/api/fs" -> Respond(fileOperation(request))
            "/api/download" -> download(request)
            "/api/artifact" -> artifactDownload(request)
            "/api/terminals" -> if (get) Respond(HttpResponse.json(terminalsJson().toString())) else Respond(openTerminal(request))
            "/api/terminals/close" -> {
                terminals.close(bodyJson(request).string("id").orEmpty())
                Respond(HttpResponse.json("{}"))
            }
            else -> Respond(HttpResponse.error(404, "Not found"))
        }
    }

    private fun securityHeaders(request: HttpRequest?, headers: MutableMap<String, String>) {
        // 'self' covers same-host WebSockets in CSP 3; older Safari needs the wss:// origin spelled out.
        val socketOrigin = request?.header("host")?.takeIf(NetworkPolicy::isAllowedHost)?.let { " wss://$it" }.orEmpty()
        headers["Content-Security-Policy"] =
            "default-src 'self'; script-src 'self' ${assets.importMapHash}; style-src 'self' 'unsafe-inline'; " +
            "img-src 'self' data: blob:; font-src 'self'; connect-src 'self'$socketOrigin; worker-src 'self' blob:; " +
            "object-src 'none'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
        headers["X-Content-Type-Options"] = "nosniff"
        headers["X-Frame-Options"] = "DENY"
        headers["Referrer-Policy"] = "no-referrer"
        headers["Cross-Origin-Opener-Policy"] = "same-origin"
        headers["Cross-Origin-Resource-Policy"] = "same-origin"
        headers["Permissions-Policy"] = "camera=(), microphone=(), geolocation=(), payment=(), usb=()"
        if (!headers.containsKey("Cache-Control")) headers["Cache-Control"] = "no-store"
    }

    // ---------------------------------------------------------------- pairing

    private fun hello(request: HttpRequest): HttpResponse {
        val authenticated = access.authenticate(request.cookie(COOKIE)) != null
        return HttpResponse.json(
            Json.obj(
                "authenticated" to authenticated,
                "app" to backend.appVersion,
                "device" to backend.deviceName,
                "pairing" to (access.currentPairingCode() != null),
            ).toString(),
        )
    }

    private fun pair(request: HttpRequest): HttpResponse {
        val body = bodyJson(request)
        val label = browserLabel(request.header("user-agent"))
        return when (val result = access.pair(body.string("code").orEmpty(), label)) {
            is PairingResult.Paired -> {
                logger.info(TAG, "Browser paired: ${result.browser.label} from ${request.remoteAddress.hostAddress}")
                onStatusChanged()
                HttpResponse.json(Json.obj("ok" to true).toString()).header(
                    "Set-Cookie",
                    "$COOKIE=${result.token}; Path=/; Max-Age=${TimeUnit.DAYS.toSeconds(30)}; HttpOnly; Secure; SameSite=Strict",
                )
            }
            PairingResult.WrongCode -> {
                // Slows down guessing on top of the attempt limit.
                Thread.sleep(400)
                HttpResponse.error(401, "Wrong code")
            }
            PairingResult.TooManyAttempts -> HttpResponse.error(429, "Too many wrong codes: create a new code in the app")
            PairingResult.NoActiveCode -> HttpResponse.error(410, "No active code: create one in the app (Remote access)")
        }
    }

    private fun clearCookie() = "$COOKIE=; Path=/; Max-Age=0; HttpOnly; Secure; SameSite=Strict"

    /** Closes the connections of browsers that were revoked or logged out. */
    private fun dropRevokedSockets() {
        val sockets = mainSockets.toList() + terminals.list().flatMap { it.clients }
        sockets.forEach { socket ->
            val token = socket.attachment as? String ?: return@forEach
            if (access.authenticate(token) == null) runCatching { socket.close(4001, "Signed out") }
        }
        onStatusChanged()
    }

    // ---------------------------------------------------------------- projects

    private fun project(key: String?): Project {
        require(!key.isNullOrBlank()) { "Missing project" }
        val result = runBlocking { withTimeout(15_000) { backend.openProject(key) } }
        return when (result) {
            is Outcome.Success -> result.value
            is Outcome.Failure -> throw FileAccessException(404, result.error.message)
        }
    }

    private fun files(project: Project) = ProjectFiles(project.root, setOf(project.buildDirectory))

    private fun bootstrap(browser: TrustedBrowser) = Json.obj(
        "app" to backend.appVersion,
        "device" to backend.deviceName,
        "browser" to browser.label,
        "theme" to themeJson(),
        "projects" to projectsJson(),
        "templates" to templatesJson(),
        "build" to buildStateJson(),
        "intel" to intel.status,
        "terminals" to terminalsJson(),
    )

    private fun projectsJson() = runBlocking { backend.listProjects() }.map(::projectJson)

    private fun projectJson(project: Project) = Json.obj(
        "key" to project.key,
        "name" to project.name,
        "path" to project.root.path,
        "external" to project.isExternal,
        "language" to project.config.language.name,
        "standard" to project.config.standard,
        "buildSystem" to project.config.buildSystem.displayName,
        "runtime" to project.config.runtime.name,
        "packages" to project.config.packages,
        "modified" to project.root.lastModified(),
    )

    private fun templatesJson() = backend.templates().map { t ->
        Json.obj(
            "id" to t.id,
            "name" to t.displayName,
            "description" to t.description,
            "details" to t.details,
            "category" to t.category.displayName,
            "runtime" to t.runtime.name,
            "languages" to t.languages.map { it.name },
            "buildSystems" to t.buildSystems.map { mapOf("id" to it.name, "name" to it.displayName) },
            "packages" to t.requiredPackages,
            "standards" to t.languages.associate { it.name to ProjectOptions.standardsFor(it) },
        )
    }

    private fun createProject(request: HttpRequest): HttpResponse {
        val body = bodyJson(request)
        val name = body.string("name")?.trim().orEmpty()
        val template: ProjectTemplate = backend.templates().firstOrNull { it.id == body.string("template") }
            ?: throw IllegalArgumentException("Unknown template")
        val defaults = template.defaultOptions()
        val language = body.string("language")?.let { v -> Language.entries.firstOrNull { it.name == v } }?.takeIf { it in template.languages } ?: defaults.language
        val buildSystem = body.string("buildSystem")?.let { v -> BuildSystemKind.entries.firstOrNull { it.name == v } }?.takeIf { it in template.buildSystems }
            ?: defaults.buildSystem
        val standard = body.string("standard")?.takeIf { it in ProjectOptions.standardsFor(language) } ?: language.defaultStandard
        val options = defaults.copy(language = language, buildSystem = buildSystem, standard = standard)
        return when (val result = runBlocking { backend.createProject(name, template, options) }) {
            is Outcome.Success -> HttpResponse.json(projectJson(result.value).toString(), 201)
            is Outcome.Failure -> HttpResponse.error(400, result.error.message)
        }
    }

    private fun removeProject(request: HttpRequest): HttpResponse {
        val project = project(bodyJson(request).string("project"))
        return when (val result = runBlocking { backend.removeProject(project) }) {
            is Outcome.Success -> HttpResponse.json("{}")
            is Outcome.Failure -> HttpResponse.error(400, result.error.message)
        }
    }

    // ---------------------------------------------------------------- files

    private fun treeJson(project: Project) = Json.obj(
        "project" to projectJson(project),
        "entries" to files(project).tree().map { mapOf("path" to it.path, "dir" to it.isDirectory, "size" to it.size) },
    )

    private fun readFile(request: HttpRequest): HttpResponse {
        val result = files(project(request.query["project"])).readText(request.query["path"].orEmpty())
        return HttpResponse.json(
            Json.obj("text" to result.text, "binary" to (result.text == null), "size" to result.size, "modified" to result.modified).toString(),
        )
    }

    private fun writeFile(request: HttpRequest): HttpResponse {
        val project = project(request.query["project"])
        val files = files(project)
        val path = request.query["path"].orEmpty()
        val modified = files.write(path, request.body, request.query["expected"]?.toLongOrNull())
        val file = files.resolve(path)
        intel.saved(file)
        broadcast(Json.obj("t" to "fs", "project" to project.key, "path" to path))
        return HttpResponse.json(Json.obj("modified" to modified).toString())
    }

    private fun fileOperation(request: HttpRequest): HttpResponse {
        val body = bodyJson(request)
        val project = project(body.string("project"))
        val files = files(project)
        val path = body.string("path").orEmpty()
        when (body.string("op")) {
            "mkdir" -> files.createDirectory(path)
            "touch" -> files.createFile(path)
            "rename" -> files.rename(path, body.string("to").orEmpty())
            "delete" -> { intel.close(files.resolve(path)); files.delete(path) }
            else -> throw IllegalArgumentException("Unknown operation")
        }
        broadcast(Json.obj("t" to "fs", "project" to project.key, "path" to path))
        return HttpResponse.json("{}")
    }

    private fun download(request: HttpRequest): HttpReply {
        val project = project(request.query["project"])
        val file = files(project).resolve(request.query["path"])
        if (!file.isFile) return Respond(HttpResponse.error(404, "No such file"))
        return stream(file)
    }

    private fun artifactDownload(request: HttpRequest): HttpReply {
        val artifact = artifacts[request.query["project"].orEmpty()] ?: return Respond(HttpResponse.error(404, "Build the project first"))
        if (!artifact.file.isFile) return Respond(HttpResponse.error(404, "Build the project first"))
        return stream(artifact.file)
    }

    private fun stream(file: File): HttpReply {
        val name = file.name.filter { it.isLetterOrDigit() || it in "._-+" }.ifEmpty { "download" }
        return RespondStream(
            status = 200,
            contentType = "application/octet-stream",
            length = file.length(),
            headers = mapOf("Content-Disposition" to "attachment; filename=\"$name\""),
            open = { file.inputStream() },
        )
    }

    // ---------------------------------------------------------------- terminals

    private fun terminalsJson() = terminals.list().map { mapOf("id" to it.id, "title" to it.title, "exit" to it.exitCode) }

    private fun openTerminal(request: HttpRequest): HttpResponse {
        val body = bodyJson(request)
        val shell = backend.shellCommand()
        val project = body.string("project")?.takeIf { it.isNotBlank() }?.let(::project)
        val command = if (project != null) shell.copy(workingDirectory = project.root.path) else shell
        val session = terminals.open(command, project?.name ?: "Terminal", body.int("rows") ?: 24, body.int("cols") ?: 80)
        return HttpResponse.json(Json.obj("id" to session.id, "title" to session.title).toString(), 201)
    }

    private inner class TerminalChannel(private val session: TerminalHub.Session, private val token: String) : WebSocketListener {
        override fun onOpen(socket: WebSocket) {
            socket.attachment = token
            session.attach(socket)
        }

        override fun onBinary(socket: WebSocket, data: ByteArray) {
            touch()
            session.write(data)
        }

        override fun onText(socket: WebSocket, text: String) {
            val message = Json.parseObject(text) ?: return
            when (message.string("t")) {
                "resize" -> session.resize(message.int("rows") ?: 24, message.int("cols") ?: 80)
                "input" -> { touch(); session.write(message.string("data").orEmpty().toByteArray()) }
            }
        }

        override fun onClose(socket: WebSocket) = session.detach(socket)
    }

    // ---------------------------------------------------------------- main channel

    private inner class MainChannel(private val token: String) : WebSocketListener {
        override fun onOpen(socket: WebSocket) {
            socket.attachment = token
            mainSockets += socket
            onStatusChanged()
        }

        override fun onClose(socket: WebSocket) {
            mainSockets -= socket
            onStatusChanged()
        }

        override fun onText(socket: WebSocket, text: String) {
            if (access.authenticate(token) == null) {
                socket.close(4001, "Signed out")
                return
            }
            val message = Json.parseObject(text) ?: return
            val type = message.string("t") ?: return
            if (type == "ping") return
            touch()
            try {
                handle(socket, type, message)
            } catch (e: Exception) {
                message.int("id")?.let { id -> reply(socket, id, null, e.message ?: "Error") }
            }
        }
    }

    private fun handle(socket: WebSocket, type: String, message: JsonObject) {
        val id = message.int("id")
        when (type) {
            "build" -> startBuild(project(message.string("project")), run = message.bool("run") == true)
            "cancel" -> buildJob?.cancel()
            "run" -> runLast(project(message.string("project")))
            "lsp.sync" -> {
                val project = project(message.string("project"))
                intel.sync(project, files(project).resolve(message.string("path")), message.string("text").orEmpty())
            }
            "lsp.close" -> {
                val project = project(message.string("project"))
                intel.close(files(project).resolve(message.string("path")))
            }
            "lsp.complete", "lsp.hover", "lsp.definition", "lsp.format" -> scope.launch {
                val result = runCatching {
                    val project = project(message.string("project"))
                    val file = files(project).resolve(message.string("path"))
                    val text = message.string("text").orEmpty()
                    val line = message.int("line") ?: 0
                    val ch = message.int("ch") ?: 0
                    when (type) {
                        "lsp.complete" -> intel.completion(project, file, text, line, ch)
                        "lsp.hover" -> intel.hover(project, file, text, line, ch)?.let { Json.element(it) }
                        "lsp.definition" -> Json.element(intel.definition(project, file, text, line, ch))
                        else -> intel.format(project, file, text, message.int("tabSize") ?: 4)?.let { Json.element(it) }
                    }
                }
                if (id != null) reply(socket, id, result.getOrNull(), result.exceptionOrNull()?.message)
            }
            else -> if (id != null) reply(socket, id, null, "Unknown request")
        }
    }

    private fun reply(socket: WebSocket, id: Int, result: Any?, error: String?) {
        runCatching { socket.sendText(Json.obj("t" to "reply", "id" to id, "result" to result, "error" to error).toString()) }
    }

    private fun broadcast(message: JsonObject) {
        val text = message.toString()
        mainSockets.forEach { socket -> runCatching { socket.sendText(text) }.onFailure { mainSockets.remove(socket) } }
    }

    // ---------------------------------------------------------------- build and run

    private fun buildStateJson() = synchronized(buildLog) {
        Json.obj(
            "running" to (buildJob?.isActive == true),
            "project" to buildProject,
            "log" to buildLog.toList(),
            "diagnostics" to buildDiagnostics.toList(),
            "last" to lastBuild,
        )
    }

    private fun startBuild(project: Project, run: Boolean) {
        if (buildJob?.isActive == true) throw IllegalStateException("A build is already running")
        synchronized(buildLog) {
            buildLog.clear()
            buildDiagnostics.clear()
        }
        buildProject = project.key
        broadcast(Json.obj("t" to "build.start", "project" to project.key, "run" to run))
        onStatusChanged()
        buildJob = scope.launch {
            var finished: BuildEvent.Finished? = null
            try {
                backend.build(project).collect { event ->
                    when (event) {
                        is BuildEvent.StepStarted -> log("[${event.index + 1}/${event.total}] ${event.step.description}", false)
                        is BuildEvent.Output -> log(event.line, event.stream == OutputStream.STDERR)
                        is BuildEvent.DiagnosticReported -> diagnostic(project, event.diagnostic)
                        is BuildEvent.StepFinished -> if (event.exitCode != 0) log("Step failed with exit code ${event.exitCode}", true)
                        is BuildEvent.Finished -> finished = event
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                log("Build cancelled", true)
            } catch (e: Exception) {
                log("Build failed: ${e.message}", true)
            }
            val result = finished?.result
            val success = result?.success == true
            result?.artifact?.let { artifacts[project.key] = it }
            if (result != null) {
                log(if (success) "Build succeeded in %.1fs".format(result.durationMillis / 1000.0) else "Build failed", !success)
            }
            val summary = Json.obj(
                "project" to project.key,
                "ok" to success,
                "millis" to (result?.durationMillis ?: 0),
                "artifact" to result?.artifact?.file?.name,
            )
            lastBuild = summary
            broadcast(Json.obj("t" to "build.done").merge(summary))
            intel.refreshDatabase(project)
            onStatusChanged()
            if (success && run) runLast(project)
        }
    }

    private fun JsonObject.merge(other: JsonObject) = JsonObject(toMap() + other.toMap())

    private fun log(line: String, error: Boolean) {
        val entry = Json.obj("line" to line, "err" to error)
        synchronized(buildLog) {
            buildLog.addLast(entry)
            while (buildLog.size > MAX_LOG_LINES) buildLog.removeFirst()
        }
        broadcast(Json.obj("t" to "build.log", "line" to line, "err" to error))
    }

    private fun diagnostic(project: Project, diagnostic: Diagnostic) {
        val path = diagnostic.file?.let { raw ->
            val file = File(raw).let { if (it.isAbsolute) it else File(project.root, raw) }
            runCatching { files(project).relativePath(file) }.getOrNull()?.takeUnless { it.startsWith("..") } ?: raw
        }
        val entry = Json.obj(
            "severity" to diagnostic.severity.name.lowercase(),
            "message" to diagnostic.message,
            "path" to path,
            "line" to diagnostic.line,
            "column" to diagnostic.column,
        )
        synchronized(buildLog) { if (buildDiagnostics.size < MAX_DIAGNOSTICS) buildDiagnostics += entry }
        broadcast(Json.obj("t" to "build.diag", "d" to entry))
    }

    private fun runLast(project: Project) {
        val artifact = artifacts[project.key]?.takeIf { it.file.isFile } ?: throw IllegalStateException("Build the project first")
        if (project.config.runtime == io.nativeforge.core.project.model.RuntimeKind.CONSOLE) {
            val session = terminals.open(backend.consoleCommand(project, artifact), "▶ ${project.name}", 24, 80)
            broadcast(Json.obj("t" to "run.console", "id" to session.id, "title" to session.title))
        } else {
            val error = backend.runOnDevice(project, artifact)
            broadcast(
                Json.obj(
                    "t" to "run.device",
                    "ok" to (error == null),
                    "message" to (error ?: "${project.name} is running on the phone's screen"),
                ),
            )
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun themeJson(): JsonObject {
        val theme = backend.theme()
        return Json.obj(
            "dark" to theme.isDark,
            "colors" to theme.colors,
            "editor" to theme.editorThemeJson?.let { runCatching { Json.parser.parseToJsonElement(it) }.getOrNull() },
            "fontSize" to theme.editorFontSize,
            "tabWidth" to theme.tabWidth,
            "wordWrap" to theme.wordWrap,
            "lineNumbers" to theme.lineNumbers,
            "highlightLine" to theme.highlightCurrentLine,
            "terminalFontSize" to theme.terminalFontSize,
        )
    }

    private fun bodyJson(request: HttpRequest): JsonObject =
        Json.parseObject(request.bodyText()) ?: throw IllegalArgumentException("JSON body expected")

    companion object {
        private const val TAG = "RemoteServer"

        /** `__Host-` cookies must be Secure, host-only and Path=/, so no other site or subdomain can set them. */
        const val COOKIE = "__Host-nf_session"
        private const val MAX_LOG_LINES = 3000
        private const val MAX_DIAGNOSTICS = 500

        /** "Chrome on Windows" from a User-Agent, for the list of trusted browsers. */
        fun browserLabel(userAgent: String?): String {
            val ua = userAgent.orEmpty()
            val browser = when {
                "Edg/" in ua -> "Edge"
                "OPR/" in ua -> "Opera"
                "Firefox/" in ua -> "Firefox"
                "Chrome/" in ua -> "Chrome"
                "Safari/" in ua -> "Safari"
                else -> "Browser"
            }
            val os = when {
                "Windows" in ua -> "Windows"
                "Mac OS X" in ua && "Mobile" !in ua -> "macOS"
                "Android" in ua -> "Android"
                "iPhone" in ua || "iPad" in ua -> "iOS"
                "CrOS" in ua -> "ChromeOS"
                "Linux" in ua -> "Linux"
                else -> null
            }
            return if (os != null) "$browser on $os" else browser
        }
    }
}
