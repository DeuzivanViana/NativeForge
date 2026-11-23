package io.nativeforge.core.build

import io.nativeforge.core.toolchain.Abi
import io.nativeforge.core.toolchain.Toolchain
import java.io.File

class FakeToolchain(private val root: File) : Toolchain {
    override val abi = Abi.ARM64_V8A
    override val apiLevel = 24
    override val cCompiler = File(root, "bin/clang")
    override val cxxCompiler = File(root, "bin/clang++")
    override val cmake = File(root, "bin/cmake")
    override val ninja = File(root, "bin/ninja")
    override val make = File(root, "bin/make")
    override val shell = File("/system/bin/sh")
    override val parallelJobs = 4
    override fun environment() = mapOf("PATH" to "${root.path}/bin")
    override fun baseCompileFlags() = listOf("--target=aarch64-linux-android24")
    override fun baseLinkFlags() = listOf("--target=aarch64-linux-android24")
    override fun runtimeLibraries() = emptyList<File>()
    override fun missingComponents() = emptyList<String>()
}
