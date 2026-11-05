# M1 验收装置审计（只读）

**结论：有条件可信。** 两进程是真实独立 JVM，SHA-256 由各进程独立重算，退出码正确落盘；
但"B 的 32 MiB 来自 A"没有任何断言覆盖，且 mDNS 那条结构上不可能 FAIL、DHT 那条检不出真正的失败模式。
按 AGENTS.md §8.1：14 条 CHECK 里 1 条（mDNS）结构上不可能 FAIL，1 条（dht_nodes）检不出它声称要检的失败模式，
1 条（sha256.equal）只证明两个字符串相等、不证明字节来源。
范围：scripts/m1-headless.sh（262 行）、harness/.../M1Harness.java（358）、SelfTest.java（299）、core/.../m1/{BtSession,M1Kernel,GroupRuntime,PeerServer}.kt 相关段。

## 1. SHA-256 是独立重算，还是读进程自己打印的串？

**独立重算，但证据仍由被测进程提供。** 严重度：中

- M1Harness.java:114-115 A 侧对 a-work/share/payload-32m.bin 做全流式 MessageDigest（:278-292）；:216-219 B 侧对 saved 文件同样重算。不是 libtorrent 的 info-hash 字符串。
- m1-headless.sh:191-197 脚本自身不重算：只 grep 两个进程各自打印的 hex 再比相等，无 64 位长度、无字符集校验。
- BtSession.kt:172-175 `savedFile` 返回 `File(task.savePath, task.name)`，靠路径拼接预测，不问 libtorrent 实际完成的文件路径。
- 绕过：把 harness 的 `sha256()` 改成直接 `return "0"` —— 两个日志都是 `sha256=0`，`[ -n ]` 与相等判断都成立，CHECK PASS sha256.equal (0)。

## 2. dht_nodes=0 来自 libtorrent session，还是自己写的一行日志？

**来自 session 状态，但这条断言不可证伪。** 严重度：高

- BtSession.kt:85 `manager?.dhtNodes() ?: 0L`：真取 session 状态，但 manager 为空时同样返回 0 —— "没起会话"与"DHT 已关"同值。
- M1Kernel.kt:100、:470 取样点只有 kernel_started 一处加每 2s heartbeat（HEARTBEAT_MS，:523）；M1Kernel.kt:101 同时打印 dht_running，脚本从不 grep 它。
- BtSession.kt:204 与 :206 同时设置 enable_dht=false 与空 bootstrap。
- m1-headless.sh:221-227 只要求两份日志合计样本 ≥2（A 或 B 单侧刷够即可），且 `grep -vc 'dht_nodes=0'` 是无锚点子串匹配。
- 绕过：删掉 `setEnableDht(false)`、保留 `setDhtBootstrapNodes("")` —— DHT 真开着却没有 bootstrap 路由器，dht_nodes 恒为 0，heartbeat 照样刷出 n≥2，CHECK PASS。

## 3. 怎么证明 B 的 32 MiB 真是从 A 传过去的？

**装置里没有任何传输方向证据，证不出来。** 严重度：高

- 全库无下载/上传字节计数断言：BtSession.kt:167-170 定义了 `peers()`，harness 一次都没调用；M1Harness 不打印任何 transferred/peer 字段。
- m1-headless.sh:185 只看 `bytes=33554432`；:191-197 只比两个 sha 字符串。
- M1Harness.java:265 载荷固定种子 `new Random(20261106L)`，:111-113 尺寸相同即复用旧文件 —— 载荷每轮字节完全相同，可脱离 A 复现。
- m1-headless.sh:89 `rm -rf a-work/a-data/b-work/b-data` 的返回码不检查（全文无 set -e）。
- 绕过：上一轮被 kill 的 run 在 Windows 上留下句柄，rm -rf 失败却不报错，`b-data/downloads/payload-32m.bin` 存活；本轮 B 加种后 libtorrent 初次 hash check 即 isFinished（BtSession.kt:162-165），零字节传输，`download_done bytes=33554432 sha256=同值` 全对。硬链接同理：没有断言看 inode、链接数或 peer 连接。

## 4. grep 断言有没有排除 build/ 与二进制？会不会匹配到旧日志？

**旧事故形态在这份装置里不存在（零源码 grep），但证据可离线重放。** 严重度：中

- m1-headless.sh 全文只 grep artifacts/m1/{log-A,log-B,selftest}.log（:102-254）；:76 的 find 只走 harness/src。旧项目"grep -r 扫到 build/*.exe"的路径不成立。
- 旧日志复用风险是真的：日志路径固定（:23-24），断言段（:140-246）是纯离线 grep；日志行内无 run id、无 commit、无 jar 标识，:55 选 jar 用 `ls ... | head -1`（按文件名字面），evidence 段（:253-254）也不绑定当轮。
- 绕过：改完 BtSession 重新构建后不重跑流程，直接对 artifacts/m1 里上一轮的 log-A / log-B 重放 :140-246 的 grep → 全 PASS。轻一档：两个 agent 同时跑本脚本（无锁）会互相截断同两个路径，断言读到两次 run 的混合内容。

## 5. 退出码与 PASS 判定会不会被吞掉？

**主链路没被吞，但有一条检查永远不可能 FAIL。** 严重度：中

- :13 `set -uo pipefail`（没有 set -e）；无 `|| true`、无 tee。RC 采集正确：:84-85 selftest、:123-124 B、:136-137 A 都取自直接命令或 `wait`；:257-262 依 FAILS 决定 0/1。
- 漏洞 A：:244-245 `[ ... ] && echo CHECK PASS || echo CHECK WARN`，WARN 不进 FAILS → mDNS 那条从头到尾无法让本轮失败。绕过：mDNS 失效只剩 WARN，脚本照样 exit 0。
- 漏洞 B：:209 `[ "$LATENCY" -le 3000 ]` 无下界。绕过：op 的 ts 落在未来（时钟偏移或重放旧 op）时 latency_ms 为负（M1Harness.java:348），CHECK PASS。
- 漏洞 C：M1Harness.java:139 A 无条件打印 `RESULT=PASS`，而 :141 可能 `exit 1`（inStore=false）。脚本不看 RESULT 所以不受影响，但 log-A.log 会同时出现 RESULT=PASS 与失败退出码，只看日志的人（含后续 agent）会误判。

## 6. 两个进程真独立吗？

**真独立（两 JVM、两套目录、两套 SessionManager），但"独立"本身没有断言。** 严重度：低

- m1-headless.sh:93-97 / :116-121 两次 `java ... &`；dataDir 分别为 a-data / b-data（:94、:117）；M1Harness.java:93、:163 各自 new M1Kernel → M1Kernel.kt:79 各自 BtSession → BtSession.kt:59-63 各自 SessionManager。
- 端口只做到"提示值靠扫描兜底"：A msg_port=47100（:95），B 也是 msg_port=47100（:118），实际端口靠 hint+0..99 扫描（PeerServer.kt:26-29、BtSession.kt:53-55）；脚本不断言两者实际端口不相等。
- M1Harness.java:42-47 的 latch 与 lastFile/lastOp 是 static —— 夹具自身无法证明角色分离，分离性只由 shell 的两条 java 命令行保证。
- 绕过：改成单 JVM 内两个对象，static latch 立即满足 memberJoined/fileSeen/commentReceived，全部 grep 断言照旧 PASS。另：两进程同机同盘同 loopback，互读对方目录无任何隔离断言（见第 3 条）。

## 附：SelfTest（字节层自检）

- SelfTest.java:93-105 用 RFC 5869 公开向量，真可证伪；:47 打印 checks 数量但脚本（:235）只认 `^RESULT=PASS$` 与 rc，check 数不设下限 —— 删光 check() 调用仍打印 RESULT=PASS。
