package dev.oldcam.camera.settings

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifra pequenos segredos (a senha de publicação) com uma chave AES guardada no
 * **Android Keystore**.
 *
 * Por que não salvar a senha direto nas SharedPreferences? Porque o arquivo XML
 * fica legível em aparelhos com root ou em backups. Com o Keystore, a chave fica
 * no hardware seguro (TEE/StrongBox, quando existe) e nunca sai de lá: nem o
 * próprio app consegue ler os bytes da chave, só pedir "cifre/decifre isso".
 *
 * (A lib androidx.security-crypto fazia isso, mas foi descontinuada — por isso
 * implementamos o mínimo necessário aqui.)
 *
 * `object` em Kotlin = singleton: existe uma única instância, criada na 1ª vez que é usada.
 */
object SecretCipher {
    private const val KEYSTORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "oldcam_settings_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12        // bytes; tamanho recomendado para GCM
    private const val TAG_BITS = 128      // tag de autenticação: detecta adulteração

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        // `as?` = cast seguro: devolve null em vez de lançar exceção se o tipo não bater.
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** Devolve "IV + texto cifrado" em Base64, pronto para guardar como String. */
    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey()) // o Keystore gera um IV aleatório
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    /** Devolve "" se o dado estiver vazio ou corrompido (ex.: chave apagada ao limpar dados). */
    fun decrypt(stored: String): String {
        if (stored.isEmpty()) return ""
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val body = bytes.copyOfRange(IV_SIZE, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(body), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }
}
