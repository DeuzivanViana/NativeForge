/*
 * lv_nativeforge_font(): Montserrat at a size given in dp. See lv_nativeforge.h.
 * Part of NativeForge's "lvgl" package. SPDX-License-Identifier: Zlib
 *
 * In its own file so that only programs calling lv_nativeforge_font() link the embedded
 * Montserrat-Medium.ttf (about 240 KB), which is generated into lv_nativeforge_ttf.c from
 * LVGL's sources by packages/build-android-libs.sh.
 */
#include "lv_nativeforge.h"

extern const unsigned char lv_nativeforge_montserrat_ttf[];
extern const unsigned int lv_nativeforge_montserrat_ttf_len;

/* LVGL's built-in Montserrat sizes enabled in lv_conf.h: 12 to 48, even sizes only. */
static const lv_font_t * builtin_font(int32_t pixels)
{
    static const lv_font_t * const fonts[] = {
        &lv_font_montserrat_12, &lv_font_montserrat_14, &lv_font_montserrat_16, &lv_font_montserrat_18,
        &lv_font_montserrat_20, &lv_font_montserrat_22, &lv_font_montserrat_24, &lv_font_montserrat_26,
        &lv_font_montserrat_28, &lv_font_montserrat_30, &lv_font_montserrat_32, &lv_font_montserrat_34,
        &lv_font_montserrat_36, &lv_font_montserrat_38, &lv_font_montserrat_40, &lv_font_montserrat_42,
        &lv_font_montserrat_44, &lv_font_montserrat_46, &lv_font_montserrat_48,
    };
    int32_t size = (pixels + 1) & ~1;
    if(size < 12) size = 12;
    if(size > 48) size = 48;
    return fonts[(size - 12) / 2];
}

const lv_font_t * lv_nativeforge_font(int32_t dp)
{
    /* Larger sizes are rendered from the TrueType file, one font per size, kept for the
     * program's lifetime (programs use a handful of sizes). */
    static struct {
        int32_t pixels;
        lv_font_t * font;
    } scalable[16];

    float pixels_per_dp = lv_nativeforge_density() / lv_nativeforge_scale();
    int32_t pixels = (int32_t)((float)dp * pixels_per_dp + 0.5f);
    if(pixels <= 48) return builtin_font(pixels);

    for(size_t i = 0; i < sizeof(scalable) / sizeof(scalable[0]); i++) {
        if(scalable[i].pixels == pixels) return scalable[i].font;
        if(scalable[i].font != NULL) continue;
        lv_font_t * font = lv_tiny_ttf_create_data(lv_nativeforge_montserrat_ttf,
                                                   lv_nativeforge_montserrat_ttf_len, pixels);
        if(font == NULL) break;
        /* The TrueType file has letters only: LV_SYMBOL_* icons come from the built-in font. */
        font->fallback = &lv_font_montserrat_48;
        scalable[i].pixels = pixels;
        scalable[i].font = font;
        return font;
    }
    return builtin_font(pixels);
}
