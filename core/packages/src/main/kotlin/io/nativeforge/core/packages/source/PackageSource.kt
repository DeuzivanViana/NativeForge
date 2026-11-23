package io.nativeforge.core.packages.source

import io.nativeforge.core.packages.model.PackageInfo
import io.nativeforge.core.toolchain.Abi

/**
 * A repository of installable packages. New repositories (a mirror, a local
 * folder, a different distribution) are added by implementing this interface
 * and registering the instance in the package manager.
 */
interface PackageSource {
    val id: String
    val displayName: String

    suspend fun fetchIndex(abi: Abi): List<PackageInfo>
}
