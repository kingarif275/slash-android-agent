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

# Optional Adreno OpenCL build. The SDK/ICD loader is intentionally opt-in so
# normal clones remain portable; the accelerator build sets SLASH_OPENCL_ROOT
# to a prepared Android OpenCL toolchain directory.
if (DEFINED ENV{SLASH_OPENCL_ROOT} AND IS_DIRECTORY "$ENV{SLASH_OPENCL_ROOT}")
    set(CMAKE_PREFIX_PATH "$ENV{SLASH_OPENCL_ROOT};${CMAKE_PREFIX_PATH}")
    set(OpenCL_INCLUDE_DIR "$ENV{SLASH_OPENCL_ROOT}/../../OpenCL-Headers" CACHE PATH "" FORCE)
    set(OpenCL_LIBRARY "$ENV{SLASH_OPENCL_ROOT}/lib/libOpenCL.so" CACHE FILEPATH "" FORCE)
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
    message(STATUS "Slash: Adreno OpenCL backend enabled")
else()
    set(GGML_OPENCL OFF CACHE BOOL "" FORCE)
    message(STATUS "Slash: accelerator toolchain unavailable; CPU fallback only")
endif()

add_subdirectory(llama.cpp EXCLUDE_FROM_ALL)

add_library(slash_llama SHARED slash_jni.cpp)
target_link_libraries(slash_llama PRIVATE llama android log)
target_compile_options(slash_llama PRIVATE -Wall -Wextra -Wpedantic)
