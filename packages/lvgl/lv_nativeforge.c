/*
 * lv_nativeforge: runs LVGL full screen on Android through SDL2. See lv_nativeforge.h.
 * Part of NativeForge's "lvgl" package. SPDX-License-Identifier: Zlib
 */
#include "lv_nativeforge.h"

#include <SDL.h>

static float density = 1.0f;
static float scale = 1.0f;
static SDL_atomic_t quit_requested;
static SDL_atomic_t redraw_requested;

static void log_to_sdl(lv_log_level_t level, const char * message)
{
    SDL_LogPriority priority = level >= LV_LOG_LEVEL_ERROR ? SDL_LOG_PRIORITY_ERROR
                               : level == LV_LOG_LEVEL_WARN ? SDL_LOG_PRIORITY_WARN
                               : SDL_LOG_PRIORITY_INFO;
    SDL_LogMessage(SDL_LOG_CATEGORY_APPLICATION, priority, "LVGL: %s", message);
}

/*
 * Runs on the thread that queues the event (on Android, the activity's Java thread), so it
 * only sets flags. SDL_QUIT is kept away from LVGL's SDL driver, which would de-initialize
 * LVGL from inside a timer; lv_nativeforge_step() reports it instead.
 */
static int SDLCALL filter_events(void * userdata, SDL_Event * event)
{
    LV_UNUSED(userdata);
    switch(event->type) {
        case SDL_QUIT:
        case SDL_APP_TERMINATING:
            SDL_AtomicSet(&quit_requested, 1);
            return 0;
        case SDL_APP_DIDENTERFOREGROUND:
        case SDL_RENDER_TARGETS_RESET:
        case SDL_RENDER_DEVICE_RESET:
            SDL_AtomicSet(&redraw_requested, 1);
            return 1;
        default:
            return 1;
    }
}

/* Default theme font for the text size Android calls 14sp, as close as the built-in sizes go. */
static const lv_font_t * body_font(float pixels_per_lvgl_pixel_over_density)
{
    static const lv_font_t * const fonts[] = {
        &lv_font_montserrat_12, &lv_font_montserrat_14, &lv_font_montserrat_16, &lv_font_montserrat_18,
        &lv_font_montserrat_20, &lv_font_montserrat_22, &lv_font_montserrat_24, &lv_font_montserrat_26,
        &lv_font_montserrat_28, &lv_font_montserrat_30, &lv_font_montserrat_32, &lv_font_montserrat_34,
        &lv_font_montserrat_36, &lv_font_montserrat_38, &lv_font_montserrat_40, &lv_font_montserrat_42,
        &lv_font_montserrat_44, &lv_font_montserrat_46, &lv_font_montserrat_48,
    };
    int size = (int)(14.0f * pixels_per_lvgl_pixel_over_density + 1.0f) & ~1; /* even sizes only */
    if(size < 12) size = 12;
    if(size > 48) size = 48;
    return fonts[(size - 12) / 2];
}

lv_display_t * lv_nativeforge_init(float requested_scale)
{
    if(!lv_is_initialized()) lv_init();
    lv_log_register_print_cb(log_to_sdl);

    /* Smooth scaling of the rendered image when one LVGL pixel covers several screen pixels. */
    SDL_SetHint(SDL_HINT_RENDER_SCALE_QUALITY, "1");
    if(SDL_Init(SDL_INIT_VIDEO | SDL_INIT_EVENTS) != 0) {
        SDL_Log("lv_nativeforge: SDL_Init failed: %s", SDL_GetError());
        return NULL;
    }
    SDL_SetEventFilter(filter_events, NULL);

    float ddpi = 0.0f;
    density = (SDL_GetDisplayDPI(0, &ddpi, NULL, NULL) == 0 && ddpi > 0.0f) ? ddpi / 160.0f : 1.0f;
    scale = requested_scale > 0.0f ? requested_scale : density;
    if(scale < 1.0f) scale = 1.0f;

    SDL_DisplayMode mode;
    int width = 800, height = 480;
    if(SDL_GetDesktopDisplayMode(0, &mode) == 0 && mode.w > 0 && mode.h > 0) {
        width = mode.w;
        height = mode.h;
    }
    lv_display_t * display = lv_sdl_window_create((int32_t)(width / scale), (int32_t)(height / scale));
    if(display == NULL) {
        SDL_Log("lv_nativeforge: could not create the SDL window: %s", SDL_GetError());
        return NULL;
    }
    if(scale != 1.0f) lv_sdl_window_set_zoom(display, scale);
    /* Android decides the window size (the whole screen); follow it. */
    int window_width = 0, window_height = 0;
    SDL_GetWindowSize(lv_sdl_window_get_window(display), &window_width, &window_height);
    if(window_width > 0 && window_height > 0) {
        lv_display_set_resolution(display, (int32_t)(window_width / scale), (int32_t)(window_height / scale));
    }

    /* LV_DPX() and the default theme's paddings follow the DPI of one LVGL pixel. */
    lv_display_set_dpi(display, (int32_t)(density * 160.0f / scale + 0.5f));
#if LV_USE_THEME_DEFAULT
    lv_theme_t * theme = lv_theme_default_init(display, lv_palette_main(LV_PALETTE_BLUE),
                                               lv_palette_main(LV_PALETTE_TEAL), false,
                                               body_font(density / scale));
    lv_display_set_theme(display, theme);
#endif

    /* The driver turns text input on, which pops up Android's keyboard at start. Hide it but
     * keep text events, so hardware keyboards still type. Use lv_keyboard for touch typing. */
    SDL_StopTextInput();
    SDL_EventState(SDL_TEXTINPUT, SDL_ENABLE);

    lv_indev_t * pointer = lv_sdl_mouse_create();
    lv_indev_set_display(pointer, display);
    lv_indev_t * wheel = lv_sdl_mousewheel_create();
    lv_indev_set_display(wheel, display);
    lv_indev_t * keyboard = lv_sdl_keyboard_create();
    lv_indev_set_display(keyboard, display);
    /* Widgets join the default group, so hardware keys reach the focused text area. */
    lv_group_t * group = lv_group_get_default();
    if(group == NULL) {
        group = lv_group_create();
        lv_group_set_default(group);
    }
    lv_indev_set_group(keyboard, group);
    lv_indev_set_group(wheel, group);
    return display;
}

bool lv_nativeforge_step(uint32_t max_wait_ms)
{
    if(SDL_AtomicGet(&quit_requested)) return false;
    if(SDL_AtomicSet(&redraw_requested, 0)) {
        lv_obj_invalidate(lv_screen_active());
        lv_obj_invalidate(lv_layer_top());
        lv_obj_invalidate(lv_layer_sys());
    }
    uint32_t wait = lv_timer_handler();
    if(SDL_AtomicGet(&quit_requested)) return false;
    if(wait > max_wait_ms) wait = max_wait_ms;
    /* Sleep until the next LVGL timer is due, waking up early for input. */
    if(wait > 0) SDL_WaitEventTimeout(NULL, (int)wait);
    return !SDL_AtomicGet(&quit_requested);
}

void lv_nativeforge_run(void)
{
    while(lv_nativeforge_step(LV_DEF_REFR_PERIOD)) {
    }
}

float lv_nativeforge_density(void)
{
    return density;
}

float lv_nativeforge_scale(void)
{
    return scale;
}

char * lv_nativeforge_data_dir(const char * app_name)
{
    return SDL_GetPrefPath("nativeforge", app_name != NULL ? app_name : "app");
}
