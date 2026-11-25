#include "mem_trim.h"

#ifndef _WIN32

#include <cstdlib>
#ifdef __GLIBC__
#include <malloc.h>
#endif

namespace square {

void configureAllocatorForLowMemory() {
#if defined(__GLIBC__)
    // Cap per-thread glibc malloc arenas to limit 64 MB sub-heap sprawl under worker threads.
    mallopt(M_ARENA_MAX, 2);
    // Use mmap/munmap for >= 64 KB allocations (ONNX tensors, Skia buffers) so free() unmaps pages immediately.
    mallopt(M_MMAP_THRESHOLD, 65536);
#endif
}

void trimProcessMemory() {
#if defined(__GLIBC__)
    malloc_trim(0);
#endif
}

} // namespace square

#endif // !_WIN32
