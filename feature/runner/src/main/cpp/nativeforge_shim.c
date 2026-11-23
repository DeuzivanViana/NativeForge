// NativeActivity trampoline.
//
// Android's NativeActivity can only load libraries packaged inside the APK.
// The IDE's programs live in the app's data directory, so NativeActivity
// loads this shim instead; the shim opens the real program named by the
// NATIVEFORGE_PROGRAM environment variable (set from Java right before
// NativeActivity.onCreate) and forwards the entry point to it.

#include <android/log.h>
#include <jni.h>
#include <unistd.h>
#include <android/native_activity.h>
#include <dlfcn.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>

#define LOG_TAG "NativeForgeShim"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Logs an error and, when NATIVEFORGE_ERROR_FILE is set, also writes it there so
// the IDE can show why the program window closed.
static void report(const char* format, ...) {
    char message[2048];
    va_list args;
    va_start(args, format);
    vsnprintf(message, sizeof(message), format, args);
    va_end(args);
    LOGE("%s", message);
    const char* path = getenv("NATIVEFORGE_ERROR_FILE");
    if (path == NULL) return;
    FILE* file = fopen(path, "w");
    if (file == NULL) return;
    fputs(message, file);
    fputc('\n', file);
    fclose(file);
}

typedef void (*OnCreateFunction)(ANativeActivity*, void*, size_t);

__attribute__((visibility("default")))
void ANativeActivity_onCreate(ANativeActivity* activity, void* saved_state, size_t saved_state_size) {
    const char* program = getenv("NATIVEFORGE_PROGRAM");
    if (program == NULL) {
        report("NATIVEFORGE_PROGRAM is not set");
        ANativeActivity_finish(activity);
        return;
    }

    // Relative paths in the program resolve against its project (or app) directory.
    const char* pwd = getenv("PWD");
    if (pwd != NULL) chdir(pwd);

    void* handle = dlopen(program, RTLD_NOW | RTLD_GLOBAL);
    if (handle == NULL) {
        report("Could not load %s: %s", program, dlerror());
        ANativeActivity_finish(activity);
        return;
    }

    OnCreateFunction on_create = (OnCreateFunction)dlsym(handle, "ANativeActivity_onCreate");
    if (on_create == NULL || on_create == ANativeActivity_onCreate) {
        report("%s does not export ANativeActivity_onCreate. "
               "Link android_native_app_glue (or raylib/SFML) into the program.", program);
        ANativeActivity_finish(activity);
        return;
    }
    on_create(activity, saved_state, saved_state_size);
}

// chdir(2) for hosts that start programs without this trampoline (SDL).
JNIEXPORT jint JNICALL Java_io_nativeforge_feature_runner_Posix_chdir(JNIEnv* env, jclass clazz, jstring path) {
    (void)clazz;
    const char* chars = (*env)->GetStringUTFChars(env, path, NULL);
    int result = chdir(chars);
    (*env)->ReleaseStringUTFChars(env, path, chars);
    return result;
}
