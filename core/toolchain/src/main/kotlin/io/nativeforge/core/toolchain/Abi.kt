package io.nativeforge.core.toolchain

/** Android ABIs supported by the toolchain, with their clang target triples. */
enum class Abi(
    val androidName: String,
    val targetTriple: String,
    /** Architecture name used by Debian/Termux package indexes. */
    val debianArch: String,
    /** `e_machine` of ELF files built for this ABI. */
    val elfMachine: Int,
) {
    ARM64_V8A("arm64-v8a", "aarch64-linux-android", "aarch64", 183),
    ARMEABI_V7A("armeabi-v7a", "armv7a-linux-androideabi", "arm", 40),
    X86_64("x86_64", "x86_64-linux-android", "x86_64", 62),
    X86("x86", "i686-linux-android", "i686", 3);

    companion object {
        fun fromAndroidName(name: String): Abi? = entries.firstOrNull { it.androidName == name }

        /** Picks the preferred ABI from `Build.SUPPORTED_ABIS` (most preferred first). */
        fun preferred(supportedAbis: List<String>): Abi =
            supportedAbis.firstNotNullOfOrNull(::fromAndroidName)
                ?: error("None of the device ABIs $supportedAbis is supported")
    }
}
