package app.huchuan.core.m1

/**
 * 已知设备公钥库（dev → 64 B 裸公钥）。
 *
 * 协议 §10 的三条分发路径（hello / join.ok.members / member.name）都先算 deviceId(pub) == dev
 * 再调用 [register]；file.list 条目、评论 op、member.name、peer.announce 的验签都从这里取公钥。
 *
 * 单独成类是为了让「创建者能不能验自己的条目」这条断言能在字节层钉住（harness 的 SelfTest）：
 * M1 早期的缺陷是 createGroup 只把公钥写进成员表、没写进这个库，于是同一个进程里
 * 创建者收到别人回传的 file.list 时，自己发布的条目一律以 reason=no_pub 被丢弃
 * （证据 artifacts/m1/log-catchup-a.log）。
 */
class PubRegistry {

    private val lock = Any()
    private val keys = LinkedHashMap<String, ByteArray>()

    /** 登记一把公钥；返回 true 表示这次是新登记或换掉了旧值（调用方据此决定要不要落盘）。 */
    fun register(dev: String, pub: ByteArray): Boolean {
        if (dev.isEmpty() || pub.size != 64) return false
        synchronized(lock) {
            val old = keys[dev]
            if (old != null && old.contentEquals(pub)) return false
            keys[dev] = pub.copyOf()
            return true
        }
    }

    fun of(dev: String): ByteArray? = synchronized(lock) { keys[dev] }

    val size: Int get() = synchronized(lock) { keys.size }

    /** 落盘用快照：调用方拿到的是拷贝，之后不会再被登记改动。 */
    fun entries(): Map<String, ByteArray> = synchronized(lock) {
        LinkedHashMap(keys.mapValues { it.value.copyOf() })
    }
}

/**
 * file.list 条目的验签口径（协议 §7）：每条 entry 原样带该文件 file.add 的 sig，
 * 用 addedBy 的公钥验 file1 domain，公钥**必须**取自成员记录，不能取自条目自身。
 *
 * reason 的取值与日志 file_list_rejected 的 reason 一致：no_sig / no_pub / bad_sig / ""（通过）。
 * 抽成纯函数是为了让 SelfTest 能在字节层断言「创建者用自己的公钥能验自己的条目」。
 */
object EntryCheck {

    fun reason(pub: ByteArray?, entry: FileEntry): String = when {
        entry.sig.isEmpty() -> "no_sig"
        pub == null -> "no_pub"
        !DeviceIdentity.verify(pub, entry.sig, Domains.FILE, entry.signFields()) -> "bad_sig"
        else -> ""
    }

    fun accepts(pub: ByteArray?, entry: FileEntry): Boolean = reason(pub, entry).isEmpty()
}
