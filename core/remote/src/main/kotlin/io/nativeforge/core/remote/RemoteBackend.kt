package io.nativeforge.core.remote

import io.nativeforge.core.build.model.BuildArtifact
import io.nativeforge.core.build.model.BuildEvent
import io.nativeforge.core.build.model.BuildRequest
import io.nativeforge.core.codeintel.CodeIntelOptions
import io.nativeforge.core.common.Outcome
import io.nativeforge.core.project.model.Project
import io.nativeforge.core.project.template.ProjectOptions
import io.nativeforge.core.project.template.ProjectTemplate
import kotlinx.coroutines.flow.Flow
import java.io.InputStream
import java.io.OutputStream

/**
 * What the remote server needs from the app. The app implements it with its own services
 * (project repository, package manager, build service, settings), so a browser does exactly
 * what the app's screens do, on the same files and toolchain.
 */
interface RemoteBackend {
    /** App version, shown in the browser. */
    val appVersion: String

    /** Device model, shown in the browser's title. */
    val deviceName: String

    suspend fun listProjects(): List<Project>

    suspend fun openProject(key: String): Outcome<Project>

    fun templates(): List<ProjectTemplate>

    suspend fun createProject(name: String, template: ProjectTemplate, options: ProjectOptions): Outcome<Project>

    /** Deletes a workspace project, or forgets an external one (its folder stays). */
    suspend fun removeProject(project: Project): Outcome<Unit>

    /** Installs the project's missing packages, then builds; output arrives as build events. */
    fun build(project: Project): Flow<BuildEvent>

    /** The request clangd's compilation database is written from. */
    fun buildRequest(project: Project): BuildRequest

    fun codeIntelOptions(): CodeIntelOptions

    /** False when the user turned code intelligence off in the app. */
    fun codeIntelEnabled(): Boolean

    /** Command that runs a console program in a terminal. */
    fun consoleCommand(project: Project, artifact: BuildArtifact): PtyCommand

    /**
     * Starts a graphical program on the phone's screen.
     * @return null when started, otherwise why it could not start.
     */
    fun runOnDevice(project: Project, artifact: BuildArtifact): String?

    /** The interactive shell of the app's terminal. */
    fun shellCommand(): PtyCommand

    /** Starts processes attached to a pseudo-terminal. */
    val ptyFactory: PtyFactory

    /** The app's current look: palette, editor theme and editor preferences. */
    fun theme(): RemoteTheme
}

/** Colors and editor preferences the web UI applies so it looks like the app. */
data class RemoteTheme(
    val isDark: Boolean,
    /** CSS custom properties without the leading dashes, e.g. "bg" to "#282c34". */
    val colors: Map<String, String>,
    /** The editor theme's TextMate JSON (VS Code theme format), or null. */
    val editorThemeJson: String?,
    val editorFontSize: Float,
    val tabWidth: Int,
    val wordWrap: Boolean,
    val lineNumbers: Boolean,
    val highlightCurrentLine: Boolean,
    val terminalFontSize: Int,
)

/** A process to start in a pseudo-terminal. */
data class PtyCommand(
    val executable: String,
    val arguments: List<String>,
    val workingDirectory: String,
    val environment: Map<String, String>,
)

fun interface PtyFactory {
    fun start(command: PtyCommand, rows: Int, columns: Int): Pty
}

/** A running process on a pseudo-terminal: [output] is what it prints, [input] its keyboard. */
interface Pty {
    val output: InputStream
    val input: OutputStream
    fun resize(rows: Int, columns: Int)

    /** Blocks until the process exits; returns its exit status. */
    fun waitFor(): Int

    /** Hangs up the terminal (SIGHUP to the process group) and releases it. */
    fun close()
}
