cmake_minimum_required(VERSION 3.19.1)

# Project for BugSplat Android SDK
project(bugsplat-android)

add_library(bugsplat SHARED native-lib.cpp)

# Enable 16KB page size support
# See: https://developer.android.com/guide/practices/page-sizes
target_link_options(bugsplat PRIVATE "-Wl,-z,max-page-size=16384")

# PIE executable named like a JNI lib so Android packages it and Crashpad can
# exec it at crash time (same pattern as libcrashpad_handler.so). The wrapper
# re-reads the attachment list so addAttachment/removeAttachment work after init.
add_executable(bugsplat_handler handler_wrapper.cpp)
set_target_properties(bugsplat_handler PROPERTIES
        OUTPUT_NAME "libbugsplat_handler.so"
        PREFIX ""
        SUFFIX "")
target_link_options(bugsplat_handler PRIVATE
        "-pie"
        "-Wl,-z,max-page-size=16384")

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
        ${PROJECT_SOURCE_DIR}/app/src/main/cpp/
)

find_library(log-lib log)

target_link_libraries(bugsplat
        ${log-lib}
        crashpad_common
        crashpad_client
        crashpad_util
        base
)

target_link_libraries(bugsplat_handler ${log-lib})

# AGP only packages cmake SHARED libraries. Copy the wrapper into a jniLibs
# directory Gradle merges (app/build/handlerWrapper/<abi>/).
set(HANDLER_JNI_LIBS_DIR
        "${CMAKE_CURRENT_SOURCE_DIR}/../../../build/handlerWrapper/${ANDROID_ABI}")
add_custom_command(TARGET bugsplat_handler POST_BUILD
        COMMAND ${CMAKE_COMMAND} -E make_directory "${HANDLER_JNI_LIBS_DIR}"
        COMMAND ${CMAKE_COMMAND} -E copy
        $<TARGET_FILE:bugsplat_handler>
        "${HANDLER_JNI_LIBS_DIR}/libbugsplat_handler.so"
        COMMENT "Copying libbugsplat_handler.so into jniLibs")
add_dependencies(bugsplat bugsplat_handler)

target_compile_options(bugsplat PRIVATE
        "$<$<CONFIG:RELEASE>:-O0>"
        "$<$<CONFIG:DEBUG>:-O0>"
)