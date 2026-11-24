package io.nativeforge.app.run

import android.content.Context
import android.content.Intent
import io.nativeforge.app.di.AppContainer
import io.nativeforge.core.build.model.BuildArtifact
import io.nativeforge.core.project.model.Project
import io.nativeforge.core.project.model.RuntimeKind
import io.nativeforge.core.project.runtime.LaunchSpec
import io.nativeforge.core.toolchain.ShellSetup
import io.nativeforge.feature.runner.LaunchResult
import io.nativeforge.feature.runner.ProgramLauncher
import io.nativeforge.feature.runner.RunErrors
import io.nativeforge.feature.terminal.ConsoleContract
import io.nativeforge.feature.terminal.ConsoleRequest
import java.io.File

/** Starts built programs in the right host: the terminal for console apps, a runner activity otherwise. */
object ProgramRunner {

    /** @return an error message, or null when the program was started. */
    fun run(context: Context, container: AppContainer, project: Project, builtArtifact: BuildArtifact): String? {
        val artifact = stageIfOutside(context, builtArtifact)
            ?: return "Could not copy ${builtArtifact.file.name} to NativeForge's storage to run it"
        val environment = container.toolchain.environment()
        return when (project.config.runtime) {
            RuntimeKind.CONSOLE -> {
                artifact.file.setExecutable(true, false)
                startConsole(
                    context,
                    ConsoleRequest(
                        title = project.name,
                        workingDirectory = project.root.path,
                        executable = artifact.file.path,
                        arguments = project.config.runArguments,
                        environment = environment,
                        fontSizeSp = container.settings.settings.value.terminalFontSizeSp,
                    ),
                )
                null
            }
            else -> (ProgramLauncher.launch(context, launchSpec(context, container, project, artifact)) as? LaunchResult.Unsupported)?.reason
        }
    }

    /** What a runner host needs to start a graphical program (an already staged [artifact]); clears the last start error. */
    fun launchSpec(context: Context, container: AppContainer, project: Project, artifact: BuildArtifact): LaunchSpec {
        val environment = container.toolchain.environment()
        val errorFile = errorFile(context).apply { delete() }
        return LaunchSpec(
            runtime = project.config.runtime,
            program = artifact.file.path,
            preloadLibraries = artifact.runtimeLibraries.map(File::getPath),
            arguments = project.config.runArguments,
            workingDirectory = project.root.path,
            environment = mapOf(
                "HOME" to environment.getValue("HOME"),
                "TMPDIR" to environment.getValue("TMPDIR"),
                RunErrors.ENV to errorFile.path,
            ),
            // Preview the export's display options when running from the IDE.
            orientation = project.config.export.orientation,
            immersive = project.config.export.immersive,
            keepScreenOn = project.config.export.keepScreenOn,
        )
    }

    /** The reason the last graphical program could not start, if it reported one; clears it. */
    fun consumeStartError(context: Context): String? {
        val file = errorFile(context)
        if (!file.isFile) return null
        return runCatching { file.readText().trim() }.getOrNull().also { file.delete() }?.ifEmpty { null }
    }

    /**
     * Recent log lines of this app, including the program's process (raylib, SDL
     * and SFML log there, and so do native crashes). Android lets an app read its
     * own log without any permission.
     */
    fun readProgramLog(maxLines: Int = 400): List<String> = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "time", "-t", maxLines.toString())
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().readLines().also { process.waitFor() }
    }.getOrElse { listOf("Could not read the log: ${it.message}") }

    private fun errorFile(context: Context) = File(context.filesDir, "run/last-error.txt")

    /**
     * Android only runs programs and loads libraries from the app's own storage: shared
     * storage and SD cards are mounted noexec. A program a Make or Ninja build wrote into a
     * project folder there is copied to `files/run/staged/` first (same file name, so a
     * library's soname still matches). Returns the artifact to run, or null if the copy failed.
     */
    fun stageIfOutside(context: Context, artifact: BuildArtifact): BuildArtifact? {
        val dataDir = context.applicationInfo.dataDir.let { File(it).canonicalPath } + File.separator
        if (artifact.file.canonicalPath.startsWith(dataDir)) return artifact
        return runCatching {
            val staged = File(context.filesDir, "run/staged/${artifact.file.name}")
            staged.parentFile?.mkdirs()
            artifact.file.copyTo(staged, overwrite = true)
            staged.setExecutable(true, false)
            artifact.copy(file = staged)
        }.getOrNull()
    }

    /**
     * Opens an interactive shell with the toolchain on PATH: Termux's bash when installed
     * (with its relocated profile and the `pkg` command), otherwise Android's sh.
     */
    fun openShell(context: Context, container: AppContainer) {
        val setup = ShellSetup(container.layout)
        runCatching { setup.prepare() }
        val shell = setup.shell()
        startConsole(
            context,
            ConsoleRequest(
                title = "Terminal",
                workingDirectory = container.layout.homeDir.path,
                shell = shell.executable,
                arguments = shell.arguments,
                environment = container.toolchain.environment() + shell.environment +
                    (ShellSetup.APK_ENV to context.applicationInfo.sourceDir),
                fontSizeSp = container.settings.settings.value.terminalFontSizeSp,
            ),
        )
    }

    private fun startConsole(context: Context, request: ConsoleRequest) {
        context.startActivity(ConsoleContract.intent(context, request).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
