cmake_minimum_required(VERSION 3.22.1)
project(veycad_whisper LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 17)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Keep symbols while testing realistic CPU inference, rather than unoptimized tensor kernels.
set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} -O2")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -O2")
include(FetchContent)
FetchContent_Declare(whisper
    # Source downloads can be shared; object files must stay isolated per ABI/build/worktree.
    BINARY_DIR "${CMAKE_CURRENT_BINARY_DIR}/whisper-build"
    URL https://codeload.github.com/ggml-org/whisper.cpp/tar.gz/48f628a84833905ee4a0658ee6d4a5c915ce1997
    URL_HASH SHA256=17c4189d2ba98fedac681dc76944476217008f2c7268784bd519bf069ca37e3a)
FetchContent_MakeAvailable(whisper)
set(VEYCAD_WHISPER_UPSTREAM "${whisper_SOURCE_DIR}/src/whisper.cpp")
set(VEYCAD_WHISPER_OUTPUT "${CMAKE_CURRENT_BINARY_DIR}/whisper-audio-bounds.cpp")
include("${CMAKE_CURRENT_SOURCE_DIR}/whisper_audio_bounds.cmake")
# Replace only the implementation file; upstream headers/options/ggml stay pinned.
set_property(TARGET whisper PROPERTY SOURCES
    "${whisper_SOURCE_DIR}/include/whisper.h"
    "${whisper_SOURCE_DIR}/src/whisper-arch.h"
    "${VEYCAD_WHISPER_OUTPUT}")
add_library(veycad_whisper SHARED whisper_jni.cpp)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS
    "${CMAKE_CURRENT_SOURCE_DIR}/whisper_jni.cpp"
    "${CMAKE_CURRENT_SOURCE_DIR}/whisper_audio_bounds.cmake")
file(SHA256 "${CMAKE_CURRENT_SOURCE_DIR}/whisper_jni.cpp" VEYCAD_WHISPER_BRIDGE_SHA)
file(SHA256 "${CMAKE_CURRENT_SOURCE_DIR}/whisper_audio_bounds.cmake" VEYCAD_WHISPER_PATCH_SHA)
target_compile_definitions(veycad_whisper PRIVATE
    VEYCAD_WHISPER_BRIDGE_SHA="${VEYCAD_WHISPER_BRIDGE_SHA}"
    VEYCAD_WHISPER_PATCH_SHA="${VEYCAD_WHISPER_PATCH_SHA}")
target_link_libraries(veycad_whisper PRIVATE whisper android log)
target_link_options(veycad_whisper PRIVATE "-Wl,-z,max-page-size=16384")
