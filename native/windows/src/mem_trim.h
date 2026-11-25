#pragma once

namespace square {

/**
 * Configures allocator tuning for bounded background RSS (called at engine startup).
 */
void configureAllocatorForLowMemory();

/**
 * Compacts native allocator heaps / arenas and returns freed pages to the OS
 * (glibc malloc_trim on Linux; HeapCompact + WorkingSet trim on Windows).
 */
void trimProcessMemory();

} // namespace square
