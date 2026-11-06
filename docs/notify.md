# 通知三档（P7）

P7 的逐群三档从「只存设置」变成了真的发系统通知。实现全在 `app/`：
`notify/GroupNotifier.kt`（渠道与投递）、`notify/NotifyPermission.kt`（运行时权限）、
`notify/NotifyRoute.kt`（点击跳转）、`kernel/M1Runtime.kt`（触发判定）。

## 1. 档位 → 系统行为

| 档位 | 枚举 | 渠道重要性 | 系统通知 | 声音 / 横幅 | 应用内未读 |
| --- | --- | --- | --- | --- | --- |
| 系统气泡 | `SYSTEM_BUBBLE` | `IMPORTANCE_HIGH`（4） | 发 | 有声音、有 heads-up、锁屏可见 | +1 |
| 仅应用内 | `IN_APP_ONLY` | `IMPORTANCE_LOW`（2，建着不用） | **不发** | — | +1 |
| 静音 | `MUTED` | `IMPORTANCE_LOW`（2） | 发，但 `setSilent(true)` | 无声音、无 heads-up | +1 |

- 未读数与通知档无关：内核按「可见 op + 读游标」现算（`GroupRuntime.unreadCount`），
  通知权限被拒、档位是「仅应用内」时 P2 角标照常 +1。
- 「静音」的口径是**静默投递**（低重要性 + `setSilent(true)`）：不弹不响，通知仍留在通知栏，
  点开能进对应页面。任务卡里的「不进通知栏」如果按字面实现就等于「仅应用内」，两档会没有区别；
  这里按任务卡括号里的实现口径（低重要性 + setSilent）落地，产品文案若改口径需要 L0 拍板。
- 「气泡」= Android 的 heads-up（IMPORTANCE_HIGH + 声音），不是 Android 的 conversation bubble。

## 2. 权限（Android 13 / API 33+）

- `AndroidManifest.xml` 声明 `POST_NOTIFICATIONS`；`NotifyPermission.enforced` 只在 API ≥ 33 生效，
  API < 33 一律视为已授权（老系统没有这个权限概念）。
- 请求时机：**首次进入 P2**（`HomeScreen` 的 `LaunchedEffect`）或**首次要发通知但没有权限**
  （`M1Runtime.dispatchNotify` 里的 `notify_blocked`）。两个入口发同一个信号，
  由 `HuchuanApp` 里唯一的 `ActivityResultLauncher` 弹系统框（后台不弹，等下次前台）。
- 只自动问一次（`AppPrefs.notifyPermissionAsked`）。拒绝之后不再打扰：应用内未读照常，
  想恢复系统通知只能去系统设置里打开。
- 日志：`event=notify_permission state=request|granted|denied sdk=33 ...`。

## 3. 渠道（按群分，不重建）

- channel id = `huchuan-group-<groupId>`（含 groupId，验收直接按它 grep dumpsys）；
  渠道名 = 群名；描述 = `群内新文件与新评论`；锁屏可见 `VISIBILITY_PUBLIC`。
- 改档位走 `syncChannel`：**同一个 id 再 createNotificationChannel 就是更新**，从不 delete ——
  删掉重建会清掉用户对这个渠道做过的设置，已有通知也会变成孤儿。
- 每次同步都读回系统里的**实际重要性**并打日志：
  `event=notify_channel group_id=... channel=huchuan-group-... level=muted want_importance=2 effective_importance=2 ok=true`。
  用户若在系统设置里改过该渠道，系统会锁住并忽略应用侧更新，此时 `ok=false`（不假装成功）。
- 一个群只有一条通知：`notify(tag = groupId, id = 7)`，新事件替换旧的；进入该群/该文件页时
  （`markGroupRead` / `markFileRead`）会撤掉这条通知。

**系统已知行为**：对**已存在**的渠道，应用把声音改回 null 时系统保留建渠道那次的
`mSound`（`mUserLockedFields=0` 也一样，实测 MIX 2S / Android 13）。这不影响行为 ——
`IMPORTANCE_LOW` 的渠道永远不发声，通知本身也 `setSilent(true)`；
dumpsys 里那条通知记录的 `groupKey=silent` 才是「静音」的可证伪证据。
要让渠道的 `mSound` 真的变 null 只能删了重建，与「不重建」冲突，故不采用。

## 4. 触发条件

两个触发点都在 `M1Runtime.LogcatEvents` 里，跟未读刷新同源：

1. `fileAdded`：群内出现**别人**加的新文件；
2. `opReceived`：收到**别人**对我参与文件的评论。

「我参与该文件」的判定（`participates`）：我加的 / 本机在做种或下载（`localRole != NONE`）/
已下载完成（内存标记或盘上有文件）/ 我对它评论过。只看群内所有人新文件会变成全量推送，
与「群级提醒」的语义相反。

压住通知的条件（`isViewing`）：当前在**这个群**的 P3、或**这个文件**的 P4，且应用在前台
（`ON_START/ON_STOP` 维护）。在 P6 成员页、P2、别的文件页都照发。

**入群追赶窗口**：加入群组时对端会把已有的 file.list 与历史 op 一起推过来，这些不是「刚发生的新事」。
`M1GroupStore.joinGroup` 在发起加入时打开窗口（`beginJoinCatchup`），`joined` 回调后
再留 10 s 尾巴覆盖 sync 补齐，窗口内的文件/评论只记 `event=notify_skip ... reason=catchup`。
没有这个窗口时，入群瞬间会为群里那份素材弹一条通知（实测），还会把权限框顶到 P3 上面。

## 5. 点击通知

- 通知的 `contentIntent` → `MainActivity`（`launchMode=singleTop`），extra 是
  `huchuan.notify.group` / `huchuan.notify.file`。
- `onCreate` / `onNewIntent` 把目标放进 `NotifyRoute.pending`，`HuchuanApp` 的 `LaunchedEffect`
  消费它：有 fileId 进 P4（返回栈 = P2 / P3 / P4），否则进 P3。冷启动时最多等 15 s 等内核读完
  `state.json`，避免 P3/P4 因为 `group == null` 立刻弹回 P2。
- 日志：`event=notify_intent ...`（Activity 收到 intent）、`event=notify_open ... waited_ms=N`（真正跳转）。

## 6. 持久化

`SharedPreferences("huchuan")`：

| key | 值 |
| --- | --- |
| `notify.<groupId>` | `SYSTEM_BUBBLE` / `IN_APP_ONLY` / `MUTED`，缺省 `SYSTEM_BUBBLE` |
| `notify_permission_asked` | POST_NOTIFICATIONS 是否已自动请求过 |

冷启动时 `restoreNotifyChannels` 按落盘档位把每个群的渠道同步一次（群名/档位可能改过），
日志 `event=notify_channels_restored n=<群数>`。

## 7. 日志口径（验收脚本 grep 这些）

| event | 含义 |
| --- | --- |
| `notify_channel` | 渠道已建/更新，带 want/effective importance |
| `notify_decision` | 三档判定结果：`level=system_bubble action=post` / `level=in_app_only action=in_app_only` |
| `notify_alert` | 真的投递了，带 `silent=` / `headsup=` / `file_id=` |
| `notify_blocked` | 有权限之外的原因发不出去（`reason=permission_denied`） |
| `notify_skip` | 有意不发：`reason=self|viewing|not_participating|catchup` |
| `notify_permission` | 权限请求/授权/拒绝 |
| `notify_intent` / `notify_open` | 点通知进来的 intent / 实际跳转 |
| `ui_view` | 当前页面（`group_id=-` 表示不在群页），验收据此确认「人不在该群/该文件页」 |

## 8. 构建与验收

常规：

```bash
bash scripts/build-android.sh          # 出 app/build/outputs/apk/debug/app-debug.apk
bash scripts/verify-notify.sh          # 一条命令跑完，最后一行 PASS / FAIL
```

`scripts/verify-notify.sh` 会自己装机（**不带 -g**，为了真看到 POST_NOTIFICATIONS 授权框）、
起桌面 JVM 对端、跑三档 + 点击 + 撤权五组断言。证据写在 `artifacts/notify/`：

```
artifacts/notify/
  run.log                    整轮输出（CHECK PASS/FAIL 逐条）
  screenshots/               p2-notify-permission / p2-system-bubble / shade-system-bubble /
                             p2-in-app / p2-muted / p4-from-notification ...
  dumpsys/                   1-system-bubble / 2-in-app-only / 3-muted / 4-permission-denied
  logs/                      desktop-S|D1..D4 的对端日志
  ui-dumps/                  各步骤的界面树
  payload/                   本轮随机素材
```

**隔离构建**：主树的 `core/` 在别的 agent 手里改到编不过时，可以在
`artifacts/notify/build-tree`（`git worktree add --detach artifacts/notify/build-tree HEAD`）
里用同一份 `app/src` 出包：

```bash
git worktree add --detach artifacts/notify/build-tree HEAD
rm -rf artifacts/notify/build-tree/app/src && cp -r app/src artifacts/notify/build-tree/app/src
mkdir -p artifacts/notify/build-tree/artifacts/build-libtorrent/run5-sde1
cp artifacts/build-libtorrent/run5-sde1/libtorrent4j.so artifacts/notify/build-tree/artifacts/build-libtorrent/run5-sde1/
cd artifacts/notify/build-tree && bash scripts/build-android.sh
```

`verify-notify.sh` 会在两个 APK 里取修改时间更新的那个（`NOTIFY_APK=<path>` 可显式指定）。

## 9. 已知限制 / 未决

- 渠道 `mSound` 的系统保留行为见 §3（不影响发声行为）。
- 「静音」仍会进通知栏（产品文案口径见 §1，待 L0 确认）。
- 通知正文只用系统默认铃声/声音，没有自定义提示音；没有按群分组（一个群一条通知，够了）。
- 没有做「通知点开后把整个群的未读清零」之外的清理策略：点通知进 P4 只清该文件未读，
  与在应用里点文件的行为一致。
- 触发不覆盖「评论的回复链」细分：任何别人对我参与文件的评论都算（含回复）。
