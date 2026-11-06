package app.huchuan.core.m1

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 冷启动重连（缺口一）的退避与候选编排 —— 纯函数，SelfTest 直接在字节层钉住，
 * 不需要起内核、不需要网络。
 *
 * 缺陷现场（docs/m1-device.md §2.7）：消息层只在「加入群」时建链，进程杀掉再起来之后
 * 群还在本地库里，但没有任何一条主动连出去的链路，对端要等对方先连过来才恢复；
 * P6 上对端的平台与做种数一直是「未知 / 0」。
 */
object ReconnectPolicy {

    /**
     * 指数退避：1 / 2 / 4 / 8 / 16 / 30 s，到 30 s 封顶。
     *
     * 只看「一轮候选全部失败之后」的等待，不是每个候选各等一次；成功建链立刻归零，
     * 所以链路掉一下再回来不会先等 30 s。
     */
    val BACKOFF_MS = longArrayOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L)

    /** 每群同时在拨的上限。候选再多也只排队，不并发轰对方。 */
    const val MAX_PARALLEL_DIALS = 3

    /** 退避表按尝试次数取值，超出表长按最后一档（30 s）算。 */
    fun backoffMs(attempt: Int): Long =
        BACKOFF_MS[attempt.coerceIn(0, BACKOFF_MS.size - 1)]

    /**
     * 一轮里挑哪些候选去拨。
     *
     * [skip] 是「已经在拨」的端点 key（host:port）：同一轮里不重复拨同一个地址，
     * 上一轮还没结束的也不重拨。[budget] 是本轮还能拨几个（= 每群并发上限 − 在途数）。
     * 候选顺序就是调用方给的顺序（已连过的端点 → 手动地址 → mDNS），这里只做去重与截断。
     */
    fun plan(candidates: List<Candidate>, skip: Collection<String>, budget: Int): List<Candidate> {
        if (budget <= 0 || candidates.isEmpty()) return emptyList()
        val seen = HashSet<String>(skip)
        val out = ArrayList<Candidate>(minOf(budget, candidates.size))
        for (c in candidates) {
            if (c.host.isEmpty() || c.port <= 0) continue
            if (!seen.add(c.key)) continue
            out.add(c)
            if (out.size >= budget) break
        }
        return out
    }
}

/**
 * 成员之间的 BT 拨号编排（纯函数，SelfTest 直接钉住）。
 *
 * 缺陷现场（docs/design/team-acceleration.md §1.3）：原来的 [GroupRuntime.connectSeeders] 先看
 * `bt.peers(fileId) > 0` 就整轮跳过 —— 只要这个 torrent 已经有 1 条 peer 连接，其它成员就永远不被拨。
 * 单做种者先被一个下载者连上之后，第二个下载者与第一个就再也接不上，队友之间拿不到互补的片，
 * 「组队」在代码层面不成立。
 *
 * 现在的口径是**每成员一次拨号**：已经互连的成员跳过（幂等，不重拨），还在退避里的跳过，
 * 剩下的按预算截断。退避表与每轮预算直接复用 [ReconnectPolicy]，不另立一套。
 */
/**
 * 链路撞车兜底的开关（只用于反向验证，默认开）。
 *
 * 两端同时拨号时，[GroupRuntime.attach] 的裁决会把两条链路里的一条关成 duplicate_link。
 * 被关的那一侧如果已经有一条到同一成员的活链路，就应当直接认这条（见 [GroupRuntime.settleOnExistingLink]），
 * 而不是退进 30 s 退避里一轮轮重拨 —— 现场是手机每 30 s 拨一次、每次都 EOF，永远不 attach
 * （artifacts/m1-device/reconnect-race-evidence.txt）。
 *
 * 反向测试用 `-Dhuchuan.mutants=no_link_fallback` 关掉它：关掉之后 harness 的撞车断言必须 FAIL，
 * 否则那条断言没有证伪力（见 docs/reports/e0-team.md 与 docs/core.md §3.4）。
 */
object LinkRaceGuard {
    @JvmField
    val fallbackEnabled: Boolean =
        !System.getProperty("huchuan.mutants", "").split(",").any { it.trim() == "no_link_fallback" }
}

object MemberDialPolicy {

    /** 一个待拨的成员端点。key 里带 dev：同一台机器上两个成员的 host:port 可能相同。 */
    class Target(val dev: String, val host: String, val port: Int) {
        val key: String = dev + "@" + host + ":" + port
    }

    /**
     * 本轮该拨哪几个成员。
     *
     * [connected] 是「已经从 libtorrent 的 peer 列表里确认互连」的 dev（不是「有没有 peer」）；
     * [throttled] 是还在退避窗口里的候选 key；[budget] 是本轮最多拨几个
     * （= [ReconnectPolicy.MAX_PARALLEL_DIALS]）。顺序即调用方给的顺序，这里只做过滤与截断。
     */
    fun plan(
        targets: List<Target>,
        connected: Collection<String>,
        throttled: Collection<String>,
        budget: Int,
    ): List<Target> {
        if (budget <= 0 || targets.isEmpty()) return emptyList()
        val done = HashSet(connected)
        val hold = HashSet(throttled)
        val out = ArrayList<Target>(minOf(budget, targets.size))
        for (t in targets) {
            // 端点还没自报全（bt.announce 没到）的成员没有可拨的地址，等下一轮
            if (t.host.isEmpty() || t.port <= 0) continue
            // 已经互连：不重拨（同一成员重复调用 connectSeeders 必须幂等）
            if (done.contains(t.dev)) continue
            // 还在退避里：不提前重拨
            if (hold.contains(t.key)) continue
            out.add(t)
            if (out.size >= budget) break
        }
        return out
    }
}

/**
 * 冷启动后的主动重连：对每个「从 state.json 恢复出来的群」找成员并把消息层链路连回去。
 *
 * 候选来源三处（顺序即优先级）：
 *   1. 上次成功连过的端点 —— [GroupRuntime.peerAddrs]，落盘在 state.json；
 *   2. 上次加入时用过的手动 IP:port —— [GroupRuntime.manualAddrs]，落盘；
 *   3. mDNS 浏览（协议 §3.1）—— 后台线程每 [LAN_REFRESH_MS] 刷一次，按 TXT 的 gid 归群。
 *
 * 连上之后什么都不用重做：hello 绑定这条链路两端都认对方是成员，
 * [PeerLink.onBound] 直接把链路挂到群上、互发 peer.announce 与 sync.req —— 不需要重新 join，
 * 也不会动本地库（群密钥、文件清单、评论都还是原来那份）。
 *
 * 只在 [M1Kernel.start] 里「载入过 state.json 的群」上工作：刚建 / 刚加入的群，链路本来就是
 * 加入流程建的，让重连器也去拨会跟加入流程抢同一条对端（对端可能还没入群）。
 */
internal class PeerReconnector(private val kernel: M1Kernel) {

    private class GroupState {
        /** 已经失败了几轮（用来取退避表）。成功建链后归零。 */
        var attempts = 0

        /** 下一轮最早什么时候开始（毫秒时间戳）。 */
        var nextAttemptAt = 0L

        /** 在途的拨号：候选 key（host:port）。 */
        val inFlight = HashSet<String>()
    }

    private val lock = Any()
    private val states = HashMap<String, GroupState>()
    private val running = AtomicBoolean(false)
    private var loopThread: Thread? = null
    private var lanThread: Thread? = null

    /** mDNS 的最后一次解析结果（按实例，含 gid）。解析是阻塞的，所以只在这个类自己的线程上做。 */
    @Volatile
    private var lanEntries: List<LanEntry> = emptyList()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        val t = Thread { loop() }
        t.isDaemon = true
        t.name = "huchuan-reconnect"
        t.start()
        loopThread = t

        val l = Thread { lanLoop() }
        l.isDaemon = true
        l.name = "huchuan-reconnect-lan"
        l.start()
        lanThread = l

        kernel.events.log(
            "reconnect_started",
            mapOf(
                "groups" to kernel.reconnectTargets().size.toString(),
                "backoff_ms" to ReconnectPolicy.BACKOFF_MS.joinToString("/"),
                "max_parallel" to ReconnectPolicy.MAX_PARALLEL_DIALS.toString(),
                "tick_ms" to TICK_MS.toString(),
            ),
        )
    }

    fun close() {
        if (!running.compareAndSet(true, false)) return
        loopThread?.interrupt()
        lanThread?.interrupt()
        loopThread = null
        lanThread = null
    }

    // ------------------------------------------------------------------ 主循环

    private fun loop() {
        while (running.get() && kernel.isStarted()) {
            try {
                tick(System.currentTimeMillis())
            } catch (e: InterruptedException) {
                break
            } catch (e: Throwable) {
                kernel.events.log("reconnect_tick_error", mapOf("error" to e.toString()))
            }
            try {
                Thread.sleep(TICK_MS)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    /** 一轮调度：对每个需要重连的群，按退避与并发上限派出拨号。可单独调用（测试 / 诊断）。 */
    internal fun tick(now: Long) {
        for (group in kernel.reconnectTargets()) {
            val st = stateFor(group.groupId)
            if (group.onlineLinks().isNotEmpty()) {
                synchronized(st) {
                    if (st.attempts != 0 || st.nextAttemptAt != 0L) {
                        st.attempts = 0
                        st.nextAttemptAt = 0L
                        kernel.events.log("reconnect_backoff_reset", mapOf("gid" to group.groupId))
                    }
                }
                continue
            }
            synchronized(st) {
                if (st.inFlight.isNotEmpty()) return@synchronized
                if (now < st.nextAttemptAt) return@synchronized
                val plan = ReconnectPolicy.plan(candidatesFor(group), st.inFlight, ReconnectPolicy.MAX_PARALLEL_DIALS)
                if (plan.isEmpty()) {
                    // 一个候选都没有（mDNS 还没解析出来、手动地址也没存过）：按退避等下一轮，
                    // 不能空转 —— 每 250 ms 空转一次会把日志刷满，也救不了连接。
                    val attempt = st.attempts
                    st.attempts = attempt + 1
                    st.nextAttemptAt = now + ReconnectPolicy.backoffMs(attempt)
                    kernel.events.log(
                        "reconnect_no_candidates",
                        mapOf(
                            "gid" to group.groupId,
                            "members" to group.members().size.toString(),
                            "attempt" to (attempt + 1).toString(),
                            "next_in_ms" to ReconnectPolicy.backoffMs(attempt).toString(),
                        ),
                    )
                    return@synchronized
                }
                kernel.events.log(
                    "reconnect_attempt",
                    mapOf(
                        "gid" to group.groupId,
                        "attempt" to (st.attempts + 1).toString(),
                        "addrs" to plan.joinToString(",") { it.key },
                        "sources" to plan.joinToString(",") { it.source },
                    ),
                )
                for (c in plan) {
                    st.inFlight.add(c.key)
                    dial(group, c, st)
                }
            }
        }
    }

    private fun stateFor(gid: String): GroupState = synchronized(lock) {
        states.getOrPut(gid) { GroupState() }
    }

    /** 本群的候选：已连过的端点 → 手动地址 → mDNS（按 gid 归群、排除自己那一条）。 */
    private fun candidatesFor(group: GroupRuntime): List<Candidate> {
        val lan = ArrayList<Candidate>()
        val ownName = "hv1-" + group.meta.gHandle
        for (entry in lanEntries) {
            if (entry.gid != group.groupId) continue
            if (entry.instanceName == ownName || entry.instanceName.startsWith(ownName + ".")) continue
            lan.addAll(entry.candidates)
        }
        return group.reconnectCandidates(lan)
    }

    // ------------------------------------------------------------------ 拨号

    private fun dial(group: GroupRuntime, candidate: Candidate, st: GroupState) {
        val gid = group.groupId
        val t = Thread {
            val startedAt = System.currentTimeMillis()
            var link: PeerLink? = null
            var ok = false
            var via = "dial"
            try {
                val created = kernel.openOutboundLink(candidate.host, candidate.port)
                link = created
                ok = awaitBound(created, BIND_WAIT_MS) && created.group?.groupId == gid
                if (!ok && !created.isClosed) {
                    kernel.events.log(
                        "reconnect_dial_unbound",
                        mapOf("gid" to gid, "addr" to candidate.key, "source" to candidate.source),
                    )
                    created.close("reconnect_unbound")
                }
                // 撞车兜底（缺陷修复点）：两端同时拨号时，本端这条可能被判成 duplicate_link 关掉，
                // 而同一成员其实已经有一条活链路。认那条当绑定成功，不退避重拨 ——
                // 否则就是「手机每 30 s 拨一次、每次 EOF、永远连不上」的现场。
                if (!ok && LinkRaceGuard.fallbackEnabled && settleRace(group, created, candidate)) {
                    ok = true
                    via = "existing_link"
                }
            } catch (e: Throwable) {
                kernel.events.log(
                    "reconnect_dial_failed",
                    mapOf(
                        "gid" to gid,
                        "addr" to candidate.key,
                        "source" to candidate.source,
                        "error" to e.toString(),
                    ),
                )
            } finally {
                if (!ok) {
                    try { link?.close("reconnect_failed") } catch (_: Throwable) {}
                }
                finishDial(gid, candidate, st, ok, System.currentTimeMillis() - startedAt, link, via)
            }
        }
        t.isDaemon = true
        t.name = "huchuan-reconnect-dial"
        t.start()
    }

    /**
     * 拨号撞车的兜底：本端这条链路没成，但同一成员已经有一条活链路 → 认后者。
     *
     * 认下来之后在既有链路上补发 peer.announce 与 sync.req（[GroupRuntime.settleOnExistingLink]），
     * 所以「认那条链路」不只是账面上一句 online，对端的运行期属性与清单照样会补齐。
     */
    private fun settleRace(group: GroupRuntime, failed: PeerLink, candidate: Candidate): Boolean {
        val dev = failed.peerDeviceId
        val kept = if (dev.isNotEmpty()) group.liveLinkFor(dev) else group.liveLinkForHost(candidate.host)
        if (kept == null || kept === failed || kept.isClosed) return false
        if (!group.settleOnExistingLink(kept.peerDeviceId)) return false
        kernel.events.log(
            "reconnect_link_race_settled",
            mapOf(
                "gid" to group.groupId,
                "dev" to kept.peerDeviceId,
                "addr" to candidate.key,
                "kept_role" to if (kept.inbound) "in" else "out",
                "failed_role" to if (failed.inbound) "in" else "out",
            ),
        )
        return true
    }

    private fun awaitBound(link: PeerLink, timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (link.isClosed) return false
            if (link.isBound) return true
            try {
                Thread.sleep(15)
            } catch (e: InterruptedException) {
                return false
            }
        }
        return false
    }

    private fun finishDial(
        gid: String,
        candidate: Candidate,
        st: GroupState,
        ok: Boolean,
        elapsedMs: Long,
        link: PeerLink?,
        via: String = "dial",
    ) {
        val roundDone: Boolean
        synchronized(st) {
            st.inFlight.remove(candidate.key)
            roundDone = st.inFlight.isEmpty()
        }
        if (ok && link != null) {
            synchronized(st) {
                st.attempts = 0
                st.nextAttemptAt = 0L
            }
            kernel.events.log(
                "peer_reconnected",
                mapOf(
                    "gid" to gid,
                    "dev" to link.peerDeviceId,
                    "addr" to candidate.key,
                    "source" to candidate.source,
                    "elapsed_ms" to elapsedMs.toString(),
                    "via" to via,
                ),
            )
            return
        }
        if (!roundDone) return
        val group = kernel.group(gid)
        if (group != null && group.onlineLinks().isNotEmpty()) return
        val attempt: Int
        val delay: Long
        synchronized(st) {
            attempt = st.attempts
            delay = ReconnectPolicy.backoffMs(attempt)
            st.attempts = attempt + 1
            st.nextAttemptAt = System.currentTimeMillis() + delay
        }
        kernel.events.log(
            "reconnect_backoff",
            mapOf("gid" to gid, "attempt" to (attempt + 1).toString(), "delay_ms" to delay.toString()),
        )
    }

    // ------------------------------------------------------------------ mDNS

    /**
     * 后台刷 mDNS 候选。浏览一次要 2–3 s（JmDNS 的 requestServiceInfo + 收集），
     * 挂在拨号主循环上会把「1 s 后重试」拖成「3 s 后重试」，所以单独一条线程。
     */
    private fun lanLoop() {
        while (running.get() && kernel.isStarted()) {
            try {
                if (kernel.lanDiscovery.start()) {
                    val entries = kernel.lanDiscovery.browseEntries(LAN_BROWSE_MS)
                    lanEntries = entries
                    kernel.events.log(
                        "reconnect_lan_scan",
                        mapOf(
                            "entries" to entries.size.toString(),
                            "addrs" to entries.sumOf { it.candidates.size }.toString(),
                            "error" to kernel.lanDiscovery.lastError,
                        ),
                    )
                } else {
                    kernel.events.log(
                        "reconnect_lan_unavailable",
                        mapOf("error" to kernel.lanDiscovery.lastError),
                    )
                }
            } catch (e: InterruptedException) {
                break
            } catch (e: Throwable) {
                kernel.events.log("reconnect_lan_error", mapOf("error" to e.toString()))
            }
            try {
                Thread.sleep(LAN_REFRESH_MS)
            } catch (e: InterruptedException) {
                break
            }
        }
    }

    companion object {
        /** 调度节拍：退避的最小档是 1 s，250 ms 的节拍足够准。 */
        const val TICK_MS = 250L

        /** mDNS 候选刷新间隔。 */
        const val LAN_REFRESH_MS = 10_000L

        /** 单次 mDNS 浏览时长。 */
        const val LAN_BROWSE_MS = 2_500L

        /** 拨号之后等 hello 绑定的上限（TLS 握手 + 双向 hello 通常 < 100 ms）。 */
        const val BIND_WAIT_MS = 6_000L
    }
}
