/**
 * LVGL configuration of NativeForge's bundled "lvgl" package.
 *
 * The library in the package was compiled with exactly these settings, so programs must
 * use this file as it is (it is installed as include/lv_conf.h and found automatically).
 * Every option not listed here keeps LVGL's default (see lv_conf_internal.h).
 */
#ifndef LV_CONF_H
#define LV_CONF_H

/* 32-bit colors: no banding in gradients, and a format SDL's textures take as they are. */
#define LV_COLOR_FORMAT_DEFAULT LV_COLOR_FORMAT_XRGB8888

/* Memory, strings and printf from the C library (Android's bionic). */
#define LV_USE_STDLIB_MALLOC LV_STDLIB_CLIB
#define LV_USE_STDLIB_STRING LV_STDLIB_CLIB
#define LV_USE_STDLIB_SPRINTF LV_STDLIB_CLIB
#define LV_USE_FLOAT 1

/* LVGL runs on the program's thread; the SDL driver provides ticks and delays. */
#define LV_USE_OS LV_OS_NONE
#define LV_DEF_REFR_PERIOD 16
/* Phones have 300 to 600 DPI; programs set the real value with lv_display_set_dpi(). */
#define LV_DPI_DEF 160

/* Display and input through SDL2 (the IDE's SDL2 host activity). */
#define LV_USE_SDL 1
#define LV_SDL_INCLUDE_PATH <SDL.h>
#define LV_SDL_RENDER_MODE LV_DISPLAY_RENDER_MODE_DIRECT
#define LV_SDL_BUF_COUNT 1
#define LV_SDL_ACCELERATED 1
#define LV_SDL_FULLSCREEN 1
#define LV_SDL_DIRECT_EXIT 0 /* lv_nativeforge_step() reports SDL_QUIT instead */
#define LV_SDL_MOUSEWHEEL_MODE LV_SDL_MOUSEWHEEL_MODE_ENCODER

/* Software renderer with shadows, rounded corners and gradients. */
#define LV_USE_DRAW_SW 1
#define LV_DRAW_SW_COMPLEX 1
#define LV_USE_DRAW_SW_COMPLEX_GRADIENTS 1
#define LV_DRAW_SW_SHADOW_CACHE_SIZE 8
#define LV_DRAW_SW_CIRCLE_CACHE_SIZE 8
#define LV_USE_MATRIX 1
#define LV_USE_SNAPSHOT 1

/* Logging goes to lv_log_register_print_cb() (the templates forward it to logcat). */
#define LV_USE_LOG 1
#define LV_LOG_LEVEL LV_LOG_LEVEL_WARN
#define LV_LOG_PRINTF 0

/* Caches for decoded images. */
#define LV_CACHE_DEF_SIZE (4 * 1024 * 1024)
#define LV_IMAGE_HEADER_CACHE_DEF_CNT 16

/* Built-in fonts. lv_nativeforge_font(dp) picks one for the screen density (see the templates). */
#define LV_FONT_MONTSERRAT_12 1
#define LV_FONT_MONTSERRAT_14 1
#define LV_FONT_MONTSERRAT_16 1
#define LV_FONT_MONTSERRAT_18 1
#define LV_FONT_MONTSERRAT_20 1
#define LV_FONT_MONTSERRAT_22 1
#define LV_FONT_MONTSERRAT_24 1
#define LV_FONT_MONTSERRAT_26 1
#define LV_FONT_MONTSERRAT_28 1
#define LV_FONT_MONTSERRAT_30 1
#define LV_FONT_MONTSERRAT_32 1
#define LV_FONT_MONTSERRAT_34 1
#define LV_FONT_MONTSERRAT_36 1
#define LV_FONT_MONTSERRAT_38 1
#define LV_FONT_MONTSERRAT_40 1
#define LV_FONT_MONTSERRAT_42 1
#define LV_FONT_MONTSERRAT_44 1
#define LV_FONT_MONTSERRAT_46 1
#define LV_FONT_MONTSERRAT_48 1
#define LV_FONT_DEFAULT &lv_font_montserrat_14
/* TrueType fonts at any size, from a file (for example "A:assets/MyFont.ttf") or memory. */
#define LV_USE_TINY_TTF 1
#define LV_TINY_TTF_FILE_SUPPORT 1

/* Files: "A:path" opens path with fopen(), relative to the project folder when run from the IDE. */
#define LV_USE_FS_STDIO 1
#define LV_FS_STDIO_LETTER 'A'
#define LV_FS_STDIO_PATH ""
#define LV_FS_STDIO_CACHE_SIZE 0

/* Image decoders: PNG, JPEG, BMP and GIF animations. */
#define LV_USE_LODEPNG 1
#define LV_USE_TJPGD 1
#define LV_USE_BMP 1
#define LV_USE_GIF 1

/* Extra widgets and helpers. */
#define LV_USE_QRCODE 1
#define LV_USE_BARCODE 1
#define LV_USE_OBSERVER 1
#define LV_USE_IME_PINYIN 0
#define LV_USE_FILE_EXPLORER 1
#define LV_FILE_EXPLORER_PATH_MAX_LEN 256
#define LV_FILE_EXPLORER_QUICK_ACCESS 0
/* The FPS/CPU overlay would show on every screen when enabled, so it is left off. */
#define LV_USE_SYSMON 0
#define LV_USE_PERF_MONITOR 0

/* Asserts that stop with a log message instead of corrupting memory. */
#define LV_USE_ASSERT_NULL 1
#define LV_USE_ASSERT_MALLOC 1

/* lv_example_*() functions are not included; the widgets demo is (lv_demo_widgets()). */
#define LV_BUILD_EXAMPLES 0
#define LV_BUILD_DEMOS 1
#define LV_USE_DEMO_WIDGETS 1

#endif /* LV_CONF_H */
