/*
 * Native crash recorder for the IDE process (libnativeforge_crash.so).
 *
 * On SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL or SIGTRAP it writes the signal, the fault
 * address and a backtrace (library + offset, and the symbol when there is one) to a file
 * given at install time, then hands the signal to the handler that was there before
 * (Android's debuggerd), so the system still writes its tombstone and the app still ends.
 * The app turns that file into a crash report on its next start (CrashReportStore).
 *
 * Signal handler rules: no malloc, no stdio, no locks of our own. Text is formatted by hand
 * into a stack buffer and written with write(2). dladdr() is not formally async-signal-safe
 * but every crash reporter uses it; a crash inside it ends in the previous handler anyway.
 *
 * ART installs its own SIGSEGV handler (implicit null checks, stack overflow checks) through
 * libsigchain, which calls ART's handler first: signals ART handles never reach this one.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <fcntl.h>
#include <jni.h>
#include <signal.h>
#include <stdint.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <ucontext.h>
#include <unistd.h>
#include <unwind.h>

#define PATH_CAPACITY 1024
#define MAX_FRAMES 64
#define ALT_STACK_SIZE (64 * 1024)

static const int SIGNALS[] = {SIGSEGV, SIGABRT, SIGBUS, SIGFPE, SIGILL, SIGTRAP};
#define SIGNAL_COUNT ((int)(sizeof(SIGNALS) / sizeof(SIGNALS[0])))

static char g_path[PATH_CAPACITY];
static struct sigaction g_previous[SIGNAL_COUNT];
static volatile sig_atomic_t g_handling = 0;
static int g_installed = 0;
static char g_alt_stack[ALT_STACK_SIZE];

/* ---- formatting without libc allocation ---- */

typedef struct {
    char data[4096];
    size_t length;
    int fd;
} Out;

static void out_flush(Out *out) {
    size_t done = 0;
    while (done < out->length) {
        ssize_t n = write(out->fd, out->data + done, out->length - done);
        if (n <= 0) break;
        done += (size_t)n;
    }
    out->length = 0;
}

static void out_str(Out *out, const char *text) {
    if (text == NULL) text = "(null)";
    while (*text) {
        if (out->length == sizeof(out->data)) out_flush(out);
        out->data[out->length++] = *text++;
    }
}

static void out_hex(Out *out, uintptr_t value) {
    char buffer[2 + sizeof(uintptr_t) * 2 + 1];
    static const char digits[] = "0123456789abcdef";
    int i = (int)sizeof(buffer) - 1;
    buffer[i] = '\0';
    do {
        buffer[--i] = digits[value & 0xf];
        value >>= 4;
    } while (value != 0 && i > 2);
    buffer[--i] = 'x';
    buffer[--i] = '0';
    out_str(out, buffer + i);
}

static void out_dec(Out *out, long value) {
    char buffer[24];
    int i = (int)sizeof(buffer) - 1;
    int negative = value < 0;
    unsigned long v = negative ? (unsigned long)(-value) : (unsigned long)value;
    buffer[i] = '\0';
    do {
        buffer[--i] = (char)('0' + v % 10);
        v /= 10;
    } while (v != 0 && i > 1);
    if (negative) buffer[--i] = '-';
    out_str(out, buffer + i);
}

static const char *signal_name(int sig) {
    switch (sig) {
        case SIGSEGV: return "SIGSEGV";
        case SIGABRT: return "SIGABRT";
        case SIGBUS: return "SIGBUS";
        case SIGFPE: return "SIGFPE";
        case SIGILL: return "SIGILL";
        case SIGTRAP: return "SIGTRAP";
        default: return "?";
    }
}

/* ---- backtrace ---- */

typedef struct {
    uintptr_t frames[MAX_FRAMES];
    int count;
} Backtrace;

static _Unwind_Reason_Code unwind_frame(struct _Unwind_Context *context, void *arg) {
    Backtrace *trace = (Backtrace *)arg;
    uintptr_t pc = (uintptr_t)_Unwind_GetIP(context);
    if (pc != 0) {
        if (trace->count >= MAX_FRAMES) return _URC_END_OF_STACK;
        trace->frames[trace->count++] = pc;
    }
    return _URC_NO_REASON;
}

static uintptr_t context_pc(void *context) {
    if (context == NULL) return 0;
    ucontext_t *uc = (ucontext_t *)context;
#if defined(__aarch64__)
    return (uintptr_t)uc->uc_mcontext.pc;
#elif defined(__arm__)
    return (uintptr_t)uc->uc_mcontext.arm_pc;
#elif defined(__x86_64__)
    return (uintptr_t)uc->uc_mcontext.gregs[REG_RIP];
#elif defined(__i386__)
    return (uintptr_t)uc->uc_mcontext.gregs[REG_EIP];
#else
    return 0;
#endif
}

static void out_frame(Out *out, int index, uintptr_t pc) {
    out_str(out, "  #");
    if (index < 10) out_str(out, "0");
    out_dec(out, index);
    out_str(out, " pc ");
    Dl_info info;
    memset(&info, 0, sizeof(info));
    if (dladdr((void *)pc, &info) != 0 && info.dli_fname != NULL) {
        out_hex(out, pc - (uintptr_t)info.dli_fbase);
        out_str(out, "  ");
        out_str(out, info.dli_fname);
        if (info.dli_sname != NULL) {
            out_str(out, " (");
            out_str(out, info.dli_sname);
            out_str(out, "+");
            out_dec(out, (long)(pc - (uintptr_t)info.dli_saddr));
            out_str(out, ")");
        }
    } else {
        out_hex(out, pc);
        out_str(out, "  <unknown>");
    }
    out_str(out, "\n");
}

static void record(int sig, siginfo_t *info, void *context) {
    int fd = open(g_path, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) return;
    Out out;
    out.length = 0;
    out.fd = fd;

    char thread_name[32];
    memset(thread_name, 0, sizeof(thread_name));
    int comm = open("/proc/thread-self/comm", O_RDONLY | O_CLOEXEC);
    if (comm >= 0) {
        ssize_t n = read(comm, thread_name, sizeof(thread_name) - 1);
        if (n > 0 && thread_name[n - 1] == '\n') thread_name[n - 1] = '\0';
        close(comm);
    }

    out_str(&out, "pid: ");
    out_dec(&out, (long)getpid());
    out_str(&out, ", tid: ");
    out_dec(&out, (long)syscall(SYS_gettid));
    out_str(&out, ", thread: ");
    out_str(&out, thread_name[0] ? thread_name : "?");
    out_str(&out, "\nsignal ");
    out_dec(&out, sig);
    out_str(&out, " (");
    out_str(&out, signal_name(sig));
    out_str(&out, "), code ");
    out_dec(&out, info != NULL ? info->si_code : 0);
    if (info != NULL && info->si_code > 0) {
        out_str(&out, ", fault addr ");
        out_hex(&out, (uintptr_t)info->si_addr);
    } else if (info != NULL) {
        out_str(&out, ", sent by pid ");
        out_dec(&out, (long)info->si_pid);
    }
    out_str(&out, "\n\nbacktrace:\n");

    uintptr_t pc = context_pc(context);
    int index = 0;
    if (pc != 0) out_frame(&out, index++, pc);

    Backtrace trace;
    trace.count = 0;
    _Unwind_Backtrace(unwind_frame, &trace);
    /* The unwinder starts in this handler: skip frames until the faulting pc, if it shows up. */
    int start = 0;
    if (pc != 0) {
        for (int i = 0; i < trace.count; i++) {
            if (trace.frames[i] == pc) { start = i + 1; break; }
        }
    }
    for (int i = start; i < trace.count; i++) out_frame(&out, index++, trace.frames[i]);
    if (trace.count == 0) out_str(&out, "  (the unwinder found no frames)\n");

    out_flush(&out);
    fsync(fd);
    close(fd);
}

static int signal_index(int sig) {
    for (int i = 0; i < SIGNAL_COUNT; i++) {
        if (SIGNALS[i] == sig) return i;
    }
    return -1;
}

static void handler(int sig, siginfo_t *info, void *context) {
    int index = signal_index(sig);
    if (!g_handling) {
        g_handling = 1;
        record(sig, info, context);
    }
    /* Put the previous handler (debuggerd, or the default) back and let it see the signal. */
    if (index >= 0) sigaction(sig, &g_previous[index], NULL);
    if (info != NULL && info->si_code <= 0) {
        /* Sent with kill/tgkill/abort(): send it again, now to the previous handler. */
        syscall(SYS_rt_tgsigqueueinfo, getpid(), syscall(SYS_gettid), sig, info);
    }
    /* A fault (SIGSEGV from a bad access...) happens again when the instruction is retried. */
}

JNIEXPORT jboolean JNICALL
Java_io_nativeforge_feature_runner_crash_NativeCrashHandler_nativeInstall(JNIEnv *env, jclass clazz, jstring path) {
    (void)clazz;
    if (g_installed) return JNI_TRUE;
    const char *chars = (*env)->GetStringUTFChars(env, path, NULL);
    if (chars == NULL) return JNI_FALSE;
    size_t length = strlen(chars);
    if (length == 0 || length >= PATH_CAPACITY) {
        (*env)->ReleaseStringUTFChars(env, path, chars);
        return JNI_FALSE;
    }
    memcpy(g_path, chars, length + 1);
    (*env)->ReleaseStringUTFChars(env, path, chars);

    /* Room to run on when the crash is a stack overflow of this (the main) thread. */
    stack_t stack;
    memset(&stack, 0, sizeof(stack));
    stack.ss_sp = g_alt_stack;
    stack.ss_size = sizeof(g_alt_stack);
    sigaltstack(&stack, NULL);

    struct sigaction action;
    memset(&action, 0, sizeof(action));
    sigemptyset(&action.sa_mask);
    action.sa_sigaction = handler;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    for (int i = 0; i < SIGNAL_COUNT; i++) {
        if (sigaction(SIGNALS[i], &action, &g_previous[i]) != 0) {
            memset(&g_previous[i], 0, sizeof(g_previous[i]));
            g_previous[i].sa_handler = SIG_DFL;
        }
    }
    g_installed = 1;
    return JNI_TRUE;
}
