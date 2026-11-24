#include <SDL.h>            // renames main() to SDL_main, which the SDL2 host activity calls
#include "lvgl.h"
#include "lv_nativeforge.h"

#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>

// A calculator app in C++ with LVGL: a button matrix for the keys and a large label for
// the display. The logic is a small class that knows nothing about LVGL.

class Calculator {
public:
    // Applies one key ("7", ".", "+", "=", "C", "DEL", "+/-", "%") and returns the display text.
    const std::string& press(const std::string& key) {
        if (key == "C") {
            entry_ = "0";
            accumulator_ = 0;
            pending_ = 0;
            fresh_ = true;
        } else if (key == "DEL") {
            entry_ = entry_.size() > 1 ? entry_.substr(0, entry_.size() - 1) : "0";
            if (entry_ == "-") entry_ = "0";
        } else if (key == "+/-") {
            entry_ = entry_[0] == '-' ? entry_.substr(1) : (entry_ == "0" ? entry_ : "-" + entry_);
        } else if (key == "%") {
            setEntry(value() / 100.0);
        } else if (key == "+" || key == "-" || key == "x" || key == "/" || key == "=") {
            if (!fresh_ || key == "=") {
                accumulator_ = pending_ ? apply(accumulator_, value(), pending_) : value();
                setEntry(accumulator_);
            }
            pending_ = key == "=" ? 0 : key[0];
            fresh_ = true;
        } else { // a digit or the decimal point
            if (fresh_) entry_ = "0";
            fresh_ = false;
            if (key == ".") {
                if (entry_.find('.') == std::string::npos) entry_ += ".";
            } else if (entry_.size() < 15) {
                entry_ = entry_ == "0" ? key : entry_ + key;
            }
        }
        return entry_;
    }

    char pending() const { return pending_; }

private:
    double value() const { return std::strtod(entry_.c_str(), nullptr); }

    static double apply(double a, double b, char op) {
        switch (op) {
            case '+': return a + b;
            case '-': return a - b;
            case 'x': return a * b;
            case '/': return b == 0 ? NAN : a / b;
            default: return b;
        }
    }

    void setEntry(double v) {
        if (std::isnan(v) || std::isinf(v)) {
            entry_ = "Error";
            return;
        }
        char text[32];
        std::snprintf(text, sizeof text, "%.10g", v);
        entry_ = text;
    }

    std::string entry_ = "0";
    double accumulator_ = 0;
    char pending_ = 0;
    bool fresh_ = true;
};

namespace {

Calculator calculator;
lv_obj_t* display = nullptr;
lv_obj_t* operation = nullptr;

// "\n" starts a new row, "" ends the map. Symbols come from LVGL's built-in icon font.
const char* const keys[] = {
    "C", "+/-", "%", "/", "\n",
    "7", "8", "9", "x", "\n",
    "4", "5", "6", "-", "\n",
    "1", "2", "3", "+", "\n",
    "0", ".", LV_SYMBOL_BACKSPACE, "=", "",
};

void onKey(lv_event_t* e) {
    lv_obj_t* matrix = lv_event_get_target_obj(e);
    const uint32_t id = lv_buttonmatrix_get_selected_button(matrix);
    const char* text = lv_buttonmatrix_get_button_text(matrix, id);
    if (text == nullptr) return;
    const std::string key = std::strcmp(text, LV_SYMBOL_BACKSPACE) == 0 ? "DEL" : text;
    lv_label_set_text(display, calculator.press(key).c_str());
    const char op[2] = {calculator.pending(), '\0'};
    lv_label_set_text(operation, op);
}

} // namespace

int main(int, char*[]) {
    if (lv_nativeforge_init(LV_NATIVEFORGE_SCALE_AUTO) == nullptr) return 1;
    lv_obj_t* screen = lv_screen_active();
    lv_theme_default_init(lv_display_get_default(), lv_palette_main(LV_PALETTE_ORANGE),
                          lv_palette_main(LV_PALETTE_GREY), true, lv_theme_get_font_normal(nullptr));
    lv_obj_set_flex_flow(screen, LV_FLEX_FLOW_COLUMN);
    lv_obj_set_style_pad_all(screen, 12, 0);
    lv_obj_set_style_pad_row(screen, 12, 0);

    // Display: the pending operation on the left, the number right-aligned in a large font.
    lv_obj_t* panel = lv_obj_create(screen);
    lv_obj_set_size(panel, lv_pct(100), LV_SIZE_CONTENT);
    lv_obj_set_scrollable(panel, false);
    operation = lv_label_create(panel);
    lv_label_set_text(operation, "");
    lv_obj_set_style_text_font(operation, &lv_font_montserrat_24, 0);
    lv_obj_align(operation, LV_ALIGN_TOP_LEFT, 0, 0);
    display = lv_label_create(panel);
    lv_label_set_text(display, "0");
    lv_obj_set_width(display, lv_pct(100));
    lv_obj_set_style_text_align(display, LV_TEXT_ALIGN_RIGHT, 0);
    lv_obj_set_style_text_font(display, &lv_font_montserrat_48, 0);
    lv_obj_set_style_pad_top(display, 28, 0);

    lv_obj_t* matrix = lv_buttonmatrix_create(screen);
    lv_buttonmatrix_set_map(matrix, keys);
    lv_obj_set_width(matrix, lv_pct(100));
    lv_obj_set_flex_grow(matrix, 1);
    lv_obj_set_style_text_font(matrix, &lv_font_montserrat_28, 0);
    lv_obj_set_style_pad_all(matrix, 6, 0);
    lv_obj_set_style_pad_gap(matrix, 10, 0);
    lv_obj_add_event_cb(matrix, onKey, LV_EVENT_VALUE_CHANGED, nullptr);

    lv_nativeforge_run();
    return 0;
}
