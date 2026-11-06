# Huchuan Windows 壳：一个 Win32 窗口 + 一个 WebView2，页面资源编进 exe。
# 由 scripts/build-windows.sh 调用（它会先装 WebView2 SDK、再套 vcvars64 环境）。
cmake_minimum_required(VERSION 3.25)
project(Huchuan LANGUAGES CXX)

if(NOT MSVC)
  message(FATAL_ERROR "只支持 MSVC 工具链；请用 bash scripts/build-windows.sh 构建（它负责设置 vcvars64 环境）")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 源码是 UTF-8 且不带 BOM，必须显式告诉 cl.exe，否则中文会按 GBK 解码报"常量中有换行符"。
add_compile_options(/utf-8)
# rc.exe 也用 UTF-8 读资源脚本。
string(APPEND CMAKE_RC_FLAGS " /c65001")

# /MT：静态 CRT，exe 不依赖 VC 运行库，拷走就能跑。
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

set(WEBVIEW2_ROOT
    "${CMAKE_CURRENT_SOURCE_DIR}/.tools/packages/Microsoft.Web.WebView2/build/native"
    CACHE PATH "WebView2 SDK 目录（NuGet 包里的 build/native）")
if(NOT EXISTS "${WEBVIEW2_ROOT}/include/WebView2.h")
  message(FATAL_ERROR "找不到 WebView2 SDK：${WEBVIEW2_ROOT}/include/WebView2.h 缺失。先跑 bash scripts/build-windows.sh，它会用 NuGet 拉到 windows/.tools/")
endif()

# ---------------------------------------------------------------- 内嵌资源表
# 唯一的人工维护点就是 resources/web/ 这个目录本身：
#   - 往里面加 html / css / js / svg / png / woff2，下面的 glob 自动收录；
#   - 资源 ID（从 101 起，按路径排序）、Content-Type、app.rc、assets.generated.h 全部生成。
# 加一类新扩展名时，只在 huchuan_content_type() 里补一行。
set(HUCHUAN_WEB_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/resources/web")
file(GLOB_RECURSE HUCHUAN_WEB_FILES RELATIVE "${HUCHUAN_WEB_ROOT}" CONFIGURE_DEPENDS
  "${HUCHUAN_WEB_ROOT}/*.html"
  "${HUCHUAN_WEB_ROOT}/*.css"
  "${HUCHUAN_WEB_ROOT}/*.js"
  "${HUCHUAN_WEB_ROOT}/*.svg"
  "${HUCHUAN_WEB_ROOT}/*.png"
  "${HUCHUAN_WEB_ROOT}/*.woff2")
if(NOT HUCHUAN_WEB_FILES)
  message(FATAL_ERROR "resources/web/ 下没有任何页面资源，壳会打开一张白页")
endif()
list(SORT HUCHUAN_WEB_FILES)

function(huchuan_content_type path out_var)
  get_filename_component(ext "${path}" EXT)
  string(TOLOWER "${ext}" ext)
  if(ext STREQUAL ".html")
    set(type "text/html; charset=utf-8")
  elseif(ext STREQUAL ".css")
    set(type "text/css; charset=utf-8")
  elseif(ext STREQUAL ".js")
    set(type "text/javascript; charset=utf-8")
  elseif(ext STREQUAL ".svg")
    set(type "image/svg+xml")
  elseif(ext STREQUAL ".png")
    set(type "image/png")
  elseif(ext STREQUAL ".woff2")
    set(type "font/woff2")
  else()
    set(type "application/octet-stream")
  endif()
  set(${out_var} "${type}" PARENT_SCOPE)
endfunction()

set(HUCHUAN_ASSET_RC "")
set(HUCHUAN_ASSET_ROWS "")
set(HUCHUAN_ASSET_FILES "")
set(HUCHUAN_ASSET_ID 101)
set(HUCHUAN_ASSET_BYTES 0)
foreach(rel IN LISTS HUCHUAN_WEB_FILES)
  huchuan_content_type("${rel}" content_type)
  file(TO_CMAKE_PATH "${HUCHUAN_WEB_ROOT}/${rel}" abs_path)
  list(APPEND HUCHUAN_ASSET_FILES "${abs_path}")
  file(SIZE "${abs_path}" asset_bytes)
  math(EXPR HUCHUAN_ASSET_BYTES "${HUCHUAN_ASSET_BYTES} + ${asset_bytes}")
  string(APPEND HUCHUAN_ASSET_RC "${HUCHUAN_ASSET_ID} RCDATA \"${abs_path}\"\n")
  string(APPEND HUCHUAN_ASSET_ROWS
    "  {L\"${rel}\", ${HUCHUAN_ASSET_ID}, L\"${content_type}\"},\n")
  math(EXPR HUCHUAN_ASSET_ID "${HUCHUAN_ASSET_ID} + 1")
endforeach()
list(LENGTH HUCHUAN_WEB_FILES HUCHUAN_ASSET_COUNT)
message(STATUS "内嵌页面资源：${HUCHUAN_ASSET_COUNT} 个文件，${HUCHUAN_ASSET_BYTES} 字节")

# rc.exe 只认绝对路径，而且必须是正斜杠；模板见 resources/app.rc.in。
configure_file(
  "${CMAKE_CURRENT_SOURCE_DIR}/resources/app.rc.in"
  "${CMAKE_CURRENT_BINARY_DIR}/app.rc"
  @ONLY)

# main.cpp 用同一张表做路由：{请求路径, 资源 ID, Content-Type}。全壳只有这一处路由判断。
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/assets.generated.h"
  "// 由 CMakeLists.txt 从 resources/web/** 生成，不要手改。\n"
  "// 每行 = 页内请求路径 / 资源 ID / Content-Type。\n"
  "#pragma once\n"
  "\n"
  "struct HuchuanAsset {\n"
  "  const wchar_t* path;\n"
  "  int resource_id;\n"
  "  const wchar_t* content_type;\n"
  "};\n"
  "\n"
  "static const HuchuanAsset kHuchuanAssets[] = {\n"
  "${HUCHUAN_ASSET_ROWS}"
  "};\n"
  "\n"
  "static const int kHuchuanAssetCount =\n"
  "    static_cast<int>(sizeof(kHuchuanAssets) / sizeof(kHuchuanAssets[0]));\n")

add_executable(huchuan WIN32
  src/main.cpp
  "${CMAKE_CURRENT_BINARY_DIR}/app.rc")

# rc.exe 不会把 RCDATA 指向的文件写进依赖：不声明的话，改了 css/js 之后 ninja 认为
# app.rc 没变，exe 里还是旧页面（最容易踩的坑，改完页面看不到效果就是它）。
set_property(SOURCE "${CMAKE_CURRENT_BINARY_DIR}/app.rc" APPEND PROPERTY
  OBJECT_DEPENDS "${HUCHUAN_ASSET_FILES}")

set_target_properties(huchuan PROPERTIES OUTPUT_NAME "Huchuan")

target_compile_definitions(huchuan PRIVATE UNICODE _UNICODE)

target_include_directories(huchuan PRIVATE
  "${CMAKE_CURRENT_SOURCE_DIR}/src"
  "${CMAKE_CURRENT_BINARY_DIR}"
  "${WEBVIEW2_ROOT}/include")

target_link_libraries(huchuan PRIVATE
  "${WEBVIEW2_ROOT}/x64/WebView2LoaderStatic.lib"   # 静态 loader：exe 旁边不需要 WebView2Loader.dll
  shlwapi
  shell32
  ole32
  windowscodecs   # --shot= 离屏截图：用 WIC 解码 PNG 量颜色数/亮度（系统 dll，不进 exe 旁边）
  uuid            # CLSID_FileOpenDialog / IID_IFileOpenDialog（只是导入库，不多一个 dll 依赖）
  user32
  gdi32
  ws2_32)                                            # 探端口 / 请 daemon 体面退出

target_link_options(huchuan PRIVATE
  "/MANIFEST:EMBED"
  "/MANIFESTINPUT:${CMAKE_CURRENT_SOURCE_DIR}/resources/app.manifest")
