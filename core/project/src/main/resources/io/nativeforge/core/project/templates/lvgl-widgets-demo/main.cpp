#include <SDL.h>            // renames main() to SDL_main, which the SDL2 host activity calls
#include "lvgl.h"
#include "lv_nativeforge.h"
#include "lvgl/demos/lv_demos.h"

/*
 * LVGL's own widgets demo: a profile form, an analytics dashboard with charts and a shop
 * page, in tabs. Its source is in LVGL's repository (demos/widgets) and is a good place to
 * see how real screens are put together: https://github.com/lvgl/lvgl/tree/master/demos/widgets
 *
 * LVGL is written in C, but its headers declare everything extern "C", so the same calls work
 * from C++. Add your own C++ code (classes, the standard library) around them.
 *
 * The demo's phone layout needs about 400 LVGL pixels across, so on small phones (or with a
 * large display size setting) the screen is zoomed out a little to make room. Try
 * LV_NATIVEFORGE_SCALE_PIXELS to see the demo at the screen's full resolution (smaller, sharper).
 */
int main(int, char *[])
{
    if (lv_nativeforge_init(LV_NATIVEFORGE_SCALE_MIN_SIZE(400)) == nullptr) return 1;

    lv_demo_widgets();

    lv_nativeforge_run();
    return 0;
}
