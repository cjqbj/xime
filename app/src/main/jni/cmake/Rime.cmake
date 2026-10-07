# SPDX-FileCopyrightText: 2015 - 2024 Rime community
#
# SPDX-License-Identifier: GPL-3.0-or-later

# 应用 Lua 5.4 Android 兼容性补丁（修复 32 位设备上 fseeko/ftello 不可用的问题）
# 对 liolib.c 中 l_fseek 配置块做条件增强，使 32 位 Android < API 24 能编译
# 使用 CMake 原生方式修补，无需依赖 git/patch
string(ASCII 10 LUA_NL)
set(LUA_LIOLIB_SRC "${CMAKE_SOURCE_DIR}/librime-lua-deps/lua5.4/liolib.c")
if(EXISTS "${LUA_LIOLIB_SRC}")
  file(READ "${LUA_LIOLIB_SRC}" LUA_LIOLIB_CONTENT)
  # 检查补丁是否已应用
  string(FIND "${LUA_LIOLIB_CONTENT}" "ANDROID" LUA_ALREADY_PATCHED)
  if(LUA_ALREADY_PATCHED EQUAL -1)
    string(FIND "${LUA_LIOLIB_CONTENT}" "#if !defined(l_fseek)" LUA_ANCHOR_POS)
    if(LUA_ANCHOR_POS GREATER -1)
      string(SUBSTRING "${LUA_LIOLIB_CONTENT}" ${LUA_ANCHOR_POS} -1 LUA_SUB_CONTENT)
      string(FIND "${LUA_SUB_CONTENT}" "#if defined(LUA_USE_POSIX)" LUA_REL_POS)
      if(LUA_REL_POS GREATER -1)
        math(EXPR LUA_TARGET_POS "${LUA_ANCHOR_POS} + ${LUA_REL_POS}")
        string(SUBSTRING "${LUA_LIOLIB_CONTENT}" ${LUA_TARGET_POS} -1 LUA_TARGET_CONTENT)
        string(FIND "${LUA_TARGET_CONTENT}" "${LUA_NL}" LUA_REL_NL_POS)
        if(LUA_REL_NL_POS GREATER -1)
          math(EXPR LUA_NL_POS "${LUA_TARGET_POS} + ${LUA_REL_NL_POS}")
          string(SUBSTRING "${LUA_LIOLIB_CONTENT}" 0 ${LUA_TARGET_POS} LUA_HEAD)
          string(SUBSTRING "${LUA_LIOLIB_CONTENT}" ${LUA_NL_POS} -1 LUA_TAIL)
          math(EXPR LUA_SUFFIX_START "${LUA_TARGET_POS} + 26")
          math(EXPR LUA_SUFFIX_LEN "${LUA_NL_POS} - ${LUA_SUFFIX_START}")
          string(SUBSTRING "${LUA_LIOLIB_CONTENT}" ${LUA_SUFFIX_START} ${LUA_SUFFIX_LEN} LUA_SUFFIX)
          set(LUA_PATCHED_LINE
            "#if defined(LUA_USE_POSIX) && \\${LUA_NL}   (!defined(ANDROID) || (defined(__LP64__) || ANDROID_PLATFORM >= 24))${LUA_SUFFIX}")
          set(LUA_LIOLIB_CONTENT "${LUA_HEAD}${LUA_PATCHED_LINE}${LUA_TAIL}")
          file(WRITE "${LUA_LIOLIB_SRC}" "${LUA_LIOLIB_CONTENT}")
        endif()
      endif()
    endif()
  endif()
endif()

# 修复单字母简拼被同形全拼音节遮蔽的问题（输入 m 首选“呒”而非“吗/嘛”，
# 输入 n 只有“嗯”而非“你/那”，a/e/o 同理）。
# librime 的音节图在逆向剪枝时，若输入能被一条全拼(normal)路径完整解释，
# 就会删除路径上所有缩写(abbreviation)边；当字母本身是叹词音节时
# （呒 m、嗯 n、啊 a、额 e、哦 o），以该字母为首字母简拼的高频字
# （吗 ma、你 ni、爱 ai…）永远无法成为候选，用户反复选词也无法学习。
# 将缩写边的保留下限从 kFuzzySpelling 提升到 kAbbreviation，使简拼候选与
# 全拼候选共存，先后次序交给简拼 credibility 罚分(-2.3)、词频与用户词库
# 学习在排序阶段决定（高频常用字自然排前，越用越准）。
set(SYLLABIFIER_SRC "${CMAKE_SOURCE_DIR}/librime/src/rime/algo/syllabifier.cc")
if(EXISTS "${SYLLABIFIER_SRC}")
  file(READ "${SYLLABIFIER_SRC}" SYLLABIFIER_CONTENT)
  string(FIND "${SYLLABIFIER_CONTENT}" "kAbbrevSimpleSpellPatchedMarker" SYLLABIFIER_PATCHED)
  if(SYLLABIFIER_PATCHED EQUAL -1)
    string(FIND "${SYLLABIFIER_CONTENT}"
      "graph->vertices[farthest], kFuzzySpelling" SYLLABIFIER_ANCHOR)
    if(SYLLABIFIER_ANCHOR GREATER -1)
      string(REPLACE
        "graph->vertices[farthest], kFuzzySpelling"
        "graph->vertices[farthest], kAbbreviation /*kAbbrevSimpleSpellPatchedMarker*/"
        SYLLABIFIER_CONTENT "${SYLLABIFIER_CONTENT}")
      file(WRITE "${SYLLABIFIER_SRC}" "${SYLLABIFIER_CONTENT}")
      message(STATUS "librime syllabifier patched: keep abbreviation edges alongside exact spellings")
    else()
      message(WARNING "syllabifier.cc patch anchor not found; single-letter abbreviations may stay shadowed")
    endif()
  endif()
endif()

# 已集成的插件
set(RIME_PLUGINS librime-octagram librime-predict librime-t9)

# 将插件复制到 plugins/ 目录。
# 顶层插件目录（librime-t9 等）是唯一权威源码，这里在每次 configure 时
# 都全量同步，确保插件编译副本与顶层一致（file(COPY) 保留源文件时间戳，
# 内容未变的文件不会触发重编译）。
foreach(plugin ${RIME_PLUGINS})
  file(COPY "${CMAKE_SOURCE_DIR}/${plugin}/"
       DESTINATION "${CMAKE_SOURCE_DIR}/librime/plugins/${plugin}")
endforeach()

# librime-lua 需要特殊命名 lua
file(COPY "${CMAKE_SOURCE_DIR}/librime-lua/"
     DESTINATION "${CMAKE_SOURCE_DIR}/librime/plugins/lua")

# librime-lua thirdparty 依赖（Lua 5.4 源码）
if(NOT EXISTS "${CMAKE_SOURCE_DIR}/librime/plugins/lua/thirdparty")
  file(COPY "${CMAKE_SOURCE_DIR}/librime-lua-deps/"
       DESTINATION "${CMAKE_SOURCE_DIR}/librime/plugins/lua/thirdparty")
endif()

option(BUILD_TEST "" OFF)
option(BUILD_STATIC "" ON)
add_subdirectory(librime)
target_compile_options(
  rime-static PRIVATE "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=." "-Wno-error=deprecated-declarations")

target_compile_options(
  rime-lua-objs PRIVATE "-ffile-prefix-map=${CMAKE_SOURCE_DIR}=.")
