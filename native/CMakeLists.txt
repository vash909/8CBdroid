cmake_minimum_required(VERSION 3.22.1)
project(eightcbdroid_native LANGUAGES CXX)
add_library(eightcbdroid_native SHARED bridge.cpp)
target_compile_features(eightcbdroid_native PRIVATE cxx_std_17)
# Future: add pinned Hamlib / FT8 engines and JNI entry points here.
# This optional target is deliberately not wired into the Android build yet.
