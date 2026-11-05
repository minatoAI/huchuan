package app.huchuan.core.m1

/**
 * state.json 里的群密钥包裹方案（协议 §2.2 要求 keySeed 只在 TLS 通道内下发）。
 *
 * 桌面 harness 用明文直存（[PlaintextSecretBox]，与 M1 之前的格式兼容）；
 * Android 用 Android Keystore 的 AES-GCM 包裹（app 侧 [app.huchuan.kernel.KeystoreSecretBox]），
 * keySeed 不再明文落盘。两端的 token 里都自带方案标记，读回来时按标记挑实现，
 * 认不出的方案一律跳过该群，绝不静默降级成明文。
 */
interface SecretBox {
    /** 方案标识，写进 state.json 的 keySeedProt 字段。 */
    val scheme: String

    /** 明文 → 落盘 token。 */
    fun seal(plain: ByteArray): String

    /** 落盘 token → 明文；打不开返回 null。 */
    fun open(token: String): ByteArray?
}

/** 明文方案：token 就是 hex，和 M1 早期的 state.json 一致。 */
object PlaintextSecretBox : SecretBox {
    override val scheme: String = "plain"

    override fun seal(plain: ByteArray): String = plain.toHex()

    override fun open(token: String): ByteArray? = try {
        hexToBytes(token)
    } catch (e: Throwable) {
        null
    }
}
