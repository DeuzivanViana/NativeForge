#include <SDL.h>            // renames main() to SDL_main, which the SDL2 host activity calls
#include "lvgl.h"
#include "lv_nativeforge.h" // full screen display, touch and keyboard on Android

#include <string>

// LVGL keeps a tree of widgets: create them once, then react to events in callbacks
// (captureless lambdas work as callbacks). The screen is drawn at its full resolution
// (LV_NATIVEFORGE_SCALE_PIXELS) for sharp text; sizes are written in dp with LV_DPX() and
// lv_nativeforge_font(), like Android layouts. Documentation: https://docs.lvgl.io

namespace {

struct App {
    lv_obj_t* counterLabel = nullptr;
    lv_obj_t* nameInput = nullptr;
    lv_obj_t* keyboard = nullptr;
    int taps = 0;
};

App app;

// A white card with rounded corners that stacks its children vertically.
lv_obj_t* card(lv_obj_t* parent, const char* title) {
    lv_obj_t* box = lv_obj_create(parent);
    lv_obj_set_size(box, lv_pct(100), LV_SIZE_CONTENT);
    lv_obj_set_flex_flow(box, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_style_pad_row(box, LV_DPX(10), 0);
    lv_obj_t* label = lv_label_create(box);
    lv_label_set_text(label, title);
    lv_obj_set_style_text_font(label, lv_nativeforge_font(18), 0);
    return box;
}

lv_obj_t* button(lv_obj_t* parent, const char* text, lv_event_cb_t onClick) {
    lv_obj_t* b = lv_button_create(parent);
    lv_obj_add_event_cb(b, onClick, LV_EVENT_CLICKED, nullptr);
    lv_label_set_text(lv_label_create(b), text);
    return b;
}

// Shows or hides the keyboard, and scrolls the text field above it.
void showKeyboard(bool show) {
    lv_obj_t* screen = lv_screen_active();
    lv_obj_set_hidden(app.keyboard, !show);
    lv_obj_update_layout(app.keyboard);
    // Room under the cards, so that the last one can scroll up above the keyboard.
    lv_obj_set_style_pad_bottom(screen, show ? lv_obj_get_height(app.keyboard) + LV_DPX(16) : LV_DPX(16), 0);
    if (show) {
        lv_obj_update_layout(screen);
        lv_obj_scroll_to_view_recursive(app.nameInput, LV_ANIM_ON);
    }
}

// Shows the on-screen keyboard while the text field has the focus.
void onNameInput(lv_event_t* e) {
    const lv_event_code_t code = lv_event_get_code(e);
    if (code == LV_EVENT_FOCUSED || code == LV_EVENT_CLICKED) {
        lv_keyboard_set_textarea(app.keyboard, app.nameInput);
        showKeyboard(true);
    } else if (code == LV_EVENT_DEFOCUSED || code == LV_EVENT_READY || code == LV_EVENT_CANCEL) {
        showKeyboard(false);
        lv_obj_remove_state(app.nameInput, LV_STATE_FOCUSED);
    }
}

} // namespace

int main(int, char*[]) {
    if (lv_nativeforge_init(LV_NATIVEFORGE_SCALE_PIXELS) == nullptr) return 1;

    lv_obj_t* screen = lv_screen_active();
    lv_obj_set_flex_flow(screen, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_style_pad_all(screen, LV_DPX(16), 0);
    lv_obj_set_style_pad_row(screen, LV_DPX(12), 0);

    lv_obj_t* title = lv_label_create(screen);
    lv_label_set_text(title, "@PROJECT_NAME@");
    lv_obj_set_style_text_font(title, lv_nativeforge_font(28), 0);
    // A long project name continues on the next line instead of running off the screen.
    lv_obj_set_width(title, lv_pct(100));
    lv_label_set_long_mode(title, LV_LABEL_LONG_MODE_WRAP);

    lv_obj_t* counter = card(screen, "Counter");
    app.counterLabel = lv_label_create(counter);
    lv_label_set_text(app.counterLabel, "Not tapped yet");
    button(counter, LV_SYMBOL_PLUS " Tap me", [](lv_event_t*) {
        ++app.taps;
        const std::string text = "Tapped " + std::to_string(app.taps) + (app.taps == 1 ? " time" : " times");
        lv_label_set_text(app.counterLabel, text.c_str());
    });

    lv_obj_t* settings = card(screen, "Settings");
    lv_obj_t* row = lv_obj_create(settings);
    lv_obj_remove_style_all(row);
    lv_obj_set_size(row, lv_pct(100), LV_SIZE_CONTENT);
    lv_obj_set_flex_flow(row, LV_FLEX_FLOW_ROW);
    lv_obj_set_flex_align(row, LV_FLEX_ALIGN_SPACE_BETWEEN, LV_FLEX_ALIGN_CENTER, LV_FLEX_ALIGN_CENTER);
    lv_label_set_text(lv_label_create(row), "Dark mode");
    lv_obj_t* dark = lv_switch_create(row);
    lv_obj_add_event_cb(dark, [](lv_event_t* e) {
        const bool on = lv_obj_has_state(lv_event_get_target_obj(e), LV_STATE_CHECKED);
        lv_theme_default_init(lv_display_get_default(), lv_palette_main(LV_PALETTE_BLUE),
                              lv_palette_main(LV_PALETTE_TEAL), on, lv_theme_get_font_normal(nullptr));
    }, LV_EVENT_VALUE_CHANGED, nullptr);
    lv_obj_t* volume = lv_label_create(settings);
    lv_label_set_text(volume, "Volume: 40%");
    lv_obj_t* slider = lv_slider_create(settings);
    lv_obj_set_width(slider, lv_pct(95));
    lv_slider_set_value(slider, 40, LV_ANIM_OFF);
    // The label travels with the event as user data, so the lambda needs no capture.
    lv_obj_add_event_cb(slider, [](lv_event_t* e) {
        auto* label = static_cast<lv_obj_t*>(lv_event_get_user_data(e));
        lv_label_set_text_fmt(label, "Volume: %d%%", static_cast<int>(lv_slider_get_value(lv_event_get_target_obj(e))));
    }, LV_EVENT_VALUE_CHANGED, volume);

    lv_obj_t* greeting = card(screen, "Greeting");
    app.nameInput = lv_textarea_create(greeting);
    lv_textarea_set_one_line(app.nameInput, true);
    lv_textarea_set_placeholder_text(app.nameInput, "Your name");
    lv_obj_set_width(app.nameInput, lv_pct(100));
    lv_obj_add_event_cb(app.nameInput, onNameInput, LV_EVENT_ALL, nullptr);
    button(greeting, "Say hello", [](lv_event_t*) {
        const std::string name = lv_textarea_get_text(app.nameInput);
        showKeyboard(false);
        lv_obj_t* box = lv_msgbox_create(nullptr); // nullptr: a modal box over the whole screen
        lv_msgbox_add_title(box, "@PROJECT_NAME@");
        lv_msgbox_add_text(box, ("Hello, " + (name.empty() ? std::string("stranger") : name) + "!").c_str());
        lv_msgbox_add_close_button(box);
    });

    // The keyboard floats over the content, on the top layer, until a text field needs it.
    app.keyboard = lv_keyboard_create(lv_layer_top());
    lv_obj_set_hidden(app.keyboard, true);
    lv_obj_add_event_cb(app.keyboard, onNameInput, LV_EVENT_READY, nullptr);
    lv_obj_add_event_cb(app.keyboard, onNameInput, LV_EVENT_CANCEL, nullptr);

    lv_nativeforge_run();
    return 0;
}
