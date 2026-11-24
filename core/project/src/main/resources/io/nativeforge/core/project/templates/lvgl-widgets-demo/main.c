#include <SDL.h>            /* renames main() to SDL_main, which the SDL2 host activity calls */
#include "lvgl.h"
#include "lv_nativeforge.h"
#include "lvgl/demos/lv_demos.h"

/*
 * LVGL's own widgets demo: a profile form, an analytics dashboard with charts and a shop
 * page, in tabs. Its source is in LVGL's repository (demos/widgets) and is a good place to
 * see how real screens are put together: https://github.com/lvgl/lvgl/tree/master/demos/widgets
 *
 * Try LV_NATIVEFORGE_SCALE_PIXELS instead of LV_NATIVEFORGE_SCALE_AUTO to see the demo at the
 * screen's full resolution (smaller, sharper).
 */
int main(int argc, char *argv[])
{
    LV_UNUSED(argc);
    LV_UNUSED(argv);
    if (lv_nativeforge_init(LV_NATIVEFORGE_SCALE_AUTO) == NULL) return 1;

    lv_demo_widgets();

    lv_nativeforge_run();
    return 0;
}
