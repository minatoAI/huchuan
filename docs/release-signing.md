# Android release 签名

release APK 由仓库外的 keystore 签名；debug keystore 只服务日常构建。

## 放在哪

| 文件 | 位置 |
| --- | --- |
| keystore | `E:\agentHome\huchuan-keys\huchuan-release.jks`（PKCS12 / RSA 4096 / alias `huchuan` / 有效到 2056-10） |
| 口令与指纹 | 同目录 `keystore-info.txt`、`keystore.properties` |

`keystore.properties` 四个键：`storeFile` `storePassword` `keyAlias` `keyPassword`。
`app/build.gradle.kts` 查找顺序：`-Phuchuan.keystoreProperties=<path>` → 环境变量 `HUCHUAN_KEYSTORE_PROPERTIES`
→ 默认路径 `E:/agentHome/huchuan-keys/keystore.properties`。三处都没有时 release 降级为不签名
（产出 `app-release-unsigned.apk`）并打印一条 `[签名]` 警告：CI 与别人 clone 的仓库照样能构建，debug 不受影响。

证书 SHA-256：`2B:78:20:1B:42:4C:D9:40:B6:04:B1:53:A5:C9:1F:65:08:E9:30:12:FB:15:F3:E7:C9:95:F8:25:02:E9:16:3D`

## 备份

至少两份离线副本，keystore 与口令**分开存**（不同介质、不同地方）。口令丢失等于 keystore 丢失。
这些文件永不进 git、不发群、不进 CI artifact；仓库的 `.gitignore` 管不到仓库外的路径，只能靠人守。
**当前（2026-11-07）只有本机这一份，离线备份尚未做。**

## 丢了会怎样

APK 的身份就是这张证书。keystore 或口令丢失后换新 key 签名，Android 拒绝覆盖安装
（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），用户必须先卸载——**群组、评论、已下载文件全清**。
在老用户身上等于强制清数据，所以备份不是可选项。

## 轮换

1. 新建 keystore（`keytool -genkeypair ... -alias <新别名>`），旧的不删，两个并存。
2. 新的 `keystore.properties` / `keystore-info.txt` 同样放仓库外。
3. 用新 key 出的包只能全新安装：发版说明写明"本次起换签名，需卸载旧版（会清数据）"。
4. 过渡期用 `--keystore-properties` 指定签哪把。
5. 证书 SHA-256 变了必须写在发布页，用户与工具都靠它核对。

## 构建

```bash
bash scripts/build-android-release.sh                 # 构建 + apksigner 验签
bash scripts/build-android-release.sh --keystore-properties D:/keys/prod.properties
bash scripts/build-android-release.sh --no-verify     # 只构建，不验签
```

产出 `app/build/outputs/apk/release/app-release.apk`，并打印字节数、SHA-256、签名证书 DN 与 SHA-256
（`apksigner verify --print-certs`，SDK build-tools 37.0.0）。完整发版（改名、SHA256SUMS、发布说明）
走 `bash scripts/release.sh <version>`。真机验证记录见 `artifacts/release-signing/summary.md`。
