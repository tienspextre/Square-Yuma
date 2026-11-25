cmake_minimum_required(VERSION 3.24)
project(square_win_native LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(MSVC)
    set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")
    add_compile_definitions(_USE_MATH_DEFINES NOMINMAX WIN32_LEAN_AND_MEAN)
endif()

option(SQUARE_BUILD_TESTS "Build native CTest unit tests" ON)

include(FetchContent)
# libogg v1.3.5 declares cmake_minimum_required(<3.5), which CMake 4 rejects.
set(CMAKE_POLICY_VERSION_MINIMUM 3.5)

# --- Third-party: libogg, libopus, opusfile (FetchContent pinned tags) ---
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(INSTALL_DOCS OFF CACHE BOOL "" FORCE)
set(INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(INSTALL_CMAKE_PACKAGE_MODULE OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    ogg
    GIT_REPOSITORY https://github.com/xiph/ogg.git
    GIT_TAG v1.3.5
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(ogg)

set(OPUS_BUILD_SHARED_LIBRARY OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_TESTING OFF CACHE BOOL "" FORCE)
set(OPUS_BUILD_PROGRAMS OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_PKG_CONFIG_MODULE OFF CACHE BOOL "" FORCE)
set(OPUS_INSTALL_CMAKE_CONFIG_MODULE OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    opus
    GIT_REPOSITORY https://github.com/xiph/opus.git
    GIT_TAG v1.5.2
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(opus)

FetchContent_Declare(
    opusfile
    GIT_REPOSITORY https://github.com/xiph/opusfile.git
    GIT_TAG v0.12
    GIT_SHALLOW TRUE
    SOURCE_SUBDIR cmake_no_build
)
FetchContent_MakeAvailable(opusfile)

add_library(opusfile STATIC
    ${opusfile_SOURCE_DIR}/src/info.c
    ${opusfile_SOURCE_DIR}/src/internal.c
    ${opusfile_SOURCE_DIR}/src/opusfile.c
    ${opusfile_SOURCE_DIR}/src/stream.c
)
target_include_directories(opusfile PUBLIC
    ${opusfile_SOURCE_DIR}/include
    PRIVATE
    ${opusfile_SOURCE_DIR}/src
    ${ogg_SOURCE_DIR}/include
    ${ogg_BINARY_DIR}/include
    ${opus_SOURCE_DIR}/include
)
target_link_libraries(opusfile PUBLIC opus ogg)

# --- Third-party: vendored libebur128 (MIT) ---
add_library(ebur128 STATIC
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libebur128/ebur128.c
)
target_include_directories(ebur128 PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libebur128
)

# --- Reused by path: Bungee from native-dsp/bungee ---
set(BUNGEE_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../native-dsp/bungee)
if(NOT EXISTS ${BUNGEE_DIR}/bungee/Bungee.h)
    message(FATAL_ERROR "Bungee sources missing at ${BUNGEE_DIR}")
endif()

add_library(pffft STATIC
    ${BUNGEE_DIR}/submodules/pffft/pffft.c
    ${BUNGEE_DIR}/submodules/pffft/fftpack.c
)
if(NOT MSVC)
    target_compile_options(pffft PRIVATE -ffast-math -fno-finite-math-only -fno-exceptions)
endif()

file(GLOB BUNGEE_SOURCES ${BUNGEE_DIR}/src/*.cpp)
add_library(bungee STATIC ${BUNGEE_SOURCES})
target_include_directories(bungee PUBLIC
    ${BUNGEE_DIR}
    ${BUNGEE_DIR}/submodules
    ${BUNGEE_DIR}/submodules/eigen
)
target_compile_definitions(bungee PUBLIC
    BUNGEE_SELF_TEST=0
    eigen_assert=BUNGEE_ASSERT1
    BUNGEE_VERSION="0.0.0"
)
target_link_libraries(bungee PUBLIC pffft)

# --- Reused by path: app/src/main/cpp/analyzer ---
set(APP_CPP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../app/src/main/cpp)
add_library(yuma_analyzer_core STATIC
    ${APP_CPP_DIR}/analyzer/audio_analysis.cpp
    ${APP_CPP_DIR}/analyzer/tempo_analysis.cpp
    ${APP_CPP_DIR}/analyzer/resampler.cpp
    ${APP_CPP_DIR}/analyzer/mel_spectrogram.cpp
    ${APP_CPP_DIR}/analyzer/vocal_spectrogram.cpp
)
target_include_directories(yuma_analyzer_core PUBLIC ${APP_CPP_DIR}/analyzer)
if(NOT MSVC)
    target_compile_options(yuma_analyzer_core PRIVATE -O3)
endif()

# --- Platform HTTP & platform services ---
set(PLATFORM_SOURCES)
set(PLATFORM_LIBS)
if(WIN32)
    list(APPEND PLATFORM_SOURCES
        src/http_stream_win.cpp
        src/mf_aac_decoder.cpp
        src/smtc_win.cpp
    )
    list(APPEND PLATFORM_LIBS
        winhttp
        mfplat
        mfreadwrite
        mfuuid
        propsys
        avrt
        ole32
        user32
        dwmapi
        comctl32
        psapi
        windowsapp
    )
else()
    find_package(CURL QUIET)
    if(CURL_FOUND)
        list(APPEND PLATFORM_SOURCES src/http_stream_curl.cpp)
        list(APPEND PLATFORM_LIBS CURL::libcurl)
        add_compile_definitions(SQUARE_HAS_CURL=1)
    else()
        list(APPEND PLATFORM_SOURCES src/http_stream_linux_stub.cpp)
    endif()
    list(APPEND PLATFORM_SOURCES src/smtc_stub.cpp)
    find_package(Threads REQUIRED)
    list(APPEND PLATFORM_LIBS Threads::Threads dl m)
endif()

# --- Core engine static library (shared between JNI DLL/SO and CTest binaries) ---
add_library(square_engine_core STATIC
    src/third_party_impl.cpp
    src/dsp_chain.cpp
    src/audio_stream.cpp
    src/decoder.cpp
    src/engine.cpp
    ${PLATFORM_SOURCES}
)
target_include_directories(square_engine_core PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/src
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/miniaudio
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/stb
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/libebur128
)
target_link_libraries(square_engine_core PUBLIC
    bungee
    ebur128
    opusfile
    yuma_analyzer_core
    ${PLATFORM_LIBS}
)
if(MSVC)
    target_compile_options(square_engine_core PRIVATE /O2 /EHsc /W3)
else()
    target_compile_options(square_engine_core PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter)
endif()

# --- JNI shared library: square_win_native ---
find_package(JNI QUIET)
if(JNI_FOUND)
    if(MSVC)
        set_source_files_properties(${APP_CPP_DIR}/stretch.cpp PROPERTIES COMPILE_FLAGS "/FIcmath /FIvector /FIlimits")
    else()
        set_source_files_properties(${APP_CPP_DIR}/stretch.cpp PROPERTIES COMPILE_FLAGS "-include cmath -include vector -include limits")
    endif()
    set(JNI_WINDOW_SRC)
    if(WIN32)
        list(APPEND JNI_WINDOW_SRC src/window_win.cpp src/mem_trim_win.cpp)
    else()
        list(APPEND JNI_WINDOW_SRC src/window_frame_stub.cpp src/mem_trim_linux.cpp)
    endif()
    add_library(square_win_native SHARED
        src/jni_bridge.cpp
        ${JNI_WINDOW_SRC}
        ${APP_CPP_DIR}/jni/analysis_jni.cpp
        ${APP_CPP_DIR}/jni/mel_jni.cpp
        ${APP_CPP_DIR}/jni/vocal_jni.cpp
        ${APP_CPP_DIR}/stretch.cpp
    )
    target_include_directories(square_win_native PRIVATE
        ${JNI_INCLUDE_DIRS}
        ${APP_CPP_DIR}/analyzer
    )
    target_link_libraries(square_win_native PRIVATE
        square_engine_core
    )
    if(MSVC)
        target_compile_options(square_win_native PRIVATE /O2 /GL /EHsc)
        target_link_options(square_win_native PRIVATE /LTCG)
    else()
        target_compile_options(square_win_native PRIVATE -O3 -fvisibility=hidden)
    endif()
else()
    message(WARNING "JNI not found; skipping square_win_native shared library target")
endif()

# --- Native CTest unit tests ---
if(SQUARE_BUILD_TESTS)
    enable_testing()

    add_executable(test_ring_buffer tests/test_ring_buffer.cpp)
    target_link_libraries(test_ring_buffer PRIVATE square_engine_core)
    add_test(NAME ring_buffer COMMAND test_ring_buffer)

    add_executable(test_dsp_chain tests/test_dsp_chain.cpp)
    target_link_libraries(test_dsp_chain PRIVATE square_engine_core)
    add_test(NAME dsp_chain COMMAND test_dsp_chain)

    add_executable(test_decoders tests/test_decoders.cpp)
    target_link_libraries(test_decoders PRIVATE square_engine_core)
    add_test(NAME decoders COMMAND test_decoders)

    add_executable(test_exclusive_format tests/test_exclusive_format.cpp)
    target_link_libraries(test_exclusive_format PRIVATE square_engine_core)
    add_test(NAME exclusive_format COMMAND test_exclusive_format)
endif()
