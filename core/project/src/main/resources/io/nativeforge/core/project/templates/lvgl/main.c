#include <SDL.h>            /* renames main() to SDL_main, which the SDL2 host activity calls */
#include "lvgl.h"
#include "lv_nativeforge.h" /* full screen display, touch and keyboard on Android */

/*
 * LVGL keeps a tree of widgets: create them once, then react to events in callbacks.
 * The screen is drawn at its full resolution (LV_NATIVEFORGE_SCALE_PIXELS) for sharp text;
 * sizes are written in dp with LV_DPX() and lv_nativeforge_font(), like Android layouts,
 * so they look the same on every phone. Documentation: https://docs.lvgl.io
 */

static lv_obj_t *counter_label;
static lv_obj_t *slider_label;
static lv_obj_t *name_input;
static lv_obj_t *keyboard;
static int taps;

static void on_tap(lv_event_t *e)
{
    LV_UNUSED(e);
    taps++;
    lv_label_set_text_fmt(counter_label, "Tapped %d time%s", taps, taps == 1 ? "" : "s");
}

static void on_dark_mode(lv_event_t *e)
{
    bool dark = lv_obj_has_state(lv_event_get_target_obj(e), LV_STATE_CHECKED);
    lv_theme_default_init(lv_display_get_default(), lv_palette_main(LV_PALETTE_BLUE), lv_palette_main(LV_PALETTE_TEAL),
                          dark, lv_theme_get_font_normal(NULL));
}

static void on_slider(lv_event_t *e)
{
    lv_label_set_text_fmt(slider_label, "Volume: %d%%", (int)lv_slider_get_value(lv_event_get_target_obj(e)));
}

/* Shows or hides the keyboard, and scrolls the text field above it. */
static void show_keyboard(bool show)
{
    lv_obj_t *screen = lv_screen_active();
    lv_obj_set_hidden(keyboard, !show);
    lv_obj_update_layout(keyboard);
    /* Room under the cards, so that the last one can scroll up above the keyboard. */
    lv_obj_set_style_pad_bottom(screen, show ? lv_obj_get_height(keyboard) + LV_DPX(16) : LV_DPX(16), 0);
    if (show) {
        lv_obj_update_layout(screen);
        lv_obj_scroll_to_view_recursive(name_input, LV_ANIM_ON);
    }
}

/* Shows the on-screen keyboard while the text field has the focus. */
static void on_name_input(lv_event_t *e)
{
    lv_event_code_t code = lv_event_get_code(e);
    if (code == LV_EVENT_FOCUSED || code == LV_EVENT_CLICKED) {
        lv_keyboard_set_textarea(keyboard, name_input);
        show_keyboard(true);
    } else if (code == LV_EVENT_DEFOCUSED || code == LV_EVENT_READY || code == LV_EVENT_CANCEL) {
        show_keyboard(false);
        lv_obj_remove_state(name_input, LV_STATE_FOCUSED);
    }
}

static void on_greet(lv_event_t *e)
{
    LV_UNUSED(e);
    const char *name = lv_textarea_get_text(name_input);
    show_keyboard(false);
    lv_obj_t *box = lv_msgbox_create(NULL); /* NULL: a modal box over the whole screen */
    lv_msgbox_add_title(box, "@PROJECT_NAME@");
    lv_msgbox_add_text_fmt(box, "Hello, %s!", name[0] ? name : "stranger");
    lv_msgbox_add_close_button(box);
}

/* A white card with rounded corners that stacks its children vertically. */
static lv_obj_t *card(lv_obj_t *parent, const char *title)
{
    lv_obj_t *box = lv_obj_create(parent);
    lv_obj_set_width(box, lv_pct(100));
    lv_obj_set_height(box, LV_SIZE_CONTENT);
    lv_obj_set_flex_flow(box, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_style_pad_row(box, LV_DPX(10), 0);
    lv_obj_t *label = lv_label_create(box);
    lv_label_set_text(label, title);
    lv_obj_set_style_text_font(label, lv_nativeforge_font(18), 0);
    return box;
}

int main(int argc, char *argv[])
{
    LV_UNUSED(argc);
    LV_UNUSED(argv);
    if (lv_nativeforge_init(LV_NATIVEFORGE_SCALE_PIXELS) == NULL) return 1;

    lv_obj_t *screen = lv_screen_active();
    lv_obj_set_flex_flow(screen, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_style_pad_all(screen, LV_DPX(16), 0);
    lv_obj_set_style_pad_row(screen, LV_DPX(12), 0);

    lv_obj_t *title = lv_label_create(screen);
    lv_label_set_text(title, "@PROJECT_NAME@");
    lv_obj_set_style_text_font(title, lv_nativeforge_font(28), 0);
    /* A long project name continues on the next line instead of running off the screen. */
    lv_obj_set_width(title, lv_pct(100));
    lv_label_set_long_mode(title, LV_LABEL_LONG_MODE_WRAP);

    lv_obj_t *counter = card(screen, "Counter");
    counter_label = lv_label_create(counter);
    lv_label_set_text(counter_label, "Not tapped yet");
    lv_obj_t *button = lv_button_create(counter);
    lv_obj_add_event_cb(button, on_tap, LV_EVENT_CLICKED, NULL);
    lv_obj_t *button_label = lv_label_create(button);
    lv_label_set_text(button_label, LV_SYMBOL_PLUS " Tap me");

    lv_obj_t *settings = card(screen, "Settings");
    lv_obj_t *row = lv_obj_create(settings);
    lv_obj_remove_style_all(row);
    lv_obj_set_size(row, lv_pct(100), LV_SIZE_CONTENT);
    lv_obj_set_flex_flow(row, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(row, LV_FLEX_ALIGN_SPACE_BETWEEN, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
    lv_label_set_text(lv_label_create(row), "Dark mode");
    lv_obj_t *dark = lv_switch_create(row);
    lv_obj_add_event_cb(dark, on_dark_mode, LV_EVENT_VALUE_CHANGED, NULL);
    slider_label = lv_label_create(settings);
    lv_label_set_text(slider_label, "Volume: 40%");
    lv_obj_t *slider = lv_slider_create(settings);
    lv_obj_set_width(slider, lv_pct(95));
    lv_slider_set_value(slider, 40, LV_ANIM_OFF);
    lv_obj_add_event_cb(slider, on_slider, LV_EVENT_VALUE_CHANGED, NULL);

    lv_obj_t *greeting = card(screen, "Greeting");
    name_input = lv_textarea_create(greeting);
    lv_textarea_set_one_line(name_input, true);
    lv_textarea_set_placeholder_text(name_input, "Your name");
    lv_obj_set_width(name_input, lv_pct(100));
    lv_obj_add_event_cb(name_input, on_name_input, LV_EVENT_ALL, NULL);
    lv_obj_t *greet = lv_button_create(greeting);
    lv_obj_add_event_cb(greet, on_greet, LV_EVENT_CLICKED, NULL);
    lv_label_set_text(lv_label_create(greet), "Say hello");

    /* The keyboard floats over the content, on the top layer, until a text field needs it. */
    keyboard = lv_keyboard_create(lv_layer_top());
    lv_obj_set_hidden(keyboard, true);
    lv_obj_add_event_cb(keyboard, on_name_input, LV_EVENT_READY, NULL);
    lv_obj_add_event_cb(keyboard, on_name_input, LV_EVENT_CANCEL, NULL);

    lv_nativeforge_run();
    return 0;
}
