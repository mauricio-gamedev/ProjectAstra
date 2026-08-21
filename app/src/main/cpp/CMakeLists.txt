cmake_minimum_required(VERSION 3.22.1)

project(astra_native LANGUAGES C CXX)

add_library(
    astra_native
    SHARED
    astra_native.cpp
)

target_compile_features(astra_native PRIVATE cxx_std_17)
target_compile_options(astra_native PRIVATE -Wall -Wextra -Werror=return-type)

target_link_libraries(
    astra_native
    android
    dl
    log
)
