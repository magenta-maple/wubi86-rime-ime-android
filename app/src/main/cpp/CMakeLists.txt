cmake_minimum_required(VERSION 3.22.1)
project(meifeng_rime)

# app/src/main/cpp -> repo root is four levels up
set(PREBUILT "${CMAKE_CURRENT_LIST_DIR}/../../../../vendor/prebuilt")

add_library(meifeng_rime SHARED rime_jni.cpp)

target_include_directories(meifeng_rime PRIVATE
        "${PREBUILT}/librime/${ANDROID_ABI}/include")

target_compile_features(meifeng_rime PRIVATE cxx_std_17)
target_compile_options(meifeng_rime PRIVATE -frtti -fexceptions -Wall -Wno-unused-parameter)

target_link_options(meifeng_rime PRIVATE "-Wl,-z,max-page-size=16384")

# librime.a references these static libs. Lua is required by the fcitx prebuilt,
# which was compiled with librime-lua objects. The wubi schema does not load Lua.
target_link_libraries(meifeng_rime
        "${PREBUILT}/librime/${ANDROID_ABI}/lib/librime.a"
        "${PREBUILT}/yaml-cpp/${ANDROID_ABI}/lib/libyaml-cpp.a"
        "${PREBUILT}/opencc/${ANDROID_ABI}/lib/libopencc.a"
        "${PREBUILT}/marisa/${ANDROID_ABI}/lib/libmarisa.a"
        "${PREBUILT}/leveldb/${ANDROID_ABI}/lib/libleveldb.a"
        "${PREBUILT}/glog/${ANDROID_ABI}/lib/libglog.a"
        "${PREBUILT}/lua/${ANDROID_ABI}/lib/liblua_static.a"
        log)
