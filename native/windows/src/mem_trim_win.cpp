#include "mem_trim.h"

#ifdef _WIN32

#include <windows.h>
#include <psapi.h>

#include <algorithm>
#include <vector>

namespace square {

void configureAllocatorForLowMemory() {
    // Win32 LFH is enabled by default for modern heaps; no extra startup hook needed.
}

void trimProcessMemory() {
    // Compact all Win32 process heaps so free tail blocks can decommit before trimming the working set.
    const DWORD count = GetProcessHeaps(0, nullptr);
    if (count > 0) {
        std::vector<HANDLE> heaps(count);
        const DWORD got = GetProcessHeaps(count, heaps.data());
        const DWORD limit = std::min(count, got);
        for (DWORD i = 0; i < limit; ++i) {
            if (heaps[i]) {
                HeapCompact(heaps[i], 0);
            }
        }
    }

    const HANDLE self = GetCurrentProcess();
    SetProcessWorkingSetSizeEx(self, static_cast<SIZE_T>(-1), static_cast<SIZE_T>(-1), 0);
    EmptyWorkingSet(self);
}

} // namespace square

#endif // _WIN32
