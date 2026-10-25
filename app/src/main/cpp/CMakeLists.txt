# PocketIDE native library build - P3a PTY backend (one source file, pure C).
# Constructive details and ABI policy are documented in app/build.gradle.kts.
cmake_minimum_required(VERSION 3.22.1)
project(pocketpty LANGUAGES C)

add_library(pocketpty SHARED pocketpty.c)

# Pure C, no STL, no third-party code; keep compiler diagnostics visible.
target_compile_options(pocketpty PRIVATE -Wall -Wextra)

# Debug-variant-only diagnostics accessor (nativeLastChildPid); never shipped
# in release builds. AGP passes -DCMAKE_BUILD_TYPE=Debug for debug variants.
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    target_compile_definitions(pocketpty PRIVATE POCKETPTY_DEBUG_DIAG=1)
endif()
