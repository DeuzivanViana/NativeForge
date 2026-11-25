cmake_minimum_required(VERSION 3.22.1)
project(nativeforge_shim C)

# Tiny trampoline loaded by NativeActivity. It dlopen()s the user's program
# (whose path is given at run time) and forwards ANativeActivity_onCreate.
# plt_hook.c lets it see the window size a streamed raylib program asks for.
add_library(nativeforge_shim SHARED nativeforge_shim.c plt_hook.c)
target_link_libraries(nativeforge_shim PRIVATE android log dl)
target_link_options(nativeforge_shim PRIVATE "-Wl,-z,max-page-size=16384")

# Records native crashes of the IDE process (signal, backtrace) for its crash reports;
# loaded by io.nativeforge.feature.runner.crash.NativeCrashHandler.
add_library(nativeforge_crash SHARED nf_crash_handler.c)
target_link_libraries(nativeforge_crash PRIVATE dl)
target_link_options(nativeforge_crash PRIVATE "-Wl,-z,max-page-size=16384")
