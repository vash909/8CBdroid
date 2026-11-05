cmake_minimum_required(VERSION 3.22.1)
project(eightcbdroid_native LANGUAGES C CXX)
set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(FT8 third_party/ft8_lib)
# Text packing/unpacking is deliberately excluded: CB policy belongs above DSP.
add_library(ft8_channel STATIC
    ${FT8}/ft8/constants.c ${FT8}/ft8/crc.c ${FT8}/ft8/ldpc.c
    ${FT8}/ft8/encode.c ${FT8}/ft8/decode.c
    ${FT8}/fft/kiss_fft.c ${FT8}/fft/kiss_fftr.c ${FT8}/common/monitor.c
    engine.cpp)
target_include_directories(ft8_channel PUBLIC ${CMAKE_CURRENT_SOURCE_DIR} ${FT8})
target_link_libraries(ft8_channel PUBLIC m)
if(ANDROID)
    add_library(eightcbdroid_ft8 SHARED jni.cpp)
    target_link_libraries(eightcbdroid_ft8 PRIVATE ft8_channel)
else()
    add_executable(engine_probe host_probe.cpp)
    target_link_libraries(engine_probe PRIVATE ft8_channel)
    add_executable(codec_probe codec_probe.c ${FT8}/ft8/message.c ${FT8}/ft8/text.c)
    target_link_libraries(codec_probe PRIVATE ft8_channel)
endif()
