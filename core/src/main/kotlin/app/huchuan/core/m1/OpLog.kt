package app.huchuan.core.m1

/**
 * 协议 §6 评论日志：签名追加、Lamport 全序、prev 哈希链、opId 去重、乱序 pending。
 * 「按 (lamport, author 字典序, seq) 升序」是唯一排序口径；ts 只用于展示。
 */
class OpLog(
    private val identity: DeviceIdentity,
    private val pubOf: (String) -> ByteArray?,
) {
    enum class Ingest { ADDED, DUPLICATE, BAD_SIG, UNKNOWN_AUTHOR, PENDING, MALFORMED }

    internal class Pending(val op: OpRecord, val at: Long)

    private val lock = Any()
    private val ops = LinkedHashMap<String, OpRecord>()
    private val byAuthor = HashMap<String, java.util.TreeMap<Long, OpRecord>>()
    private val pending = HashMap<String, MutableList<Pending>>()

    @Volatile
    var lamport: Long = 0L
        private set

    val size: Int get() = synchronized(lock) { ops.size }

    fun setLamport(v: Long) {
        synchronized(lock) { if (v > lamport) lamport = v }
    }

    fun ingest(op: OpRecord, now: Long = System.currentTimeMillis()): Ingest {
        synchronized(lock) {
            if (ops.containsKey(op.opId)) return Ingest.DUPLICATE
            val pub = pubOf(op.author) ?: return Ingest.UNKNOWN_AUTHOR
            if (!DeviceIdentity.verify(pub, op.sig, Domains.OP, op.signFields())) return Ingest.BAD_SIG
            expungePending(now)
            if (op.lamport >= lamport) lamport = op.lamport + 1
            if (op.prev.isNotEmpty() && !ops.containsKey(op.prev)) {
                val list = pending.getOrPut(op.prev) { ArrayList() }
                if (list.none { it.op.opId == op.opId }) list.add(Pending(op, now))
                return Ingest.PENDING
            }
            insert(op)
            drainChildren(op.opId, now)
            return Ingest.ADDED
        }
    }

    /** 本机自己写的 op：已签名、链天然连续。 */
    fun addLocal(op: OpRecord) {
        synchronized(lock) {
            if (ops.containsKey(op.opId)) return
            if (op.lamport >= lamport) lamport = op.lamport + 1
            insert(op)
            drainChildren(op.opId, System.currentTimeMillis())
        }
    }

    /** 载入本地库时用：不验签（入库时已验过），只重建索引。 */
    fun loadAll(records: List<OpRecord>) {
        synchronized(lock) {
            for (op in records) {
                if (ops.containsKey(op.opId)) continue
                insert(op)
                if (op.lamport >= lamport) lamport = op.lamport + 1
            }
        }
    }

    fun all(): List<OpRecord> = synchronized(lock) {
        ops.values.sortedWith(compareBy({ it.lamport }, { it.author }, { it.seq }))
    }

    fun ofFile(fileId: String): List<OpRecord> = all().filter { it.fileId == fileId }

    /** 墓碑（kind=2）本地不物理删除，只隐藏（协议 §6）。 */
    fun visible(fileId: String): List<OpRecord> {
        val list = ofFile(fileId)
        val hidden = HashSet<String>()
        for (op in list) if (op.kind == 2 && op.parent.isNotEmpty()) hidden.add(op.parent)
        return list.filter { it.kind != 2 && !hidden.contains(it.opId) }
    }

    /**
     * 所有文件各自可见的 op，一次排序搞定（未读总数用；逐个文件调 [visible] 会把整份日志重排 N 遍）。
     * 墓碑（kind=2）与被墓碑指向的 op 都不出现，口径与 [visible] 完全一致。
     */
    fun visibleByFile(): Map<String, List<OpRecord>> = synchronized(lock) {
        val list = all()
        val hidden = HashSet<String>()
        for (op in list) if (op.kind == 2 && op.parent.isNotEmpty()) hidden.add(op.parent)
        val out = LinkedHashMap<String, MutableList<OpRecord>>()
        for (op in list) {
            if (op.kind == 2 || hidden.contains(op.opId)) continue
            out.getOrPut(op.fileId) { ArrayList() }.add(op)
        }
        out
    }

    fun byId(opId: String): OpRecord? = synchronized(lock) { ops[opId] }

    fun maxSeqOf(author: String): Long = synchronized(lock) {
        byAuthor[author]?.lastKey() ?: 0L
    }

    fun lastOpIdOf(author: String): String = synchronized(lock) {
        byAuthor[author]?.lastEntry()?.value?.opId ?: ""
    }

    /** 每作者游标 {a, up}（协议 §6 log.sync）。 */
    fun cursors(): List<Cursor> = synchronized(lock) {
        val authors = sortedSetOf<String>()
        authors.add(identity.deviceId)
        authors.addAll(membersWithOps())
        authors.map { Cursor(it, byAuthor[it]?.lastKey() ?: 0L) }
    }

    /** 应答补差：每作者 seq > up 的 op，按 (author, seq) 升序，取 limit 条。 */
    fun opsAfter(cursors: List<Cursor>, limit: Int): List<OpRecord> = synchronized(lock) {
        val cursorMap = HashMap<String, Long>()
        for (c in cursors) cursorMap[c.author] = c.up
        val out = ArrayList<OpRecord>()
        for ((author, tree) in byAuthor) {
            val up = cursorMap[author] ?: 0L
            for ((seq, op) in tree) {
                if (seq > up) out.add(op)
                if (out.size >= limit) break
            }
            if (out.size >= limit) break
        }
        out.sortedWith(compareBy({ it.author }, { it.seq }))
    }

    /** 本机新写一条：seq 连续 +1、lamport 本地 +1、prev 指向自己上一条。 */
    fun create(fileId: String, body: String, parent: String?, kind: Int = 0): OpRecord {
        synchronized(lock) {
            val author = identity.deviceId
            val seq = maxSeqOf(author) + 1
            val prev = lastOpIdOf(author)
            val opId = OpRecord.opIdOf(identity.pub64, seq)
            lamport += 1
            val unsigned = OpRecord(
                opId = opId,
                fileId = fileId,
                author = author,
                seq = seq,
                lamport = lamport,
                ts = System.currentTimeMillis(),
                kind = kind,
                parent = parent ?: "",
                body = nfc(body),
                prev = prev,
                sig = "",
            )
            val signed = unsigned.withSig(identity.sign(Domains.OP, unsigned.signFields()))
            insert(signed)
            return signed
        }
    }

    private fun membersWithOps(): Set<String> = byAuthor.keys

    private fun insert(op: OpRecord) {
        ops[op.opId] = op
        byAuthor.getOrPut(op.author) { java.util.TreeMap() }[op.seq] = op
    }

    private fun drainChildren(parentOpId: String, now: Long) {
        val children = pending.remove(parentOpId) ?: return
        for (p in children) {
            if (now - p.at > PENDING_TTL_MS) continue
            if (ops.containsKey(p.op.opId)) continue
            insert(p.op)
            drainChildren(p.op.opId, now)
        }
    }

    private fun expungePending(now: Long) {
        if (pending.isEmpty()) return
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            entry.value.removeIf { p -> now - p.at > PENDING_TTL_MS }
            if (entry.value.isEmpty()) it.remove()
        }
    }

    companion object {
        const val PENDING_TTL_MS = 10L * 60 * 1000
        const val MAX_PENDING_PER_AUTHOR = 512
    }
}

/**
 * 「上次读到哪条 op」的游标（未读数 G4 的记账口径）。
 *
 * 键就是协议 §6 的唯一排序口径 (lamport, author 字典序, seq)，不是自增计数也不是时间戳：
 * 用键比较，重连补齐、乱序到达、同一条 op 重复广播都不会把未读数算错，也不需要为每条 op
 * 单独记「读过没读过」。
 *
 * 方向：游标指向「最后一条读过的 op」。unread = 当前可见 op 里键**严格大于**游标的条数。
 * [ZERO] 表示「一条都没读过」，此时全部 op 都是未读。
 */
data class ReadCursor(val lamport: Long, val author: String, val seq: Long) : Comparable<ReadCursor> {

    override fun compareTo(other: ReadCursor): Int {
        if (lamport != other.lamport) return lamport.compareTo(other.lamport)
        val byAuthor = author.compareTo(other.author)
        if (byAuthor != 0) return byAuthor
        return seq.compareTo(other.seq)
    }

    fun toJson(): J.Obj = jobj(
        "lamport" to jnum(lamport),
        "author" to jstr(author),
        "seq" to jnum(seq),
    )

    companion object {
        val ZERO = ReadCursor(0L, "", 0L)

        fun of(op: OpRecord): ReadCursor = ReadCursor(op.lamport, op.author, op.seq)

        fun fromJson(o: J): ReadCursor = ReadCursor(
            lamport = o.num("lamport") ?: 0L,
            author = o.str("author") ?: "",
            seq = o.num("seq") ?: 0L,
        )

        fun max(a: ReadCursor, b: ReadCursor): ReadCursor = if (a >= b) a else b
    }
}

/** 未读数的纯函数实现：输入是某个文件的可见 op 列表与该文件的读游标，与存储/网络无关。 */
object Unread {

    /** 键严格大于 [cursor] 的 op 条数；cursor 为 null 等于「一条都没读过」。 */
    fun count(ops: List<OpRecord>, cursor: ReadCursor?): Int {
        if (cursor == null || cursor == ReadCursor.ZERO) return ops.size
        var unread = 0
        for (op in ops) {
            if (ReadCursor.of(op) > cursor) unread++
        }
        return unread
    }

    /** 清零用：把游标推到这批 op 里最新的那一条。空列表返回 [ReadCursor.ZERO]。 */
    fun newest(ops: List<OpRecord>): ReadCursor {
        var newest = ReadCursor.ZERO
        for (op in ops) newest = ReadCursor.max(newest, ReadCursor.of(op))
        return newest
    }
}
