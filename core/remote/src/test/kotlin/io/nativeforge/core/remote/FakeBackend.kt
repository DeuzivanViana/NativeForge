package io.nativeforge.core.remote

import io.nativeforge.core.build.model.ArtifactKind
import io.nativeforge.core.build.model.BuildArtifact
import io.nativeforge.core.build.model.BuildEvent
import io.nativeforge.core.build.model.BuildRequest
import io.nativeforge.core.build.model.BuildResult
import io.nativeforge.core.codeintel.CodeIntelOptions
import io.nativeforge.core.common.Outcome
import io.nativeforge.core.common.process.OutputStream
import io.nativeforge.core.project.FileSystemProjectRepository
import io.nativeforge.core.project.model.Project
import io.nativeforge.core.project.template.ProjectOptions
import io.nativeforge.core.project.template.ProjectTemplate
import io.nativeforge.core.project.template.ProjectTemplates
import io.nativeforge.core.remote.screen.ProgramStream
import io.nativeforge.core.remote.screen.RemoteInput
import io.nativeforge.core.remote.screen.StreamSink
import io.nativeforge.core.remote.screen.VideoFrame
import io.nativeforge.core.toolchain.Abi
import io.nativeforge.core.toolchain.Toolchain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.InputStream
import java.io.OutputStream as JavaOutputStream

/** A backend over real project files; builds write a shell script "program" that prints a line. */
class FakeBackend(root: File) : RemoteBackend {
    private val repository = FileSystemProjectRepository(
        workspaceRoot = File(root, "projects").apply { mkdirs() },
        linkedProjectsFile = File(root, "linked.json"),
        externalBuildRoot = File(root, "external-builds"),
    )
    override val appVersion = "test"
    override val deviceName = "Test device"
    val deviceRuns = mutableListOf<String>()

    override suspend fun listProjects() = repository.listProjects()
    override suspend fun openProject(key: String) = repository.open(key)
    override fun templates(): List<ProjectTemplate> = ProjectTemplates.all
    override suspend fun createProject(name: String, template: ProjectTemplate, options: ProjectOptions) = repository.create(name, template, options)
    override suspend fun removeProject(project: Project): Outcome<Unit> = repository.delete(project)

    override fun build(project: Project): Flow<BuildEvent> = flow {
        emit(BuildEvent.Output(OutputStream.STDOUT, "compiling ${project.name}"))
        val program = File(project.buildDirectory, "debug/${project.name}").apply { parentFile.mkdirs() }
        program.writeText("#!/bin/sh\necho hello from ${project.name}\n")
        program.setExecutable(true)
        emit(BuildEvent.Finished(BuildResult(true, BuildArtifact(program, ArtifactKind.EXECUTABLE, emptyList()), emptyList(), 42)))
    }

    override fun buildRequest(project: Project) = BuildRequest(project, NoToolchain)
    override fun codeIntelOptions() = CodeIntelOptions()
    override fun codeIntelEnabled() = false
    override fun consoleCommand(project: Project, artifact: BuildArtifact) = PtyCommand("/bin/sh", listOf(artifact.file.path), project.root.path, emptyMap())
    override fun runOnDevice(project: Project, artifact: BuildArtifact): String? { deviceRuns += project.name; return null }
    val streams = mutableListOf<FakeStream>()
    /** An H.264 Annex B file the dev server plays as the program's picture (see DevServerTest). */
    var devVideo: File? = null
    override fun streamProgram(project: Project, artifact: BuildArtifact, width: Int, height: Int, sink: StreamSink): ProgramStream =
        devVideo?.let { DevVideoStream(it, sink) } ?: FakeStream(width, height, sink).also { streams += it }
    override fun shellCommand() = PtyCommand("/bin/sh", listOf("-i"), System.getProperty("java.io.tmpdir"), mapOf("PS1" to "$ "))
    override val ptyFactory = PtyFactory { command, _, _ -> ProcessPty(command) }
    override fun theme() = RemoteTheme(
        isDark = true,
        colors = mapOf("bg" to "#282c34", "panel" to "#21252b", "text" to "#d7dae0", "accent" to "#61afef"),
        // The app's real One Dark Pro theme when the repository is around (development server).
        editorThemeJson = File("../../feature/editor/src/main/assets/textmate/themes/one-dark-pro.json").takeIf { it.isFile }?.readText()
            ?: """{"colors":{"editor.background":"#282c34"},"tokenColors":[{"scope":"keyword","settings":{"foreground":"#c678dd"}}]}""",
        editorFontSize = 13f, tabWidth = 4, wordWrap = false, lineNumbers = true, highlightCurrentLine = true, terminalFontSize = 12,
    )
}

/** Plain pipes instead of a terminal: enough for tests and the development server. */
class ProcessPty(command: PtyCommand) : Pty {
    private val process = ProcessBuilder(listOf(command.executable) + command.arguments)
        .directory(File(command.workingDirectory))
        .redirectErrorStream(true)
        .apply { environment().putAll(command.environment) }
        .start()
    override val output: InputStream get() = process.inputStream
    override val input: JavaOutputStream get() = process.outputStream
    override fun resize(rows: Int, columns: Int) {}
    override fun waitFor() = process.waitFor()
    override fun close() { process.destroy() }
}

object NoToolchain : Toolchain {
    override val abi = Abi.ARM64_V8A
    override val apiLevel = 24
    override val cCompiler = File("/nonexistent/clang")
    override val cxxCompiler = File("/nonexistent/clang++")
    override val cmake = File("/nonexistent/cmake")
    override val ninja = File("/nonexistent/ninja")
    override val make = File("/nonexistent/make")
    override val shell = File("/bin/sh")
    override fun environment() = emptyMap<String, String>()
    override fun baseCompileFlags() = emptyList<String>()
    override fun baseLinkFlags() = emptyList<String>()
    override fun runtimeLibraries() = emptyList<File>()
    override fun missingComponents() = emptyList<String>()
}

/** A stream that sends a codec configuration at once and a small key frame whenever one is asked for. */
class FakeStream(width: Int, height: Int, val sink: StreamSink) : ProgramStream {
    override var width = width
        private set
    override var height = height
        private set
    val inputs = java.util.concurrent.CopyOnWriteArrayList<RemoteInput>()
    val resizes = java.util.concurrent.CopyOnWriteArrayList<Pair<Int, Int>>()
    @Volatile var stopped = false
    @Volatile var answerKeyFrames = true
    private var pts = 0L

    init {
        sink.onFrame(VideoFrame(CONFIG, 0, keyFrame = false, config = true))
    }

    override fun input(event: RemoteInput) { inputs += event }
    override fun resize(width: Int, height: Int) { this.width = width; this.height = height; resizes += width to height }
    override fun requestKeyFrame() { if (answerKeyFrames) frame(key = true) }
    fun frame(key: Boolean) = sink.onFrame(VideoFrame(byteArrayOf(0, 0, 0, 1, if (key) 0x65 else 0x41, 1, 2, 3), pts++, key))
    override fun stop() { stopped = true; sink.onEnded("Stopped") }

    companion object {
        val CONFIG = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0xc0.toByte(), 0x1f, 0x11, 0, 0, 0, 1, 0x68, 0xce.toByte(), 0x3c, 0x80.toByte())
    }
}

/** Plays an H.264 file in a loop at 30 frames per second and prints the input it gets. */
class DevVideoStream(file: File, private val sink: StreamSink) : ProgramStream {
    private val data = file.readBytes()
    private val units = io.nativeforge.core.remote.screen.H264.nalUnits(data)
    override val width = 640
    override val height = 360
    @Volatile private var running = true

    init {
        Thread {
            val config = units.filter { (s, _) -> (data[s].toInt() and 0x1f) in 7..8 }
                .fold(ByteArray(0)) { acc, (s, e) -> acc + byteArrayOf(0, 0, 0, 1) + data.copyOfRange(s, e) }
            sink.onFrame(VideoFrame(config, 0, keyFrame = false, config = true))
            var pts = 0L
            while (running) {
                for ((s, e) in units) {
                    if (!running) break
                    val type = data[s].toInt() and 0x1f
                    if (type != 1 && type != 5) continue
                    sink.onFrame(VideoFrame(byteArrayOf(0, 0, 0, 1) + data.copyOfRange(s, e), pts, keyFrame = type == 5))
                    pts += 33_333
                    Thread.sleep(33)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    override fun input(event: RemoteInput) = println("INPUT $event")
    override fun resize(width: Int, height: Int) = println("RESIZE $width x $height") // the video keeps its size
    override fun requestKeyFrame() = Unit // the file has a key frame every second
    override fun stop() { running = false; sink.onEnded("Stopped") }
}
