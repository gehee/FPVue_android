
cmake_minimum_required(VERSION 3.22.1)


project("WfbngRtl8812")

include_directories(include)

add_library(${CMAKE_PROJECT_NAME} SHARED
        devourer/hal/Hal8812PhyReg.h
        devourer/hal/Hal8812PwrSeq.c
        devourer/hal/Hal8812PwrSeq.h
        devourer/hal/basic_types.h
        devourer/hal/hal8812a_fw.c
        devourer/hal/hal8812a_fw.h
        devourer/hal/hal_com_reg.h
        devourer/hal/rtl8812a_hal.h
        devourer/hal/rtl8812a_recv.h
        devourer/hal/rtl8812a_spec.h

        devourer/src/EepromManager.cpp
        devourer/src/EepromManager.h
        devourer/src/Firmware.h
        devourer/src/FirmwareManager.cpp
        devourer/src/FirmwareManager.h
        devourer/src/FrameParser.cpp
        devourer/src/FrameParser.h
        devourer/src/HalModule.cpp
        devourer/src/HalModule.h
        devourer/src/ParsedRadioPacket.cpp
        devourer/src/RadioManagementModule.cpp
        devourer/src/RadioManagementModule.h
        devourer/src/Rtl8812aDevice.cpp
        devourer/src/Rtl8812aDevice.h
        devourer/src/RtlUsbAdapter.cpp
        devourer/src/RtlUsbAdapter.h
        devourer/src/SelectedChannel.h
        devourer/src/WiFiDriver.cpp
        devourer/src/WiFiDriver.h
        devourer/src/registry_priv.h

        wfb-ng/src/fec.c
        wfb-ng/src/fec.h
        wfb-ng/src/ieee80211_radiotap.h
        wfb-ng/src/radiotap.c
        wfb-ng/src/rx.cpp
        wfb-ng/src/rx.hpp
        wfb-ng/src/wifibroadcast.hpp
        wfb-ng/src/wifibroadcast.cpp

        RxFrame.h
        RxFrame.cpp
        WfbngLink.cpp)

target_include_directories(${CMAKE_PROJECT_NAME} PRIVATE devourer/hal)

target_link_libraries(${CMAKE_PROJECT_NAME}
        # List libraries link to the target library
        android
        log
        ${CMAKE_SOURCE_DIR}/libs/${ANDROID_ABI}/libusb1.0.so
        ${CMAKE_SOURCE_DIR}/libs/${ANDROID_ABI}/libsodium.so
        ${CMAKE_SOURCE_DIR}/libs/${ANDROID_ABI}/libpcap.a  )


#set(CMAKE_CXX_STANDARD 20)
set_property(TARGET ${CMAKE_PROJECT_NAME} PROPERTY CXX_STANDARD 20)
target_compile_options(${CMAKE_PROJECT_NAME} PRIVATE -fno-omit-frame-pointer)