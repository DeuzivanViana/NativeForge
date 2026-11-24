/* Forced into LVGL's SDL driver by packages/build-android-libs.sh: the driver allocates
 * its frame buffers with aligned_alloc(), which bionic only has from API 28, and the
 * bundled libraries target API 24. */
#ifndef NATIVEFORGE_LVGL_ANDROID_COMPAT_H
#define NATIVEFORGE_LVGL_ANDROID_COMPAT_H
#if defined(__ANDROID__) && __ANDROID_API__ < 28
#include <stdlib.h>
static inline void *nf_lvgl_aligned_alloc(size_t alignment, size_t size)
{
    void *memory = NULL;
    return posix_memalign(&memory, alignment, size) == 0 ? memory : NULL;
}
#define aligned_alloc nf_lvgl_aligned_alloc
#endif
#endif
