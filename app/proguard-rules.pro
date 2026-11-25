# R8 for the IDE's release build: it removes the unused parts of Kotlin, kotlinx, AndroidX,
# Compose and the Material icons, which are most of the dex. Everything that may be reached
# by reflection, JNI or by name from outside is kept whole, so behaviour does not change:
# only code that nothing references is dropped.

# Readable stack traces in bug reports; renaming would save little here.
-dontobfuscate

# The IDE itself: view models created by factories, @Serializable models, the `pkg` command's
# entry point (io.nativeforge.app.cli.PkgMain, started by name with app_process) and the
# feature modules' activities and JNI glue.
-keep class io.nativeforge.** { *; }

# JNI and by-name lookups from native code (also in the feature modules' consumer rules).
-keep class org.libsdl.** { *; }
-keep class com.termux.** { *; }

# Libraries that load their own classes by name: JGit (NLS bundles, transports, ServiceLoader),
# BouncyCastle (JCA providers), apksig, Commons Compress, XZ, Sora editor and TextMate
# (grammars and themes read with Gson into tm4e's model classes), ZXing.
-keep class org.eclipse.jgit.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class com.android.apksig.** { *; }
-keep class org.apache.commons.compress.** { *; }
-keep class org.tukaani.xz.** { *; }
-keep class io.github.rosemoe.** { *; }
-keep class org.eclipse.tm4e.** { *; }
-keep class org.joni.** { *; }
-keep class org.jcodings.** { *; }
-keep class com.google.gson.** { *; }
-keep class com.google.zxing.** { *; }
-keep class org.slf4j.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*

# Optional dependencies these libraries reference but only use when present, and JDK APIs
# Android does not have; the code paths that need them are never taken on Android.
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn org.osgi.**
-dontwarn javax.naming.**
-dontwarn javax.management.**
-dontwarn java.lang.management.**
-dontwarn com.jcraft.jsch.**
-dontwarn org.ietf.jgss.**
-dontwarn javax.security.auth.kerberos.**
-dontwarn org.yaml.snakeyaml.**
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn com.google.errorprone.annotations.**
# Sora's ShareableData was compiled against an older Kotlin; the reference is never resolved on Android either.
-dontwarn kotlin.Cloneable$DefaultImpls
