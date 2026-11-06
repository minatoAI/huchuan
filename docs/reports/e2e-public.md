# E2E · 公开种子：A 发布 → B 纯磁力下载 → 两端 SHA-256 一致

- 版本：2026-11-07 · 执行层工程 agent · 前置：docs/core.md §3.6 / §3.9
- 结论：**首次端到端验收通过。** B 只用后 8 位加入，从 join.ok 拿到 scope=public 的磁力条目，经 A 的公开会话（pubBtPort 直连 + BEP 9）取元数据并下完 32 MiB；两端 SHA-256 一致，这条公开文件的传输面没有私有会话参与。反证 `publish_private` 变异 16 条断言变红、脚本 FAIL。
- 装置：`bash scripts/e2e-public.sh`（M1Harness A3/B3，两个独立 JVM）；证据 `artifacts/e2e-public/{acceptance,log-A3,log-B3,mutant}.log`。
- 本机实测：正向 17 s（Gradle 增量；冷构建约 1–2 min），变异 9 s。
- 回归：既有 `bash scripts/m1-headless.sh` 35 条 CHECK + SelfTest 376 条全 PASS（`artifacts/m1/regression.log`；本轮只加 A3/B3 角色与默认关闭的 core 钩子）。

## 读数

| 项 | 值 |
|---|---|
| 私有 / 公开 infohash | `034388ea…a906` / `afb8eb03…c2cc` |
| A / B 公开会话端口 | 6881 / 6882 |
| A publish → B metadata_arrived | 2 440 ms |
| B 加入 → 元数据到手 | 531 ms |
| B 公开会话下载 32 MiB | 1 434 ms，payload_downloaded=33 554 432 |
| A 公开会话上传 / 私有会话上传 | 33 554 432 / 0 |
| SHA-256（两端日志 + 脚本复算） | `ad5cfbff91a0c299b43bfbfda003e066c7a8e2bd4984c7b8913f729f78d65dad` |

## 断言表

| 断言（CHECK 名） | 钉住什么 | 怎么红 |
|---|---|---|
| `mutant.off` / `mutant.active` | 正向跑确认钩子没开；反证跑确认钩子生效 | 钩子没接进 `publishPublic` |
| `A.publish_ok` | 返回新公开 infohash，且 ≠ 私有 | 变异 `publish_private`（本次实测红） |
| `A.private_kept_public_entry` | A 私有条目仍在做种；公开条目 scope=public、有 magnet、无内联种子 | 发布覆盖/删除私有条目 |
| `B.public_entry_scope_magnet` | B 的条目 scope=public、magnet=yes、inline_torrent=no，infohash 与 A 一致 | 广播带内联 .torrent / 丢 scope |
| `B.add_public_magnet_duplicate` | `addPublicMagnet` 对群内已有 infohash 返回 duplicate（§3.6） | 去掉判重（会多建 torrent） |
| `B.a_pub_bt_port_matches` | B 收到的 pubBtPort = A 公开会话端口 | 去掉 pubBtPort / 报 0 |
| `B.pub_session_dial` / `B.no_private_dial` | 至少一条 `bt_connect_peer … sess=pub`，没有 `sess=priv` | 公开条目 early-return 或回落私有 btPort |
| `B.pub_session_download_start` / `B.no_private_download_start` | 激活走 `sess=pub`，没有 `sess=priv` | 公开种子加进私有会话 |
| `B.private_session_has_no_public_torrent` | B 私有会话 `status=missing`、private_torrents=0 | 公开种子加进私有会话 |
| `B.metadata_after_publish_not_local` | 激活前无 meta 缓存；元数据 ts > A publish 且 > B join；缓存 mtime > A publish | 预拷 .torrent / 本地缓存取元数据 |
| `B.metadata_via_public_swarm` | `pub_background_metadata … sess=pub`、`private=false` | 元数据走私有会话 / 伪造 |
| `B.downloaded_bytes_from_public_session` | B 公开会话 payload_downloaded=33 554 432 | 本地拷贝 / 私有会话下载 |
| `A.public_session_uploaded` / `A.private_session_uploaded_zero` | A 公开会话上传 32 MiB、私有会话 0 | B 走私有会话 / 数据没从 A 出去 |
| `sha256.equal` | A 日志、B 日志、两端文件独立 sha256sum 四值一致 | 任一字节损坏 |
| `B.report_received_by_A` / `process.rc_0_0` | B 的 SHA 评论被 A 收到；两进程 rc=0 | 评论链路断 / 任一端失败 |

## 变异结果

```
$ E2E_MUTANT=publish_private bash scripts/e2e-public.sh
M1 A3 public_publish_done ok=false reason=hash_collision ...
M1 A3 FAIL reason=publish_failed reason=hash_collision
CHECK FAIL A.publish_ok ...
FAIL (16 checks failed)  run=20261107T000236-97480-14954
```

core 里的钩子是默认关闭的 `-Dhuchuan.mutants=publish_private`（`M1Kernel.publishPublic` 建种时把 private 位翻回 1）；正向 `mutant.off` 通过，反证 16 条 CHECK FAIL、末行 FAIL、rc 1。

## 风险与未决

- **同机 loopback**：A/B 两个 JVM 在同一台机器，公开会话直连 `127.0.0.1:<pubBtPort>`；跨 NAT / 真公网组合未测（DHT/tracker 发现本轮没有参与）。下一步真机/跨网验收。
- **addPublicMagnet 不是群内条目的下载入口**：它对群清单里已有的 infohash 按 §3.6 判 duplicate；群成员下载公开条目的生产路径是 `startDownload`（内部同一条 `PublicBtSession.addMagnet`）。本轮按生产路径验收并单独断言 duplicate 口径；若要求直接调 addPublicMagnet 下完，需改 API 语义（不推荐）。
- 元数据来源由「激活前无缓存 + 时间戳 + 公开会话上传计数」三条共同证明，没有抓 BEP 9 报文本身。
- `A.member_joined` 出现两次（join 与随后的 member.name 各触发一次，既有行为，不影响本场景断言）。
