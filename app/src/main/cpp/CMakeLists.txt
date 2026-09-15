cmake_minimum_required(VERSION 3.22.1)
project(family_qwen LANGUAGES CXX)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(PREBUILT "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
add_library(family_qwen SHARED family_qwen.cpp)
target_include_directories(family_qwen PRIVATE include)
target_link_libraries(family_qwen PRIVATE
    "${PREBUILT}/libllama.so" "${PREBUILT}/libggml.so"
    "${PREBUILT}/libggml-base.so" log)
target_link_options(family_qwen PRIVATE "-Wl,-z,max-page-size=16384")
