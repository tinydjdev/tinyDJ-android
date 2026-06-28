cmake_minimum_required(VERSION 3.22.1)
project(tinydj LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Oboe is delivered as a prefab package by the com.google.oboe:oboe AAR.
find_package(oboe REQUIRED CONFIG)

add_library(tinydj-audio SHARED
        native_audio_engine.cpp)

# dr_flac.h / dr_mp3.h are fetched into third_party/ by download_dr_libs.sh
target_include_directories(tinydj-audio PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/third_party)

# NOTE: do NOT add -ffast-math here. The audio callback's transport/reverse logic
# gates on exact float comparisons (step == 0.0, step < 0.0); -ffinite-math-only
# and unsafe reassociation would corrupt it.
target_compile_options(tinydj-audio PRIVATE -O3 -fno-math-errno)

target_link_libraries(tinydj-audio
        oboe::oboe
        android
        log)
