package io.nativeforge.app.remote

import android.content.Context
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.nativeforge.app.BuildConfig
import io.nativeforge.app.di.AppContainer
import io.nativeforge.app.run.ProgramRunner
import io.nativeforge.core.build.model.BuildArtifact
import io.nativeforge.core.build.model.BuildEvent
import io.nativeforge.core.build.model.BuildRequest
import io.nativeforge.core.build.model.BuildResult
import io.nativeforge.core.codeintel.CodeIntelOptions
import io.nativeforge.core.common.Outcome
import io.nativeforge.core.common.process.OutputStream
import io.nativeforge.core.packages.model.InstallEvent
import io.nativeforge.core.project.model.Project
import io.nativeforge.core.project.template.ProjectOptions
import io.nativeforge.core.project.template.ProjectTemplate
import io.nativeforge.core.project.template.ProjectTemplates
import io.nativeforge.core.remote.PtyCommand
import io.nativeforge.core.remote.PtyFactory
import io.nativeforge.core.remote.RemoteBackend
import io.nativeforge.core.remote.RemoteTheme
import io.nativeforge.core.remote.screen.ProgramStream
import io.nativeforge.core.remote.screen.StreamSink
import io.nativeforge.core.toolchain.ShellSetup
import io.nativeforge.feature.runner.LaunchResult
import io.nativeforge.feature.runner.ProgramLauncher
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** The remote server's view of the app: the same repository, packages, builds and settings as the screens use. */
class AndroidRemoteBackend(private val context: Context, private val container: AppContainer) : RemoteBackend {
    override val appVersion: String = BuildConfig.VERSION_NAME
    override val deviceName: String =
        if (Build.MODEL.startsWith(Build.MANUFACTURER, ignoreCase = true)) Build.MODEL
        else "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"

    override suspend fun listProjects(): List<Project> = container.projectRepository.listProjects()

    override suspend fun openProject(key: String): Outcome<Project> = container.projectRepository.open(key)

    override fun templates(): List<ProjectTemplate> = ProjectTemplates.all

    override suspend fun createProject(name: String, template: ProjectTemplate, options: ProjectOptions): Outcome<Project> =
        container.projectRepository.create(name, template, options)

    override suspend fun removeProject(project: Project): Outcome<Unit> =
        if (project.isExternal) container.projectRepository.forget(project) else container.projectRepository.delete(project)

    /** Same steps as the workspace's Build button: update bundled libraries, install missing packages, build. */
    override fun build(project: Project): Flow<BuildEvent> = flow {
        container.updateBundledPackages().takeIf { it.isNotEmpty() }?.let {
            emit(BuildEvent.Output(OutputStream.STDOUT, "Updated libraries: ${it.joinToString()}"))
        }
        val missing = project.config.packages.filterNot(container.packageManager::isInstalled)
        if (missing.isNotEmpty()) {
            emit(BuildEvent.Output(OutputStream.STDOUT, "Installing packages: ${missing.joinToString()}"))
            var failed = false
            container.packageManager.install(missing).collect { event ->
                when (event) {
                    is InstallEvent.Downloading -> if (event.bytesRead == 0L) emit(BuildEvent.Output(OutputStream.STDOUT, "  downloading ${event.pkg.name} ${event.pkg.version}"))
                    is InstallEvent.Failed -> { emit(BuildEvent.Output(OutputStream.STDERR, event.error.message)); failed = true }
                    else -> Unit
                }
            }
            if (failed) {
                emit(BuildEvent.Finished(BuildResult(false, null, emptyList(), 0)))
                return@flow
            }
        }
        emitAll(container.buildService.build(buildRequest(project)))
    }

    override fun buildRequest(project: Project) = BuildRequest(
        project = project,
        toolchain = container.toolchain,
        dependencies = container.packageManager.nativeDependencies(project.config.packages),
    )

    override fun codeIntelOptions() = CodeIntelOptions(
        clangTidy = container.settings.settings.value.clangTidy,
        threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4),
    )

    override fun codeIntelEnabled() = container.settings.settings.value.codeIntelligence

    override fun consoleCommand(project: Project, artifact: BuildArtifact): PtyCommand {
        val staged = ProgramRunner.stageIfOutside(context, artifact) ?: artifact
        staged.file.setExecutable(true, false)
        return PtyCommand(
            executable = staged.file.path,
            arguments = project.config.runArguments,
            workingDirectory = project.root.path,
            environment = container.toolchain.environment() + ("TERM" to "xterm-256color"),
        )
    }

    override fun runOnDevice(project: Project, artifact: BuildArtifact): String? =
        ProgramRunner.run(context, container, project, artifact)

    override fun streamProgram(project: Project, artifact: BuildArtifact, width: Int, height: Int, sink: StreamSink): ProgramStream {
        val staged = ProgramRunner.stageIfOutside(context, artifact)
            ?: throw IllegalStateException("Could not copy ${artifact.file.name} to NativeForge's storage to run it")
        val spec = ProgramRunner.launchSpec(context, container, project, staged)
        val blackScreen = container.remoteAccess.settings.value.blackScreen
        val stream = AndroidProgramStream.open(context, container.logger, width, height, sink, blackScreen) { ProgramRunner.consumeStartError(context) }
        val result = try {
            ProgramLauncher.launch(context, spec, stream.run)
        } catch (e: Exception) {
            stream.stop()
            throw IllegalStateException("Android did not start the program: ${e.message}", e)
        }
        if (result is LaunchResult.Unsupported) {
            stream.stop()
            throw IllegalStateException(result.reason)
        }
        return stream
    }

    override fun shellCommand(): PtyCommand {
        val setup = ShellSetup(container.layout)
        runCatching { setup.prepare() }
        val shell = setup.shell()
        return PtyCommand(
            executable = shell.executable,
            arguments = shell.arguments,
            workingDirectory = container.layout.homeDir.path,
            environment = container.toolchain.environment() + shell.environment +
                (ShellSetup.APK_ENV to context.applicationInfo.sourceDir) + ("TERM" to "xterm-256color"),
        )
    }

    override val ptyFactory = PtyFactory { command, rows, columns -> AndroidPty.start(command, rows, columns) }

    private val editorThemes = ConcurrentHashMap<String, String>()

    override fun theme(): RemoteTheme {
        val settings = container.settings.settings.value
        val p = settings.appTheme.palette
        val editorTheme = settings.editor.theme
        val json = editorThemes.getOrPut(editorTheme.id) {
            runCatching { context.assets.open(editorTheme.assetPath).bufferedReader().use { it.readText() } }.getOrDefault("")
        }.ifEmpty { null }
        return RemoteTheme(
            isDark = p.isDark,
            colors = mapOf(
                "bg" to p.background, "panel" to p.panel, "raised" to p.raised, "overlay" to p.overlay, "border" to p.border,
                "text" to p.text, "muted" to p.textMuted, "accent" to p.accent, "on-accent" to p.onAccent,
                "accent-container" to p.accentContainer, "secondary" to p.secondary, "tertiary" to p.tertiary,
                "error" to p.error, "warning" to p.warning, "success" to p.success,
            ).mapValues { (_, color) -> hex(color) },
            editorThemeJson = json,
            editorFontSize = settings.editor.fontSizeSp,
            tabWidth = settings.editor.tabWidth,
            wordWrap = settings.editor.wordWrap,
            lineNumbers = settings.editor.lineNumbers,
            highlightCurrentLine = settings.editor.highlightCurrentLine,
            terminalFontSize = settings.terminalFontSizeSp,
            formatOnSave = settings.formatOnSave,
        )
    }

    private fun hex(color: Color) = "#%06X".format(color.toArgb() and 0xFFFFFF)
}
