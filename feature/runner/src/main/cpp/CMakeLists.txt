cmake_minimum_required(VERSION 3.22.1)
project(nativeforge_shim C)

# Tiny trampoline loaded by NativeActivity. It dlopen()s the user's program
# (whose path is given at run time) and forwards ANativeActivity_onCreate.
add_library(nativeforge_shim SHARED nativeforge_shim.c)
target_link_libraries(nativeforge_shim android log dl)
target_link_options(nativeforge_shim PRIVATE "-Wl,-z,max-page-size=16384")
