#include "window_frame.h"

#ifndef _WIN32

namespace square {

intptr_t hwndFromAwtWindow(JNIEnv*, jobject) {
    return 0;
}

bool installWindowFrame(intptr_t, const FrameLayout&) {
    return false;
}

void updateWindowFrame(intptr_t, const FrameLayout&) {}

void uninstallWindowFrame(intptr_t) {}

} // namespace square

#endif // !_WIN32
