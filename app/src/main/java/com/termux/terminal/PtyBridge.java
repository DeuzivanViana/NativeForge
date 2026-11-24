package com.termux.terminal;

/**
 * Public access to the pseudo-terminal functions of Termux's terminal-emulator library
 * ({@link JNI} is package-private). The remote web IDE needs the raw terminal bytes for
 * xterm.js, while {@link TerminalSession} only exposes an emulated screen.
 */
public final class PtyBridge {
    private PtyBridge() {}

    /** Starts {@code cmd} on a new pseudo-terminal; returns its master file descriptor. */
    public static int createSubprocess(String cmd, String cwd, String[] args, String[] env, int[] processId, int rows, int columns) {
        return JNI.createSubprocess(cmd, cwd, args, env, processId, rows, columns);
    }

    public static void setPtyWindowSize(int fd, int rows, int columns) {
        JNI.setPtyWindowSize(fd, rows, columns);
    }

    /** Blocks until the process exits; returns its exit status (negative for a signal). */
    public static int waitFor(int processId) {
        return JNI.waitFor(processId);
    }

    public static void close(int fd) {
        JNI.close(fd);
    }
}
