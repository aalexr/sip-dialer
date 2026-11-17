cmake_minimum_required(VERSION 3.22)
project(sipdialer C CXX)

# Static PJSIP/OpenSSL/Opus from native/build-deps.sh (Gradle runs it before this).
set(DEPS ${CMAKE_CURRENT_SOURCE_DIR}/../../../../native/build/${ANDROID_ABI})
if(NOT EXISTS ${DEPS}/.stamp)
    message(FATAL_ERROR "Native dependencies missing: run native/build-deps.sh ${ANDROID_ABI}")
endif()

add_library(sipdialer SHARED pjsip_jni.c)

file(STRINGS ${DEPS}/defines.txt PJ_DEFINES)
target_compile_options(sipdialer PRIVATE ${PJ_DEFINES} -Wall -Wextra -Wno-unused-parameter)
target_include_directories(sipdialer PRIVATE ${DEPS}/include)

file(GLOB DEP_LIBS ${DEPS}/lib/*.a)
list(FILTER DEP_LIBS EXCLUDE REGEX "libpjsua2\\.a$") # C++ API, unused
target_link_libraries(sipdialer PRIVATE
    -Wl,--start-group ${DEP_LIBS} -Wl,--end-group
    mediandk log android m)
# Parts of PJSIP are C++ (MediaCodec codec, atomic queue): link with the C++ driver.
set_target_properties(sipdialer PROPERTIES LINKER_LANGUAGE CXX)
# Keep only our JNI entry points visible.
target_link_options(sipdialer PRIVATE -Wl,--exclude-libs,ALL -Wl,--gc-sections)
