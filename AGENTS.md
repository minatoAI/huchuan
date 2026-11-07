# bt-share-app — 项目治理规范

## 0. 层级

- L0 用户（老板）：定方向、拍板、验收。
- L1 本会话（管理层 / 秘书 / CEO）：拆解、派单、验收、沉淀状态、向 L0 汇报。
- L2 子 agent（执行层）：一个职能一个 agent，彼此隔离；L2 可以自行派生 L3，L1 不管其内部组织。

L1 不做实现：不写业务代码、不做细粒度交互或视觉设计、不手动点界面调试。
L1 只做：任务分解、边界设定、验收标准、结果核验、状态维护、对外汇报。
管理层的核验可以用只读命令（ls / adb / grep / 跑验收脚本），这不算"做实现"。

**只向 L0 请示这四类事**：花钱、用他的账号或设备、产品方向取舍、不可逆动作（删除、发版转正、清数据）。
其余（做不做某件事、记录不记录、先做哪个、技术方案怎么选）由 L1 自己决定并承担后果。
问"要不要记录"这种问题本身就是失职——认为该记录就记录，认为不该就不记。

## 1. 执行层纪律

1. 子 agent 之间不共享上下文、不直接通信、不做协同式开发。每个任务必须自包含。
2. 同一时刻，同一文件路径只归一个 agent 写。跨域改动由 L1 重新分派。
3. 只认落盘产物：报告、代码、脚本、截图、日志必须写进工作区；回复里说"我做了"不算。
4. 每个 agent 的汇报固定四段：结论 / 证据（文件路径 + 命令 + 输出摘要）/ 风险与未决 / 建议下一步。
5. 研究型 agent 只读，不改代码。工程型 agent 不顺手做架构决策，遇分叉停下来上报。

## 2. 派单模板（L1 发给 L2 的 prompt 必须包含）

- 目标：一句话，可验收。
- 范围：允许改的目录 / 禁止碰的目录。
- 输入：要读的文件、已知环境事实。
- 产出：具体路径与格式。
- 验收：L1 用什么命令或检查判定通过。
- 边界：不许做的事（重构、加依赖、改他人文件……）。

## 3. 状态

`docs/STATE.md` 是唯一事实来源：阶段、在飞任务、已完成、已知风险、待决策。
每轮结束更新。子 agent 不写 STATE。

## 4. 产品 UI：极简化（硬约束）

1. 一个页面只放一个功能；该功能只有一个启动/关闭入口。
2. 每个页面必须有入口与出口：进入路径明确，返回或回首页只需一次操作。
3. 奥卡姆剃刀：对完成操作没有帮助的元素（说明文字、装饰、冗余按钮、二次确认）一律删。
4. 文案只写"是什么 / 会怎样"，不写教程、不写营销、不写解释性废话。
5. 动效服务于状态变化，不做炫耀性动画。
6. Android 用 Material 3 与 Now in Android 的组件、动效范式；Windows 用 WebView 壳承载同一套信息架构。
7. 机器能判断的事，不要让用户点。自检、放行、重连一律自动完成；只有不可逆破坏动作才允许中断用户。

## 5. 工程约束

- 目标平台：Android 真机（Android 13 / API 33、Android 17 / API 37）＋ Windows 桌面。
- 传输内核：BitTorrent。私有模式禁用 DHT / PEX / LSD 等公网发现，只用内部网络与手动连接。
- 跨平台必须互通：同一协议、同一内核或等价实现。
- 构建可脚本化、可重复。不接受"我这里能跑"。

## 6. 调试：自动化优先

1. 能用命令验证的，不进手工流程；截图、点击、断言全部脚本化。
2. 功能交付必须附带可重跑的验证脚本（安装 → 启动 → 操作 → 断言 → 留证）。
3. 真机是唯一事实：模拟器结论不能替代两台真机上的结论。
4. 日志分级、结构化，验证脚本能直接 grep 关键事件。
5. 工具先于功能：调试链路的短板会放大成整个项目的返工。
6. 真机操作统一走 tools/devkit（devices / install / launch / screenshot / ui-dump / tap-text / wait-text / logcat），不要各自手搓 adb。
7. 禁止按镜像名全局清理进程（`taskkill /IM java.exe` 之类）——本机同时跑着多个 agent 的 JVM。只能按 PID 或命令行精确匹配，清不掉就报告。

## 7. 沟通

- 全部用中文。
- 汇报先说结论与证据，再说计划；不写过程流水账。

## 8. 验收防伪（来自旧项目事故）

1. 检查必须可证伪：grep / 脚本断言要带后缀白名单，排除 build、vendor、二进制产物；二进制里的符号不算源码证据。
2. 代码是事实源，文档不是。凡"文档说 X"，动手前回代码核一次；文档与代码冲突时以代码为准并当场修文档。
3. 关键节点由不参与实现的独立 agent 复核。同一上下文里的自审，不算验收。
4. 一个事实只留一份：禁止同一文件多副本，发现副本即列为缺陷。

## 9. 技术基线（2026-11-06 定，改基线须 L0 同意）

- 内核：libtorrent4j 2.1.0-39（MIT）。Windows 用 Maven 制品；Android arm64 用自编 .so（官方 .so 在 Android 13 触发 Scudo abort，旧项目 10×343MB 对照验证过）。
- 架构 A：单一 JVM 内核库 + 本地 HTTP/WS 控制面。Android 进程内嵌；Windows 独立 daemon + 原生壳。
- 私有与公开必须两套 SessionManager（settings_pack 是会话级）。私有侧固定关 DHT / LSD / uTP。
- 端口映射（UPnP / NAT-PMP / PCP）默认关；只在「跨网分享窗口」内按需开，窗口结束立即撤销，端口随机。libtorrent 2.1 的 PCP 藏在 natpmp 模块里（先 PCP 后回落），不需要额外开关。
- ip_filter 必须是「私网段 + 消息层确认的群成员公网地址」；只放行私网等于让跨网在代码层面永远不可能成功。
- 永久不做：TURN / 任何数据中继、TCP 同时打开、端口预测。UDP 打洞只在消息面做，排 M3 评估。
- PEX 没有会话级开关（settings_pack 里没有这一项，实测 64 个 bool 全查过），必须 per-torrent flag：0x380000（DISABLE_DHT | DISABLE_LSD | DISABLE_PEX）。
- 私有种子用 **v1**：v2 没有性能收益（K1 实测 v1/v2 起传与完成时间同分布），兼容面却更窄。
- 单文件限速 = 一文件一 torrent + setUploadLimit；手动加 peer 走 swig connect_peer（高层 API 没有）。
- Android：AGP 9.5.1 / Gradle 9.6.0 / JDK 17 / compileSdk 37 / targetSdk 37 / minSdk 26 / Compose BOM 2026.09.00。
- API 37 必须声明 ACCESS_LOCAL_NETWORK（运行时权限，系统归在「附近的设备」）。**未授权时不得先起 M1Runtime**——内核一启动就开 mDNS，带错状态活到进程结束，之后授权也不恢复。
- Windows：C++ Win32 + WebView2（本机 BuildTools 2022 / MSVC 14.44 / Win SDK 10.0.26100 / cmake 3.31.6 / ninja 1.13.2 / WebView2 156.0.4314.58 均已就位，安装成本 0）。
- 发现层三层：mDNS（局域网）+ 自带 tracker（跨网）+ 手动连接。
- **中国大陆网络下 workers.dev 不可用**（DNS 污染 / SNI 阻断），入口统一走 **pages.dev**（Pages Functions 转发到 Worker）。客户端默认地址用 pages.dev 域名，不用 workers.dev。
- 公开种子走**独立 PublicBtSession**，不得复用私有 BtSession（BtSession 里私有 flag 是硬编码的）。公开侧：DHT 开、PEX 开、LSD 关、uTP 开、加种前一律按 TorrentInfo.priv() 决定会话；端口 6881 起、目录 <data>/public、独立日志、按需启停；限速每会话各自封顶（libtorrent 无跨会话令牌桶，总峰值最多 2×）。
- 评论：签名追加日志 + Lamport 全序 + 墓碑，不上通用 CRDT。
- 邀请码：16 字符 Crockford Base32（80 bit），HKDF 派生组密钥。

## 10. 运行约束（会话层）

- **并发目标 2–3 个**，稳定后继续往上探。只有一个 agent 在跑是浪费；限流要靠退避解决，不靠降并发。
- **文件域冲突用 git worktree 隔离**：`bash scripts/wt.sh new <task>` 建 `.worktrees/<task>` 与分支 `wt/<task>`，把子 agent 的 cwd 指过去；完成后 `bash scripts/wt.sh merge <task>`。只读任务与纯文档任务可以直接用主树。
- 派 worktree 任务时必须在 prompt 里写明：**每条命令显式带 workdir，或先 cd 到工作树**。子 agent 的 shell 默认 cwd 可能是主树，不写清就会在主树上跑构建。
- 新 worktree 里 `./gradlew` 会在配置期失败：`artifacts/build-libtorrent/run5-sde1/libtorrent4j.so` 被 gitignore，不在工作树里。派单时要求先 `cp` 一份过去（sha256 97d2663a…）。
- **子 agent 静默失败多半是上游负载波动**（未必针对本账号）：单次失败不等于该降并发。积极重试，30–60 秒后重派同一单，抢负载回落的空档；同一单最多重试 4 次。
- **并发维持在 3 起步，稳定就继续加**（4、5…）。失败率上升时先增加重试次数，再考虑降档；不要一遇错就退回串行。
- 重试要留痕：每次重派在 STATE 或任务记录里写一行（时间、档位、结果），几次之后就能看出哪一档稳定。
- 失败时先看错误码再决定动作：429/静默死按上一条重试；编译/测试失败改任务；连续多次同一任务都死在同一处，才怀疑任务本身有问题。

## 11. 目录地图

| 路径 | 是什么 | 谁在写 |
| --- | --- | --- |
| app/ | Android Compose UI（页面、主题、资源） | UI agent |
| core/ | JVM 内核：协议、群、BT 会话、评论日志 | 内核 agent |
| harness/ | 无头集成夹具（两进程跑全流程） | 内核 agent |
| windows/ | C++ Win32 + WebView2 壳 | Windows agent |
| tools/devkit/ | 真机调试 CLI，所有真机操作唯一入口 | 工具 agent |
| tools/build-libtorrent-android/ | arm64 .so 可复现构建 | 构建 agent |
| assets/brand/ | 品牌资产（图标、许可） | 设计 agent |
| docs/design/ | 产品规格、协议 | 设计 agent |
| docs/recon/ · docs/research/ · docs/tooling/ | 考古、选型、调试方法论 | 研究 agent |
| scripts/ | 构建与验收脚本 | 各工程 agent（一人一文件） |
| spikes/ · artifacts/ | 一次性验证与证据，不进版本库 | 谁跑谁写 |


