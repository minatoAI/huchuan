package app.huchuan.core.m1

import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/**
 * 协议 §1 的身份：P-256 密钥对、64 B 裸公钥、deviceId、低-S 签名。
 * 私钥落盘用 PKCS8（协议要求 Android Keystore / Windows PKCS12，M1 桌面先落文件，见 docs/core.md 已知限制）。
 */
class DeviceIdentity private constructor(private val keyPair: KeyPair) {

    val publicKey: ECPublicKey = keyPair.public as ECPublicKey
    val privateKey: PrivateKey = keyPair.private

    /** 64 B 裸值 x‖y，不含 0x04。 */
    val pub64: ByteArray = encodePoint(publicKey)

    /** B32(SHA-256("huchuan/dev1/v1"‖pub64)[0..10])，16 字符。 */
    val deviceId: String = deviceIdOf(pub64)

    fun sign(domain: String, fields: List<ByteArray>): String = signRaw(domain, fields).toHex()

    /** 签在 signBytes 上（SHA256withECDSA），输出 r‖s 64 B 并归一成低-S。 */
    fun signRaw(domain: String, fields: List<ByteArray>): ByteArray {
        val data = SignBytes.of(domain, fields)
        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(privateKey)
        sig.update(data)
        val der = sig.sign()
        val (r, s) = derToRs(der)
        val lowS = if (s > HALF_N) CURVE_N.subtract(s) else s
        return toFixed32(r) + toFixed32(lowS)
    }

    fun toPkcs8Base64(): String = Base64.getEncoder().encodeToString(privateKey.encoded)

    fun toX509Base64(): String = Base64.getEncoder().encodeToString(publicKey.encoded)

    companion object {
        val CURVE_N: BigInteger = BigInteger("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551", 16)
        val CURVE_P: BigInteger = BigInteger("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF", 16)
        val HALF_N: BigInteger = CURVE_N.shiftRight(1)

        fun deviceIdOf(pub64: ByteArray): String =
            B32.encode(sha256(Domains.DEVICE.toByteArray(Charsets.UTF_8), pub64).copyOf(10))

        fun generate(): DeviceIdentity {
            val kpg = KeyPairGenerator.getInstance("EC")
            kpg.initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            return DeviceIdentity(kpg.generateKeyPair())
        }

        /** 首次启动生成，之后永久复用（协议 §1.2）。 */
        fun loadOrCreate(file: File): DeviceIdentity {
            if (file.isFile) {
                try {
                    val lines = file.readText(Charsets.UTF_8).lineSequence()
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("#") }
                        .toList()
                    if (lines.size >= 2) {
                        val kf = KeyFactory.getInstance("EC")
                        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(lines[0])))
                        val pub = kf.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(lines[1])))
                        return DeviceIdentity(KeyPair(pub, priv))
                    }
                } catch (e: Exception) {
                    // 密钥文件坏了就重新生成；dev id 会变，日志里留痕。
                }
            }
            val created = generate()
            file.parentFile?.mkdirs()
            file.writeText(
                "# huchuan device identity v1\n" + created.toPkcs8Base64() + "\n" + created.toX509Base64() + "\n",
                Charsets.UTF_8,
            )
            return created
        }

        /** 验签：接受高-S（先归一），拒绝 r=0/s=0/r≥n/s≥n（协议 §1.1）。 */
        fun verify(pub64: ByteArray, sigHex: String, domain: String, fields: List<ByteArray>): Boolean {
            if (sigHex.length != 128) return false
            return try {
                val raw = hexToBytes(sigHex)
                val r = BigInteger(1, raw.copyOfRange(0, 32))
                val s0 = BigInteger(1, raw.copyOfRange(32, 64))
                if (r.signum() == 0 || s0.signum() == 0) return false
                if (r >= CURVE_N || s0 >= CURVE_N) return false
                val s = if (s0 > HALF_N) CURVE_N.subtract(s0) else s0
                val params = ecParams()
                val w = ECPoint(BigInteger(1, pub64.copyOfRange(0, 32)), BigInteger(1, pub64.copyOfRange(32, 64)))
                if (w.affineX.signum() < 0 || w.affineX >= CURVE_P || w.affineY.signum() < 0 || w.affineY >= CURVE_P) return false
                val pub = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(w, params))
                val sig = Signature.getInstance("SHA256withECDSA")
                sig.initVerify(pub)
                sig.update(SignBytes.of(domain, fields))
                sig.verify(rsToDer(r, s))
            } catch (e: Exception) {
                false
            }
        }

        fun ecParams(): ECParameterSpec {
            val ap = java.security.AlgorithmParameters.getInstance("EC")
            ap.init(ECGenParameterSpec("secp256r1"))
            return ap.getParameterSpec(ECParameterSpec::class.java)
        }

        fun encodePoint(pub: ECPublicKey): ByteArray {
            val x = toFixed32(pub.w.affineX)
            val y = toFixed32(pub.w.affineY)
            return x + y
        }

        fun toFixed32(v: BigInteger): ByteArray {
            val raw = v.toByteArray()
            val out = ByteArray(32)
            if (raw.size >= 32) {
                System.arraycopy(raw, raw.size - 32, out, 0, 32)
            } else {
                System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
            }
            return out
        }

        private fun derToRs(der: ByteArray): Pair<BigInteger, BigInteger> {
            var p = 0
            require(der[p++].toInt() and 0xff == 0x30) { "not a DER sequence" }
            var len = der[p++].toInt() and 0xff
            if (len and 0x80 != 0) {
                val n = len and 0x7f
                len = 0
                for (k in 0 until n) len = (len shl 8) or (der[p++].toInt() and 0xff)
            }
            require(der[p++].toInt() and 0xff == 0x02) { "bad r tag" }
            var rLen = der[p++].toInt() and 0xff
            val r = BigInteger(1, der.copyOfRange(p, p + rLen))
            p += rLen
            require(der[p++].toInt() and 0xff == 0x02) { "bad s tag" }
            var sLen = der[p++].toInt() and 0xff
            val s = BigInteger(1, der.copyOfRange(p, p + sLen))
            return r to s
        }

        private fun rsToDer(r: BigInteger, s: BigInteger): ByteArray {
            val rBytes = r.toByteArray()
            val sBytes = s.toByteArray()
            fun int(tag: ByteArray): ByteArray = byteArrayOf(0x02) + derLen(tag.size) + tag
            val body = int(rBytes) + int(sBytes)
            return byteArrayOf(0x30) + derLen(body.size) + body
        }

        private fun derLen(n: Int): ByteArray {
            if (n < 0x80) return byteArrayOf(n.toByte())
            val list = ArrayList<Byte>()
            var v = n
            while (v > 0) {
                list.add(0, (v and 0xff).toByte())
                v = v ushr 8
            }
            val arr = ByteArray(list.size + 1)
            arr[0] = (0x80 or list.size).toByte()
            for (i in list.indices) arr[i + 1] = list[i]
            return arr
        }
    }
}

/**
 * 协议 §2 邀请码：16 字符 = 前 8 字符校验位 + 后 8 字符秘密；P5 只收后 8 位。
 * 真实熵 40 bit（前 8 位可由后 8 位离线重算），补偿手段是「码派生值不进 mDNS/TXT」与在线限速。
 */
class InviteCode private constructor(
    val secretBytes: ByteArray,
    val checkBytes: ByteArray,
) {
    val codeBytes: ByteArray = checkBytes + secretBytes

    /** 16 字符整码。 */
    val code: String = B32.encode(codeBytes)

    /** 用户输入的 8 字符秘密段。 */
    val tail8: String = code.substring(8)

    /** 前 8 字符校验段。 */
    val head8: String = code.substring(0, 8)

    /** 显示用 4-4-4-4，按整码顺序切块（协议 §2.1）。 */
    val pretty: String = code.chunked(4).joinToString("-")

    /** 加入门禁 HMAC 密钥：HKDF(ikm=secret, salt=check, info="huchuan/join1/v1", 32)。 */
    val kJoin: ByteArray = Hkdf.derive(secretBytes, checkBytes, Domains.JOIN, 32)

    companion object {
        private val RANDOM = SecureRandom()

        fun create(random: SecureRandom = RANDOM): InviteCode {
            val secret = ByteArray(5)
            random.nextBytes(secret)
            return InviteCode(secret, checkOf(secret))
        }

        fun checkOf(secret: ByteArray): ByteArray =
            sha256(Domains.CHECK.toByteArray(Charsets.UTF_8), secret).copyOf(5)

        /**
         * 用给定的秘密段重建一枚码（校验段按 §2.1 现算）。
         *
         * 只给**派生码**用：个人空间的码是从设备身份派生的（不是随机生成），但它仍然是合法的
         * 16 字符码，[fromFull] 能原样读回来。个人空间不接受加入，所以这枚码不作为准入凭据。
         */
        fun ofSecret(secret: ByteArray): InviteCode {
            require(secret.size == 5) { "invite secret must be 5 bytes" }
            return InviteCode(secret, checkOf(secret))
        }

        /**
         * 只给后 8 位：本机重算前 8 位（§2.1「由后 8 位重算前 8 位再比对」）。
         * 返回 null 表示字符集或长度不对。
         */
        fun fromTail8(raw: String): InviteCode? {
            val normalized = B32.normalize(raw)
            if (normalized.length != 8) return null
            if (!normalized.all { B32.ALPHABET.indexOf(it) >= 0 }) return null
            val secret = B32.decode(normalized)
            if (secret.size != 5) return null
            return InviteCode(secret, checkOf(secret))
        }

        /** 收完整 16 位：校验前 8 位必须等于重算值，否则判「邀请码不对」。 */
        fun fromFull(raw: String): InviteCode? {
            val normalized = B32.normalize(raw)
            if (normalized.length != 16) return null
            if (!normalized.all { B32.ALPHABET.indexOf(it) >= 0 }) return null
            val bytes = B32.decode(normalized)
            if (bytes.size != 10) return null
            val check = bytes.copyOfRange(0, 5)
            val secret = bytes.copyOfRange(5, 10)
            if (!constantTimeEquals(check, checkOf(secret))) return null
            return InviteCode(secret, check)
        }
    }
}
