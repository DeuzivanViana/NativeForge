# SDL2 Java glue (relocated)

These files are SDL 2.32.10's `android-project/app/src/main/java/org/libsdl/app/`
(zlib license, see SDL's LICENSE.txt) with the package renamed to `org.libsdl.sdl2`,
so they can live next to SDL3's glue, which keeps `org.libsdl.app`.

`packages/build-android-libs.sh` builds libSDL2.so with the same rename in its JNI
names (`SDL_JAVA_PREFIX` and the `register_methods` class names). Keep
`SDL2_VERSION` there and these files on the same release: SDL checks the Java and
native versions at start-up.
