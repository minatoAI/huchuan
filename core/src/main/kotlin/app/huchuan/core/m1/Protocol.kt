package app.huchuan.core.m1

import java.io.DataInputStream
import java.net.URLEncoder

/**
 * 协议 §5 帧格式与 §9/§10 的 15 个 M1 消息。
 * 帧 = u32be(len) ‖ u8(ver=1) ‖ u8(type) ‖ JSON，len = 2 + bodyLen，单帧上限 65536 B。
 * 未知 type 静默忽略，未知字段忽略，必需字段缺失 → E_PROTO（见 PeerLink 的异常处理）。
 */
object MsgType {
    const val HELLO = 0x01
    const val JOIN_REQ = 0x02
    const val JOIN_OK = 0x03
    const val SYNC_REQ = 0x10
    const val FILE_LIST = 0x11
    const val FILE_ADD = 0x12
    const val LOG_SYNC = 0x20
    const val LOG_OPS = 0x21
    const val LOG_OP = 0x22
    const val MEMBER_NAME = 0x23
    const val PEER_ANNOUNCE = 0x30
    const val PING = 0x40
    const val PONG = 0x41
    const val BYE = 0x42
    const val ERR = 0x7f

    fun name(type: Int): String = when (type) {
        HELLO -> "hello"
        JOIN_REQ -> "join.req"
        JOIN_OK -> "join.ok"
        SYNC_REQ -> "sync.req"
        FILE_LIST -> "file.list"
        FILE_ADD -> "file.add"
        LOG_SYNC -> "log.sync"
        LOG_OPS -> "log.ops"
        LOG_OP -> "log.op"
        MEMBER_NAME -> "member.name"
        PEER_ANNOUNCE -> "peer.announce"
        PING -> "ping"
        PONG -> "pong"
        BYE -> "bye"
        ERR -> "err"
        else -> "0x" + Integer.toHexString(type)
    }
}

object Proto {
    const val VERSION = 1
    const val PROTO = "hv1"
    const val REV = 1
    const val MAX_FRAME = 65536
    const val MAX_BODY = MAX_FRAME - 2
    const val ALPN = "huchuan/1"
    const val CAP_FILES = "files"
    const val CAP_LOG = "log"
    const val CAP_PEER = "peer"
    val CAPS: List<String> = listOf(CAP_FILES, CAP_LOG, CAP_PEER)

    /** M1 magnet（协议 §4）：magnet:?xt=urn:btih:<fileId>&dn=<name>&xl=<size>。 */
    fun magnet(fileId: String, name: String, size: Long): String =
        "magnet:?xt=urn:btih:" + fileId + "&dn=" + URLEncoder.encode(name, "UTF-8") + "&xl=" + size
}

data class Frame(val ver: Int, val type: Int, val body: J.Obj) {
    fun text(key: String): String? = body.str(key)
    fun long(key: String): Long? = body.num(key)
    fun arr(key: String): List<J>? = body.arr(key)
    fun obj(key: String): J.Obj? = body.obj(key)
    fun bool(key: String): Boolean? = body.bool(key)
}

object Frames {
    fun encode(type: Int, body: J.Obj): ByteArray {
        val json = Json.write(body).toByteArray(Charsets.UTF_8)
        val len = 2 + json.size
        require(len <= Proto.MAX_FRAME) { "E_TOO_LARGE frame " + len + " B" }
        val out = ByteArray(4 + len)
        out[0] = ((len ushr 24) and 0xff).toByte()
        out[1] = ((len ushr 16) and 0xff).toByte()
        out[2] = ((len ushr 8) and 0xff).toByte()
        out[3] = (len and 0xff).toByte()
        out[4] = Proto.VERSION.toByte()
        out[5] = type.toByte()
        System.arraycopy(json, 0, out, 6, json.size)
        return out
    }

    /** 读到 EOF 返回 null；帧超限或 JSON 非法抛异常。 */
    fun read(input: DataInputStream): Frame? {
        val head = ByteArray(4)
        val first = input.read()
        if (first < 0) return null
        head[0] = first.toByte()
        input.readFully(head, 1, 3)
        val len = ((head[0].toInt() and 0xff) shl 24) or
            ((head[1].toInt() and 0xff) shl 16) or
            ((head[2].toInt() and 0xff) shl 8) or
            (head[3].toInt() and 0xff)
        if (len < 2 || len > Proto.MAX_FRAME) throw ProtocolException("E_TOO_LARGE frame length " + len)
        val payload = ByteArray(len)
        input.readFully(payload)
        val ver = payload[0].toInt() and 0xff
        val type = payload[1].toInt() and 0xff
        val body = if (len > 2) {
            Json.parse(String(payload, 2, len - 2, Charsets.UTF_8)).asObj()
        } else {
            J.Obj(emptyMap())
        }
        return Frame(ver, type, body)
    }
}

// ---------------------------------------------------------------- 记录类型

/**
 * 协议 §7/§10 的文件条目。
 *
 * [sig] 是这条 file.add 的原签名（file1 domain，覆盖 [signFields]），由添加者 [addedBy] 的私钥签。
 * file.add 把它放在帧级 sig 字段（§10 的例子），file.list 的每条 entry 原样再带一份（§7）；
 * 两处都读：签名跟着条目走，接手的成员按 [addedBy] 的公钥就能独立验，不必信任转发通道。
 */
data class FileEntry(
    val fileId: String,
    val name: String,
    val size: Long,
    val pieceSize: Int,
    val addedBy: String,
    val addedAt: Long,
    val magnet: String = "",
    val torrent: String = "",
    val sig: String = "",
) {
    fun toJson(): J.Obj = jobj(
        "fileId" to jstr(fileId),
        "name" to jstr(name),
        "size" to jnum(size),
        "pieceSize" to jnum(pieceSize.toLong()),
        "addedBy" to jstr(addedBy),
        "addedAt" to jnum(addedAt),
        "magnet" to jstr(magnet),
        "torrent" to jstr(torrent),
        "sig" to jstr(sig),
    )

    /** 参与 file1 签名的字段，顺序写死（协议 §1.2）。 */
    fun signFields(): List<ByteArray> = listOf(
        SignBytes.str(fileId),
        SignBytes.str(name),
        SignBytes.u64(size),
        SignBytes.u64(pieceSize.toLong()),
        SignBytes.str(addedBy),
        SignBytes.u64(addedAt),
    )

    companion object {
        fun fromJson(o: J): FileEntry = FileEntry(
            fileId = o.reqStr("fileId"),
            name = o.reqStr("name"),
            size = o.reqNum("size"),
            pieceSize = o.reqNum("pieceSize").toInt(),
            addedBy = o.reqStr("addedBy"),
            addedAt = o.reqNum("addedAt"),
            magnet = o.str("magnet") ?: "",
            torrent = o.str("torrent") ?: "",
            sig = o.str("sig") ?: "",
        )
    }
}

/** 协议 §6 的评论 op。 */
data class OpRecord(
    val opId: String,
    val fileId: String,
    val author: String,
    val seq: Long,
    val lamport: Long,
    val ts: Long,
    val kind: Int,
    val parent: String,
    val body: String,
    val prev: String,
    val sig: String,
) {
    fun signFields(): List<ByteArray> = listOf(
        SignBytes.str(opId),
        SignBytes.str(fileId),
        SignBytes.str(author),
        SignBytes.u64(seq),
        SignBytes.u64(lamport),
        SignBytes.u64(ts),
        SignBytes.u8(kind),
        SignBytes.str(parent),
        SignBytes.str(body),
        SignBytes.str(prev),
    )

    fun withSig(signature: String): OpRecord = copy(sig = signature)

    fun toJson(): J.Obj = jobj(
        "opId" to jstr(opId),
        "fileId" to jstr(fileId),
        "author" to jstr(author),
        "seq" to jnum(seq),
        "lamport" to jnum(lamport),
        "ts" to jnum(ts),
        "kind" to jnum(kind.toLong()),
        "parent" to jstr(parent),
        "body" to jstr(body),
        "prev" to jstr(prev),
        "sig" to jstr(sig),
    )

    companion object {
        /** 协议 §6：opId = B32(SHA-256(authorPub64 ‖ u32(seq))[0..16])，26 字符。 */
        fun opIdOf(authorPub64: ByteArray, seq: Long): String {
            val seqBytes = byteArrayOf(
                ((seq ushr 24) and 0xff).toByte(),
                ((seq ushr 16) and 0xff).toByte(),
                ((seq ushr 8) and 0xff).toByte(),
                (seq and 0xff).toByte(),
            )
            return B32.encode(sha256(authorPub64, seqBytes).copyOf(16))
        }

        fun fromJson(o: J): OpRecord = OpRecord(
            opId = o.reqStr("opId"),
            fileId = o.reqStr("fileId"),
            author = o.reqStr("author"),
            seq = o.reqNum("seq"),
            lamport = o.reqNum("lamport"),
            ts = o.reqNum("ts"),
            kind = o.reqNum("kind").toInt(),
            parent = o.str("parent") ?: "",
            body = o.str("body") ?: "",
            prev = o.str("prev") ?: "",
            sig = o.reqStr("sig"),
        )
    }
}

data class MemberRecord(
    val dev: String,
    val name: String,
    val nameSeq: Long,
    val pub: String = "",
)

data class Cursor(val author: String, val up: Long) {
    fun toJson(): J.Obj = jobj("a" to jstr(author), "up" to jnum(up))
    companion object {
        fun fromJson(o: J): Cursor = Cursor(o.reqStr("a"), o.reqNum("up"))
        fun listFrom(arr: List<J>?): List<Cursor> = arr?.map { fromJson(it) } ?: emptyList()
        fun toJsonArray(cursors: List<Cursor>): J.Arr = jarr(cursors.map { it.toJson() })
    }
}

data class HelloMsg(
    val proto: String,
    val rev: Int,
    val gid: String,
    val dev: String,
    val pub: String,
    val nonce: String,
    val caps: List<String>,
    val ts: Long,
    val proof: String,
    val sig: String,
) {
    companion object {
        fun fromJson(o: J): HelloMsg = HelloMsg(
            proto = o.reqStr("proto"),
            rev = (o.num("rev") ?: 1L).toInt(),
            gid = o.str("gid") ?: "",
            dev = o.reqStr("dev"),
            pub = o.reqStr("pub"),
            nonce = o.str("nonce") ?: "",
            caps = o.arr("caps")?.mapNotNull { (it as? J.Str)?.value } ?: emptyList(),
            ts = o.num("ts") ?: 0L,
            proof = o.str("proof") ?: "",
            sig = o.str("sig") ?: "",
        )
    }
}

data class JoinReqMsg(
    val gid: String,
    val dev: String,
    val pub: String,
    val nonce: String,
    val ts: Long,
    val mac: String,
) {
    companion object {
        fun fromJson(o: J): JoinReqMsg = JoinReqMsg(
            gid = o.reqStr("gid"),
            dev = o.reqStr("dev"),
            pub = o.reqStr("pub"),
            nonce = o.reqStr("nonce"),
            ts = o.reqNum("ts"),
            mac = o.reqStr("mac"),
        )
    }
}

data class JoinOkMsg(
    val gid: String,
    val dev: String,
    val nonce: String,
    val ts: Long,
    val gname: String,
    val epoch: Int,
    val keySeed: String,
    val fp: String,
    val members: List<MemberRecord>,
    val files: List<FileEntry>,
    val cursors: List<Cursor>,
    val sig: String,
) {
    companion object {
        fun fromJson(o: J): JoinOkMsg = JoinOkMsg(
            gid = o.reqStr("gid"),
            dev = o.reqStr("dev"),
            nonce = o.str("nonce") ?: "",
            ts = o.num("ts") ?: 0L,
            gname = o.str("gname") ?: "",
            epoch = (o.num("epoch") ?: 0L).toInt(),
            keySeed = o.str("keySeed") ?: "",
            fp = o.str("fp") ?: "",
            members = o.arr("members")?.mapNotNull { m ->
                val pub = (m as? J.Obj) ?: return@mapNotNull null
                MemberRecord(
                    dev = pub.str("dev") ?: return@mapNotNull null,
                    name = pub.str("name") ?: "",
                    nameSeq = pub.num("nameSeq") ?: 0L,
                    pub = pub.str("pub") ?: "",
                )
            } ?: emptyList(),
            files = o.arr("files")?.mapNotNull { f -> (f as? J.Obj)?.let { FileEntry.fromJson(it) } } ?: emptyList(),
            cursors = Cursor.listFrom(o.arr("cursors")),
            sig = o.str("sig") ?: "",
        )
    }
}

data class PeerAnnounceMsg(
    val gid: String,
    val dev: String,
    val btPort: Int,
    val btHost: String,
    val ts: Long,
    val sig: String,
) {
    fun signFields(): List<ByteArray> = listOf(
        SignBytes.str(dev),
        SignBytes.str(gid),
        SignBytes.u64(btPort.toLong()),
        SignBytes.u64(ts),
    )

    companion object {
        fun fromJson(o: J): PeerAnnounceMsg = PeerAnnounceMsg(
            gid = o.reqStr("gid"),
            dev = o.reqStr("dev"),
            btPort = o.reqNum("btPort").toInt(),
            btHost = o.str("btHost") ?: "",
            ts = o.num("ts") ?: 0L,
            sig = o.str("sig") ?: "",
        )
    }
}

data class MemberNameMsg(
    val dev: String,
    val name: String,
    val nameSeq: Long,
    val ts: Long,
    val sig: String,
    /**
     * 发送者的 64 B 裸公钥（128 hex）。不进 [signFields]：deviceId = B32(SHA-256(dev1‖pub)[0..10])，
     * 公钥由 dev 自己钉死，改了 pub 就再也算不出同一个 dev，比再签一次更有约束力。
     */
    val pub: String = "",
) {
    fun signFields(): List<ByteArray> = listOf(
        SignBytes.str(dev),
        SignBytes.str(name),
        SignBytes.u64(nameSeq),
        SignBytes.u64(ts),
    )

    companion object {
        fun fromJson(o: J): MemberNameMsg = MemberNameMsg(
            dev = o.reqStr("dev"),
            name = o.reqStr("name"),
            nameSeq = o.reqNum("nameSeq"),
            ts = o.reqNum("ts"),
            sig = o.reqStr("sig"),
            pub = o.str("pub") ?: "",
        )
    }
}
