# Windows 上的 Android 模拟器：评估（2026-11-06）

**结论：装得了，跑不起来。** emulator 37.2.13 与 API 35 x86_64 镜像已装好、AVD 已建，脚本在
`scripts/emulator.sh`；但本机没有 CPU 加速驱动，本会话无管理员权限补装，该版本 emulator 又拒绝
纯软件（TCG）跑 x86_64 镜像。**模拟器暂时不能当调试环境用，真机仍是唯一可用平台。**

## 一、四个问题

**1. 本机能不能跑模拟器？硬件能，软件不能。**
CPU 是 Ryzen 7 9700X（8C/16T），固件虚拟化已开、VT-x 等价的 AMD-V 与 SLAT 都在
（systeminfo: Virtualization Enabled In Firmware / SLAT = Yes）。但：
`HypervisorPresent=False`（Hyper-V/WHPX 未启用，VBS=0），`gvm`/`aehd` 服务不存在
（AEHD 未装），HAXM 是 Intel 专用，不适用。`emulator -accel-check` 返回 **6**：
"Android Emulator hypervisor driver is not installed on this machine"。会话账号非管理员
（IsInRole(Administrator)=False）、approval 关闭 → 装不了驱动、开不了系统功能。
磁盘：C 20.8G / D 230.7G / E 506G 可用；SDK 与 AVD 都落在 E:，没占 C:。

**2. x86_64 镜像能不能跑 arm64-only 的应用 + arm64 原生库？能，靠 NDK 翻译层。**
官方 emulator 发布说明：Android 11（API 30）起 x86_64 镜像的 ABI 表含 ARMv7+ARM64，
ARM 二进制默认就能跑。本机镜像实测（不靠印象）：
`build.prop` 里 `ro.system.product.cpu.abilist=x86_64,arm64-v8a`、
`ro.dalvik.vm.native.bridge=libndk_translation.so`、`ro.ndk_translation.version=0.2.3`；
`system.img` 内含 `libndk_translation.so` 与 14 个 `libndk_translation_proxy_*.so`（含 libc）。
所以只带 arm64-v8a 的 debug APK（libtorrent4j.so + libandroidx.graphics.path.so）能装能跑；
自编 .so 的 NEEDED 只有 libdl/libm/libc，翻译层覆盖得到。
代价：所有 arm64 代码走翻译，有性能损耗，且翻译层自身有已知 bug
（37.x 发布说明里有 `libndk_translation.so` 在 Arm64AesEncode 上 abort 的修复记录）。本机因加速缺失未能实测。

**3. 给内核补 x86_64 .so 的成本？约 40–60 行 + 20 分钟构建，但不解决根本问题。**
`tools/build-libtorrent-android` 是 arm64 硬编码：`versions.env` 的 `ANDROID_ABI`；
`build.sh` 里 OpenSSL 的 `linux-aarch64`/`-march=armv8-a+crypto`、`aarch64-linux-android` clang、
`cmake -DANDROID_ABI`、产物与 jar 名（`lib/arm64-v8a/`）。改成 ABI 到
(triple, openssl target, -march) 的映射表、每 ABI 独立 openssl 前缀与 build 目录、
jar 内加 `lib/x86_64/`，即可。一次性构建 OpenSSL ~5 min + native ~5–8 min（-j16）。
app 侧还要改 `abiFilters` 并换成含 x86_64 的 jar（属 app/ 域，本次不动）；而且前提是先解决加速，否则编出来也没处跑。

**4. 两个模拟器之间能不能互传？默认不能，只能模拟器↔真机（且方向受限）。**
guest 走 QEMU slirp 用户态 NAT（10.0.2.0/24），组播不过 slirp，JmDNS 的 224.0.0.251:5353
到不了宿主机和真机；两个 emulator 各自一套 NAT，彼此也不通。
emulator 37 里能找到两条官方通道：`-shared-net-id`（用 10.1.2.x 共享网络，emulator↔emulator）
和 netsimd 的 `--forward-host-mdns`（默认 false，字面只写 host→guest Wi-Fi）。
官方 networking 文档称 Wi-Fi 上的 NSD/P2P 自 emulator 36.5 起支持。
这三条本机都无法实测（起不来）。保守结论：模拟器不能用来验收局域网发现与互传。

## 二、实测记录

- 安装：`sdkmanager emulator "system-images;android-35;google_apis;x86_64"`，exit 0。
  434 MB + 3.5 GB 下载并解压在 **约 3 分钟**内完成（21:52:47 起，21:55:24 完成）。
  落盘：emulator 680 MB、system-images 2.3 GB；AVD 首次启动准备出 2.6 GB（userdata/cache）。
- AVD：`huchuan_api35`（pixel_6，x86_64，API 35 google_apis），AVD home 指向 `artifacts/emulator/avd`。
- 镜像可用性：sdkmanager 只有 android-34/35/36 的 x86_64 镜像，**没有 android-37 镜像**。
- 启动（两次都是 -no-window headless）：`-accel off` 起 qemu 后秒退；`-qemu -accel tcg` 明确报
  `x86_64 emulation currently requires hardware acceleration!`。
  **该版本无加速就没有任何启动路径**（不是慢，是不让跑）。
- 因没有可用的 adb 设备，装包 / 启动 / P0 / 建群 / libtorrent 加载（UnsatisfiedLinkError、
  NoSuchMethod）/ headless 截图 / devkit 全部**未完成**；启动耗时也无法给出。
- 未动系统环境变量；只在进程内设 `ANDROID_SDK_ROOT`/`ANDROID_AVD_HOME`。

## 三、能力边界

| 能做（加速修好后） | 不能做 |
| --- | --- |
| 本机 UI 回归、截图比对、单机内核冒烟 | 局域网 mDNS 发现、模拟器↔真机互传 |
| 翻译层下跑 arm64 APK（慢） | BT 吞吐验收、Scudo/arm64 特有崩溃复现 |
| 与真机并行的 P0/建群 UI 冒烟 | 替代真机结论（AGENTS §6.3：真机是唯一事实） |

## 四、恢复步骤（管理员，二选一，需重启）

A. 开 WHPX：`dism /online /enable-feature /featurename:HypervisorPlatform /all /norestart`
   （另开 `VirtualMachinePlatform`），重启后 `-accel-check` 应为 0。
B. 装 AEHD（包已下好）：管理员运行 `E:\agentHome\android-sdk\extras\google\Android_Emulator_Hypervisor_Driver\silent_install.bat`，重启。

之后：`bash scripts/emulator.sh doctor && bash scripts/emulator.sh start`（默认 -no-window）；`install`/`shot`/`logcat` 走 devkit，serial 固定 `emulator-5554`。

## 五、证据

`artifacts/emulator/`：accel-check.txt、arm-translation-evidence.txt（build.prop + system.img）、
sdk-images.txt、netsim-mdns.txt、emulator-accel-fatal.log、emulator-launch-verbose.log、
install-summary.txt、avd-list.txt、avd/（AVD 本体）。
