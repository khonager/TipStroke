cmake_minimum_required(VERSION 3.22.1)
project(tipstroke_pixels LANGUAGES CXX)
add_library(tipstroke_pixels SHARED eraser.cpp)
target_compile_features(tipstroke_pixels PRIVATE cxx_std_17)
# Match Kotlin/Java's separate float operations; contraction can change alpha rounding.
target_compile_options(tipstroke_pixels PRIVATE -O3 -ffp-contract=off -fno-exceptions -fno-rtti)
