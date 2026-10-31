cmake_minimum_required(VERSION 3.24)
project(SkyCraftLink VERSION 0.1.0 LANGUAGES CXX)

# Static runtime: SkyCraft Link runs on a friend's PC without the Visual C++ redistributable.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

add_library(LinkCore STATIC
	src/Frames.cpp
	src/LoopbackTransport.cpp
	src/Net.cpp
	src/Status.cpp
	src/SteamApi.cpp
	src/SteamTransport.cpp
	src/Tunnel.cpp)
target_include_directories(LinkCore PUBLIC src)
target_compile_features(LinkCore PUBLIC cxx_std_23)
# Lean windows.h: winsock2.h must not meet the old winsock.h.
target_compile_definitions(LinkCore PUBLIC WIN32_LEAN_AND_MEAN NOMINMAX _WIN32_WINNT=0x0A00)
target_compile_options(LinkCore PUBLIC /W4 /permissive- /utf-8 /EHsc)
target_link_libraries(LinkCore PUBLIC ws2_32)

add_executable(SkyCraftLink src/main.cpp)
target_link_libraries(SkyCraftLink PRIVATE LinkCore winmm)

add_executable(LinkTests tests/main.cpp)
target_link_libraries(LinkTests PRIVATE LinkCore)
