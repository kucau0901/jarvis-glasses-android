cmake_minimum_required(VERSION 3.22.1)
project(jarvis_lc3 C)

# Google's liblc3 (liblc3/README.md) and its JNI wrapper, for the G2's microphone.
file(GLOB LC3_SOURCES ${CMAKE_CURRENT_SOURCE_DIR}/liblc3/src/*.c)
add_library(jarvis_lc3 SHARED lc3_jni.c ${LC3_SOURCES})
target_include_directories(jarvis_lc3 PRIVATE liblc3/include liblc3/src)
target_compile_options(jarvis_lc3 PRIVATE -O2 -std=c11)
target_link_libraries(jarvis_lc3 m)
