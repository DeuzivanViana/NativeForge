# Native code calls into these classes by name through JNI: SDL's C code calls static
# methods of SDLActivity, SDLAudioManager, SDLControllerManager, HIDDeviceManager... and
# user programs may look up the host activity's methods. Keep them whole.
-keep class org.libsdl.** { *; }
-keep class io.nativeforge.feature.runner.** { *; }
