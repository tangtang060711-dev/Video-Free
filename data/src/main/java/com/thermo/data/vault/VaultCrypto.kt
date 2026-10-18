package com.thermo.data.vault

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 加密空间的密钥管理。
 *
 * ## 两层密钥，为什么不是一层
 * - **主密钥**（`thermo_vault_master`）活在 Android Keystore 里，只用于"包裹"数据密钥。
 *   它的好处是**永远不以明文形式进入应用进程**（硬件支持时材料在 TEE 里）。
 * - **数据密钥**（32 字节随机数）被主密钥用 AES-GCM 包起来，密文存在 SharedPreferences。
 *
 * 为什么不直接把数据密钥交给 Keystore 生成：Keystore 里的 AES 密钥做 CTR 时，
 * 每次都得走一次 KeyStore 查询（真机上约 0.3~1 ms）。播放一个视频要读几万次，
 * 那点开销会变成实打实的卡顿。包裹一层、把数据密钥缓存在内存里是标准做法。
 *
 * ## 关于 setUserAuthenticationRequired
 * **刻意不设 true。** 设了以后密钥会绑定"距上次解锁不超过 N 秒"，
 * 播放到一半（尤其是长片 + 拖动进度条）会随机抛 KeyPermanentlyInvalidatedException。
 * 用户身份校验放在 UI 层（系统密码/图案/指纹，见 :app 的 VaultAuth），
 * 进入加密空间前必须通过；通不过就拿不到 VaultStore.unlock()，
 * 数据源会直接拒绝打开。这是一个可接受的折中，**但它确实弱于**
 * "密钥本身要求每次使用都验身份"。真要更强，得配合 Android Keystore 的
 * setUnlockedDeviceRequired + 每次 open 前重新取密钥，代价是拖动进度条会卡。
 */
@Singleton
class VaultCrypto @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** Keystore 里的主密钥；不存在就生成。 */
    private fun masterKey(): SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (ks.getEntry(MASTER_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                MASTER_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // setUserAuthenticationRequired 保持默认 false，理由见类注释。
                .build(),
        )
        return generator.generateKey()
    }

    /**
     * 数据密钥（AES-256）。首次调用会生成并包裹保存，之后都是解包。
     *
     * 注意它是**每次调用都可能走一次 GCM 解密**的；调用方（VaultStore）
     * 自己缓存一份，别在 read() 里调它。
     */
    fun dataKey(): SecretKey {
        val wrapped = prefs.getString(KEY_WRAPPED, null)
        val ivText = prefs.getString(KEY_WRAP_IV, null)

        if (wrapped != null && ivText != null) {
            return runCatching {
                val c = Cipher.getInstance(TRANSFORMATION_GCM)
                c.init(
                    Cipher.DECRYPT_MODE,
                    masterKey(),
                    GCMParameterSpec(GCM_TAG_BITS, Base64.decode(ivText, Base64.NO_WRAP)),
                )
                SecretKeySpec(c.doFinal(Base64.decode(wrapped, Base64.NO_WRAP)), "AES")
            }.getOrElse {
                // 解不开只可能是：用户清了应用数据、系统重置了 Keystore、
                // 或者换了签名重装。任何一种情况下旧密文都已经不可读了，
                // 直接重造一把新钥匙，让"清空加密空间"成为唯一可行路径。
                freshDataKey()
            }
        }
        return freshDataKey()
    }

    private fun freshDataKey(): SecretKey {
        val raw = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val c = Cipher.getInstance(TRANSFORMATION_GCM)
        c.init(Cipher.ENCRYPT_MODE, masterKey())
        prefs.edit()
            .putString(KEY_WRAPPED, Base64.encodeToString(c.doFinal(raw), Base64.NO_WRAP))
            .putString(KEY_WRAP_IV, Base64.encodeToString(c.iv, Base64.NO_WRAP))
            .apply()
        return SecretKeySpec(raw, "AES")
    }

    /** 新随机 nonce（8 字节）。每个文件一个，索引文件也有自己的。 */
    fun newNonce(): ByteArray = ByteArray(VaultFormat.NONCE_SIZE).also { SecureRandom().nextBytes(it) }

    /**
     * 计数器 = nonce(高 64 位) || 块号(低 64 位)。
     *
     * 这样拼的好处：偏移只影响低 64 位，**永远不会进位污染 nonce**。
     * 若把随机 nonce 直接当 128 位初值，跳到两小时后可能进位，
     * 那时解出来的就完全是垃圾 —— 而且只在长片上复现，非常难查。
     */
    private fun counterFor(nonce: ByteArray, offset: Long): ByteArray {
        val counter = ByteArray(16)
        System.arraycopy(nonce, 0, counter, 0, VaultFormat.NONCE_SIZE)
        VaultFormat.writeLongBe(counter, 8, offset / 16)
        return counter
    }

    /** 从头开始加密用的 cipher。 */
    fun encryptStart(nonce: ByteArray): Cipher {
        val c = Cipher.getInstance(TRANSFORMATION_CTR)
        c.init(Cipher.ENCRYPT_MODE, dataKey(), IvParameterSpec(counterFor(nonce, 0L)))
        return c
    }

    /**
     * 从明文偏移 [offset] 开始解密的 cipher。
     *
     * 返回的第二个值是"要先丢掉多少字节"：调用方必须从**块对齐**的位置
     * （`offset / 16 * 16`）开始读密文，解密后丢掉前 `offset % 16` 字节。
     * 因为 CTR 的计数器只能按 16 字节块走，没有"半块起点"这种东西。
     */
    fun decryptAt(nonce: ByteArray, offset: Long): Pair<Cipher, Int> {
        val c = Cipher.getInstance(TRANSFORMATION_CTR)
        c.init(Cipher.DECRYPT_MODE, dataKey(), IvParameterSpec(counterFor(nonce, offset)))
        return c to (offset % 16L).toInt()
    }

    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val MASTER_ALIAS = "thermo_vault_master"
        private const val PREFS = "thermo_vault"
        private const val KEY_WRAPPED = "wrapped_key"
        private const val KEY_WRAP_IV = "wrapped_key_iv"
        private const val TRANSFORMATION_GCM = "AES/GCM/NoPadding"
        private const val TRANSFORMATION_CTR = "AES/CTR/NoPadding"
        private const val GCM_TAG_BITS = 128
    }
}