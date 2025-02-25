cmake_minimum_required(VERSION 3.19.1)

add_library(bugsplat SHARED native-lib.cpp)

add_library(crashpad_common STATIC IMPORTED)
set_property(TARGET crashpad_common PROPERTY IMPORTED_LOCATION ${PROJECT_SOURCE_DIR}/crashpad/lib/${ANDROID_ABI}/client/libcommon.a)

add_library(crashpad_client STATIC IMPORTED)
set_property(TARGET crashpad_client PROPERTY IMPORTED_LOCATION ${PROJECT_SOURCE_DIR}/crashpad/lib/${ANDROID_ABI}/client/libclient.a)

add_library(crashpad_util STATIC IMPORTED)
set_property(TARGET crashpad_util PROPERTY IMPORTED_LOCATION ${PROJECT_SOURCE_DIR}/crashpad/lib/${ANDROID_ABI}/util/libutil.a)

add_library(base STATIC IMPORTED)
set_property(TARGET base PROPERTY IMPORTED_LOCATION ${PROJECT_SOURCE_DIR}/crashpad/lib/${ANDROID_ABI}/base/libbase.a)

add_library(crashpad_handler SHARED IMPORTED)
set_property(TARGET crashpad_handler PROPERTY IMPORTED_LOCATION ${PROJECT_SOURCE_DIR}/crashpad/lib/${ANDROID_ABI}/libcrashpad_handler.so)

include_directories(
        ${PROJECT_SOURCE_DIR}/crashpad/include/
        ${PROJECT_SOURCE_DIR}/crashpad/include/third_party/mini_chromium/mini_chromium/
)

find_library(log-lib log)

target_link_libraries(bugsplat
        ${log-lib}
        crashpad_common
        crashpad_client
        crashpad_util
        base
)

target_compile_options(bugsplat PRIVATE
        "$<$<CONFIG:RELEASE>:-O0>"
        "$<$<CONFIG:DEBUG>:-O0>"
)