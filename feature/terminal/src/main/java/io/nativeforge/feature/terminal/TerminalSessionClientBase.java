package io.nativeforge.feature.terminal;

import android.util.Log;

import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;

/**
 * No-op base implementation of {@link TerminalSessionClient}.
 *
 * <p>Written in Java without {@code @Override} on purpose: the Termux interface
 * gained methods across releases, and this keeps the adapter source-compatible
 * with several library versions. Kotlin subclasses override what they need.
 */
public abstract class TerminalSessionClientBase implements TerminalSessionClient {
    private static final String TAG = "NfTerminal";

    public void onTextChanged(TerminalSession changedSession) {}
    public void onTitleChanged(TerminalSession changedSession) {}
    public void onSessionFinished(TerminalSession finishedSession) {}
    public void onCopyTextToClipboard(TerminalSession session, String text) {}
    public void onPasteTextFromClipboard(TerminalSession session) {}
    public void onBell(TerminalSession session) {}
    public void onColorsChanged(TerminalSession session) {}
    public void onTerminalCursorStateChange(boolean state) {}
    public void setTerminalShellPid(TerminalSession session, int pid) {}
    public Integer getTerminalCursorStyle() { return null; }

    public void logError(String tag, String message) { Log.e(TAG, tag + ": " + message); }
    public void logWarn(String tag, String message) { Log.w(TAG, tag + ": " + message); }
    public void logInfo(String tag, String message) { Log.i(TAG, tag + ": " + message); }
    public void logDebug(String tag, String message) { Log.d(TAG, tag + ": " + message); }
    public void logVerbose(String tag, String message) { Log.v(TAG, tag + ": " + message); }
    public void logStackTraceWithMessage(String tag, String message, Exception e) { Log.e(TAG, tag + ": " + message, e); }
    public void logStackTrace(String tag, Exception e) { Log.e(TAG, tag, e); }
}
