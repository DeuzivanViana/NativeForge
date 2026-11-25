package io.nativeforge.core.packages.model

import io.nativeforge.core.common.NfError

/** Progress reported while installing packages. */
sealed interface InstallEvent {
    /** Before the plan is known: waiting for another install, or reading package lists. */
    data class Preparing(val message: String) : InstallEvent
    data class Resolved(val packages: List<PackageInfo>) : InstallEvent
    data class Downloading(val pkg: PackageInfo, val bytesRead: Long, val totalBytes: Long?) : InstallEvent
    data class Extracting(val pkg: PackageInfo) : InstallEvent
    data class Installed(val pkg: PackageInfo) : InstallEvent
    data class Failed(val error: NfError) : InstallEvent
    data object Completed : InstallEvent
}
