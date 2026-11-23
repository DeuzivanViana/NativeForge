package io.nativeforge.feature.terminal;

import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;

import com.termux.terminal.TerminalSession;
import com.termux.view.TerminalViewClient;

/**
 * Default implementation of {@link TerminalViewClient}. See
 * {@link TerminalSessionClientBase} for why this is Java without {@code @Override}.
 */
public abstract class TerminalViewClientBase implements TerminalViewClient {
    private static final String TAG = "NfTerminalView";

    public float onScale(float scale) { return scale; }
    public void onSingleTapUp(MotionEvent e) {}
    public boolean shouldBackButtonBeMappedToEscape() { return false; }
    public boolean shouldEnforceCharBasedInput() { return true; }
    public boolean shouldUseCtrlSpaceWorkaround() { return false; }
    public boolean isTerminalViewSelected() { return true; }
    public void copyModeChanged(boolean copyMode) {}
    public boolean onKeyDown(int keyCode, KeyEvent e, TerminalSession session) { return false; }
    public boolean onKeyUp(int keyCode, KeyEvent e) { return false; }
    public boolean onLongPress(MotionEvent event) { return false; }
    public boolean readControlKey() { return false; }
    public boolean readAltKey() { return false; }
    public boolean readShiftKey() { return false; }
    public boolean readFnKey() { return false; }
    public boolean onCodePoint(int codePoint, boolean ctrlDown, TerminalSession session) { return false; }
    public void onEmulatorSet() {}

    public void logError(String tag, String message) { Log.e(TAG, tag + ": " + message); }
    public void logWarn(String tag, String message) { Log.w(TAG, tag + ": " + message); }
    public void logInfo(String tag, String message) { Log.i(TAG, tag + ": " + message); }
    public void logDebug(String tag, String message) { Log.d(TAG, tag + ": " + message); }
    public void logVerbose(String tag, String message) { Log.v(TAG, tag + ": " + message); }
    public void logStackTraceWithMessage(String tag, String message, Exception e) { Log.e(TAG, tag + ": " + message, e); }
    public void logStackTrace(String tag, Exception e) { Log.e(TAG, tag, e); }
}
