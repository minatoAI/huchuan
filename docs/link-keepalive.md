# 链接分享 · 后台保活与离页提示

- 2026-11-07 · Mi MIX 2S `5b94673a`（Android 13 / API 33）· `./gradlew :app:assembleDebug` 通过
- 范围：只改 `app/`；证据 `artifacts/link-keepalive/`

## 1. 规则

链接只在分享者进程活着、信令不断时 available。所以：**有分享在跑 = 前台服务在跑 = 通知栏常驻一条**；
离开 P8 不结束分享，只有用户主动点「结束分享」（页面内或通知内）才撤销链接并停服务；
进程真被系统杀掉，下次启动就把那批分享记成已结束 —— 不许出现「没人服务、界面还说等待访客」。

## 2. 落点

| 件 | 文件 | 要点 |
|---|---|---|
| 通知 | `link/LinkNotify.kt` | 渠道 `huchuan-link-active`（LOW、静音、无角标），与群通知 `huchuan-group-*` 分开；标题「正在分享 <文件名>」，正文剩余有效期（与 P8/P9 同一句 `expiryText`） |
| 服务 | `link/LinkKeepAliveService.kt` | dataSync 前台服务；启动即 startForeground；通知动作「结束分享」；onDestroy / onTimeout 回报控制器 |
| 开关 | `link/LinkKeepAlive.kt` | 池子：会话集合。加入 = 起服务，空了 = 停服务 + 撤通知；本端主动停不打「已结束」标记 |
| 记录 | `link/LinkStore.kt` | 新增 `ended`/`endedReason`；`listed()` 给 P9（含已结束行）；`endActive()` 冷启动清扫 |
| 控制器 | `link/LinkShareController.kt` | `startSession`→加入、`closeSession`→移除；`endFromNotification`；`REASON_PROCESS_RESTARTED` 清扫 |
| P8 | `ui/ShareLinkScreen.kt` | 结束分享按钮、已结束态、权限提示、返回 toast（BackHandler） |
| P9 | `ui/ShareManageScreen.kt` | 已结束行显示「分享已结束」，不再显示「被访问 n 次」 |
| 入口 | `link/LinkNotifyRoute.kt`、`MainActivity`、`HuchuanApp` | 点通知回 P8（singleTop + 待消费目标） |

## 3. 不变式

`池子里有会话 ⟺ 前台服务在跑 ⟺ 服务端看到的这条分享是 available`。加入/移除只在
`startSession`/`closeSession` 两个点，没有第三个入口。

| 触发 | 结果 |
|---|---|
| P8 打开且分享有效（含 P9 恢复会话） | 起服务 + 常驻通知；点通知回 P8，通知里「结束分享」= 撤销 |
| P8 返回 / 切后台 / 回桌面 | 服务不打断；返回时一次性 toast「分享继续，通知栏可随时结束」 |
| 页面内或通知栏「结束分享」 | 签名撤销 → 关会话 → 空池 → 停服务、撤通知；撤销请求失败（离线）也停，本机按已撤销处理并记 `link_end_offline` |
| 进程被杀 / force-stop | 下次启动 `endActive("process-restarted")`：记录标 ended，P9 显示「分享已结束」，服务端该链接转 offline |
| 服务被系统停（onDestroy 非本端、API 35 onTimeout） | 池里的分享标 ended + 关会话，P8/P9 立刻不可用 |
| 通知权限被拒（API 33+） | 服务照跑（`link_fgs_start visible=false`），P8 红字「离开此页可能中断分享」，不弹窗、不崩 |

## 4. 真机验收（表）

| 项 | 结果 | 证据 |
|---|---|---|
| 创建分享 → 常驻通知 | PASS（`dumpsys notification` 见 `channel=huchuan-link-active`、`mFgServiceShown=true`；`dumpsys activity services` 见 `LinkKeepAliveService isForeground=true foregroundId=8`） | `shade-A.png`、`shade-expanded.png`、`logs/dumpsys-notification-A.txt` |
| 返回离开 P8 → 服务/通知仍在、链接仍 available | PASS（`/api/share/<token>`=`available`；toast 见截图） | `back-toast.png`、`logs/api-after-back.txt` |
| 通知点结束 → 撤销 + 停服务 + 通知消失 | PASS（服务记录 0、通知记录 0、API `revoked`） | `logs/step-C-notify-end.log` |
| 通知权限拒绝 | PASS（`visible=false` 仍起服务；P8 一句话提示） | `20261107-125715-752-p8-notify-denied.png` |
| 进程被杀 → 标记已结束 | PASS（`link_end_orphans count=1`；P9 行「分享已结束」；API `offline`） | `20261107-125834-374-p9-ended.png`、`logs/step-E-process-kill.log` |
| 点通知回 P8 | PASS（`link_notify_open`→`link_notify_route`） | `20261107-125946-326-p8-from-notify.png` |

- 事件时间线：`artifacts/link-keepalive/logs/logcat-link-events.txt`（create / fgs_start / notify_stop / revoked / end_orphans）。

## 5. 已知限制

- 通知正文的有效期是投递那一刻的剩余值，进程长期活着不刷新（P8/P9 始终实时现算）。
- Android 15+ 的 dataSync 6 小时上限走 `onTimeout` → 标已结束；测试机是 API 33，这条只做了代码路径，没跑到。
- 用户从最近任务划掉 App（进程被杀）后分享不会自动恢复，只能重新建链接 —— 这是「不假装还在分享」的代价。
- P9 行不接「点行进 P8」（沿用旧限制）；已结束的行仍可点「撤销」清理。
