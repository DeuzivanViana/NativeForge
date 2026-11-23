package io.nativeforge.core.packages

import io.nativeforge.core.packages.model.ArchiveFormat
import io.nativeforge.core.packages.model.InstallTarget
import io.nativeforge.core.packages.model.PackageInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DependencyResolverTest {

    private fun pkg(name: String, vararg depends: String, provides: List<String> = emptyList()) = PackageInfo(
        name = name, version = "1", architecture = "aarch64", depends = depends.toList(), provides = provides,
        downloadUrl = "https://example.invalid/$name.deb", format = ArchiveFormat.DEB, target = InstallTarget.PREFIX,
    )

    private val catalog = listOf(
        pkg("clang", "libc++", "libllvm (= 19.1.7)", "ndk-sysroot", "binutils-is-llvm | binutils"),
        pkg("libllvm", "libc++", "zlib"),
        pkg("libc++"),
        pkg("zlib"),
        pkg("ndk-sysroot"),
        pkg("binutils-is-llvm", "llvm"),
        pkg("llvm", "libllvm"),
        pkg("pkgconf", provides = listOf("pkg-config")),
        pkg("cycle-a", "cycle-b"),
        pkg("cycle-b", "cycle-a"),
    )

    @Test
    fun `dependencies come before dependents`() {
        val order = DependencyResolver(catalog).resolve(listOf("clang")).map { it.name }
        assertEquals(order.toSet(), setOf("libc++", "libllvm", "zlib", "ndk-sysroot", "binutils-is-llvm", "llvm", "clang"))
        assertEquals("clang", order.last())
        assert(order.indexOf("zlib") < order.indexOf("libllvm"))
        assert(order.indexOf("libllvm") < order.indexOf("llvm"))
    }

    @Test
    fun `installed packages are skipped`() {
        val order = DependencyResolver(catalog).resolve(listOf("libllvm"), installed = setOf("libc++", "zlib"))
        assertEquals(listOf("libllvm"), order.map { it.name })
    }

    @Test
    fun `virtual packages and cycles resolve`() {
        assertEquals(listOf("pkgconf"), DependencyResolver(catalog).resolve(listOf("pkg-config")).map { it.name })
        assertEquals(setOf("cycle-a", "cycle-b"), DependencyResolver(catalog).resolve(listOf("cycle-a")).map { it.name }.toSet())
    }

    @Test
    fun `missing dependency fails with a helpful message`() {
        val error = assertFailsWith<DependencyResolutionException> {
            DependencyResolver(listOf(pkg("sdl2", "libGLESv9"))).resolve(listOf("sdl2"))
        }
        assert("libGLESv9" in error.message.orEmpty())
    }

    @Test
    fun `every missing requested package is reported at once`() {
        val error = assertFailsWith<DependencyResolutionException> {
            DependencyResolver(catalog).resolve(listOf("pkg-config", "nope", "pkgconf2"))
        }
        assertEquals("Packages 'nope', 'pkgconf2' were not found", error.message)
    }

    @Test
    fun `package name strips version and arch qualifiers`() {
        assertEquals("libfoo", DependencyResolver.packageName(" libfoo:any (>= 1.2) "))
    }
}
