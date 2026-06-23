cmake_minimum_required(VERSION 3.22.1)
project("networkoptimizer")

add_library(${CMAKE_PROJECT_NAME} SHARED
        packet_processor.cpp)

find_library(log-lib log)

target_link_libraries(${CMAKE_PROJECT_NAME}
        ${log-lib})
