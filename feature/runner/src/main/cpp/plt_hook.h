// Redirects calls that a loaded library makes through its PLT (procedure linkage table).
#pragma once

#include <stddef.h>

struct nf_plt_hook {
    const char* symbol; // the function's name
    void* replacement;  // what calls to it should reach instead
    void** original;    // receives the function the slot pointed at (once)
};

// Points every loaded library's PLT slots for the given symbols at their replacements.
// Returns how many slots were changed.
int nf_plt_hook_all(struct nf_plt_hook* hooks, size_t count);
