#include "window_frame.h"

#ifdef _WIN32

#include <windows.h>
#include <windowsx.h>
#include <commctrl.h>
#include <dwmapi.h>
#include <jni.h>
#include <jawt.h>
#include <jawt_md.h>

#include <algorithm>
#include <mutex>
#include <unordered_map>

#ifndef DWMWA_WINDOW_CORNER_PREFERENCE
#define DWMWA_WINDOW_CORNER_PREFERENCE 33
#endif
#ifndef DWMWCP_ROUND
#define DWMWCP_ROUND 2
#endif

namespace square {

namespace {

constexpr UINT_PTR kSubclassId = 0x53515541; // 'SQUA'

struct WindowFrameState {
    FrameLayout layout;
    WPARAM pressedNcButton = 0;
};

std::mutex gFramesMu;
std::unordered_map<HWND, WindowFrameState*> gFrames;

bool contains(const FrameRect& r, int32_t x, int32_t y) {
    return r.right > r.left && r.bottom > r.top &&
           x >= r.left && x < r.right && y >= r.top && y < r.bottom;
}

LRESULT hitTestPoint(HWND hwnd, const FrameLayout& layout, int screenX, int screenY) {
    RECT wr{};
    if (!GetWindowRect(hwnd, &wr)) return HTCLIENT;

    const bool maximized = IsZoomed(hwnd) != FALSE;
    const int32_t border = std::max<int32_t>(4, layout.resizeBorderPx);
    if (!maximized) {
        const bool left = screenX >= wr.left && screenX < wr.left + border;
        const bool right = screenX >= wr.right - border && screenX < wr.right;
        const bool top = screenY >= wr.top && screenY < wr.top + border;
        const bool bottom = screenY >= wr.bottom - border && screenY < wr.bottom;
        if (top && left) return HTTOPLEFT;
        if (top && right) return HTTOPRIGHT;
        if (bottom && left) return HTBOTTOMLEFT;
        if (bottom && right) return HTBOTTOMRIGHT;
        if (top) return HTTOP;
        if (bottom) return HTBOTTOM;
        if (left) return HTLEFT;
        if (right) return HTRIGHT;
    }

    POINT pt{screenX, screenY};
    ScreenToClient(hwnd, &pt);
    const int32_t cx = pt.x;
    const int32_t cy = pt.y;

    if (contains(layout.closeButton, cx, cy)) return HTCLOSE;
    if (contains(layout.maxButton, cx, cy)) return HTMAXBUTTON;
    if (contains(layout.minButton, cx, cy)) return HTMINBUTTON;

    for (const auto& r : layout.interactiveRects) {
        if (contains(r, cx, cy)) return HTCLIENT;
    }

    if (cy >= 0 && cy < layout.captionHeightPx) {
        return HTCAPTION;
    }

    return HTCLIENT;
}

LRESULT CALLBACK frameSubclassProc(
    HWND hwnd,
    UINT msg,
    WPARAM wParam,
    LPARAM lParam,
    UINT_PTR uIdSubclass,
    DWORD_PTR dwRefData) {

    auto* state = reinterpret_cast<WindowFrameState*>(dwRefData);

    switch (msg) {
    case WM_NCCALCSIZE: {
        if (wParam == TRUE && lParam != 0) {
            auto* params = reinterpret_cast<NCCALCSIZE_PARAMS*>(lParam);
            if (IsZoomed(hwnd)) {
                HMONITOR mon = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
                MONITORINFO mi{};
                mi.cbSize = sizeof(mi);
                if (GetMonitorInfoW(mon, &mi)) {
                    params->rgrc[0] = mi.rcWork;
                    if (EqualRect(&mi.rcWork, &mi.rcMonitor)) {
                        // Leave 1px at bottom when taskbar is auto-hidden so it can still reveal.
                        params->rgrc[0].bottom -= 1;
                    }
                }
            }
            return 0;
        }
        break;
    }

    case WM_NCHITTEST: {
        if (state) {
            FrameLayout snapshot;
            {
                std::lock_guard<std::mutex> lock(gFramesMu);
                snapshot = state->layout;
            }
            const int sx = GET_X_LPARAM(lParam);
            const int sy = GET_Y_LPARAM(lParam);
            const LRESULT hit = hitTestPoint(hwnd, snapshot, sx, sy);
            if (hit != HTCLIENT) return hit;
        }
        break;
    }

    case WM_NCLBUTTONDOWN: {
        if (state && (wParam == HTMINBUTTON || wParam == HTMAXBUTTON || wParam == HTCLOSE)) {
            // Suppress DefWindowProc's legacy Win32 caption button draw loop; handle on button-up.
            state->pressedNcButton = wParam;
            return 0;
        }
        break;
    }

    case WM_NCLBUTTONUP: {
        if (state && (wParam == HTMINBUTTON || wParam == HTMAXBUTTON || wParam == HTCLOSE)) {
            const WPARAM pressed = state->pressedNcButton;
            state->pressedNcButton = 0;
            if (pressed == wParam) {
                WPARAM cmd = 0;
                if (wParam == HTMINBUTTON) cmd = SC_MINIMIZE;
                else if (wParam == HTMAXBUTTON) cmd = IsZoomed(hwnd) ? SC_RESTORE : SC_MAXIMIZE;
                else if (wParam == HTCLOSE) cmd = SC_CLOSE;
                if (cmd != 0) {
                    PostMessageW(hwnd, WM_SYSCOMMAND, cmd, 0);
                }
            }
            return 0;
        }
        break;
    }

    case WM_NCRBUTTONUP: {
        if (wParam == HTCAPTION) {
            HMENU sysMenu = GetSystemMenu(hwnd, FALSE);
            if (sysMenu) {
                const int sx = GET_X_LPARAM(lParam);
                const int sy = GET_Y_LPARAM(lParam);
                const BOOL zoomed = IsZoomed(hwnd);
                EnableMenuItem(sysMenu, SC_RESTORE, MF_BYCOMMAND | (zoomed ? MF_ENABLED : MF_GRAYED));
                EnableMenuItem(sysMenu, SC_MOVE, MF_BYCOMMAND | (!zoomed ? MF_ENABLED : MF_GRAYED));
                EnableMenuItem(sysMenu, SC_SIZE, MF_BYCOMMAND | (!zoomed ? MF_ENABLED : MF_GRAYED));
                EnableMenuItem(sysMenu, SC_MINIMIZE, MF_BYCOMMAND | MF_ENABLED);
                EnableMenuItem(sysMenu, SC_MAXIMIZE, MF_BYCOMMAND | (!zoomed ? MF_ENABLED : MF_GRAYED));
                const UINT cmd = TrackPopupMenu(
                    sysMenu,
                    TPM_LEFTALIGN | TPM_TOPALIGN | TPM_RETURNCMD | TPM_RIGHTBUTTON,
                    sx, sy, 0, hwnd, nullptr);
                if (cmd != 0) {
                    PostMessageW(hwnd, WM_SYSCOMMAND, cmd, 0);
                }
            }
            return 0;
        }
        break;
    }

    case WM_GETMINMAXINFO: {
        if (state && lParam != 0) {
            auto* mmi = reinterpret_cast<MINMAXINFO*>(lParam);
            HMONITOR mon = MonitorFromWindow(hwnd, MONITOR_DEFAULTTONEAREST);
            MONITORINFO mi{};
            mi.cbSize = sizeof(mi);
            if (GetMonitorInfoW(mon, &mi)) {
                mmi->ptMaxPosition.x = mi.rcWork.left - mi.rcMonitor.left;
                mmi->ptMaxPosition.y = mi.rcWork.top - mi.rcMonitor.top;
                mmi->ptMaxSize.x = mi.rcWork.right - mi.rcWork.left;
                mmi->ptMaxSize.y = mi.rcWork.bottom - mi.rcWork.top;
            }
            int32_t minW = 0;
            int32_t minH = 0;
            {
                std::lock_guard<std::mutex> lock(gFramesMu);
                minW = state->layout.minWidthPx;
                minH = state->layout.minHeightPx;
            }
            if (minW > 0) mmi->ptMinTrackSize.x = minW;
            if (minH > 0) mmi->ptMinTrackSize.y = minH;
            return 0;
        }
        break;
    }

    case WM_NCDESTROY: {
        RemoveWindowSubclass(hwnd, &frameSubclassProc, uIdSubclass);
        std::lock_guard<std::mutex> lock(gFramesMu);
        auto it = gFrames.find(hwnd);
        if (it != gFrames.end()) {
            delete it->second;
            gFrames.erase(it);
        }
        break;
    }

    default:
        break;
    }

    return DefSubclassProc(hwnd, msg, wParam, lParam);
}

void applyDwmChrome(HWND hwnd) {
    // Restore DWM drop shadow on undecorated frame.
    const MARGINS shadowMargins{0, 0, 0, 1};
    DwmExtendFrameIntoClientArea(hwnd, &shadowMargins);

    // Windows 11 rounded corners (ignored safely on Windows 10).
    const DWORD cornerPref = DWMWCP_ROUND;
    DwmSetWindowAttribute(hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, &cornerPref, sizeof(cornerPref));
}

} // namespace

intptr_t hwndFromAwtWindow(JNIEnv* env, jobject awtWindow) {
    if (!env || !awtWindow) return 0;

    using PfnGetAwt = jboolean(JNICALL*)(JNIEnv*, JAWT*);
    HMODULE jawtMod = GetModuleHandleW(L"jawt.dll");
    if (!jawtMod) jawtMod = LoadLibraryW(L"jawt.dll");
    if (!jawtMod) return 0;

    auto getAwt = reinterpret_cast<PfnGetAwt>(GetProcAddress(jawtMod, "JAWT_GetAWT"));
    if (!getAwt) return 0;

    JAWT awt{};
    awt.version = JAWT_VERSION_1_4;
    if (getAwt(env, &awt) == JNI_FALSE || !awt.GetDrawingSurface) return 0;

    JAWT_DrawingSurface* ds = awt.GetDrawingSurface(env, awtWindow);
    if (!ds) return 0;

    intptr_t result = 0;
    if ((ds->Lock(ds) & JAWT_LOCK_ERROR) == 0) {
        JAWT_DrawingSurfaceInfo* dsi = ds->GetDrawingSurfaceInfo(ds);
        if (dsi && dsi->platformInfo) {
            auto* winInfo = reinterpret_cast<JAWT_Win32DrawingSurfaceInfo*>(dsi->platformInfo);
            result = reinterpret_cast<intptr_t>(winInfo->hwnd);
        }
        if (dsi) ds->FreeDrawingSurfaceInfo(dsi);
        ds->Unlock(ds);
    }
    awt.FreeDrawingSurface(ds);
    return result;
}

bool installWindowFrame(intptr_t hwndPtr, const FrameLayout& layout) {
    HWND hwnd = reinterpret_cast<HWND>(hwndPtr);
    if (!hwnd || !IsWindow(hwnd)) return false;

    WindowFrameState* state = nullptr;
    {
        std::lock_guard<std::mutex> lock(gFramesMu);
        auto it = gFrames.find(hwnd);
        if (it != gFrames.end()) {
            it->second->layout = layout;
            return true;
        }
        state = new WindowFrameState{layout, 0};
        gFrames[hwnd] = state;
    }

    // Add standard resize/snap/min/max styles to AWT undecorated window so Aero Snap and
    // Windows 11 Snap Layouts work while WM_NCCALCSIZE removes the OS titlebar.
    LONG_PTR style = GetWindowLongPtrW(hwnd, GWL_STYLE);
    style |= WS_THICKFRAME | WS_MAXIMIZEBOX | WS_MINIMIZEBOX | WS_CAPTION | WS_SYSMENU;
    SetWindowLongPtrW(hwnd, GWL_STYLE, style);

    if (!SetWindowSubclass(hwnd, &frameSubclassProc, kSubclassId, reinterpret_cast<DWORD_PTR>(state))) {
        std::lock_guard<std::mutex> lock(gFramesMu);
        gFrames.erase(hwnd);
        delete state;
        return false;
    }

    applyDwmChrome(hwnd);
    SetWindowPos(
        hwnd, nullptr, 0, 0, 0, 0,
        SWP_FRAMECHANGED | SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOACTIVATE);
    return true;
}

void updateWindowFrame(intptr_t hwndPtr, const FrameLayout& layout) {
    HWND hwnd = reinterpret_cast<HWND>(hwndPtr);
    if (!hwnd) return;
    std::lock_guard<std::mutex> lock(gFramesMu);
    auto it = gFrames.find(hwnd);
    if (it != gFrames.end()) {
        it->second->layout = layout;
    }
}

void uninstallWindowFrame(intptr_t hwndPtr) {
    HWND hwnd = reinterpret_cast<HWND>(hwndPtr);
    if (!hwnd) return;
    RemoveWindowSubclass(hwnd, &frameSubclassProc, kSubclassId);
    std::lock_guard<std::mutex> lock(gFramesMu);
    auto it = gFrames.find(hwnd);
    if (it != gFrames.end()) {
        delete it->second;
        gFrames.erase(it);
    }
}

} // namespace square

#endif // _WIN32
