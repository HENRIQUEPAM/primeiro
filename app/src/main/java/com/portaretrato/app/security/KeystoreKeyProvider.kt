package com.portaretrato.app.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

/**
 * Chave AES-256 de cifragem de campos, no Android Keystore.
 *
 * **Distinta da chave de identidade ECDSA** ([DeviceIdentityManager]): alias
 * diferente, proposito diferente, nunca reaproveitada. Reusar uma chave para
 * assinar e cifrar e um erro classico — alem de ser ma pratica criptografica,
 * amarra dois ciclos de vida que deveriam ser independentes (rotacionar a
 * chave de cifragem nao pode invalidar o pareamento dos aparelhos).
 *
 * A chave nunca sai do Keystore: o material bruto e inacessivel ao app. Se o
 * aparelho for comprometido em nivel de aplicativo, o atacante consegue pedir
 * ao Keystore que decifre, mas nao consegue extrair a chave para levar embora.
 *
 * `setUserAuthenticationRequired(false)` de proposito: o porta-retrato precisa
 * ler o banco em background para varrer rostos, sem ninguem desbloquear a tela.
 */
class KeystoreKeyProvider {

    /** Devolve a chave, criando na primeira vez. `null` se o Keystore falhar. */
    fun fieldKey(): SecretKey? = try {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        existing ?: generate()
    } catch (e: Exception) {
        Log.e(TAG, "Falha ao obter a chave de cifragem de campos", e)
        null
    }

    private fun generate(): SecretKey? = try {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                // Sem exigir desbloqueio: a varredura roda em background.
                .setUserAuthenticationRequired(false)
                // Deixa o app fornecer o IV, como o FieldCrypto faz. Sem isto o
                // Keystore gera o IV e a leitura do payload gravado quebraria.
                .setRandomizedEncryptionRequired(false)
                .build(),
        )
        generator.generateKey()
    } catch (e: Exception) {
        Log.e(TAG, "Falha ao gerar a chave de cifragem de campos", e)
        null
    }

    private companion object {
        const val TAG = "KeystoreKeyProvider"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** Alias proprio, separado do da chave de identidade. */
        const val KEY_ALIAS = "portaretrato_field_encryption_aes"
        const val KEY_SIZE_BITS = 256
    }
}
