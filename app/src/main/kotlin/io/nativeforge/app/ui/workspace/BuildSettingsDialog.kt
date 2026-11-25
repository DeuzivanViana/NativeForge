package io.nativeforge.app.ui.workspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.nativeforge.core.project.model.BuildSystemKind
import io.nativeforge.core.project.model.CMakeGenerator
import io.nativeforge.core.project.model.Project
import io.nativeforge.core.project.model.ProjectConfig
import io.nativeforge.core.project.model.RuntimeKind
import io.nativeforge.core.ui.component.DialogPrimaryButton
import io.nativeforge.core.ui.component.DialogSecondaryButton
import io.nativeforge.core.ui.component.FieldLabel
import io.nativeforge.core.ui.component.FormSection
import io.nativeforge.core.ui.component.NfDialog
import io.nativeforge.core.ui.component.NfTextField
import io.nativeforge.core.ui.component.SegmentedChoice
import io.nativeforge.core.ui.component.SwitchRow
import java.io.File

/**
 * How the project is built and run. Each build system is independent: CMake (with Ninja
 * or Makefiles), a hand-written Makefile, a build.ninja, or NativeForge's built-in build.
 */
@Composable
fun BuildSettingsDialog(project: Project, onSave: ((ProjectConfig) -> ProjectConfig) -> Unit, onDismiss: () -> Unit) {
    val config = project.config
    var buildSystem by remember { mutableStateOf(config.buildSystem) }
    var generator by remember { mutableStateOf(config.cmakeGenerator) }
    var runtime by remember { mutableStateOf(config.runtime) }
    var cmakeTarget by remember { mutableStateOf(config.cmakeTarget) }
    var buildTarget by remember { mutableStateOf(config.buildTarget) }
    var arguments by remember { mutableStateOf(config.buildArguments.joinToString(" ")) }
    var artifact by remember { mutableStateOf(config.artifactPath.orEmpty()) }
    var sourceDir by remember { mutableStateOf(config.sourceDir) }
    var precompiledHeader by remember { mutableStateOf(config.precompiledHeader.orEmpty()) }
    var compilerCache by remember { mutableStateOf(config.compilerCache) }
    val scripts = remember(project.root) {
        BuildSystemKind.entries.associateWith { kind ->
            when (kind) {
                BuildSystemKind.SINGLE -> true
                BuildSystemKind.MAKE -> listOf("GNUmakefile", "makefile", "Makefile").any { File(project.root, it).isFile }
                else -> File(project.root, kind.scriptFile!!).isFile
            }
        }
    }

    NfDialog(
        title = "Build settings",
        subtitle = project.name,
        onDismiss = onDismiss,
        actions = {
            DialogSecondaryButton("Cancel", onDismiss)
            DialogPrimaryButton("Save", {
                onSave {
                    it.copy(
                        buildSystem = buildSystem,
                        cmakeGenerator = generator,
                        runtime = runtime,
                        cmakeTarget = cmakeTarget.trim(),
                        buildTarget = buildTarget.trim(),
                        buildArguments = arguments.split(' ').map(String::trim).filter(String::isNotEmpty),
                        artifactPath = artifact.trim().trimStart('/').ifEmpty { null },
                        sourceDir = sourceDir.trim().trimStart('/').ifEmpty { "." },
                        precompiledHeader = precompiledHeader.trim().trimStart('/').ifEmpty { null },
                        compilerCache = compilerCache,
                    )
                }
                onDismiss()
            })
        },
    ) {
        Column(
            Modifier.verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FormSection("Build with") {
                SegmentedChoice(
                    options = BuildSystemKind.entries,
                    selected = buildSystem,
                    onSelect = { buildSystem = it },
                    label = { it.displayName },
                    enabled = { scripts.getValue(it) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint(
                    when (buildSystem) {
                        BuildSystemKind.SINGLE -> "NativeForge compiles every C/C++ file in the source folder. No build script needed."
                        BuildSystemKind.CMAKE -> "Runs CMakeLists.txt. Libraries from More › Libraries are linked into every target."
                        BuildSystemKind.MAKE -> "Runs the Makefile with GNU Make. CC, CXX, CFLAGS, CXXFLAGS, LDFLAGS and LDLIBS are set for Android; desktop Makefiles calling gcc or g++ get the project's libraries and the right program type automatically."
                        BuildSystemKind.NINJA -> "Runs build.ninja directly. clang finds the Android sysroot on its own."
                    },
                )
                if (scripts.values.count { it } < BuildSystemKind.entries.size) {
                    Hint("Greyed out options need their script (CMakeLists.txt, Makefile or build.ninja) in the project root.")
                }
                when (buildSystem) {
                    BuildSystemKind.CMAKE -> {
                        FieldLabel("CMake generator")
                        SegmentedChoice(
                            options = CMakeGenerator.entries,
                            selected = generator,
                            onSelect = { generator = it },
                            label = { it.cmakeName },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        NfTextField(cmakeTarget, { cmakeTarget = it }, label = "Target", placeholder = "all targets", monospace = true)
                    }
                    BuildSystemKind.MAKE, BuildSystemKind.NINJA ->
                        NfTextField(buildTarget, { buildTarget = it }, label = "Target", placeholder = "default target", monospace = true)
                    BuildSystemKind.SINGLE -> {
                        NfTextField(sourceDir, { sourceDir = it }, label = "Source folder", placeholder = ". (project root)", monospace = true)
                        NfTextField(
                            precompiledHeader,
                            { precompiledHeader = it },
                            label = "Precompiled header",
                            placeholder = "none (for example src/pch.h)",
                            supportingText = "Compiled once and loaded by every source: put the big library headers (raylib.h, <vector>, imgui.h) in it.",
                            monospace = true,
                        )
                    }
                }
                SwitchRow(
                    title = "Compiler cache (ccache)",
                    checked = compilerCache,
                    onCheckedChange = { compilerCache = it },
                    subtitle = "Reuses earlier compiles after a clean or a debug/release switch. Needs the ccache package (pkg install ccache).",
                )
                if (buildSystem != BuildSystemKind.SINGLE) {
                    NfTextField(
                        arguments,
                        { arguments = it },
                        label = "Extra arguments",
                        placeholder = if (buildSystem == BuildSystemKind.CMAKE) "passed to the build tool" else "VERBOSE=1",
                        monospace = true,
                    )
                }
            }
            FormSection("Run as") {
                SegmentedChoice(
                    options = listOf(RuntimeKind.CONSOLE, RuntimeKind.NATIVE_ACTIVITY, RuntimeKind.SDL3, RuntimeKind.SDL2),
                    selected = runtime,
                    onSelect = { runtime = it },
                    label = {
                        when (it) {
                            RuntimeKind.CONSOLE -> "Terminal"
                            RuntimeKind.NATIVE_ACTIVITY -> "NativeActivity"
                            RuntimeKind.SDL2 -> "SDL2"
                            else -> "SDL3"
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint(
                    if (runtime == RuntimeKind.CONSOLE) {
                        "The program is an executable; it runs in the terminal."
                    } else {
                        "The program is a shared library (.so) shown full screen: raylib, SFML, OpenGL ES, Vulkan (NativeActivity), SDL3 or SDL2."
                    },
                )
                NfTextField(
                    artifact,
                    { artifact = it },
                    label = "Program file",
                    placeholder = if (buildSystem == BuildSystemKind.SINGLE) "build/debug/…" else "found automatically",
                    supportingText = "Relative to the project. Empty: the build's default, or the newest program it wrote.",
                    monospace = true,
                )
            }
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
