package io.nativeforge.core.packages.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ArchiveFormat {
    @SerialName("deb") DEB,
    @SerialName("tar.xz") TAR_XZ,
    @SerialName("tar.gz") TAR_GZ,
    @SerialName("zip") ZIP,
}

/** Where a package's files are installed. */
@Serializable
enum class InstallTarget {
    /** Shared Termux-compatible prefix (`<files>/usr`): toolchain and console libraries. */
    @SerialName("prefix") PREFIX,

    /** Isolated directory per package (`<files>/android-libs/<name>`): Android-targeted libraries. */
    @SerialName("isolated") ISOLATED,
}

/** Build metadata for Android-targeted libraries. Paths are relative to the package's install root. */
@Serializable
data class BuildMetadata(
    val includeDirs: List<String> = listOf("include"),
    val libDirs: List<String> = listOf("lib"),
    val linkLibraries: List<String> = emptyList(),
    val compileFlags: List<String> = emptyList(),
    val linkFlags: List<String> = emptyList(),
    /** Shared libraries to preload/bundle, relative to the install root, in load order. */
    val runtimeLibraries: List<String> = emptyList(),
)

/** A package as described by a source index. */
@Serializable
data class PackageInfo(
    val name: String,
    val version: String,
    val description: String = "",
    /** Debian architecture name (`aarch64`, `arm`, ...) or `all`. */
    val architecture: String,
    /** Debian-style dependency clauses, e.g. `libc++`, `zlib (>= 1.3)`, `a | b`. */
    val depends: List<String> = emptyList(),
    /** Virtual package names this package satisfies. */
    val provides: List<String> = emptyList(),
    val downloadUrl: String,
    val sha256: String? = null,
    val sizeBytes: Long? = null,
    val format: ArchiveFormat,
    val target: InstallTarget,
    val build: BuildMetadata = BuildMetadata(),
    /** Id of the [io.nativeforge.core.packages.source.PackageSource] that published it. */
    val sourceId: String = "",
    /** Archive path prefix stripped on extraction (e.g. the Termux prefix inside .deb files). */
    val stripPrefix: String = "",
)

/** A package recorded in the local database after installation. */
@Serializable
data class InstalledPackage(
    val info: PackageInfo,
    /** Absolute install root. */
    val root: String,
    /** Installed files relative to [root], used for clean uninstallation. */
    val files: List<String>,
    val installedAtMillis: Long,
)
