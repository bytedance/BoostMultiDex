# For more information about using CMake with Android Studio, read the
# documentation: https://d.android.com/studio/projects/add-native-code.html

# Sets the minimum version of CMake required to build the native library.

cmake_minimum_required(VERSION 3.4.1)

add_library( boost_multidex
             SHARED
             src/main/cpp/boost_multidex.cpp)

target_link_libraries( boost_multidex
                       log
                       z )