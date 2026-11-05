package app.huchuan.core.m1

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 协议 §5：每安装自签 EC P-256 证书，CN=deviceId，10 年。
 *
 * JDK 没有公开的证书生成 API（sun.security.x509 未导出），所以这里手写最小 DER：
 * 只覆盖 TLS 需要的字段（v3 + CN + 有效期 + SPKI + basicConstraints/keyUsage/extKeyUsage）。
 * 纯 JDK、可移植到 Android，不引密码库。
 */
object X509SelfSigned {

    private const val OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2"
    private const val OID_EC_PUBLIC_KEY = "1.2.840.10045.2.1"
    private const val OID_PRIME256V1 = "1.2.840.10045.3.1.7"
    private const val OID_CN = "2.5.4.3"
    private const val OID_BASIC_CONSTRAINTS = "2.5.29.19"
    private const val OID_KEY_USAGE = "2.5.29.15"
    private const val OID_EXT_KEY_USAGE = "2.5.29.37"
    private const val OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
    private const val OID_CLIENT_AUTH = "1.3.6.1.5.5.7.3.2"

    fun create(keyPair: KeyPair, commonName: String, validityDays: Long = 3650): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 24L * 3600 * 1000)
        val notAfter = Date(now + validityDays * 24L * 3600 * 1000)

        val serial = BigInteger(63, SecureRandom()).abs().add(BigInteger.ONE)
        val name = seq(set(seq(oid(OID_CN), utf8(commonName))))

        val spki = seq(
            seq(oid(OID_EC_PUBLIC_KEY), oid(OID_PRIME256V1)),
            // BIT STRING：unused=0 ‖ 0x04 ‖ X ‖ Y（未压缩点）
            der(
                0x03,
                byteArrayOf(0) + byteArrayOf(0x04) +
                    DeviceIdentity.encodePoint(keyPair.public as java.security.interfaces.ECPublicKey),
            ),
        )

        val extensions = seq(
            seq(oid(OID_BASIC_CONSTRAINTS), derBoolean(true), der(0x04, seq())),
            seq(oid(OID_KEY_USAGE), derBoolean(true), der(0x04, der(0x03, byteArrayOf(0x07, 0x80.toByte())))),
            seq(oid(OID_EXT_KEY_USAGE), der(0x04, seq(oid(OID_SERVER_AUTH), oid(OID_CLIENT_AUTH)))),
        )

        val tbs = seq(
            der(0xA0, derInteger(BigInteger.valueOf(2))),
            derInteger(serial),
            seq(oid(OID_ECDSA_SHA256)),
            name,
            seq(utcTime(notBefore), utcTime(notAfter)),
            name,
            spki,
            der(0xA3, extensions),
        )

        val sig = Signature.getInstance("SHA256withECDSA")
        sig.initSign(keyPair.private)
        sig.update(tbs)
        val sigBytes = sig.sign()

        val certDer = seq(tbs, seq(oid(OID_ECDSA_SHA256)), der(0x03, byteArrayOf(0) + sigBytes))
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
        cert.verify(keyPair.public)
        return cert
    }

    // ---- 最小 DER 编码 ----

    private fun der(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + derLen(content.size) + content

    private fun derLen(n: Int): ByteArray {
        if (n < 0x80) return byteArrayOf(n.toByte())
        val list = ArrayList<Byte>()
        var v = n
        while (v > 0) {
            list.add(0, (v and 0xff).toByte())
            v = v ushr 8
        }
        val out = ByteArray(list.size + 1)
        out[0] = (0x80 or list.size).toByte()
        for (i in list.indices) out[i + 1] = list[i]
        return out
    }

    private fun join(parts: Array<out ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in parts) out.write(p)
        return out.toByteArray()
    }

    private fun seq(vararg parts: ByteArray): ByteArray = der(0x30, join(parts))
    private fun set(vararg parts: ByteArray): ByteArray = der(0x31, join(parts))
    private fun derBoolean(v: Boolean): ByteArray = der(0x01, byteArrayOf(if (v) 0xff.toByte() else 0x00))
    private fun derInteger(v: BigInteger): ByteArray = der(0x02, v.toByteArray())
    private fun utf8(s: String): ByteArray = der(0x0c, s.toByteArray(Charsets.UTF_8))

    private fun utcTime(d: Date): ByteArray {
        val fmt = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return der(0x17, fmt.format(d).toByteArray(Charsets.US_ASCII))
    }

    private fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toLong() }
        require(parts.size >= 2) { "bad oid " + dotted }
        val out = ByteArrayOutputStream()
        out.write((parts[0] * 40 + parts[1]).toInt())
        for (i in 2 until parts.size) {
            var v = parts[i]
            if (v < 0x80) {
                out.write(v.toInt())
                continue
            }
            val stack = ArrayList<Int>()
            while (v > 0) {
                stack.add((v and 0x7f).toInt())
                v = v ushr 7
            }
            for (k in stack.indices.reversed()) {
                var b = stack[k]
                if (k != 0) b = b or 0x80
                out.write(b)
            }
        }
        return der(0x06, out.toByteArray())
    }
}
