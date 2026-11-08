package app.huchuan.core.m1

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLSocket

/**
 * 一条消息层连接（协议 §5）：TLS 建连 → 双向 hello 绑定 → join / sync / 广播。
 *
 * hello 的绑定分两步：协议 §1.2 规定 hello 签名覆盖「本端 nonce + 对端 nonce」，
 * 单帧内无法同时知道两边 nonce，所以先各发一条不带 sig 的 hello 交换 nonce，
 * 再各发一条带 sig 的 hello。两条都是 0x01，见 docs/core.md「协议缺口」。
 */
internal class PeerLink(
    private val kernel: M1Kernel,
    private val socket: SSLSocket,
    /** true = 对端拨进来的。同时拨号的两条链路要靠它 + deviceId 定出唯一胜者（见 GroupRuntime.attach）。 */
    internal val inbound: Boolean,
    val remoteHost: String,
) : AutoCloseable {

    /** 对端的端口。去程链路里这就是它的消息层监听端口，可以落盘给冷启动重连用。 */
    internal val remotePort: Int get() = try { socket.port } catch (_: Throwable) { 0 }

    private val output = DataOutputStream(BufferedOutputStream(socket.outputStream, 64 * 1024))
    private val input = DataInputStream(BufferedInputStream(socket.inputStream, 64 * 1024))
    private val writeLock = Any()
    private val random = SecureRandom()
    private val myNonce = ByteArray(16).also { random.nextBytes(it) }

    private val chanId: ByteArray = run {
        val peerCert = socket.session.peerCertificates.first() as X509Certificate
        Tls.chanId(kernel.tlsIdentity.certificate, peerCert, inbound)
    }

    @Volatile
    var peerDeviceId: String = ""
        private set

    @Volatile
    private var peerPub: ByteArray? = null

    @Volatile
    private var peerGid: String = ""

    @Volatile
    private var peerNonce: ByteArray? = null

    @Volatile
    private var peerBound = false

    /** 双向 hello 验完、链路已经按群绑定（[onBound] 跑过）。冷启动重连靠它判断「连上了」。 */
    @Volatile
    private var bound = false

    @Volatile
    private var sentSignedHello = false

    @Volatile
    private var closed = false

    /** 连续写失败帧数；任何一帧写成功就归零。达到上限才断链（见 [send]）。 */
    @Volatile
    private var consecutiveSendFailures = 0

    /** 本端最近一次 join.req 的 nonce（hex）：join.ok 必须回显它（协议 §10）。 */
    @Volatile
    private var joinReqNonce: ByteArray? = null

    /** 关闭原因，只在 peer_link_closed 里出现。 */
    @Volatile
    private var closeReason: String = ""

    @Volatile
    internal var group: GroupRuntime? = null

    private var framesThisSecond = 0
    private var secondMark = System.currentTimeMillis()

    val isClosed: Boolean get() = closed

    val isBound: Boolean get() = bound

    fun chanIdHex(): String = chanId.toHex()

    fun run() {
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            sendHello(signed = false)
            while (!closed) {
                val frame = try {
                    Frames.read(input)
                } catch (e: SocketTimeoutException) {
                    throw e
                } catch (e: ProtocolException) {
                    sendErr("E_PROTO", e.message ?: "bad frame", "")
                    break
                }
                if (frame == null) {
                    kernel.events.log("peer_link_eof", mapOf("dev" to peerDeviceId, "host" to remoteHost))
                    break
                }
                if (!throttle()) break
                dispatch(frame)
            }
        } catch (e: Throwable) {
            if (!closed) {
                kernel.events.log(
                    "peer_link_error",
                    mapOf("dev" to peerDeviceId, "host" to remoteHost, "error" to e.toString()),
                )
            }
        } finally {
            close()
        }
    }

    // ------------------------------------------------------------------ 帧读写

    /**
     * 写一帧。返回 false = 这一帧没写出去（已记 peer_send_failed），连接保留。
     * 单帧写失败多半是这一帧编码超限或对端读得慢，为它掐掉整条已认证的连接代价太大：
     * 广播里的一个坏帧会让该成员瞬间掉线，还要重走 hello/join。
     * 只有连续 [MAX_CONSECUTIVE_SEND_FAILURES] 帧都写不出去，才认定链路真的坏了。
     */
    fun send(type: Int, body: J.Obj): Boolean {
        if (closed) return false
        val bytes = try {
            Frames.encode(type, body)
        } catch (e: Throwable) {
            return onSendFailure(type, e)
        }
        return try {
            synchronized(writeLock) {
                output.write(bytes)
                output.flush()
            }
            consecutiveSendFailures = 0
            true
        } catch (e: Throwable) {
            onSendFailure(type, e)
        }
    }

    /** 同 [send]：消息层内部一律用这个口径，失败只影响当前这一帧。 */
    fun sendQuietly(type: Int, body: J.Obj): Boolean = send(type, body)

    private fun onSendFailure(type: Int, e: Throwable): Boolean {
        val n = ++consecutiveSendFailures
        kernel.events.log(
            "peer_send_failed",
            mapOf(
                "dev" to peerDeviceId,
                "host" to remoteHost,
                "type" to MsgType.name(type),
                "consecutive" to n.toString(),
                "limit" to MAX_CONSECUTIVE_SEND_FAILURES.toString(),
                "error" to e.toString(),
            ),
        )
        if (n >= MAX_CONSECUTIVE_SEND_FAILURES) close("send_failures")
        return false
    }

    private fun sendErr(code: String, message: String, ref: String) {
        sendQuietly(MsgType.ERR, jobj("t" to jstr("err"), "code" to jstr(code), "msg" to jstr(message), "ref" to jstr(ref)))
    }

    private fun throttle(): Boolean {
        val now = System.currentTimeMillis()
        if (now - secondMark >= 1000) {
            secondMark = now
            framesThisSecond = 0
        }
        framesThisSecond++
        if (framesThisSecond > MAX_FRAMES_PER_SECOND) {
            sendErr("E_RATE", "too many frames", "")
            return false
        }
        return true
    }

    // ------------------------------------------------------------------ hello

    private fun sendHello(signed: Boolean) {
        val identity = kernel.identity
        val g = kernel.helloGroupOrNull()
        val gid = if (g != null) g.groupId else ""
        val proof = if (g != null) g.meta.proofHex(chanId) else ""
        val peerN = peerNonce
        val sig = if (signed && peerN != null) {
            identity.sign(
                Domains.HELLO,
                listOf(
                    SignBytes.str(identity.deviceId),
                    SignBytes.str(gid),
                    SignBytes.bin(myNonce),
                    SignBytes.bin(peerN),
                    SignBytes.bin(chanId),
                ),
            )
        } else {
            ""
        }
        send(
            MsgType.HELLO,
            jobj(
                "t" to jstr("hello"),
                "proto" to jstr(Proto.PROTO),
                "rev" to jnum(Proto.REV.toLong()),
                "gid" to jstr(gid),
                "dev" to jstr(identity.deviceId),
                "pub" to jstr(identity.pub64.toHex()),
                "nonce" to jstr(myNonce.toHex()),
                "caps" to jarr(Proto.CAPS.map { jstr(it) }),
                "ts" to jnum(System.currentTimeMillis()),
                "proof" to jstr(proof),
                "sig" to jstr(sig),
            ),
        )
    }

    private fun onHello(h: HelloMsg) {
        if (h.proto != Proto.PROTO) {
            sendErr("E_VERSION", "unsupported proto " + h.proto, "")
            close()
            return
        }
        val pub = try { hexToBytes(h.pub) } catch (e: Exception) { null }
        if (pub == null || pub.size != 64) {
            sendErr("E_SIG", "bad pub", "")
            close()
            return
        }
        if (DeviceIdentity.deviceIdOf(pub) != h.dev) {
            sendErr("E_SIG", "deviceId does not match pub", "")
            close()
            return
        }
        if (peerDeviceId.isNotEmpty() && peerDeviceId != h.dev) {
            sendErr("E_SIG", "device changed on a live channel", "")
            close()
            return
        }
        peerDeviceId = h.dev
        peerPub = pub
        peerGid = h.gid
        kernel.registerPub(h.dev, pub)
        kernel.observePeerClock(h.dev, h.ts, System.currentTimeMillis())
        if (h.nonce.length == 32) peerNonce = hexToBytes(h.nonce)

        if (h.sig.isEmpty()) {
            if (!sentSignedHello && peerNonce != null) {
                sendHello(signed = true)
                sentSignedHello = true
            }
            return
        }

        val fields = listOf(
            SignBytes.str(h.dev),
            SignBytes.str(h.gid),
            SignBytes.bin(hexToBytes(h.nonce)),
            SignBytes.bin(myNonce),
            SignBytes.bin(chanId),
        )
        if (!DeviceIdentity.verify(pub, h.sig, Domains.HELLO, fields)) {
            sendErr("E_SIG", "hello signature invalid", "")
            close()
            return
        }
        if (h.gid.isNotEmpty()) {
            val g = kernel.groupById(h.gid)
            if (g != null && g.isPersonal) {
                // 个人空间不接受任何对端（docs/design/personal-space.md）：连「我已经在这个群里」
                // 的旧数据都不认，回明确的失败码再断链 —— 不是静默忽略。
                rejectPersonalPeer(g.groupId, h.dev, "hello")
                return
            }
            if (g != null) {
                // 我们是该群成员：可以验 proof（HMAC(kGrp, gid‖chanId)）
                val expected = hexToBytes(g.meta.proofHex(chanId))
                val actual = try { hexToBytes(h.proof) } catch (e: Exception) { ByteArray(0) }
                if (!constantTimeEquals(expected, actual)) {
                    sendErr("E_SIG", "group proof invalid", "")
                    close()
                    return
                }
            } else {
                // 我们还不是该群成员（典型场景：加入方）：没有 kGrp 就验不了 proof，
                // 只接受「对端声称属于某群」，成员资格一律由 join.req/join.ok 决定。
                kernel.events.log("hello_group_unknown", mapOf("gid" to h.gid, "dev" to h.dev))
            }
        }
        if (!sentSignedHello) {
            sendHello(signed = true)
            sentSignedHello = true
        }
        if (peerBound) return
        peerBound = true
        onBound()
    }

    private fun onBound() {
        val gid = peerGid
        val g = if (gid.isNotEmpty()) kernel.groupById(gid) else null
        if (g != null && g.members().any { it.dev == peerDeviceId }) {
            group = g
            bound = true
            // 记下「这条端点连得通」：去程链路记的是对端的监听端口，落盘给下次冷启动重连用（缺口一）。
            kernel.onLinkBound(this, g)
            g.attach(this)
            kernel.events.log(
                "peer_bound",
                mapOf("dev" to peerDeviceId, "gid" to gid, "host" to remoteHost, "role" to if (inbound) "in" else "out"),
            )
            sendPeerAnnounce(g)
            sendSyncReq(g)
            return
        }
        if (g == null) {
            // 对端可能想加入我们的群；等 join.req。若我们是加入方，这里就发 join.req。
            val intent = kernel.currentJoinIntent()
            if (intent != null && peerGid.isNotEmpty()) {
                sendJoinReq(peerGid, intent)
            }
        }
    }

    // ------------------------------------------------------------------ join

    private fun sendJoinReq(gid: String, intent: JoinIntent) {
        val nonce = ByteArray(16).also { random.nextBytes(it) }
        // 记下来给 join.ok 用：应答方必须回显这个 nonce，否则这条 join.ok 不是对这次加入的回答。
        joinReqNonce = nonce
        val ts = System.currentTimeMillis()
        val mac = hmacSha256(
            intent.invite.kJoin,
            SignBytes.of(
                Domains.JOIN,
                listOf(
                    SignBytes.str(kernel.deviceId),
                    SignBytes.bin(kernel.identity.pub64),
                    SignBytes.bin(nonce),
                    SignBytes.u64(ts),
                ),
            ),
        ).toHex()
        kernel.events.log(
            "join_request",
            mapOf("gid" to gid, "host" to remoteHost, "tail8" to intent.invite.tail8),
        )
        send(
            MsgType.JOIN_REQ,
            jobj(
                "t" to jstr("join.req"),
                "gid" to jstr(gid),
                "dev" to jstr(kernel.deviceId),
                "pub" to jstr(kernel.identity.pub64.toHex()),
                "nonce" to jstr(nonce.toHex()),
                "ts" to jnum(ts),
                "mac" to jstr(mac),
            ),
        )
    }

    /** 被连接方：按协议 §2.3 的七步校验。 */
    private fun onJoinReq(msg: JoinReqMsg) {
        kernel.observePeerClock(msg.dev, msg.ts, System.currentTimeMillis())
        val g = kernel.groupById(msg.gid)
        if (g == null) {
            sendErr("E_NOT_MEMBER", "unknown group", "")
            return
        }
        // 个人空间不可加入：任何针对它的 join 一律拒绝，且**排在其它校验之前** ——
        // 失败码必须是「这是个人空间」，不能是邀请码不对 / 加入已关闭这类会让人以为「换个码还能试」的码。
        if (g.isPersonal) {
            kernel.events.log(
                "personal_space_join_rejected",
                mapOf("gid" to msg.gid, "dev" to msg.dev, "host" to remoteHost, "via" to "join.req"),
            )
            sendErr(ERR_PERSONAL, "personal space cannot be joined", "")
            return
        }
        if (!g.meta.joinOpen) {
            sendErr("E_JOIN_CLOSED", "group is closed for joins", "")
            return
        }
        if (msg.dev != peerDeviceId) {
            sendErr("E_SIG", "join dev does not match hello dev", "")
            return
        }
        val now = System.currentTimeMillis()
        if (Math.abs(now - msg.ts) > CLOCK_SKEW_MS) {
            sendErr("E_CLOCK", "local ts=" + now, "")
            return
        }
        if (kernel.nonceSeen(msg.dev, msg.nonce)) {
            kernel.events.log("join_nonce_replay", mapOf("dev" to msg.dev))
            return
        }
        if (!kernel.joinRate.allowed(remoteHost, msg.gid, now)) {
            kernel.events.log("join_rate_limited", mapOf("host" to remoteHost, "gid" to msg.gid))
            sendErr("E_RATE", "join too frequent", "")
            return
        }
        val pub = try { hexToBytes(msg.pub) } catch (e: Exception) { null }
        val nonce = try { hexToBytes(msg.nonce) } catch (e: Exception) { null }
        if (pub == null || pub.size != 64 || nonce == null || nonce.size != 16) {
            sendErr("E_PROTO", "bad join fields", "")
            return
        }
        val expected = hmacSha256(
            g.meta.invite.kJoin,
            SignBytes.of(
                Domains.JOIN,
                listOf(SignBytes.str(msg.dev), SignBytes.bin(pub), SignBytes.bin(nonce), SignBytes.u64(msg.ts)),
            ),
        )
        val actual = try { hexToBytes(msg.mac) } catch (e: Exception) { ByteArray(0) }
        if (!constantTimeEquals(expected, actual)) {
            kernel.joinRate.recordFailure(remoteHost, msg.gid, now)
            kernel.events.log("join_bad_code", mapOf("dev" to msg.dev, "host" to remoteHost))
            sendErr("E_JOIN_CODE", "invite code mismatch", "")
            return
        }
        kernel.rememberNonce(msg.dev, msg.nonce)
        group = g
        g.attach(this)
        g.addMember(msg.dev, "", 0, msg.pub)
        sendJoinOk(g, msg)
        // 让新成员立刻知道本机的 BT 端点（协议 §11 第 14 步的前提）
        sendPeerAnnounce(g)
        kernel.saveState()
        kernel.events.log(
            "join_accepted",
            mapOf("gid" to g.groupId, "dev" to msg.dev, "host" to remoteHost, "members" to g.members().size.toString()),
        )
        // 让其他在线成员知道新成员：触发一次群内广播（协议 §2.3 第 7 步）
        g.broadcastPeerAnnounce(msg.dev)
        sendSyncReq(g)
    }

    /**
     * 个人空间的对端一律拒绝：留一条可 grep 的日志，回 [ERR_PERSONAL]，然后断链。
     *
     * 走的是协议 §10 的 0x7F err 帧，所以对端（[dispatch] 的 ERR 分支）拿到的就是
     * `code=E_JOIN_PERSONAL` —— 明确失败码，不是一次无故的 EOF。
     */
    private fun rejectPersonalPeer(gid: String, dev: String, via: String) {
        kernel.events.log(
            "personal_space_peer_rejected",
            mapOf("gid" to gid, "dev" to dev, "host" to remoteHost, "via" to via),
        )
        sendErr(ERR_PERSONAL, "personal space takes no peers", "")
        close("personal_space")
    }

    private fun sendJoinOk(g: GroupRuntime, msg: JoinReqMsg) {
        val ts = System.currentTimeMillis()
        // members 每条带 pub（128 hex，64 B 裸值）：加入方在跟其他成员握手之前就能验他们的评论与文件签名。
        val members = g.members().map { m ->
            jobj(
                "dev" to jstr(m.dev),
                "name" to jstr(m.name),
                "nameSeq" to jnum(m.nameSeq),
                "pub" to jstr(m.pub),
            )
        }
        val files = g.files()
        val filesJson = if (files.size > 32) jarr(emptyList()) else jarr(files.map { it.toJson() })
        val cursors = Cursor.toJsonArray(g.oplog.cursors())
        val keySeed = g.meta.keySeed.toHex()
        val fp = g.meta.fp
        // 协议 §10：joinok1 domain，字段 [dev, gid, keySeed, fp, nonce, ts]。
        // 这条签名把 keySeed 的下发者钉在 dev 上，nonce 把这次下发钉死在这一轮加入上。
        val sig = kernel.identity.sign(
            Domains.JOIN_OK,
            listOf(
                SignBytes.str(kernel.deviceId),
                SignBytes.str(g.groupId),
                SignBytes.str(keySeed),
                SignBytes.str(fp),
                SignBytes.str(msg.nonce),
                SignBytes.u64(ts),
            ),
        )
        send(
            MsgType.JOIN_OK,
            jobj(
                "t" to jstr("join.ok"),
                "gid" to jstr(g.groupId),
                "dev" to jstr(kernel.deviceId),
                "nonce" to jstr(msg.nonce),
                "ts" to jnum(ts),
                "gname" to jstr(g.name),
                "epoch" to jnum(g.meta.epoch.toLong()),
                "keySeed" to jstr(keySeed),
                "fp" to jstr(fp),
                "members" to jarr(members),
                "files" to filesJson,
                "cursors" to cursors,
                "sig" to jstr(sig),
            ),
        )
    }

    /**
     * 加入方：收 join.ok。协议 §10 的顺序是「先验签，验不过就断开、不装群」。
     *
     * 这条签名是整个加入流程里唯一把 keySeed 绑到某个设备身上的东西：
     * 验它用的是 hello 阶段登记在这条通道上的应答方公钥，nonce 还必须回显本轮 join.req 的，
     * 所以同一份 join.ok 搬到别的通道或别的 join.req 上必然验不过。
     */
    private fun onJoinOk(msg: JoinOkMsg) {
        val recvAt = System.currentTimeMillis()
        val intent = kernel.currentJoinIntent()
        if (intent == null) {
            kernel.events.log("join_ok_unexpected", mapOf("gid" to msg.gid))
            return
        }
        val responderPub = kernel.pubOf(msg.dev)
        val sigFields = listOf(
            SignBytes.str(msg.dev),
            SignBytes.str(msg.gid),
            SignBytes.str(msg.keySeed),
            SignBytes.str(msg.fp),
            SignBytes.str(msg.nonce),
            SignBytes.u64(msg.ts),
        )
        val reason = when {
            msg.dev != peerDeviceId -> "dev_mismatch"
            responderPub == null -> "no_pub"
            !DeviceIdentity.verify(responderPub, msg.sig, Domains.JOIN_OK, sigFields) -> "bad_sig"
            joinReqNonce?.let { msg.nonce == it.toHex() } != true -> "nonce_mismatch"
            else -> ""
        }
        if (reason.isNotEmpty()) {
            kernel.events.log(
                "join_ok_rejected",
                mapOf("gid" to msg.gid, "dev" to msg.dev, "peer" to peerDeviceId, "reason" to reason),
            )
            sendErr("E_SIG", "join.ok rejected: " + reason, "")
            kernel.joinFailed(intent, "join_ok_" + reason)
            close("join_ok_" + reason)
            return
        }
        kernel.observePeerClock(msg.dev, msg.ts, recvAt)
        val keySeed = try { hexToBytes(msg.keySeed) } catch (e: Exception) { null }
        if (keySeed == null || keySeed.size != 32) {
            kernel.joinFailed(intent, "bad keySeed")
            close("join_ok_bad_keyseed")
            return
        }
        // 群指纹核对：kGrp 由 keySeed + 码派生，fp 必须一致（协议 §2.2）。
        val probe = Hkdf.derive(keySeed, intent.invite.codeBytes, Domains.GROUP, 32)
        val fp = B32.encode(sha256(probe).copyOf(5))
        if (msg.fp.isNotEmpty() && fp != msg.fp) {
            kernel.joinFailed(intent, "fp mismatch local=" + fp + " remote=" + msg.fp)
            close("join_ok_fp_mismatch")
            return
        }
        kernel.events.log(
            "join_ok_verified",
            mapOf("gid" to msg.gid, "dev" to msg.dev, "members" to msg.members.size.toString()),
        )
        val g = kernel.installJoinedGroup(msg, intent.invite, keySeed)
        group = g
        g.attach(this)

        sendMemberName(g)
        sendSyncReq(g)
        sendPeerAnnounce(g)
        kernel.onJoinComplete(intent, g, this)
    }

    private fun sendMemberName(g: GroupRuntime) {
        val ts = System.currentTimeMillis()
        val name = kernel.deviceName
        val sig = kernel.identity.sign(
            Domains.NAME,
            listOf(
                SignBytes.str(kernel.deviceId),
                SignBytes.str(name),
                SignBytes.u64(1),
                SignBytes.u64(ts),
            ),
        )
        val pubHex = kernel.identity.pub64.toHex()
        g.addMember(kernel.deviceId, name, 1, pubHex)
        g.broadcast(
            MsgType.MEMBER_NAME,
            jobj(
                "t" to jstr("member.name"),
                "dev" to jstr(kernel.deviceId),
                "name" to jstr(name),
                "nameSeq" to jnum(1),
                "pub" to jstr(pubHex),
                "ts" to jnum(ts),
                "sig" to jstr(sig),
            ),
            null,
        )
    }

    // ------------------------------------------------------------------ sync

    private fun sendSyncReq(g: GroupRuntime) {
        send(
            MsgType.SYNC_REQ,
            jobj(
                "t" to jstr("sync.req"),
                "gid" to jstr(g.groupId),
                "fdigest" to jstr(g.fdigest()),
                "cursors" to Cursor.toJsonArray(g.oplog.cursors()),
                "nonce" to jstr(randomHex(8)),
            ),
        )
    }

    private fun onSyncReq(frame: Frame) {
        val g = group ?: run {
            sendErr("E_NOT_MEMBER", "sync before join", "")
            null
        } ?: return
        val peerDigest = frame.text("fdigest") ?: ""
        if (peerDigest != g.fdigest()) {
            sendFileList(g, frame.text("nonce") ?: "")
        }
        sendLogOps(g, Cursor.listFrom(frame.arr("cursors")), 200, frame.text("nonce") ?: "")
        kernel.events.log(
            "sync_served",
            mapOf("gid" to g.groupId, "digest_equal" to (peerDigest == g.fdigest()).toString()),
        )
    }

    private fun onLogSync(frame: Frame) {
        val g = group ?: return
        val limit = (frame.long("limit") ?: 200L).toInt().coerceIn(1, 200)
        sendLogOps(g, Cursor.listFrom(frame.arr("cursors")), limit, frame.text("nonce") ?: "")
    }

    private fun sendFileList(g: GroupRuntime, nonce: String) {
        val entries = g.files().sortedBy { it.fileId }
        var index = 0
        do {
            val page = entries.drop(index).take(32)
            index += page.size
            val more = index < entries.size
            send(
                MsgType.FILE_LIST,
                jobj(
                    "t" to jstr("file.list"),
                    "gid" to jstr(g.groupId),
                    "entries" to jarr(page.map { it.toJson() }),
                    "more" to jbool(more),
                    "nonce" to jstr(nonce),
                ),
            )
        } while (more)
    }

    private fun sendLogOps(g: GroupRuntime, cursors: List<Cursor>, limit: Int, nonce: String) {
        val ops = g.oplog.opsAfter(cursors, limit)
        if (ops.isEmpty()) {
            send(
                MsgType.LOG_OPS,
                jobj("t" to jstr("log.ops"), "gid" to jstr(g.groupId), "ops" to jarr(emptyList()), "more" to jbool(false), "nonce" to jstr(nonce)),
            )
            return
        }
        var index = 0
        while (index < ops.size) {
            val page = ops.drop(index).take(200)
            index += page.size
            val more = index < ops.size
            send(
                MsgType.LOG_OPS,
                jobj(
                    "t" to jstr("log.ops"),
                    "gid" to jstr(g.groupId),
                    "ops" to jarr(page.map { it.toJson() }),
                    "more" to jbool(more),
                    "nonce" to jstr(nonce),
                ),
            )
        }
    }

    /**
     * file.list 的每条 entry 原样带着该文件 file.add 的 sig（协议 §7）。
     * 验签、丢弃都在 [GroupRuntime.onFileListEntry] 里做：验不过的条目不入库，自然也不进 fdigest。
     */
    private fun onFileList(frame: Frame) {
        val g = group ?: return
        for (item in frame.arr("entries") ?: emptyList()) {
            val entry = try { FileEntry.fromJson(item) } catch (e: Exception) { continue }
            g.onFileListEntry(entry, peerDeviceId)
        }
    }

    private fun onLogOps(frame: Frame) {
        val g = group ?: return
        for (item in frame.arr("ops") ?: emptyList()) {
            val op = try { OpRecord.fromJson(item) } catch (e: Exception) { continue }
            g.onOp(op, peerDeviceId)
        }
    }

    private fun onLogOp(frame: Frame) {
        val g = group ?: return
        val item = frame.obj("op") ?: return
        val op = try { OpRecord.fromJson(item) } catch (e: Exception) { return }
        g.onOp(op, peerDeviceId)
    }

    private fun onFileAdd(frame: Frame) {
        val g = group ?: return
        val entryJson = frame.obj("entry") ?: run {
            sendErr("E_PROTO", "file.add without entry", "")
            return
        }
        val entry = try { FileEntry.fromJson(entryJson) } catch (e: Exception) {
            sendErr("E_PROTO", e.message ?: "bad entry", "")
            return
        }
        // 协议 §10 的例子把 sig 放在帧级；条目里也带一份（file.list 要原样转发），两处都认。
        val sig = frame.text("sig")?.takeIf { it.isNotEmpty() } ?: entry.sig
        if (sig.isEmpty()) {
            sendErr("E_SIG", "file.add without signature", "")
            return
        }
        g.onFileEntry(entry, sig, peerDeviceId)
    }

    private fun onMemberName(frame: Frame) {
        val g = group ?: return
        val msg = try { MemberNameMsg.fromJson(frame.body) } catch (e: Exception) { return }
        g.onMemberName(msg, peerDeviceId)
    }

    private fun onPeerAnnounce(frame: Frame) {
        val g = group ?: return
        val msg = try { PeerAnnounceMsg.fromJson(frame.body) } catch (e: Exception) { return }
        g.onPeerAnnounce(msg, remoteHost, peerDeviceId)
    }

    private fun sendPeerAnnounce(g: GroupRuntime) {
        val body = g.peerAnnounceBody() ?: return
        sendQuietly(MsgType.PEER_ANNOUNCE, body)
    }

    /**
     * 撞车兜底（[GroupRuntime.settleOnExistingLink]）：在一条已经绑定的链路上补发
     * peer.announce 与 sync.req。
     *
     * 两条都是幂等的（announce 只是覆盖同一个 dev 的端点；sync.req 换回的 file.list 会按
     * fileId 去重），所以对端多收一次没有副作用 —— 这正是「撞车后直接复用这条活链路」的实现。
     */
    internal fun announceAndSync(g: GroupRuntime) {
        sendPeerAnnounce(g)
        sendSyncReq(g)
    }

    // ------------------------------------------------------------------ 分发

    private fun dispatch(frame: Frame) {
        if (frame.ver != Proto.VERSION) {
            sendErr("E_VERSION", "unsupported frame version " + frame.ver, "")
            close()
            return
        }
        val memberLink = group != null
        when (frame.type) {
            MsgType.HELLO -> onHello(helloOf(frame))
            MsgType.JOIN_REQ -> if (memberLink) sendErr("E_PROTO", "already a member", "") else onJoinReq(joinReqOf(frame))
            MsgType.JOIN_OK -> onJoinOk(joinOkOf(frame))
            MsgType.PING -> sendQuietly(MsgType.PONG, jobj("t" to jstr("pong"), "ts" to jnum(frame.long("ts") ?: 0L)))
            MsgType.PONG -> {}
            MsgType.BYE -> close()
            MsgType.ERR -> {
                val code = frame.text("code") ?: ""
                kernel.events.log(
                    "peer_err",
                    mapOf(
                        "dev" to peerDeviceId,
                        "host" to remoteHost,
                        "code" to code,
                        "msg" to (frame.text("msg") ?: ""),
                    ),
                )
                // 加入被拒：立刻放弃这个候选，换下一个（§3.4 逐个候选试 join.req）
                val intent = kernel.currentJoinIntent()
                if (intent != null && code.startsWith("E_")) {
                    kernel.joinFailed(intent, code)
                }
            }
            else -> {
                if (!memberLink) {
                    sendErr("E_NOT_MEMBER", "not a member", "")
                    return
                }
                when (frame.type) {
                    MsgType.SYNC_REQ -> onSyncReq(frame)
                    MsgType.FILE_LIST -> onFileList(frame)
                    MsgType.FILE_ADD -> onFileAdd(frame)
                    MsgType.LOG_SYNC -> onLogSync(frame)
                    MsgType.LOG_OPS -> onLogOps(frame)
                    MsgType.LOG_OP -> onLogOp(frame)
                    MsgType.MEMBER_NAME -> onMemberName(frame)
                    MsgType.PEER_ANNOUNCE -> onPeerAnnounce(frame)
                    else -> {
                        // 未知 type 静默忽略（协议 §5）
                    }
                }
            }
        }
    }

    private fun helloOf(frame: Frame): HelloMsg = HelloMsg.fromJson(frame.body)
    private fun joinReqOf(frame: Frame): JoinReqMsg = JoinReqMsg.fromJson(frame.body)
    private fun joinOkOf(frame: Frame): JoinOkMsg = JoinOkMsg.fromJson(frame.body)

    fun ping() {
        sendQuietly(MsgType.PING, jobj("t" to jstr("ping"), "ts" to jnum(System.currentTimeMillis())))
    }

    fun sendBye(code: String) {
        sendQuietly(MsgType.BYE, jobj("t" to jstr("bye"), "code" to jstr(code)))
    }

    /** 带原因的关闭：日志里的 reason 是排查「对端掉线」的第一手线索。 */
    fun close(reason: String) {
        closeReason = reason
        close()
    }

    override fun close() {
        if (closed) return
        closed = true
        // 谁先关的、什么时候关的，是排查「对端掉线」的唯一线索，必须留痕。
        kernel.events.log(
            "peer_link_closed",
            mapOf("dev" to peerDeviceId, "host" to remoteHost, "reason" to closeReason),
        )
        val g = group
        if (g != null) g.detach(this)
        kernel.unregisterLink(this)
        try { socket.close() } catch (_: Throwable) {}
    }

    private fun randomHex(bytes: Int): String = ByteArray(bytes).also { random.nextBytes(it) }.toHex()

    companion object {
        const val READ_TIMEOUT_MS = 130_000
        const val CLOCK_SKEW_MS = 300_000L
        const val MAX_FRAMES_PER_SECOND = 60

        /**
         * 落到个人空间上的 hello / join.req 的失败码（docs/core.md §3.13）。
         *
         * 对端在 0x7F err 帧里收到的就是这个字符串，[M1Kernel.joinByTail8] 失败时
         * `join_failed reason=E_JOIN_PERSONAL` 也是它 —— 宿主 / 验收脚本按它判「被明确拒绝」。
         */
        const val ERR_PERSONAL = "E_JOIN_PERSONAL"

        /**
         * 连续写失败多少帧才断开对端连接（docs/core.md 同步了这个数）。
         *
         * 单帧失败不掐连接：广播里某一帧超限、或者对端这一刻读得慢，都只该丢那一帧；
         * 断整条已认证的连接要重走 hello/join/公钥登记，代价远大于重发。
         * 连续 3 帧都写不出去才认定这条通道真的不可用。
         */
        const val MAX_CONSECUTIVE_SEND_FAILURES = 3
    }
}
