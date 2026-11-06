# 互传

多台设备之间直接传文件。基于 BitTorrent，群组由邀请码建立，文件在成员之间点对点传输。

Android 客户端用 Compose，Windows 客户端是 Win32 加 WebView2 壳。两端共用同一个 JVM 内核 `core/`：Android 在进程内嵌它，Windows 由独立 daemon 进程承载，壳只做界面。

## 能做什么

| 能力 | 现状 | 说明 |
| --- | --- | --- |
| 群组 | 可用 | 建群生成 16 字符邀请码，加入时只输后 8 位 |
| 私有直连 | 可用 | 局域网 mDNS 发现与手动 IP:port；私有群固定关 DHT、PEX、LSD |
| 组队通告 | 可用 | 显示「队友 N 人」，N 是实际互连的对端数（观测口径），不做分片调度 |
| 评论 | 可用 | 每个文件一条签名追加日志，离线补差时逐条验签 |
| 限速 | 可用 | 单文件限速与全局限速（上行、下行），下发内核后读回校验 |
| 跨平台 | 可用 | Android 与 Windows 互通，同一协议、同一内核 |
| 公开种子 | 未实现 | 只有设计文档 `docs/design/public-swarm.md`，客户端里没有入口 |

## 平台与最低版本

| 平台 | 要求 |
| --- | --- |
| Android | minSdk 26（Android 8.0），targetSdk 37，只打 arm64-v8a。Android 17 首次启动要授权「本地网络」 |
| Windows | x64，Windows 10 及以上，需要 WebView2 Runtime（Windows 11 自带）。绿色版自带 jlink 精简 JRE，目标机不用装 Java |

## 构建

公共前置：JDK 17。Windows 下的脚本按 Git Bash（MSYS）写，一律用 `bash scripts/xxx.sh` 跑。

Android 另需 Android SDK（platform 37 与 build-tools）和 NDK `28.2.13676358`：

```bash
# 1. 自编 arm64 原生库。官方 Maven 的 .so 在 Android 13 会 Scudo abort，所以自己编；
#    产物目录名 run5-sde1 是 app/build.gradle.kts 里写死的路径。
bash tools/build-libtorrent-android/build.sh --out "$PWD/artifacts/build-libtorrent/run5-sde1"

# 2. 打 APK，最后一行打印 APK 绝对路径
bash scripts/build-android.sh

# 3. 装到在线真机并启动
bash scripts/install-android.sh
```

SDK 或 NDK 不在默认位置时，用 `ANDROID_HOME` 和 `ANDROID_NDK_ROOT` 指过去。

Windows 壳另需 MSVC（BuildTools 2022）、CMake 3.16 及以上、Ninja：

```bash
bash scripts/build-windows.sh     # windows/build/Huchuan.exe，首次会从 NuGet 拉 WebView2 SDK
bash scripts/package-windows.sh   # dist/Huchuan/：exe + daemon.jar + 精简 JRE
```

跨网发现的 tracker 用仓库里的 `worker/`，部署见 `docs/deploy-cloudflare.md`（`cd worker` 后 `npx --yes wrangler@3 login`、`npx --yes wrangler@3 deploy`）。

## 验收

三条脚本，最后一行打印 PASS 或 FAIL，退出码 0 或 1。

| 脚本 | 验什么 | 需要什么 |
| --- | --- | --- |
| `bash scripts/m1-headless.sh` | 两个 JVM 进程建群、后 8 位加入、32 MiB 传输、SHA-256 比对、评论往返，并断言 DHT 关闭 | JDK 17，不需要设备 |
| `bash scripts/m1-windows.sh` | 两个 daemon 全程只走 HTTP 与 WS：建群、加入、共享、下载、评论、限速读回、tracker 接线 | Windows 与 MSVC |
| `bash scripts/m1-device.sh --quick` | 真机与桌面 JVM 对端互通，P0 自检到 P6 成员页，两端 SHA-256 一致 | 一台 arm64 真机与 adb |

`scripts/m2-team-verify.sh` 与 `scripts/verify-targetsdk37.sh` 绑定了具体设备序列号（环境变量可覆盖），用前先读脚本头部注释。

## 隐私边界

- 私有群里每个 torrent 固定带 `DISABLE_DHT | DISABLE_LSD | DISABLE_PEX`（`0x380000`）。会话层关闭 DHT 与 LSD、清空 bootstrap 节点、关闭 UPnP 与 NAT-PMP，群的 infohash 不会进入任何公网发现面。
- `ip_filter` 只放行私网与回环地址，因此当前版本的文件传输只在同一局域网内成立。跨网发现（自建 Worker tracker）与手动 IP:port 已在代码里，但公网方向的 BitTorrent 连接被这条 filter 挡着，把群成员的公网地址并入 filter 还没做。
- mDNS 广播只带版本、群 id、BT 端口与会话标识，不带群名，也不带在线人数。
- 跨网发现默认开着，默认地址是作者部署的一个 Worker 实例（`app/.../AppPrefs.kt`、`daemon/.../SettingsStore.kt` 里的默认值），不是本仓库自带的公共服务。它能看到你的公网 IP、由邀请码派生的群标识、设备 id 前缀与端口。要自己掌握这一跳就部署 `worker/` 换成自己的地址，或在设置里关掉「跨网发现」。

## 许可证

MIT，全文见 [LICENSE](LICENSE)。第三方组件、版本、许可证据与是否分发见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。
