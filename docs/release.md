# 发布

GitHub Releases 是唯一发布渠道，不上应用商店。

## 版本号

- 形如 `0.1.0`，tag 带 `v` 前缀：`v0.1.0`；预发布用 `v0.2.0-rc1`，在 Release 上勾 Pre-release。
- 修 bug 抬末位，加功能抬中位，协议或数据格式不兼容时才动第一位。
- 版本号只写在 `app/build.gradle.kts`：`versionName` 与发布版本一致，`versionCode` 每次递增（同 `versionCode` 的包盖不上）。
  `scripts/release.sh` 发现版本号不一致会告警。

## 发布步骤

1. 合到 master，工作区干净。keystore 只准备一次，保管与签名口径见 [release-signing.md](release-signing.md)。
2. 一条命令收口：`bash scripts/release.sh <version>`。它自己调 `scripts/build-android-release.sh`（release keystore 签名）
   与 `scripts/package-windows.sh`（daemon.jar + Huchuan.exe + jlink 精简 JRE，自带启动冒烟），
   产物落 `dist/release/<version>/`，最后打印一条 `gh release create` 命令。脚本不联网、不建 tag、不推送。
3. 执行那条命令（带 `--draft`），在网页上把两个包各下一次、跑一遍校验，再转正。
4. 没装 Android 工具链的机器只能做半边：`--only win` 与 `--only apk` 各跑一次，都写进同一个目录。
5. 打 tag 与 push 由人做。
6. 签名脚本还不存在时，`release.sh` 会退回 `scripts/build-android.sh` 的 debug 包，并在终端与 NOTES.md 里写明「不是发布签名」；
   apksigner 验不过（没签名、装不上）则直接失败，不会把废包收进去。

CI（[`.github/workflows/build.yml`](../.github/workflows/build.yml)）自动的只有 Windows 绿色版。Android 一律手动：
runner 上没有自编的 arm64 `.so`，现编要下 NDK 再交叉编译 Boost 与 OpenSSL，30 分钟起步，不值得按 tag 自动烧。
tag 推送只建 **draft** release：Windows 包自动挂上，APK 由人补传。

## 产物

| 文件 | 内容 |
| --- | --- |
| `Huchuan-<version>-arm64-v8a.apk` | Android arm64-v8a，release keystore 签名 |
| `Huchuan-windows-x64.zip` | 解压出 `Huchuan/{Huchuan.exe, daemon.jar, runtime/}`，目标机不用装 Java |
| `SHA256SUMS` | 上面两个文件的校验值 |
| `NOTES.md` | 发布页正文，`gh release create --notes-file` 用它 |

## 校验

```bash
sha256sum -c SHA256SUMS                                        # macOS / Linux / Git Bash
Get-FileHash .\Huchuan-windows-x64.zip -Algorithm SHA256        # Windows PowerShell
apksigner verify --print-certs Huchuan-<version>-arm64-v8a.apk  # 看签名证书是谁
```

## 已知限制

- Android 只有 arm64-v8a：x86_64 模拟器与 32 位真机装不上。
- 真机验证覆盖 Android 13（API 33）与 Android 17（API 37）；其他 Android 版本未验。
- Windows 只在 Windows 11 x64 上验过；Windows 10、ARM Windows 未验。包没有代码签名，SmartScreen 会拦一次。
- 跨网传输只在同机与局域网验过；真实跨网（两端 NAT 类型各异）没实测。
- 覆盖安装认安装包签名：换了 keystore 之后必须卸载重装。
