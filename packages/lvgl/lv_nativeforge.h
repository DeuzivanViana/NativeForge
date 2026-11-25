/*
 * lv_nativeforge: runs LVGL full screen on Android through SDL2 (NativeForge's SDL2 host).
 * Part of NativeForge's "lvgl" package. SPDX-License-Identifier: Zlib
 *
 * Typical program:
 *
 *     #include <SDL.h>                // renames main() to SDL_main, which the host calls
 *     #include "lvgl.h"
 *     #include "lv_nativeforge.h"
 *
 *     int main(int argc, char *argv[]) {
 *         lv_nativeforge_init(LV_NATIVEFORGE_SCALE_PIXELS);
 *         lv_obj_t *label = lv_label_create(lv_screen_active());
 *         lv_label_set_text(label, "Hello");
 *         lv_obj_set_style_text_font(label, lv_nativeforge_font(24), 0);  // 24 dp
 *         lv_obj_set_width(label, LV_DPX(200));                            // 200 dp
 *         lv_obj_center(label);
 *         lv_nativeforge_run();       // returns when the app is closed
 *         return 0;
 *     }
 *
 * The same code builds on a desktop with SDL2 and LVGL configured like include/lv_conf.h.
 */
#ifndef LV_NATIVEFORGE_H
#define LV_NATIVEFORGE_H

#include "lvgl.h"

#ifdef __cplusplus
extern "C" {
#endif

/**
 * One LVGL pixel is one Android density-independent pixel (dp), so sizes written for a
 * 160 DPI screen (most LVGL examples and tutorials) look the same on every phone.
 * The rendered image is scaled up by the screen density with linear filtering, so text
 * and edges look soft (2 to 3 times larger pixels on most phones). Good for porting code
 * written in plain pixels; use LV_NATIVEFORGE_SCALE_PIXELS for a sharp picture.
 */
#define LV_NATIVEFORGE_SCALE_AUTO 0.0f

/**
 * One LVGL pixel is one screen pixel: sharp text and edges (recommended). The display's
 * DPI is the real one, so the default theme already sizes its paddings and text for the
 * screen; write your own sizes in dp with LV_DPX(n) (or lv_dpx(n)) and pick fonts with
 * lv_nativeforge_font(n).
 */
#define LV_NATIVEFORGE_SCALE_PIXELS 1.0f

/**
 * Like LV_NATIVEFORGE_SCALE_AUTO, but zooms out on screens whose short side would have fewer
 * than @p min_size LVGL pixels, so layouts made for that width still fit (for example
 * LV_NATIVEFORGE_SCALE_MIN_SIZE(400) for LVGL's widgets demo on small phones).
 */
#define LV_NATIVEFORGE_SCALE_MIN_SIZE(min_size) (-(float)(min_size))

/**
 * Initializes LVGL (if lv_init() was not called yet), opens a full screen SDL window and
 * creates a display for it, a touch/mouse pointer, a keyboard and a mouse wheel input
 * device. LVGL's log messages go to logcat (More > Program log in the IDE).
 *
 * @param scale LV_NATIVEFORGE_SCALE_AUTO, LV_NATIVEFORGE_SCALE_PIXELS,
 *              LV_NATIVEFORGE_SCALE_MIN_SIZE(n) or any factor >= 1
 *              (for example 2.0f to draw everything twice as large as at 160 DPI).
 * @return the display, or NULL when SDL could not open a window (see the log).
 */
lv_display_t * lv_nativeforge_init(float scale);

/**
 * Runs LVGL's timers and redraws the screen until the app is closed (back button or
 * swiping the app away). Use lv_nativeforge_step() instead to keep your own loop.
 */
void lv_nativeforge_run(void);

/**
 * One iteration of the main loop: handles input, runs LVGL timers and redraws what
 * changed. Waits at most @p max_wait_ms when nothing needs to be drawn.
 * @return false once the app is closing.
 */
bool lv_nativeforge_step(uint32_t max_wait_ms);

/** Screen density reported by Android: 1.0 at 160 DPI, about 2.6 on a 420 DPI phone. */
float lv_nativeforge_density(void);

/** The scale passed to lv_nativeforge_init() after resolving LV_NATIVEFORGE_SCALE_AUTO. */
float lv_nativeforge_scale(void);

/**
 * Montserrat with letters @p dp density-independent pixels tall, at any scale: a built-in
 * font up to 48 screen pixels, larger sizes rendered from a TrueType copy of the same font
 * (made once per size and kept). Call it after lv_nativeforge_init(). LV_SYMBOL_* icons
 * are available at every size, up to 48 pixels.
 */
const lv_font_t * lv_nativeforge_font(int32_t dp);

/**
 * A folder where the program can keep its own files (settings, saves), created if needed.
 * On Android it is private to the app; it ends with a '/'. Free it with SDL_free().
 * Returns NULL if no such folder can be created.
 */
char * lv_nativeforge_data_dir(const char * app_name);

#ifdef __cplusplus
}
#endif

#endif /* LV_NATIVEFORGE_H */
