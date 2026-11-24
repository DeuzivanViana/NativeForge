# The runtime template ships inside every exported app, so R8 strips the libraries it
# carries (Kotlin, kotlinx, AndroidX) down to what the hosts use. Keep rules for JNI
# entry points live in feature/runner and feature/terminal (consumer-rules.pro).

# Readable stack traces in users' apps; renaming would save little here.
-dontobfuscate

-keep class io.nativeforge.runtime.template.** { *; }
