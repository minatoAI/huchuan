// Huchuan Windows 壳：一个 Win32 窗口装一个 WebView2，页面资源全部编进 exe。
// 这里只管"窗口 + 渲染"，不含任何业务逻辑；后续界面改动只动 resources/ 下的文件。
// winsock2.h 必须排在 windows.h 前面，否则 windows.h 会先把旧 winsock 拉进来。
#include <winsock2.h>
#include <ws2tcpip.h>
#include <windows.h>
#include <shellapi.h>
#include <shlwapi.h>
#include <wrl.h>
#include <WebView2.h>

#include <cstdarg>
#include <cstdio>
#include <string>

// 资源表由 CMake 生成：一条资源 = {请求路径, 资源 ID, Content-Type}。
// 往 resources/web/ 加文件不需要动这个头文件，也不用改 main.cpp。
#include "assets.generated.h"

using Microsoft::WRL::Callback;
using Microsoft::WRL::ComPtr;

namespace {

const wchar_t kWindowClass[] = L"HuchuanShellWindow";
const wchar_t kWindowTitle[] = L"互传";
// 页面走一个假域名，好处是拿到正常 origin（localStorage、fetch 相对路径都能用），
// 所有请求由下方 WebResourceRequested 从 exe 内嵌资源里回，不碰网络。
const wchar_t kAppOrigin[] = L"https://huchuan.invalid/";
const wchar_t kStartUrl[] = L"https://huchuan.invalid/index.html";

// 只在开发与截图取证时用的命令行开关，正常双击启动一个都不带：
//   --route=p3            直接落到某一页（跳过开屏）
//   --theme=dark|light    强制主题（默认跟随系统）
//   --network=limited|fail  自检降级 / 自检失败（默认通过）
//   --hold=boot|checking|result  把 P0 停在某个阶段，供逐帧截图
struct LaunchOptions {
  std::wstring route;
  std::wstring theme;
  std::wstring network;
  std::wstring hold;
  std::wstring daemon_jar;   // --daemon-jar=D:\path\daemon.jar（完整路径，不做字符白名单）
  int daemon_port = 47200;   // --daemon-port=47200
};

const int kWindowWidthDip = 1100;
const int kWindowHeightDip = 760;

// 页面在导航完成后再补两次 DOM 探针：1.5s 记「当前页」，4s 记「自检放行后落到了哪一页」。
// 冷启动没有命令行参数，只能靠第二次探针证明「没有任何点击也进了 P1」。
const UINT_PTR kDomProbeTimerId = 1;
const UINT_PTR kLateProbeTimerId = 2;
const UINT kDomProbeDelayMs = 1500;
const UINT kLateProbeDelayMs = 4000;

HWND g_hwnd = nullptr;
std::wstring g_start_url;
// 页面主题决定窗口自己的深色外观：标题栏、WebView2 首帧底色、窗口背景刷。
bool g_dark_chrome = false;
ComPtr<ICoreWebView2Environment> g_env;
ComPtr<ICoreWebView2Controller> g_controller;
ComPtr<ICoreWebView2> g_webview;
std::wstring g_log_path;
int g_exit_code = 0;

// ---------------------------------------------------------------- 日志
// 每次启动截断，一次运行一个文件：%LOCALAPPDATA%\Huchuan\logs\shell.log

void EnsureDir(const std::wstring& path) {
  std::wstring cur;
  for (size_t i = 0; i < path.size(); ++i) {
    cur.push_back(path[i]);
    if (path[i] == L'\\' || path[i] == L'/') {
      if (cur.size() > 3) CreateDirectoryW(cur.c_str(), nullptr);
    }
  }
  CreateDirectoryW(path.c_str(), nullptr);
}

std::wstring EnvVar(const wchar_t* name) {
  DWORD need = GetEnvironmentVariableW(name, nullptr, 0);
  if (need == 0) return std::wstring();
  std::wstring buf(need, L'\0');
  DWORD got = GetEnvironmentVariableW(name, buf.data(), need);
  buf.resize(got);
  return buf;
}

std::wstring LocalAppDataDir() {
  std::wstring base = EnvVar(L"LOCALAPPDATA");
  if (base.empty()) return std::wstring();
  return base + L"\\Huchuan";
}

void Log(const wchar_t* fmt, ...) {
  wchar_t body[2048];
  va_list args;
  va_start(args, fmt);
  _vsnwprintf_s(body, _TRUNCATE, fmt, args);
  va_end(args);

  SYSTEMTIME st;
  GetLocalTime(&st);
  wchar_t line[2300];
  _snwprintf_s(line, _TRUNCATE, L"[%02u:%02u:%02u.%03u] %s\r\n", st.wHour, st.wMinute,
               st.wSecond, st.wMilliseconds, body);

  OutputDebugStringW(line);
  if (g_log_path.empty()) return;

  int bytes = WideCharToMultiByte(CP_UTF8, 0, line, -1, nullptr, 0, nullptr, nullptr);
  if (bytes <= 1) return;
  std::string utf8(static_cast<size_t>(bytes - 1), '\0');
  WideCharToMultiByte(CP_UTF8, 0, line, -1, utf8.data(), bytes, nullptr, nullptr);

  FILE* file = nullptr;
  if (_wfopen_s(&file, g_log_path.c_str(), L"ab") == 0 && file) {
    fwrite(utf8.data(), 1, utf8.size(), file);
    fclose(file);
  }
}

// 打开日志并清空上一次的内容，让每次运行的日志自成一份证据。
void InitLog() {
  std::wstring base = LocalAppDataDir();
  if (base.empty()) return;
  EnsureDir(base + L"\\logs");
  g_log_path = base + L"\\logs\\shell.log";
  FILE* file = nullptr;
  if (_wfopen_s(&file, g_log_path.c_str(), L"wb") == 0 && file) fclose(file);
  Log(L"=== Huchuan 启动 pid=%lu ===", GetCurrentProcessId());

  wchar_t module[MAX_PATH * 4];
  DWORD module_len = GetModuleFileNameW(nullptr, module, ARRAYSIZE(module));
  if (module_len > 0) Log(L"exe：%s", module);
}

// --------------------------------------------------------- daemon 生命周期
//
// 壳只做两件事：启动时把 daemon 拉起来，退出时把它带走。群的增删、传输、评论全在
// daemon 里（独立的 JVM 进程，接口见 docs/daemon-api.md），main.cpp 不碰任何业务。
//
// 做法是 Windows 的 job object + KILL_ON_JOB_CLOSE：daemon 和壳绑在同一个 job 里，
// 壳无论正常退出、崩溃还是被任务管理器结束，句柄一关 daemon 就跟着结束 —— 不会留下孤儿进程。
// 关闭前先发一次 POST /api/shutdown 让它自己体面收尾（落盘 state.json）。

HANDLE g_daemon_job = nullptr;
PROCESS_INFORMATION g_daemon_process{};
bool g_daemon_owned = false;   // true = 这个 daemon 是壳拉起来的，退出时才该杀
int g_daemon_port = 47200;     // 窗口销毁时要知道该敲哪个端口

std::wstring ExeDirectory() {
  wchar_t module[MAX_PATH * 4];
  const DWORD len = GetModuleFileNameW(nullptr, module, ARRAYSIZE(module));
  if (len == 0) return std::wstring();
  std::wstring path(module, len);
  const size_t slash = path.find_last_of(L"\\/");
  return slash == std::wstring::npos ? std::wstring() : path.substr(0, slash);
}

bool FileExists(const std::wstring& path) {
  if (path.empty()) return false;
  const DWORD attr = GetFileAttributesW(path.c_str());
  return attr != INVALID_FILE_ATTRIBUTES && (attr & FILE_ATTRIBUTE_DIRECTORY) == 0;
}

/** daemon.jar 查找顺序：命令行 → HUCHUAN_DAEMON_JAR → %LOCALAPPDATA%\Huchuan → exe 同级 → 源码树。 */
std::wstring ResolveDaemonJar(const std::wstring& explicit_path) {
  if (FileExists(explicit_path)) return explicit_path;

  const std::wstring from_env = EnvVar(L"HUCHUAN_DAEMON_JAR");
  if (FileExists(from_env)) return from_env;

  const std::wstring base = LocalAppDataDir();
  if (!base.empty()) {
    const std::wstring packaged = base + L"\\daemon\\daemon.jar";
    if (FileExists(packaged)) return packaged;
  }

  const std::wstring exe_dir = ExeDirectory();
  if (!exe_dir.empty()) {
    if (FileExists(exe_dir + L"\\daemon.jar")) return exe_dir + L"\\daemon.jar";
    // 直接在构建目录里跑 exe 时（windows/build/Huchuan.exe），往上找源码树里的产物。
    std::wstring probe = exe_dir;
    for (int i = 0; i < 4; ++i) {
      const size_t slash = probe.find_last_of(L"\\/");
      if (slash == std::wstring::npos) break;
      probe = probe.substr(0, slash);
      const std::wstring candidate = probe + L"\\daemon\\build\\dist\\daemon.jar";
      if (FileExists(candidate)) return candidate;
    }
  }
  return std::wstring();
}

std::wstring FindJava() {
  const std::wstring home = EnvVar(L"JAVA_HOME");
  if (!home.empty()) {
    if (FileExists(home + L"\\bin\\javaw.exe")) return home + L"\\bin\\javaw.exe";
    if (FileExists(home + L"\\bin\\java.exe")) return home + L"\\bin\\java.exe";
  }
  return L"javaw.exe";   // 交给 PATH
}

/** 只是探一下 127.0.0.1 的端口有没有人在听。 */
bool PortAccepting(int port) {
  WSADATA wsa{};
  if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) return false;
  SOCKET sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  bool ok = false;
  if (sock != INVALID_SOCKET) {
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<u_short>(port));
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    ok = connect(sock, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == 0;
    closesocket(sock);
  }
  WSACleanup();
  return ok;
}

/** POST /api/shutdown：让 daemon 自己收尾。手写是因为壳里不值得为一次请求引 HTTP 栈。 */
void RequestDaemonShutdown(int port) {
  WSADATA wsa{};
  if (WSAStartup(MAKEWORD(2, 2), &wsa) != 0) return;
  SOCKET sock = socket(AF_INET, SOCK_STREAM, IPPROTO_TCP);
  if (sock != INVALID_SOCKET) {
    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_port = htons(static_cast<u_short>(port));
    addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    if (connect(sock, reinterpret_cast<sockaddr*>(&addr), sizeof(addr)) == 0) {
      const char body[] = "{}";
      char request[256];
      const int len = _snprintf_s(
          request, sizeof(request), _TRUNCATE,
          "POST /api/shutdown HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: application/json\r\n"
          "Content-Length: %d\r\nConnection: close\r\n\r\n%s",
          static_cast<int>(sizeof(body) - 1), body);
      if (len > 0) {
        send(sock, request, len, 0);
        char drain[512];
        recv(sock, drain, sizeof(drain), 0);
      }
    }
    closesocket(sock);
  }
  WSACleanup();
}

bool BindToJob(HANDLE process) {
  if (!g_daemon_job) {
    g_daemon_job = CreateJobObjectW(nullptr, nullptr);
    if (!g_daemon_job) return false;
    JOBOBJECT_EXTENDED_LIMIT_INFORMATION limit{};
    limit.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
    if (!SetInformationJobObject(g_daemon_job, JobObjectExtendedLimitInformation, &limit,
                                 sizeof(limit))) {
      CloseHandle(g_daemon_job);
      g_daemon_job = nullptr;
      return false;
    }
  }
  return AssignProcessToJobObject(g_daemon_job, process) != FALSE;
}

/** 拉起 daemon 并等到端口可连。已经在跑就直接复用（那种情况退出时也不会去关它）。 */
bool StartDaemon(const LaunchOptions& options, int wait_ms) {
  if (PortAccepting(options.daemon_port)) {
    Log(L"端口 %d 上已经有 daemon，直接复用", options.daemon_port);
    return true;
  }

  const std::wstring jar = ResolveDaemonJar(options.daemon_jar);
  if (jar.empty()) {
    Log(L"找不到 daemon.jar：--daemon-jar= / HUCHUAN_DAEMON_JAR / %%LOCALAPPDATA%%\\Huchuan\\daemon.jar 都没有");
    return false;
  }

  const std::wstring java = FindJava();
  std::wstring cmd = L"\"" + java + L"\" -jar \"" + jar + L"\" --port=" +
                     std::to_wstring(options.daemon_port);
  Log(L"拉起 daemon：%s", cmd.c_str());

  STARTUPINFOW si{};
  si.cb = sizeof(si);
  si.dwFlags = STARTF_USESHOWWINDOW;
  si.wShowWindow = SW_HIDE;
  PROCESS_INFORMATION pi{};
  if (!CreateProcessW(nullptr, cmd.data(), nullptr, nullptr, FALSE, CREATE_NO_WINDOW, nullptr,
                      nullptr, &si, &pi)) {
    Log(L"CreateProcessW 失败：%lu", GetLastError());
    return false;
  }
  g_daemon_process = pi;
  CloseHandle(pi.hThread);
  g_daemon_owned = true;
  if (!BindToJob(pi.hProcess)) {
    Log(L"绑 job 失败（%lu）：退出时只能尽力关掉 daemon", GetLastError());
  }

  const DWORD deadline = GetTickCount() + static_cast<DWORD>(wait_ms);
  while (GetTickCount() < deadline) {
    if (PortAccepting(options.daemon_port)) {
      Log(L"daemon 就绪：http://127.0.0.1:%d/", options.daemon_port);
      return true;
    }
    if (WaitForSingleObject(pi.hProcess, 200) == WAIT_OBJECT_0) {
      DWORD code = 0;
      GetExitCodeProcess(pi.hProcess, &code);
      Log(L"daemon 进程提前退出，退出码=%lu", code);
      return false;
    }
  }
  Log(L"daemon 在 %d ms 内没起来", wait_ms);
  return false;
}

/** 退出时收尾：先请它自己停，停不下来再让 job 统一带走。 */
void StopDaemon(int port) {
  if (!g_daemon_owned) {
    Log(L"daemon 不是本进程拉起来的，退出时不动它");
    return;
  }
  RequestDaemonShutdown(port);
  const DWORD deadline = GetTickCount() + 3000;
  while (GetTickCount() < deadline) {
    if (!PortAccepting(port)) break;
    Sleep(100);
  }
  if (g_daemon_process.hProcess) {
    if (WaitForSingleObject(g_daemon_process.hProcess, 0) == WAIT_TIMEOUT) {
      Log(L"daemon 没在 3s 内退出，按 job 规则强制结束");
    }
    CloseHandle(g_daemon_process.hProcess);
    g_daemon_process.hProcess = nullptr;
  }
  if (g_daemon_job) {
    CloseHandle(g_daemon_job);   // KILL_ON_JOB_CLOSE：句柄一关，job 里的进程全部结束
    g_daemon_job = nullptr;
  }
  g_daemon_owned = false;
  Log(L"daemon 已结束");
}

// 系统是否在用深色主题（设置 → 个性化 → 颜色 → 应用模式）。
// 页面默认跟随系统，窗口标题栏也得跟着，否则深色页面顶一条白栏。
bool SystemUsesDarkTheme() {
  HKEY key = nullptr;
  if (RegOpenKeyExW(HKEY_CURRENT_USER,
                    L"Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", 0,
                    KEY_READ, &key) != ERROR_SUCCESS) {
    return false;
  }
  DWORD light = 1;
  DWORD size = sizeof(light);
  DWORD type = 0;
  bool dark = false;
  if (RegQueryValueExW(key, L"AppsUseLightTheme", nullptr, &type,
                       reinterpret_cast<LPBYTE>(&light), &size) == ERROR_SUCCESS &&
      type == REG_DWORD) {
    dark = light == 0;
  }
  RegCloseKey(key);
  return dark;
}

// DWMWA_USE_IMMERSIVE_DARK_MODE = 20（老 SDK 头里没有这个枚举，直接传数字）。
void ApplyDarkTitleBar(HWND hwnd, bool dark) {
  HMODULE dwm = LoadLibraryW(L"dwmapi.dll");
  if (!dwm) return;
  auto set_attribute = reinterpret_cast<HRESULT(WINAPI*)(HWND, DWORD, LPCVOID, DWORD)>(
      GetProcAddress(dwm, "DwmSetWindowAttribute"));
  if (set_attribute) {
    BOOL value = dark ? TRUE : FALSE;
    set_attribute(hwnd, 20, &value, sizeof(value));
  }
  FreeLibrary(dwm);
}

void Fail(const std::wstring& message) {
  Log(L"失败：%s", message.c_str());
  g_exit_code = 1;
  MessageBoxW(nullptr, message.c_str(), L"互传", MB_ICONERROR | MB_OK);
  if (g_hwnd) {
    DestroyWindow(g_hwnd);
  } else {
    PostQuitMessage(1);
  }
}

// ------------------------------------------------------------ 命令行开关

// 只放行小写字母、数字和 . - _：查询串里不必再转义，也顺手挡掉乱传的参数。
std::wstring SafeToken(const std::wstring& value) {
  std::wstring out;
  for (wchar_t ch : value) {
    if ((ch >= L'a' && ch <= L'z') || (ch >= L'0' && ch <= L'9') || ch == L'.' ||
        ch == L'-' || ch == L'_') {
      out.push_back(ch);
    }
  }
  return out;
}

LaunchOptions ParseLaunchOptions() {
  LaunchOptions options;
  int argc = 0;
  LPWSTR* argv = CommandLineToArgvW(GetCommandLineW(), &argc);
  if (!argv) return options;

  for (int i = 1; i < argc; ++i) {
    const std::wstring arg = argv[i];
    auto value_after = [&arg](const wchar_t* prefix) -> std::wstring {
      const size_t len = wcslen(prefix);
      if (arg.size() <= len || arg.compare(0, len, prefix) != 0) return std::wstring();
      return SafeToken(arg.substr(len));
    };
    if (const std::wstring value = value_after(L"--route="); !value.empty()) {
      options.route = value;
    } else if (const std::wstring theme = value_after(L"--theme="); !theme.empty()) {
      options.theme = theme;
    } else if (const std::wstring network = value_after(L"--network="); !network.empty()) {
      options.network = network;
    } else if (const std::wstring hold = value_after(L"--hold="); !hold.empty()) {
      options.hold = hold;
    } else if (arg.compare(0, 13, L"--daemon-jar=") == 0) {
      options.daemon_jar = arg.substr(13);   // 路径里有 : 和 \，不能过 SafeToken
    } else if (const std::wstring port = value_after(L"--daemon-port="); !port.empty()) {
      const int parsed = _wtoi(port.c_str());
      if (parsed > 0 && parsed < 65536) options.daemon_port = parsed;
    }
  }

  LocalFree(argv);
  return options;
}

// 页面参数统一走查询串：WebResourceRequested 拿到的 URI 不含 #fragment，带 ? 也只切在 '?' 上。
std::wstring BuildStartUrl(const std::wstring& base_url, const LaunchOptions& options) {
  std::wstring query;
  auto append = [&query](const wchar_t* key, const std::wstring& value) {
    if (value.empty()) return;
    if (!query.empty()) query += L"&";
    query += key;
    query += L"=";
    query += value;
  };
  append(L"route", options.route);
  append(L"theme", options.theme);
  append(L"network", options.network);
  append(L"hold", options.hold);

  std::wstring url = base_url;
  if (!query.empty()) url += L"?" + query;
  return url;
}

// ------------------------------------------------------------ 内嵌资源

struct Blob {
  const void* data = nullptr;
  DWORD size = 0;
};

bool LoadResourceBlob(int id, Blob* out) {
  HMODULE module = GetModuleHandleW(nullptr);
  HRSRC found = FindResourceW(module, MAKEINTRESOURCEW(id), MAKEINTRESOURCEW(RT_RCDATA));
  if (!found) return false;
  HGLOBAL loaded = LoadResource(module, found);
  if (!loaded) return false;
  out->data = LockResource(loaded);
  out->size = SizeofResource(module, found);
  return out->data != nullptr && out->size > 0;
}

// 请求路径 → 资源表（表由 CMake 从 resources/web/ 生成）。
// 全壳只有这一处路由判断：表里没有的路径就是 404。
const HuchuanAsset* FindAsset(const std::wstring& path) {
  for (int i = 0; i < kHuchuanAssetCount; ++i) {
    if (path == kHuchuanAssets[i].path) return &kHuchuanAssets[i];
  }
  return nullptr;
}

// ------------------------------------------------------- WebView2 渲染

ComPtr<IStream> MemoryStream(const void* data, size_t size) {
  ComPtr<IStream> stream;
  stream.Attach(SHCreateMemStream(static_cast<const BYTE*>(data), static_cast<UINT>(size)));
  return stream;
}

HRESULT Respond(ICoreWebView2WebResourceRequestedEventArgs* args, const void* body, size_t size,
                int status, const wchar_t* reason, const wchar_t* content_type) {
  const std::wstring headers =
      std::wstring(L"Content-Type: ") + content_type + L"\r\nCache-Control: no-store\r\n";
  ComPtr<IStream> body_stream;
  if (body && size > 0) body_stream = MemoryStream(body, size);

  ComPtr<ICoreWebView2WebResourceResponse> response;
  HRESULT hr = g_env->CreateWebResourceResponse(body_stream.Get(), status, reason,
                                                headers.c_str(), &response);
  if (FAILED(hr)) return hr;
  return args->put_Response(response.Get());
}

HRESULT OnWebResourceRequested(ICoreWebView2* /*sender*/,
                               ICoreWebView2WebResourceRequestedEventArgs* args) {
  ComPtr<ICoreWebView2WebResourceRequest> request;
  args->get_Request(&request);

  std::wstring uri;
  LPWSTR raw_uri = nullptr;
  if (request && SUCCEEDED(request->get_Uri(&raw_uri)) && raw_uri) {
    uri.assign(raw_uri);
    CoTaskMemFree(raw_uri);
  }

  const std::wstring origin = kAppOrigin;
  if (uri.compare(0, origin.size(), origin) != 0) {
    // 只有本壳自己的假域名才由内嵌资源回，别的源一律 404。
    Log(L"拒绝非同源请求：%s", uri.c_str());
    static const char kForbidden[] = "forbidden origin";
    return Respond(args, kForbidden, sizeof(kForbidden) - 1, 404, L"Not Found",
                   L"text/plain; charset=utf-8");
  }
  std::wstring path = uri.substr(origin.size());
  size_t cut = path.find_first_of(L"?#");
  if (cut != std::wstring::npos) path.resize(cut);
  if (!path.empty() && path[0] == L'/') path.erase(0, 1);

  if (path.empty()) path = L"index.html";

  // WebView2 每次导航都会顺手要 favicon，给个空 204，别让它落到 404 分支里当噪音。
  if (path == L"favicon.ico") {
    return Respond(args, nullptr, 0, 204, L"No Content", L"text/plain; charset=utf-8");
  }

  const HuchuanAsset* asset = FindAsset(path);
  if (!asset) {
    Log(L"请求未命中内嵌资源：%s", uri.c_str());
    static const char kNotFound[] = "not found";
    return Respond(args, kNotFound, sizeof(kNotFound) - 1, 404, L"Not Found",
                   L"text/plain; charset=utf-8");
  }

  Blob blob;
  if (!LoadResourceBlob(asset->resource_id, &blob)) {
    Log(L"内嵌资源读取失败：id=%d", asset->resource_id);
    static const char kMissing[] = "resource missing";
    return Respond(args, kMissing, sizeof(kMissing) - 1, 500, L"Internal Server Error",
                   asset->content_type);
  }

  Log(L"内嵌资源命中：/%s -> id=%d (%lu 字节)", path.c_str(), asset->resource_id, blob.size);
  return Respond(args, blob.data, blob.size, 200, L"OK", asset->content_type);
}

// 导航完成后用脚本读回 DOM：主页名 + 标题 + 正文，作为"页面真的渲染了"的机读证据。
// 页名来自 document.body.dataset.page，与 app.js 的路由一一对应（p0…p7）。
void ProbeDom(const std::wstring& tag) {
  if (!g_webview) return;
  // 输出形如：page=p2| title=互传| text=…。page= 后面跟 '|'，取证脚本可以直接当字段切。
  const wchar_t* script =
      L"(function(){var b=document.body;var p=(b&&b.dataset&&b.dataset.page)||'?';"
      L"return 'page='+p+'| title='+document.title+'| text='"
      L"+(b?(b.innerText||'').replace(/\\s+/g,' ').trim():'NO_BODY');})()";
  g_webview->ExecuteScript(
      script,
      Callback<ICoreWebView2ExecuteScriptCompletedHandler>(
          [tag](HRESULT hr, LPCWSTR json) -> HRESULT {
            if (FAILED(hr) || !json) {
              Log(L"DOM 探针(%s) 失败 hr=0x%08X", tag.c_str(), static_cast<unsigned>(hr));
            } else {
              Log(L"DOM 探针(%s)：%s", tag.c_str(), json);
            }
            return S_OK;
          })
          .Get());
}

void UpdateWebViewBounds() {
  if (!g_controller || !g_hwnd) return;
  RECT client{};
  GetClientRect(g_hwnd, &client);
  g_controller->put_Bounds(client);
}

void AttachWebView() {
  std::wstring root = LocalAppDataDir();
  if (root.empty()) {
    Fail(L"读不到 %LOCALAPPDATA%，无法确定 WebView2 用户数据目录。");
    return;
  }
  std::wstring user_data = root + L"\\WebView2";
  EnsureDir(user_data);
  Log(L"WebView2 用户数据目录：%s", user_data.c_str());

  HRESULT hr = CreateCoreWebView2EnvironmentWithOptions(
      nullptr, user_data.c_str(), nullptr,
      Callback<ICoreWebView2CreateCoreWebView2EnvironmentCompletedHandler>(
          [](HRESULT result, ICoreWebView2Environment* env) -> HRESULT {
            if (FAILED(result) || !env) {
              Fail(L"初始化 WebView2 运行时失败（0x" +
                   std::to_wstring(static_cast<unsigned>(result)) +
                   L"）。请确认已安装 WebView2 Runtime。");
              return S_OK;
            }
            g_env = env;

            LPWSTR version = nullptr;
            if (SUCCEEDED(env->get_BrowserVersionString(&version)) && version) {
              Log(L"WebView2 运行时版本：%s", version);
              CoTaskMemFree(version);
            }

            env->CreateCoreWebView2Controller(
                g_hwnd,
                Callback<ICoreWebView2CreateCoreWebView2ControllerCompletedHandler>(
                    [](HRESULT result, ICoreWebView2Controller* controller) -> HRESULT {
                      if (FAILED(result) || !controller) {
                        Fail(L"创建 WebView2 控制器失败（0x" +
                             std::to_wstring(static_cast<unsigned>(result)) + L"）。");
                        return S_OK;
                      }
                      g_controller = controller;
                      g_controller->get_CoreWebView2(&g_webview);
                      Log(L"WebView2 控制器就绪");

                      // WebView2 首帧之前的底色：浅色页面用 #F7FBF8，深色页面用 #0F1513，
                      // 避免窗口出现时先闪一块白。
                      ComPtr<ICoreWebView2Controller2> controller2;
                      if (SUCCEEDED(g_controller.As(&controller2)) && controller2) {
                        COREWEBVIEW2_COLOR background = g_dark_chrome
                            ? COREWEBVIEW2_COLOR{255, 0x0F, 0x15, 0x13}
                            : COREWEBVIEW2_COLOR{255, 0xF7, 0xFB, 0xF8};
                        controller2->put_DefaultBackgroundColor(background);
                      }

                      ComPtr<ICoreWebView2Settings> settings;
                      if (SUCCEEDED(g_webview->get_Settings(&settings)) && settings) {
                        settings->put_IsStatusBarEnabled(FALSE);
                        settings->put_AreDefaultContextMenusEnabled(FALSE);
                        settings->put_AreDevToolsEnabled(TRUE);  // 开发期保留 F12
                      }

                      UpdateWebViewBounds();
                      g_controller->put_IsVisible(TRUE);

                      EventRegistrationToken token{};
                      g_webview->AddWebResourceRequestedFilter(
                          L"https://huchuan.invalid/*", COREWEBVIEW2_WEB_RESOURCE_CONTEXT_ALL);
                      g_webview->add_WebResourceRequested(
                          Callback<ICoreWebView2WebResourceRequestedEventHandler>(
                              [](ICoreWebView2* sender,
                                 ICoreWebView2WebResourceRequestedEventArgs* args) -> HRESULT {
                                return OnWebResourceRequested(sender, args);
                              })
                              .Get(),
                          &token);

                      g_webview->add_NavigationCompleted(
                          Callback<ICoreWebView2NavigationCompletedEventHandler>(
                              [](ICoreWebView2* /*sender*/,
                                 ICoreWebView2NavigationCompletedEventArgs* args) -> HRESULT {
                                BOOL ok = FALSE;
                                args->get_IsSuccess(&ok);
                                COREWEBVIEW2_WEB_ERROR_STATUS status =
                                    COREWEBVIEW2_WEB_ERROR_STATUS_UNKNOWN;
                                args->get_WebErrorStatus(&status);
                                Log(L"导航完成 success=%d error_status=%d", ok ? 1 : 0,
                                    static_cast<int>(status));
                                if (!ok) {
                                  Fail(L"页面加载失败（WebView2 错误码 " +
                                       std::to_wstring(static_cast<int>(status)) + L"）。");
                                  return S_OK;
                                }
                                ProbeDom(L"nav");
                                SetTimer(g_hwnd, kDomProbeTimerId, kDomProbeDelayMs, nullptr);
                                SetTimer(g_hwnd, kLateProbeTimerId, kLateProbeDelayMs, nullptr);
                                return S_OK;
                              })
                              .Get(),
                          &token);

                      Log(L"导航到 %s", g_start_url.c_str());
                      g_webview->Navigate(g_start_url.c_str());
                      return S_OK;
                    })
                    .Get());
            return S_OK;
          })
          .Get());

  if (FAILED(hr)) {
    Fail(L"调用 WebView2 环境创建失败（0x" + std::to_wstring(static_cast<unsigned>(hr)) +
         L"）。请确认已安装 WebView2 Runtime。");
  }
}

// --------------------------------------------------------------- 窗口

LRESULT CALLBACK WndProc(HWND hwnd, UINT message, WPARAM wparam, LPARAM lparam) {
  switch (message) {
    case WM_SIZE:
      UpdateWebViewBounds();
      return 0;
    case WM_TIMER:
      if (wparam == kDomProbeTimerId) {
        KillTimer(hwnd, kDomProbeTimerId);
        ProbeDom(L"settled");
        return 0;
      }
      if (wparam == kLateProbeTimerId) {
        KillTimer(hwnd, kLateProbeTimerId);
        ProbeDom(L"late");
        return 0;
      }
      break;
    case WM_DPICHANGED: {
      // PerMonitorV2：跨屏时按系统建议的矩形重排，WebView2 自己处理缩放。
      RECT* suggested = reinterpret_cast<RECT*>(lparam);
      SetWindowPos(hwnd, nullptr, suggested->left, suggested->top,
                   suggested->right - suggested->left, suggested->bottom - suggested->top,
                   SWP_NOZORDER | SWP_NOACTIVATE);
      UpdateWebViewBounds();
      return 0;
    }
    case WM_DESTROY:
      if (g_controller) {
        g_controller->Close();
        g_controller.Reset();
      }
      g_webview.Reset();
      g_env.Reset();
      StopDaemon(g_daemon_port);
      Log(L"窗口销毁，进程退出");
      PostQuitMessage(g_exit_code);
      return 0;
    default:
      break;
  }
  return DefWindowProcW(hwnd, message, wparam, lparam);
}

// 按 DPI 把 1100x760 的客户区换算成外层窗口尺寸并居中。
HWND CreateShellWindow(HINSTANCE instance) {
  const DWORD style = WS_OVERLAPPEDWINDOW;
  const DWORD ex_style = 0;

  UINT dpi = 96;
  HMODULE user32 = GetModuleHandleW(L"user32.dll");
  if (user32) {
    auto get_dpi_for_system = reinterpret_cast<UINT(WINAPI*)()>(
        GetProcAddress(user32, "GetDpiForSystem"));
    if (get_dpi_for_system) dpi = get_dpi_for_system();
  }

  RECT rect{0, 0, MulDiv(kWindowWidthDip, dpi, 96), MulDiv(kWindowHeightDip, dpi, 96)};
  bool adjusted = false;
  if (user32) {
    auto adjust_for_dpi = reinterpret_cast<BOOL(WINAPI*)(LPRECT, DWORD, BOOL, DWORD, UINT)>(
        GetProcAddress(user32, "AdjustWindowRectExForDpi"));
    if (adjust_for_dpi) adjusted = adjust_for_dpi(&rect, style, FALSE, ex_style, dpi) != FALSE;
  }
  if (!adjusted) AdjustWindowRectEx(&rect, style, FALSE, ex_style);

  const int width = rect.right - rect.left;
  const int height = rect.bottom - rect.top;
  const int screen_w = GetSystemMetrics(SM_CXSCREEN);
  const int screen_h = GetSystemMetrics(SM_CYSCREEN);
  const int x = (screen_w - width) / 2;
  const int y = (screen_h - height) / 2;

  return CreateWindowExW(ex_style, kWindowClass, kWindowTitle, style, x, y, width, height, nullptr,
                         nullptr, instance, nullptr);
}

}  // namespace

int APIENTRY wWinMain(HINSTANCE instance, HINSTANCE /*prev*/, LPWSTR /*cmd_line*/,
                      int /*show*/) {
  // manifest 里已经声明 PerMonitorV2；这里再调一次，manifest 没生效时兜底。
  if (HMODULE user32 = GetModuleHandleW(L"user32.dll")) {
    auto set_dpi_context = reinterpret_cast<BOOL(WINAPI*)(DPI_AWARENESS_CONTEXT)>(
        GetProcAddress(user32, "SetProcessDpiAwarenessContext"));
    if (set_dpi_context) set_dpi_context(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
  }

  InitLog();

  const LaunchOptions options = ParseLaunchOptions();
  g_daemon_port = options.daemon_port;
  if (!options.route.empty() || !options.theme.empty() || !options.network.empty() ||
      !options.hold.empty()) {
    Log(L"调试参数：route=%s theme=%s network=%s hold=%s", options.route.c_str(),
        options.theme.c_str(), options.network.c_str(), options.hold.c_str());
  }

  // 标题栏与窗口底色跟着页面主题走：默认跟系统，--theme= 强制覆盖（截图取证用）。
  g_dark_chrome = options.theme == L"dark"
                      ? true
                      : (options.theme == L"light" ? false : SystemUsesDarkTheme());

  WNDCLASSEXW wc{};
  wc.cbSize = sizeof(wc);
  wc.style = CS_HREDRAW | CS_VREDRAW;
  wc.lpfnWndProc = WndProc;
  wc.hInstance = instance;
  wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
  wc.hbrBackground = CreateSolidBrush(g_dark_chrome ? RGB(0x0F, 0x15, 0x13)
                                                    : RGB(0xF7, 0xFB, 0xF8));
  wc.lpszClassName = kWindowClass;
  if (!RegisterClassExW(&wc)) {
    Fail(L"注册窗口类失败（" + std::to_wstring(GetLastError()) + L"）。");
    return g_exit_code;
  }

  g_hwnd = CreateShellWindow(instance);
  if (!g_hwnd) {
    Fail(L"创建窗口失败（" + std::to_wstring(GetLastError()) + L"）。");
    return g_exit_code;
  }
  ApplyDarkTitleBar(g_hwnd, g_dark_chrome);
  Log(L"窗口已创建 %dx%d，标题栏深色=%d", kWindowWidthDip, kWindowHeightDip,
      g_dark_chrome ? 1 : 0);
  ShowWindow(g_hwnd, SW_SHOW);
  UpdateWindow(g_hwnd);

  // 页面由 daemon 同源提供（http://127.0.0.1:47200/）：既没有跨源问题，也不用把业务塞进壳。
  // daemon 起不来就退回 exe 内嵌的那份页面 —— 同一套代码，它会把自检显示成「网络不可用」。
  const bool daemon_ready = StartDaemon(options, 20000);
  const std::wstring daemon_url =
      L"http://127.0.0.1:" + std::to_wstring(options.daemon_port) + L"/index.html";
  g_start_url = BuildStartUrl(daemon_ready ? daemon_url : std::wstring(kStartUrl), options);
  if (!daemon_ready) Log(L"daemon 没起来：退回内嵌页面（页面会显示自检失败）");

  AttachWebView();

  MSG msg{};
  while (GetMessageW(&msg, nullptr, 0, 0)) {
    TranslateMessage(&msg);
    DispatchMessageW(&msg);
  }
  return static_cast<int>(msg.wParam);
}
