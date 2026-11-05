# build-libtorrent-android

从源码构建 Android arm64-v8a 的 libtorrent4j 原生库，产出可直接替换官方 Maven 制品的 jar。

## 结论先行

- 一条命令出产物：`./build.sh`。热缓存约 **70 秒**，全新机器约 **10–14 分钟**。
- 产物可复现：两个不同工作目录各编一次，`.so` / `.debug` / `.jar` 的 sha256 完全相同。
  证据在 `artifacts/build-libtorrent/REPRODUCIBILITY.md`。
- 为什么不用官方件：官方 `.so`（libtorrent `24a3adf`）在 Android 13 上触发
  `Scudo ERROR: invalid chunk state`。本构建把 libtorrent 挪到 RC_2_1 的 `170663b`，
  该版本包含磁盘 I/O 生命周期修复。

## 依赖

| 类别 | 需要什么 | 版本（`versions.env` 里改） |
| --- | --- | --- |
| 工具链 | Android NDK | `28.2.13676358`（r28c） |
| 工具链 | CMake ≥ 3.16 | 实测 3.31.6 |
| 工具链 | Ninja、git、curl | 实测 1.13.2 / 2.55 / 8.x |
| 工具链 | Python ≥ 3.14 | 用于 `scripts/*.py`（3.14 自带 `compression.zstd`） |
| 工具链 | GNU make | Linux/macOS 用系统；Windows 自动下载 msys2 的 make |
| 工具链 | Perl | MSYS 自带；Windows 需三个 shim，见 `host/perl5/` |
| 源码 | libtorrent4j | `v2.1.0-39`（Java/SWIG 面） |
| 源码 | libtorrent | `170663b`（RC_2_1 head） |
| 源码 | Boost（仅头文件） | 1.90.0 |
| 源码 | OpenSSL（静态） | 3.5.5，为本 ABI 现场编译 |

除 NDK 外都不需要预装：`build.sh` 自己下载源码与三方库。

## 用法

```sh
./build.sh                      # 全流程，产物在 build/out/
./build.sh --out DIR            # 指定产物目录
./build.sh --work DIR           # 指定工作树（默认 ./build）
./build.sh --fresh              # 丢掉源码与三方库缓存重来
./build.sh --stage deps         # 只做依赖（源码 + Boost + OpenSSL）
./build.sh --stage native       # 只重编 native（最快的一次复现验证，约 70 秒）
./build.sh --stage package      # 只重新打包 + 体检
NO_PREFIX_MAP=1 ./build.sh …    # 诊断开关：关闭路径归一化，用来观察噪声
```

产物（`--out` 指向的目录）：

| 文件 | 内容 |
| --- | --- |
| `libtorrent4j.so` | 已 strip 的 arm64-v8a 动态库 |
| `libtorrent4j.so.debug` | 未 strip 版本，留着解符号 |
| `libtorrent4j-android-arm64-2.1.0-39.jar` | 内部路径 `lib/arm64-v8a/libtorrent4j.so`，直接替换 Maven 坐标 |
| `sha256.txt` | 上面三件的哈希 |
| `readelf-dynamic.txt` / `readelf-headers.txt` / `build-info.txt` | 体检记录 |
| `cmake-configure.log` / `cmake-build.log` / `openssl-*.log` | 原始日志 |

## 每一步在干什么

| 步骤 | 动作 | 为什么要这么写 |
| --- | --- | --- |
| 0 preflight | 找 NDK、检查 cmake/ninja/git/curl/python | 失败要早，别编到一半才发现 |
| 1 host helpers | Windows 下补 GNU make + 三个 Perl shim | Git for Windows 不带 make，Perl 是裁剪版 |
| 2 deps | 下 Boost/OpenSSL 包，clone libtorrent4j，切 libtorrent 到 `170663b`，初始化 `deps/try_signal` | 所有网络操作都套重试环 |
| 3 patch | 打 `patches/0001-export-filenames-stub.patch` | RC_2_1 改了 `renamed_files::export_filenames()` 的签名，JNI 层拿不到 `file_storage`；libtorrent4j 不用这个接口，返回空 map |
| 4 openssl | `perl Configure linux-aarch64 …` + `make -j`，装到 `build/openssl` | 交叉编译静态 libcrypto/libssl，见下节参数 |
| 5 native | `cmake -S swig -B build/cmake-build -G Ninja` + `--target torrent4j` | 只编 JNI 目标；libtorrent 作为子目录一起编进来 |
| 6 package | `llvm-objcopy --only-keep-debug`、`llvm-strip`、`make_jar.py`、readelf 体检 | 固定 jar 时间戳，否则 jar 哈希会随日期漂 |

## NDK / ABI 参数

```
ANDROID_ABI      = arm64-v8a
ANDROID_PLATFORM = android-28          # minSdk 26 也能跑，这里跟旧件保持一致
CMAKE_BUILD_TYPE = Release
CMAKE_TOOLCHAIN_FILE = <ndk>/build/cmake/android.toolchain.cmake
CMAKE_CXX_VISIBILITY_PRESET = hidden   # swig/CMakeLists.txt 自带
OpenSSL          = linux-aarch64 no-shared no-threads no-stdio no-posix-io
                   no-deprecated no-zlib -march=armv8-a+crypto -fPIC
libtorrent 选项  = -Dwebtorrent=OFF -DUSE_JUICE=OFF -Ddeprecated-functions=ON
                   -Dlogging=ON -Dbuild_tests=OFF -Dpython-bindings=OFF
```

`no-threads`/`no-posix-io`/`no-stdio` 是照抄 libtorrent4j 上游 CI
（`.github/workflows/android.yml`）的选择，目的是砍掉用不到的 OpenSSL 面；
静态链接后 `NEEDED` 只剩 `libdl.so libm.so libc.so`。

本平台不开 `webtorrent`：那需要 `deps/libdatachannel` 及其 `usrsctp` 子模块，
体积和构建时间都翻倍，而本项目的私有模式只用 BT 协议本身。

## 踩过的坑

1. **OpenSSL 把编译时刻写进二进制**。没设 `SOURCE_DATE_EPOCH` 时，`libcrypto.a`
   里带着 `built on: <wall clock>`，于是每次重编 `.so` 哈希都变
   （实测：同源两次构建 10,874,598 → 10,874,446 字节，`.so` 哈希不同）。
   `versions.env` 里钉死 `SOURCE_DATE_EPOCH_VALUE`，这是整套复现里唯一真正要紧的开关。
2. **GitHub HTTPS 不稳定**（`Failed to connect to github.com:443` 随机出现，同一条命令
   重跑就成）。因此 clone/fetch 全部套 `GIT_RETRIES` 次重试，下载走 `.part` 临时文件
   并校验。
3. **Git for Windows 没有 GNU make**。OpenSSL 3.5.5 没有 CMake 支持，只能
   `perl Configure` + make。脚本从 msys2 取 `make.exe`，用 Python 3.14 的
   `compression.zstd` 解包（否则还要先装 zstd，循环依赖）。
4. **MSYS 的 Perl 是裁剪版**，缺 `Locale::Maketext::Simple`（`Params::Check` →
   `IPC::Cmd` 链）、`ExtUtils::MakeMaker`/`MM`、`Pod::Usage`。三个 shim 在
   `host/perl5/`，`build.sh` 用 `PERL5LIB` 挂上去。它们只被 OpenSSL 的
   Configure/configdata 用于错误消息与 `can_run()`，不影响编译结果。
5. **原生 Windows 程序不认 MSYS 的 `/tmp`**。给 python 传临时路径要用工作区内的路径
   （`E:\…`/`/e/…`），否则文件会落到 `C:\tmp` 而 bash 找不到。
6. **RC_2_1 改了 `export_filenames` 签名**，不打补丁 `libtorrent_jni.cpp` 编不过。
7. **路径可能进二进制**。默认用 `-ffile-prefix-map` 把四棵树的路径换成固定值。
   但要如实说：实测 `NO_PREFIX_MAP=1` 编出来的哈希和默认完全一样 —— Release + `NDEBUG`
   + 不带 `-g` 时路径根本没进产物。映射目前只是保险，等谁加了 `-g` 才开始有用。
7. **jar 的时间戳**。用 `zip` 打包会把当前时间写进条目，同一天两次都可能不同；
   `scripts/make_jar.py` 固定 `date_time` 与条目顺序。
8. **Windows 路径传参**。给 cmake/clang 的路径统一经 `cygpath -m` 转成
   `E:/…` 形式，避免 MSYS 自动转换在参数中间出岔子。
9. **子模块只初始化需要的**。libtorrent 有 `libdatachannel`/`asio-gnutls`/
   `libsimulator` 四个子模块，只有 `deps/try_signal` 是编 JNI 目标必需的。
10. **`--strip-unneeded -x` vs `--strip-unneeded`**：官方 CI 用前者，旧自编件用后者，
    于是旧件大 827KB。本次跟 CI 一致。

## 换台干净机器要几步

三步：

1. 装 NDK `28.2.13676358`，把 `ANDROID_NDK_ROOT` 指向它；确保 `cmake`、`ninja`、
   `git`、`curl`、`python`(≥3.14) 在 PATH。Linux/macOS 还要 `make`，
   Windows 用 Git Bash（make 由脚本自己取）。
2. `cd tools/build-libtorrent-android && ./build.sh`
3. 比哈希：`sha256sum build/out/libtorrent4j.so`，期望
   `97d2663a9b13f6cb8eea39f0f6edac5273f12e10c63048009bb8553a11d1727c`。

第 3 步对不上时，先确认 NDK 版本与 `versions.env` 没变；再确认 OpenSSL 是现场重编的
还是复用了缓存。差异来源和消除方法见 `artifacts/build-libtorrent/REPRODUCIBILITY.md`。

## 目录

```
build.sh              # 入口
versions.env          # 所有版本号与 URL
patches/              # 对 libtorrent4j 的唯一一处改动
scripts/make_jar.py   # 确定性打包
scripts/unpack_zst.py # Windows 上解 msys2 的 .pkg.tar.zst
host/perl5/           # MSYS Perl 的缺失模块 shim（仅 Windows 需要）
build/                # 工作树（源码、Boost、OpenSSL、CMake 目录），可随时删
build2/ build3/       # 复现性实验用的另外两棵工作树，留着当证据，可随时删
```
