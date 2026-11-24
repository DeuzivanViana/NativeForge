package io.nativeforge.app.ui.workspace.commands

import android.view.KeyEvent

/** A key combination, VS Code style. */
data class KeyChord(val keyCode: Int, val ctrl: Boolean = false, val shift: Boolean = false, val alt: Boolean = false) {
    fun matches(event: KeyEvent): Boolean =
        event.keyCode == keyCode && event.isCtrlPressed == ctrl && event.isShiftPressed == shift && event.isAltPressed == alt

    /** Text such as "Ctrl+Shift+B". */
    val label: String
        get() = buildList {
            if (ctrl) add("Ctrl")
            if (shift) add("Shift")
            if (alt) add("Alt")
            add(keyName(keyCode))
        }.joinToString("+")

    private fun keyName(code: Int): String = when (code) {
        KeyEvent.KEYCODE_SPACE -> "Space"
        KeyEvent.KEYCODE_SLASH -> "/"
        KeyEvent.KEYCODE_BACKSLASH -> "\\"
        KeyEvent.KEYCODE_GRAVE -> "`"
        KeyEvent.KEYCODE_EQUALS -> "="
        KeyEvent.KEYCODE_PLUS -> "+"
        KeyEvent.KEYCODE_MINUS -> "-"
        KeyEvent.KEYCODE_TAB -> "Tab"
        KeyEvent.KEYCODE_PAGE_UP -> "PageUp"
        KeyEvent.KEYCODE_PAGE_DOWN -> "PageDown"
        KeyEvent.KEYCODE_DPAD_UP -> "Up"
        KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
        KeyEvent.KEYCODE_ESCAPE -> "Esc"
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> "F${code - KeyEvent.KEYCODE_F1 + 1}"
        else -> KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_")
    }
}

/**
 * Every action of the workspace that a physical keyboard or the command palette can run.
 * Shortcuts follow VS Code so they feel familiar on a tablet or Chromebook keyboard.
 */
enum class WorkspaceCommand(val title: String, val chords: List<KeyChord>) {
    COMMAND_PALETTE("Show all commands", listOf(KeyChord(KeyEvent.KEYCODE_P, ctrl = true, shift = true), KeyChord(KeyEvent.KEYCODE_F1))),
    QUICK_OPEN("Go to file…", listOf(KeyChord(KeyEvent.KEYCODE_P, ctrl = true))),
    SAVE("Save all", listOf(KeyChord(KeyEvent.KEYCODE_S, ctrl = true))),
    BUILD("Build", listOf(KeyChord(KeyEvent.KEYCODE_B, ctrl = true, shift = true), KeyChord(KeyEvent.KEYCODE_F7))),
    RUN("Build and run", listOf(KeyChord(KeyEvent.KEYCODE_F5), KeyChord(KeyEvent.KEYCODE_F5, ctrl = true))),
    CANCEL_BUILD("Cancel build", listOf(KeyChord(KeyEvent.KEYCODE_F5, shift = true))),
    FIND("Find", listOf(KeyChord(KeyEvent.KEYCODE_F, ctrl = true))),
    REPLACE("Find and replace", listOf(KeyChord(KeyEvent.KEYCODE_H, ctrl = true))),
    SEARCH_IN_PROJECT("Search in project", listOf(KeyChord(KeyEvent.KEYCODE_F, ctrl = true, shift = true))),
    FIND_NEXT("Find next", listOf(KeyChord(KeyEvent.KEYCODE_F3))),
    FIND_PREVIOUS("Find previous", listOf(KeyChord(KeyEvent.KEYCODE_F3, shift = true))),
    GO_TO_LINE("Go to line…", listOf(KeyChord(KeyEvent.KEYCODE_G, ctrl = true))),
    COMPLETE("Trigger suggestions", listOf(KeyChord(KeyEvent.KEYCODE_SPACE, ctrl = true))),
    GO_TO_DEFINITION("Go to definition", listOf(KeyChord(KeyEvent.KEYCODE_F12))),
    RENAME("Rename symbol", listOf(KeyChord(KeyEvent.KEYCODE_F2))),
    SHOW_HOVER("Show type and documentation", listOf(KeyChord(KeyEvent.KEYCODE_I, ctrl = true, shift = true))),
    FORMAT("Format document", listOf(KeyChord(KeyEvent.KEYCODE_F, shift = true, alt = true))),
    TOGGLE_COMMENT("Toggle line comment", listOf(KeyChord(KeyEvent.KEYCODE_SLASH, ctrl = true))),
    DUPLICATE_LINE("Duplicate line", listOf(KeyChord(KeyEvent.KEYCODE_DPAD_DOWN, shift = true, alt = true), KeyChord(KeyEvent.KEYCODE_D, ctrl = true, shift = true))),
    MOVE_LINE_UP("Move line up", listOf(KeyChord(KeyEvent.KEYCODE_DPAD_UP, alt = true))),
    MOVE_LINE_DOWN("Move line down", listOf(KeyChord(KeyEvent.KEYCODE_DPAD_DOWN, alt = true))),
    DELETE_LINE("Delete line", listOf(KeyChord(KeyEvent.KEYCODE_K, ctrl = true, shift = true))),
    NEXT_TAB("Next editor tab", listOf(KeyChord(KeyEvent.KEYCODE_TAB, ctrl = true), KeyChord(KeyEvent.KEYCODE_PAGE_DOWN, ctrl = true))),
    PREVIOUS_TAB("Previous editor tab", listOf(KeyChord(KeyEvent.KEYCODE_TAB, ctrl = true, shift = true), KeyChord(KeyEvent.KEYCODE_PAGE_UP, ctrl = true))),
    CLOSE_TAB("Close editor tab", listOf(KeyChord(KeyEvent.KEYCODE_W, ctrl = true))),
    NEW_FILE("New file…", listOf(KeyChord(KeyEvent.KEYCODE_N, ctrl = true))),
    SPLIT_EDITOR("Split editor", listOf(KeyChord(KeyEvent.KEYCODE_BACKSLASH, ctrl = true))),
    FOCUS_OTHER_EDITOR("Focus other editor", listOf(KeyChord(KeyEvent.KEYCODE_2, ctrl = true))),
    TOGGLE_EXPLORER("Toggle explorer", listOf(KeyChord(KeyEvent.KEYCODE_B, ctrl = true))),
    TOGGLE_PANEL("Toggle output panel", listOf(KeyChord(KeyEvent.KEYCODE_J, ctrl = true))),
    TERMINAL("Open terminal", listOf(KeyChord(KeyEvent.KEYCODE_GRAVE, ctrl = true))),
    ZOOM_IN("Increase font size", listOf(KeyChord(KeyEvent.KEYCODE_EQUALS, ctrl = true), KeyChord(KeyEvent.KEYCODE_PLUS, ctrl = true), KeyChord(KeyEvent.KEYCODE_NUMPAD_ADD, ctrl = true))),
    ZOOM_OUT("Decrease font size", listOf(KeyChord(KeyEvent.KEYCODE_MINUS, ctrl = true), KeyChord(KeyEvent.KEYCODE_NUMPAD_SUBTRACT, ctrl = true))),
    LIBRARIES("Libraries…", emptyList()),
    BUILD_SETTINGS("Build settings…", emptyList()),
    PROGRAM_LOG("Show program log", emptyList()),
    EXPORT("Export APK / AAB", emptyList()),
    PACKAGES("Open packages", emptyList()),
    SETTINGS("Open settings", listOf(KeyChord(KeyEvent.KEYCODE_COMMA, ctrl = true))),
    ;

    val shortcutLabel: String get() = chords.firstOrNull()?.label.orEmpty()

    companion object {
        /** The command bound to [event], or null. Only key-down events are matched. */
        fun forKey(event: KeyEvent): WorkspaceCommand? {
            if (event.action != KeyEvent.ACTION_DOWN) return null
            return entries.firstOrNull { command -> command.chords.any { it.matches(event) } }
        }
    }
}

/**
 * Hands hardware key events from the activity to the screen that is showing. The
 * workspace registers a handler while it is visible; MainActivity calls [dispatch]
 * before the focused view (the code editor) sees the key.
 */
object KeyboardShortcuts {
    @Volatile var handler: ((KeyEvent) -> Boolean)? = null

    fun dispatch(event: KeyEvent): Boolean = handler?.invoke(event) ?: false
}
