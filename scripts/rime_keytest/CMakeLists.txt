cmake_minimum_required(VERSION 3.18)
project(xime_rime_keytest LANGUAGES CXX)

find_package(PkgConfig REQUIRED)
pkg_check_modules(RIME REQUIRED IMPORTED_TARGET rime)

add_executable(rime_key_candidate_test rime_key_candidate_test.cc)
target_compile_features(rime_key_candidate_test PRIVATE cxx_std_17)
target_include_directories(rime_key_candidate_test PRIVATE
    "${CMAKE_CURRENT_LIST_DIR}/../../app/src/main/jni/librime_jni"
)
target_link_libraries(rime_key_candidate_test PRIVATE PkgConfig::RIME)
