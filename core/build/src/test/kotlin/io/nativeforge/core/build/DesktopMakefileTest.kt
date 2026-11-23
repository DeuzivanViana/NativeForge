package io.nativeforge.core.build

import io.nativeforge.core.build.model.ArtifactKind
import io.nativeforge.core.build.model.BuildRequest
import io.nativeforge.core.build.system.MakeBuildSystem
import io.nativeforge.core.project.model.BuildSystemKind
import io.nativeforge.core.project.model.Project
import io.nativeforge.core.project.model.ProjectConfig
import io.nativeforge.core.project.model.RuntimeKind
import io.nativeforge.core.toolchain.Abi
import io.nativeforge.core.toolchain.NativeDependency
import io.nativeforge.core.toolchain.Toolchain
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Runs a desktop-style Makefile (hard-coded `g++`, own CFLAGS/LDFLAGS, `-lGL -lX11`,
 * output named `app.elf`) through the generated wrappers with the NDK's clang
 * and host make. Needs NF_NDK; skipped otherwise.
 */
class DesktopMakefileTest {
    private val ndk = System.getenv("NF_NDK")?.let(::File)?.takeIf { it.isDirectory }

    @Test
    fun `desktop makefile builds an Android shared library`() {
        assumeTrue(ndk != null && File("/usr/bin/make").canExecute())
        val root = Files.createTempDirectory("nf-desktop-make").toFile()
        val project = File(root, "game")
        File(project, "src/game").mkdirs()
        File(project, "src/main.cpp").writeText("#include \"game/game.h\"\nextern \"C\" void ANativeActivity_onCreate(void*, void*, unsigned long) { run(); }\n")
        File(project, "src/game/game.h").writeText("#pragma once\nvoid run();\n")
        File(project, "src/game/game.cpp").writeText("#include \"game.h\"\n#include <android/log.h>\nvoid run() { __android_log_print(ANDROID_LOG_INFO, \"t\", \"x\"); }\n")
        File(project, "Makefile").writeText(
            "SRC_DIR = src\nTARGET = app.elf\nCOMPILER = g++\n" +
                "SOURCE_FILES := \$(wildcard \$(SRC_DIR)/*.cpp) \$(wildcard \$(SRC_DIR)/*/*.cpp)\n" +
                "OBJECT_FILES := \$(patsubst %.cpp,%.o,\$(SOURCE_FILES))\n" +
                "CFLAGS = -pedantic -Iinclude\nLDFLAGS = -O3 -DNDEBUG -s -lGL -lX11 -lpthread\n" +
                "%.o: %.cpp\n\t@\$(COMPILER) -c -o \$@ \$< \$(CFLAGS)\n" +
                "\$(TARGET): \$(OBJECT_FILES)\n\t@\$(COMPILER) -o \$@ \$^ \$(LDFLAGS)\n",
        )
        val request = BuildRequest(
            Project(project, ProjectConfig(name = "game", buildSystem = BuildSystemKind.MAKE, runtime = RuntimeKind.NATIVE_ACTIVITY)),
            HostNdkToolchain(ndk!!),
            dependencies = listOf(NativeDependency("log", linkLibraries = listOf("log", "android"))),
        )
        val step = MakeBuildSystem().plan(request).steps.single().process
        // Host test: run the wrapper scripts with the host shell instead of /system/bin/sh.
        File(project, "build/.nativeforge/bin").listFiles()!!.forEach { it.writeText(it.readText().replaceFirst("#!/system/bin/sh", "#!/bin/sh")) }
        val command = listOf("/usr/bin/make") + step.command.drop(1).filterNot { it.startsWith("SHELL=") }
        val process = ProcessBuilder(command).directory(project).redirectErrorStream(true)
            .apply { environment().putAll(step.environment) }.start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(120, TimeUnit.SECONDS)
        assertEquals(0, process.exitValue(), output)
        val program = File(project, "app.elf")
        assertEquals(ArtifactKind.SHARED_LIBRARY, ElfInfo.kindOf(program), output)
        assertEquals(program, ArtifactLocator.newest(project, ArtifactKind.SHARED_LIBRARY, 0))
        assertTrue(ElfInfo.kindOf(File(project, "src/main.o")) == null)
    }

    private class HostNdkToolchain(ndk: File) : Toolchain {
        private val bin = File(ndk, "toolchains/llvm/prebuilt/linux-x86_64/bin")
        private val sysroot = File(ndk, "toolchains/llvm/prebuilt/linux-x86_64/sysroot")
        override val abi = Abi.ARM64_V8A
        override val apiLevel = 24
        override val cCompiler = File(bin, "clang")
        override val cxxCompiler = File(bin, "clang++")
        override val cmake = File(bin, "cmake")
        override val ninja = File(bin, "ninja")
        override val make = File("/usr/bin/make")
        override val shell = File("/bin/sh")
        override fun environment() = mapOf("PATH" to "/usr/bin:/bin")
        override fun baseCompileFlags() = listOf("--target=aarch64-linux-android24", "--sysroot=${sysroot.path}")
        override fun baseLinkFlags() = baseCompileFlags()
        override fun runtimeLibraries() = emptyList<File>()
        override fun missingComponents() = emptyList<String>()
    }
}
