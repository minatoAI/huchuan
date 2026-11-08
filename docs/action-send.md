# 系统分享入口（ACTION_SEND）· 执行层验收

- 2026-11-07 · Mi MIX 2S（5b94673a / API 33 / MIUI）· 依据 [link-share.md](design/link-share.md) §2、§2.3、§2.4
- 结论：别的应用「分享 → 互传」能接住任意类型；文件**当场**拷进应用私有目录，再按「真实群」数量落地——**0 个真实群直接落个人空间「我的分享」**（不要求先建群，可立刻发链接）、1 个真实群直接进该群 P3 并开始做种、多个真实群落 P2 点一个。个人空间不计入群数。没有确认页。（2026-11-08 更新：个人空间落地已真机验证，见 artifacts/share-in/）

## 实现（只改 app/）

| 面 | 落点 |
| --- | --- |
| 声明 | [AndroidManifest.xml](../app/src/main/AndroidManifest.xml)：MainActivity 上 SEND + SEND_MULTIPLE × `text/plain` + `*/*`；不声明 VIEW，不申请存储权限 |
| 接收 + 拷贝 | [ShareInTake.kt](../app/src/main/kotlin/app/huchuan/share/ShareInTake.kt)：ClipData 与 EXTRA_STREAM 都读，收到即在后台线程整份拷进 `files/m1/share/`（授权只在那一次 intent 有效，§2.4），任一个失败整批回滚 |
| 落点 | [HuchuanApp.kt](../app/src/main/kotlin/app/huchuan/HuchuanApp.kt)：`ShareInRoute.pending` + 两段 LaunchedEffect；入库复用 `M1Runtime.shareFile`，与 P3「添加文件」同一条做种路径 |
| 失败 | 0 个 / 0 字节 / >1 GiB / >10 个 / 读不到：各一句 toast，不弹窗、不崩 |

## 两处与设计稿不同（以真机为准）

1. **接收方是 MainActivity，不是透明的 `ShareInActivity`**（§2.1）。实测：App 进程还活着时系统把 SEND 递给栈顶实例（`am` 报 `its current task has been brought to the front`），新声明的空壳 Activity 根本不创建，分享静默丢失；只有冷启动走得到它。挪到 MainActivity 后冷热都收得到，「返回回到空壳页」的问题也不存在了。
2. **§2.3 的落点是链接分享**，本轮按任务要求先落群（P8/P9 + Worker 信令未实现）。

## 验收

`SERIAL=5b94673a bash ../artifacts/action-send/verify.sh`：推 1 MiB → MediaStore 扫码 → `am start` 带 content:// 与 FLAG_GRANT_READ → 断言 logcat 与 P3。

| 用例 | 断言 | 证据 |
| --- | --- | --- |
| A 冷启动 1 个群 | `receive count=1` → `copied` → `import_done ok=1/1`；P3 出现 1.0 MB「做种中」 | [A-cold.png](../artifacts/action-send/shots/A-cold.png) · logcat-run-a-cold.txt |
| B 热启动（App 在后台） | `LaunchState: HOT`，同样 ok=1/1 | [B-warm.png](../artifacts/action-send/shots/B-warm.png) · logcat-run-b-warm.txt |
| C 多选 2 个 | `count=2` → `ok=2/2`，P3 两条 | [C-multi.png](../artifacts/action-send/shots/C-multi.png) · logcat-run-c-multi.txt |
| D 一次 11 个 | `share_in_reject reason=too_many` + toast「一次最多 10 个文件」，无崩溃 | [D-reject-too-many.png](../artifacts/action-send/shots/D-reject-too-many.png) · logcat-run-d-reject.txt |
| E 0 个群 | 落 P1 → P5，建群后自动 ok=1/1 | [E-zero-group-p1.png](../artifacts/action-send/shots/E-zero-group-p1.png) · [E-zero-group-imported.png](../artifacts/action-send/shots/E-zero-group-imported.png) · logcat-run-e-zero-group.txt |
| F 2 个群 | 落 P2 + toast「选一个群组」，点群后 ok=1/1 | [F-pick-toast.png](../artifacts/action-send/shots/F-pick-toast.png) · [F-imported.png](../artifacts/action-send/shots/F-imported.png) · logcat-run-f-multi-group.txt |

## 已知限制

- `am` 不能把 EXTRA_STREAM 迁成 ClipData，多选里只有 data 那条 content:// 拿得到授权；C 组因此用应用私有目录的 `file://`（真实分享由发送方带的 ClipData 授权，代码两条都读）。
- type=null 的 SEND 收不到：§2.1 规定没观察到就不加无 `<data>` 的 filter。
- 同一文件重复分享会落成 `name (2).ext` 并生成新 infohash（文件名进 v1 info dict），与 SAF 导入一致。
- 链接分享（P8/P9 + token/Worker）未实现，[link-share.md](design/link-share.md) §2.3 那条路还没接。
