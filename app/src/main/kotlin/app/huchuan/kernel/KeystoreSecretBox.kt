package app.huchuan.kernel

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import app.huchuan.core.m1.SecretBox
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * keySeed 落盘包裹：Android Keystore 里的 AES-256-GCM 密钥，明文永不落盘。
 *
 * token = base64(iv ‖ ciphertext ‖ tag)，方案标记 keystore-aes-gcm-1 写进 state.json。
 * 密钥本身不可导出（TEE/StrongBox 里），拿到 state.json 也解不开。
 */
class KeystoreSecretBox(
    private val alias: String = ALIAS,
) : SecretBox {

    override val scheme: String = SCHEME

    private val keyStore: KeyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }

    private fun key(): SecretKey {
        val existing = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        val generated = generator.generateKey()
        // 让后续 getEntry 看得到新密钥。
        keyStore.load(null)
        return generated
    }

    override fun seal(plain: ByteArray): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        val out = ByteArray(iv.size + body.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(body, 0, out, iv.size, body.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    override fun open(token: String): ByteArray? = try {
        val raw = Base64.decode(token, Base64.NO_WRAP)
        if (raw.size <= IV_BYTES) {
            null
        } else {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, raw, 0, IV_BYTES))
            cipher.doFinal(raw, IV_BYTES, raw.size - IV_BYTES)
        }
    } catch (e: Throwable) {
        null
    }

    companion object {
        const val SCHEME = "keystore-aes-gcm-1"
        const val ALIAS = "huchuan.m1.keyseed.v1"
        private const val PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }
}

/**
 * Keystore 不可用时的等效方案：包裹密钥由设备身份私钥（identity.txt 的 PKCS8）经
 * HKDF-SHA256 派生，不额外落盘任何密钥材料。identity.txt 在应用私有目录，
 * 受 Android 应用沙箱 + 文件级加密保护；代价是「root 后能读私有目录」这类攻击下
 * 强度不如 Keystore，所以只作为兜底，并在日志里明确写用了哪一套。
 */
class IdentitySecretBox(
    private val identityFile: File,
) : SecretBox {

    override val scheme: String = SCHEME

    private fun wrapKey(): ByteArray? {
        if (!identityFile.isFile) return null
        val lines = identityFile.readText(Charsets.UTF_8).lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        if (lines.isEmpty()) return null
        val pkcs8 = try {
            Base64.decode(lines[0], Base64.NO_WRAP)
        } catch (e: Throwable) {
            return null
        }
        return hkdfSha256(pkcs8, SALT, INFO, 32)
    }

    override fun seal(plain: ByteArray): String {
        val key = wrapKey() ?: throw IllegalStateException("identity.txt 不可读，无法派生包裹密钥")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(key, "AES"))
        val iv = cipher.iv
        val body = cipher.doFinal(plain)
        val out = ByteArray(iv.size + body.size)
        System.arraycopy(iv, 0, out, 0, iv.size)
        System.arraycopy(body, 0, out, iv.size, body.size)
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    override fun open(token: String): ByteArray? = try {
        val key = wrapKey() ?: return null
        val raw = Base64.decode(token, Base64.NO_WRAP)
        if (raw.size <= 12) {
            null
        } else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(key, "AES"),
                GCMParameterSpec(128, raw, 0, 12),
            )
            cipher.doFinal(raw, 12, raw.size - 12)
        }
    } catch (e: Throwable) {
        null
    }

    companion object {
        const val SCHEME = "identity-hkdf-aes-gcm-1"
        private val SALT = "huchuan/m1/state".toByteArray(Charsets.UTF_8)
        private val INFO = "keyseed-wrap".toByteArray(Charsets.UTF_8)

        /** HKDF-SHA256（RFC 5869），与 core 的 Hkdf 同口径。 */
        fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(if (salt.isEmpty()) ByteArray(32) else salt, "HmacSHA256"))
            val prk = mac.doFinal(ikm)
            mac.init(javax.crypto.spec.SecretKeySpec(prk, "HmacSHA256"))
            val out = java.io.ByteArrayOutputStream()
            var previous = ByteArray(0)
            var counter = 1
            while (out.size() < length) {
                mac.reset()
                mac.update(previous)
                mac.update(info)
                mac.update(counter.toByte())
                previous = mac.doFinal()
                out.write(previous)
                counter++
            }
            return out.toByteArray().copyOf(length)
        }
    }
}
