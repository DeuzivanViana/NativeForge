// NativeActivity trampoline.
//
// Android's NativeActivity can only load libraries packaged inside the APK.
// The IDE's programs live in the app's data directory, so NativeActivity
// loads this shim instead; the shim opens the real program named by the
// NATIVEFORGE_PROGRAM environment variable (set from Java right before
// NativeActivity.onCreate) and forwards the entry point to it.
//
// When the program is streamed to the remote web IDE (NATIVEFORGE_REMOTE_WINDOW), the
// shim also tells Java the window size a raylib program asks for in InitWindow, so the
// off-screen display can take that size before raylib sets up its framebuffer.

#include <android/log.h>
#include <jni.h>
#include <unistd.h>
#include <android/native_activity.h>
#include <dlfcn.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>

#include "plt_hook.h"

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

static JavaVM* window_vm;
static jobject window_activity;
static jmethodID window_method;
static void (*real_init_window)(int, int, const char*);

// Calls NativeProgramActivity.onProgramWindow, which returns once the display has the
// new size (or after a short timeout).
static void report_window(int width, int height, jboolean resizable) {
    if (window_vm == NULL) return;
    JNIEnv* env = NULL;
    int attached = 0;
    if ((*window_vm)->GetEnv(window_vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*window_vm)->AttachCurrentThread(window_vm, &env, NULL) != JNI_OK) return;
        attached = 1;
    }
    (*env)->CallVoidMethod(env, window_activity, window_method, width, height, resizable);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    if (attached) (*window_vm)->DetachCurrentThread(window_vm);
}

// raylib on Android ignores the size it is given and never resizes, so the display
// takes that size and keeps it.
static void hooked_init_window(int width, int height, const char* title) {
    report_window(width, height, JNI_FALSE);
    real_init_window(width, height, title);
}

static void watch_window_requests(ANativeActivity* activity, void* program) {
    if (getenv("NATIVEFORGE_REMOTE_WINDOW") == NULL) return;
    // Only raylib's InitWindow: a program's own function of that name may take other arguments.
    if (dlsym(program, "rlglInit") == NULL || dlsym(program, "InitWindow") == NULL) return;
    JNIEnv* env = activity->env;
    jclass type = (*env)->GetObjectClass(env, activity->clazz);
    jmethodID method = (*env)->GetMethodID(env, type, "onProgramWindow", "(IIZ)V");
    (*env)->DeleteLocalRef(env, type);
    if (method == NULL) {
        (*env)->ExceptionClear(env);
        return;
    }
    window_vm = activity->vm;
    window_activity = (*env)->NewGlobalRef(env, activity->clazz);
    window_method = method;
    struct nf_plt_hook hooks[] = {{"InitWindow", (void*)hooked_init_window, (void**)&real_init_window}};
    if (nf_plt_hook_all(hooks, 1) == 0) {
        LOGE("InitWindow is not called through the PLT; the stream keeps its size");
        return;
    }
    // The activity then shows the program's window only once the display has that size.
    jclass activity_type = (*env)->GetObjectClass(env, activity->clazz);
    jmethodID expect = (*env)->GetMethodID(env, activity_type, "expectWindowRequest", "()V");
    (*env)->DeleteLocalRef(env, activity_type);
    if (expect != NULL) (*env)->CallVoidMethod(env, activity->clazz, expect);
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
}

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
    watch_window_requests(activity, handle);
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
