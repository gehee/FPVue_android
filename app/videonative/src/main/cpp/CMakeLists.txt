
cmake_minimum_required(VERSION 3.6)

project("VideoNative")

add_library(${CMAKE_PROJECT_NAME} SHARED
        parser/H26XParser.cpp
        parser/ParseRTP.cpp
        UdpReceiver.cpp
        VideoDecoder.cpp
        VideoPlayer.cpp)


target_link_libraries(${CMAKE_PROJECT_NAME}
        # List libraries link to the target library
        android
        mediandk
        log)

set_property(TARGET ${CMAKE_PROJECT_NAME} PROPERTY CXX_STANDARD 20)
target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE -fno-omit-frame-pointer)