package app.huchuan.core.m1

import java.io.ByteArrayOutputStream
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.KeyManager
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/** 本机 TLS 身份：自签 EC P-256 证书 + 私钥（协议 §5）。 */
class TlsIdentity(val identity: DeviceIdentity, val certificate: X509Certificate) {
    companion object {
        fun create(identity: DeviceIdentity): TlsIdentity =
            TlsIdentity(identity, X509SelfSigned.create(identityKeyPair(identity), identity.deviceId))

        private fun identityKeyPair(identity: DeviceIdentity): java.security.KeyPair =
            java.security.KeyPair(identity.publicKey, identity.privateKey)
    }
}

object Tls {
    private val ALLOWED_PROTOCOLS = arrayOf("TLSv1.3", "TLSv1.2")
    private val ALLOWED_CIPHERS = arrayOf(
        "TLS_AES_128_GCM_SHA256",
        "TLS_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
    )

    fun serverContext(tls: TlsIdentity): SSLContext = context(tls)

    fun clientContext(tls: TlsIdentity): SSLContext = context(tls)

    private fun context(tls: TlsIdentity): SSLContext {
        val password = charArrayOf()
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(null, password)
        ks.setKeyEntry("huchuan", tls.identity.privateKey, password, arrayOf(tls.certificate))
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(ks, password)
        val km: Array<KeyManager> = kmf.keyManagers
        val tm: Array<TrustManager> = arrayOf(TRUST_ALL)
        val ctx = SSLContext.getInstance("TLS")
        ctx.init(km, tm, SecureRandom())
        return ctx
    }

    /**
     * 套件白名单 + ALPN huchuan/1。协议 §5：两端不校验证书链，身份由 hello 签名绑定，
     * chanId 含两端证书摘要。
     */
    fun configure(socket: SSLSocket) {
        val params = socket.sslParameters
        val supportedProtocols = socket.supportedProtocols.toSet()
        params.protocols = ALLOWED_PROTOCOLS.filter { supportedProtocols.contains(it) }.toTypedArray()
        val supportedCiphers = socket.supportedCipherSuites.toSet()
        val ciphers = ALLOWED_CIPHERS.filter { supportedCiphers.contains(it) }
        if (ciphers.isNotEmpty()) params.cipherSuites = ciphers.toTypedArray()
        // SSLParameters.setApplicationProtocols 在 Android 上要 API 29+；老设备没有这个方法时
        // 跳过 ALPN（协议层自己带版本号，不靠 ALPN 协商）。
        try {
            params.applicationProtocols = arrayOf(Proto.ALPN)
        } catch (_: NoSuchMethodError) {
        } catch (_: UnsupportedOperationException) {
        }
        params.useCipherSuitesOrder = true
        socket.sslParameters = params
    }

    /**
     * chanId = SHA-256(u16be(len)‖clientCertDER ‖ u16be(len)‖serverCertDER)，两端各按角色顺序计算（协议 §1.2）。
     */
    fun chanId(ownCert: X509Certificate, peerCert: X509Certificate, serverSide: Boolean): ByteArray {
        val clientDer = if (serverSide) peerCert.encoded else ownCert.encoded
        val serverDer = if (serverSide) ownCert.encoded else peerCert.encoded
        val out = ByteArrayOutputStream()
        writeLenPrefixed(out, clientDer)
        writeLenPrefixed(out, serverDer)
        return sha256(out.toByteArray())
    }

    private fun writeLenPrefixed(out: ByteArrayOutputStream, data: ByteArray) {
        require(data.size <= 0xffff)
        out.write((data.size ushr 8) and 0xff)
        out.write(data.size and 0xff)
        out.write(data)
    }

    val TRUST_ALL: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
