cmake_minimum_required(VERSION 3.22.1)

project(astra_native LANGUAGES C CXX)

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
# the small expected GLFW bridge ABI to Astra's ANativeWindow + MobileGlues path.
add_library(
    pojavexec
    SHARED
    astra_pojavexec.cpp
)

target_compile_features(pojavexec PRIVATE cxx_std_17)
target_compile_options(pojavexec PRIVATE -Wall -Wextra -Werror=return-type)

target_link_libraries(
    pojavexec
    android
    dl
    log
)
