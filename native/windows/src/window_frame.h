#pragma once

#include <jni.h>
#include <cstdint>
#include <vector>

namespace square {

struct FrameRect {
    int32_t left = 0;
    int32_t top = 0;
    int32_t right = 0;
    int32_t bottom = 0;
};

struct FrameLayout {
    int32_t captionHeightPx = 0;
    int32_t resizeBorderPx = 6;
    int32_t minWidthPx = 940;
    int32_t minHeightPx = 620;
    FrameRect minButton;
    FrameRect maxButton;
    FrameRect closeButton;
    std::vector<FrameRect> interactiveRects;
};

/**
 * Extracts the native window handle (HWND on Windows, 0 elsewhere) from an AWT Window/Component
 * using JAWT without JNA or --add-opens reflection.
 */
intptr_t hwndFromAwtWindow(JNIEnv* env, jobject awtWindow);

/** Installs the native frameless window subclass on [hwndPtr]. No-op returning false outside Windows. */
bool installWindowFrame(intptr_t hwndPtr, const FrameLayout& layout);

/** Updates titlebar caption height, button rectangles, and interactive control exclusions. */
void updateWindowFrame(intptr_t hwndPtr, const FrameLayout& layout);

/** Removes the subclass if installed. */
void uninstallWindowFrame(intptr_t hwndPtr);

} // namespace square
