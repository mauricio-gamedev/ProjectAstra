cmake_minimum_required(VERSION 3.22.1)

project(astra_native LANGUAGES C CXX)

include(FetchContent)

# Audio parity with the device-proven alpha38 stack.
# Both dependencies are pinned to immutable Git commits instead of moving tags.
set(ASTRA_OPENAL_SOFT_COMMIT "b2c48f7718ef3fcf67921a8b6534c4914e328970") # 1.25.2
set(ASTRA_OBOE_COMMIT "faa019cb12f448d18b5ab2b933272cf0c16763b2") # 1.10.2

FetchContent_Declare(
    astra_oboe_source
    URL "https://github.com/google/oboe/archive/${ASTRA_OBOE_COMMIT}.tar.gz"
)
FetchContent_GetProperties(astra_oboe_source)
if(NOT astra_oboe_source_POPULATED)
    FetchContent_Populate(astra_oboe_source)
endif()

# Keep the audio dependency focused on libopenal.so only. Oboe is statically
# linked into OpenAL Soft, matching the alpha38 runtime shape (no liboboe.so).
set(OBOE_SOURCE "${astra_oboe_source_SOURCE_DIR}" CACHE STRING "Astra pinned Oboe source" FORCE)
set(ALSOFT_BACKEND_OBOE ON CACHE BOOL "" FORCE)
set(ALSOFT_REQUIRE_OBOE ON CACHE BOOL "" FORCE)
set(ALSOFT_BACKEND_OPENSL ON CACHE BOOL "" FORCE)
set(ALSOFT_UTILS OFF CACHE BOOL "" FORCE)
set(ALSOFT_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ALSOFT_TESTS OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_CONFIG OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_HRTF_DATA OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_AMBDEC_PRESETS OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_EXAMPLES OFF CACHE BOOL "" FORCE)
set(ALSOFT_INSTALL_UTILS OFF CACHE BOOL "" FORCE)
set(ALSOFT_UPDATE_BUILD_VERSION OFF CACHE BOOL "" FORCE)
set(ALSOFT_WERROR OFF CACHE BOOL "" FORCE)

FetchContent_Declare(
    astra_openal_soft
    URL "https://github.com/kcat/openal-soft/archive/${ASTRA_OPENAL_SOFT_COMMIT}.tar.gz"
)
FetchContent_MakeAvailable(astra_openal_soft)

add_library(
    astra_native
    SHARED
    astra_native.cpp
    astra_jli.cpp
)

target_compile_features(astra_native PRIVATE cxx_std_17)
target_compile_options(astra_native PRIVATE -Wall -Wextra -Werror=return-type)

target_link_libraries(
    astra_native
    android
    dl
    log
)

# Android LWJGL's GLFW shim expects a native library named libpojavexec.so.
# Project Astra provides its own clean compatibility implementation that maps
# the expected bridge ABI to Astra's ANativeWindow + MobileGlues path.
add_library(
    pojavexec
    SHARED
    astra_pojavexec.cpp
    astra_glfw_compat.cpp
)

target_compile_features(pojavexec PRIVATE cxx_std_17)
target_compile_options(
    pojavexec
    PRIVATE
    -Wall
    -Wextra
    -Werror=return-type
    -include
    "${CMAKE_CURRENT_SOURCE_DIR}/astra_pojavexec_egl_compat.h"
)

target_link_libraries(
    pojavexec
    android
    dl
    log
)
