#!/bin/sh

rm -rf build && mkdir -p build

CXXFLAGS="-Wall -Wextra" cmake -DCMAKE_BUILD_TYPE=Debug -Bbuild -H.
cmake --build build -j `nproc` --target all
