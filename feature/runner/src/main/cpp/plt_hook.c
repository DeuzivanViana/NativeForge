// PLT hooking: a call from a shared library to a function in another library (or to an
// exported function of its own) goes through a slot in its global offset table, which
// the dynamic linker fills when the library loads. Changing that slot redirects the
// calls of that one library without touching any code. The slots are found from the
// library's dynamic section: DT_JMPREL lists one relocation per slot, naming its symbol.

#define _GNU_SOURCE
#include "plt_hook.h"

#include <elf.h>
#include <link.h>
#include <stdint.h>
#include <string.h>
#include <sys/mman.h>
#include <unistd.h>

#if defined(__LP64__)
#define NF_R_SYM(info) ELF64_R_SYM(info)
#else
#define NF_R_SYM(info) ELF32_R_SYM(info)
#endif

struct context {
    struct nf_plt_hook* hooks;
    size_t count;
    int patched;
};

// Bionic leaves the dynamic section's addresses relative to the load bias; glibc rewrites
// most of them to absolute addresses when it loads the library.
static uintptr_t address(uintptr_t base, uintptr_t value) {
    return value < base ? base + value : value;
}

static int patch(void** slot, struct nf_plt_hook* hook, uintptr_t relro_start, uintptr_t relro_end) {
    uintptr_t page = (uintptr_t)sysconf(_SC_PAGESIZE);
    uintptr_t start = (uintptr_t)slot & ~(page - 1);
    if (mprotect((void*)start, page, PROT_READ | PROT_WRITE) != 0) return 0;
    if (hook->original != NULL && *hook->original == NULL) *hook->original = *slot;
    *slot = hook->replacement;
    // Slots under RELRO (libraries linked with -z now) were read-only: make them so again.
    if (start >= (relro_start & ~(page - 1)) && start < relro_end) mprotect((void*)start, page, PROT_READ);
    return 1;
}

static int visit(struct dl_phdr_info* info, size_t size, void* data) {
    (void)size;
    struct context* ctx = data;
    uintptr_t base = info->dlpi_addr;
    const ElfW(Dyn)* dynamic = NULL;
    uintptr_t relro_start = 0, relro_end = 0;
    for (int i = 0; i < info->dlpi_phnum; i++) {
        const ElfW(Phdr)* ph = &info->dlpi_phdr[i];
        if (ph->p_type == PT_DYNAMIC) dynamic = (const ElfW(Dyn)*)(base + ph->p_vaddr);
        if (ph->p_type == PT_GNU_RELRO) {
            relro_start = base + ph->p_vaddr;
            relro_end = relro_start + ph->p_memsz;
        }
    }
    if (base == 0 || dynamic == NULL) return 0;

    const ElfW(Sym)* symbols = NULL;
    const char* strings = NULL;
    uintptr_t relocations = 0;
    size_t relocations_size = 0;
#if defined(__aarch64__) || defined(__x86_64__)
    int rela = 1;
#else
    int rela = 0;
#endif
    for (const ElfW(Dyn)* d = dynamic; d->d_tag != DT_NULL; d++) {
        switch (d->d_tag) {
            case DT_SYMTAB: symbols = (const ElfW(Sym)*)address(base, d->d_un.d_ptr); break;
            case DT_STRTAB: strings = (const char*)address(base, d->d_un.d_ptr); break;
            case DT_JMPREL: relocations = address(base, d->d_un.d_ptr); break;
            case DT_PLTRELSZ: relocations_size = d->d_un.d_val; break;
            case DT_PLTREL: rela = d->d_un.d_val == DT_RELA; break;
            default: break;
        }
    }
    if (symbols == NULL || strings == NULL || relocations == 0) return 0;

    size_t entry = rela ? sizeof(ElfW(Rela)) : sizeof(ElfW(Rel));
    for (size_t offset = 0; offset + entry <= relocations_size; offset += entry) {
        const ElfW(Rel)* relocation = (const ElfW(Rel)*)(relocations + offset); // Rela starts like Rel
        size_t symbol = NF_R_SYM(relocation->r_info);
        if (symbol == 0) continue;
        const char* name = strings + symbols[symbol].st_name;
        for (size_t h = 0; h < ctx->count; h++) {
            if (strcmp(name, ctx->hooks[h].symbol) != 0) continue;
            if (patch((void**)(base + relocation->r_offset), &ctx->hooks[h], relro_start, relro_end)) ctx->patched++;
        }
    }
    return 0;
}

int nf_plt_hook_all(struct nf_plt_hook* hooks, size_t count) {
    struct context ctx = { hooks, count, 0 };
    dl_iterate_phdr(visit, &ctx);
    return ctx.patched;
}
