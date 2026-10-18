# PocketIDE native library build - P1 packaging stub only.
# Constructive details and ABI policy are documented in app/build.gradle.kts.
cmake_minimum_required(VERSION 3.22.1)
project(pocketpty LANGUAGES C)

add_library(pocketpty SHARED pocketpty.c)

# Pure C, no STL, no third-party code; keep compiler diagnostics visible.
target_compile_options(pocketpty PRIVATE -Wall -Wextra)
