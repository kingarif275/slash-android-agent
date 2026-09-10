cmake_minimum_required(VERSION 3.22.1)
project(slash_llama LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Android's Debug variant otherwise compiles the entire ggml graph at -O0,
# making physical-device smoke tests tens of times slower than production.
# Keep symbols, but make the native inference path representative and usable.
if(CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_compile_options(-O3)
endif()

# Mobile CPU build. Keep upstream utilities/tests out of the APK and avoid
# host-specific instruction selection that can crash on a different phone.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE OFF CACHE BOOL "" FORCE)
# Use ARM KleidiAI kernels when building for arm64 Android. This keeps the
# universal CPU fallback but uses optimized dot-product/NEON kernels on
# supported phones while a Vulkan shader toolchain is not bundled.
set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)

add_subdirectory(llama.cpp EXCLUDE_FROM_ALL)

add_library(slash_llama SHARED slash_jni.cpp)
target_link_libraries(slash_llama PRIVATE llama android log)
target_compile_options(slash_llama PRIVATE -Wall -Wextra -Wpedantic)
