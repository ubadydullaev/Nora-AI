package uz.nora.ai

import android.content.Context
import android.util.Base64
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object NoraSecureStore {

    private const val ALIAS = "NoraSecureKey"
    private const val PREFS = "nora_secure_settings"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private val namePattern = Regex("[a-zA-Z0-9_]{1,80}")

    private fun validName(name: String): Boolean {
        return namePattern.matches(name)
    }

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply {
            load(null)
        }

        val existing = store.getKey(ALIAS, null) as? SecretKey

        if (existing != null) {
            return existing
        }

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore"
        )

        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or
                    KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(
                    KeyProperties.ENCRYPTION_PADDING_NONE
                )
                .setRandomizedEncryptionRequired(true)
                .build()
        )

        return generator.generateKey()
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(
            PREFS,
            Context.MODE_PRIVATE
        )

    @Synchronized
    fun save(
        context: Context,
        name: String,
        value: String
    ) {
        require(validName(name)) {
            "Sozlama nomi noto'g'ri"
        }

        if (value.isBlank()) {
            delete(context, name)
            return
        }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())

        val encrypted = cipher.doFinal(
            value.toByteArray(Charsets.UTF_8)
        )

        val saved = preferences(context).edit()
            .putString(
                "${name}_iv",
                Base64.encodeToString(
                    cipher.iv,
                    Base64.NO_WRAP
                )
            )
            .putString(
                "${name}_data",
                Base64.encodeToString(
                    encrypted,
                    Base64.NO_WRAP
                )
            )
            .commit()

        check(saved) {
            "Sozlamani saqlab bo'lmadi"
        }
    }

    @Synchronized
    fun read(
        context: Context,
        name: String
    ): String? {
        if (!validName(name)) return null

        val prefs = preferences(context)

        val ivText = prefs.getString("${name}_iv", null)
            ?: return null

        val dataText = prefs.getString("${name}_data", null)
            ?: return null

        return try {
            val iv = Base64.decode(ivText, Base64.NO_WRAP)
            val data = Base64.decode(dataText, Base64.NO_WRAP)

            if (iv.size != 12 || data.size < 16) {
                delete(context, name)
                return null
            }

            val cipher = Cipher.getInstance(TRANSFORMATION)

            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, iv)
            )

            String(
                cipher.doFinal(data),
                Charsets.UTF_8
            )
        } catch (_: Exception) {
            // Buzilgan yoki ochilmaydigan yozuvni qayta-qayta o'qishga urinmaymiz.
            delete(context, name)
            null
        }
    }

    @Synchronized
    fun exists(
        context: Context,
        name: String
    ): Boolean {
        if (!validName(name)) return false

        val prefs = preferences(context)

        return prefs.contains("${name}_iv") &&
            prefs.contains("${name}_data")
    }

    @Synchronized
    fun delete(
        context: Context,
        name: String
    ) {
        if (!validName(name)) return

        preferences(context).edit()
            .remove("${name}_iv")
            .remove("${name}_data")
            .commit()
    }

    @Synchronized
    fun deleteAll(context: Context) {
        preferences(context)
            .edit()
            .clear()
            .commit()
    }
}
