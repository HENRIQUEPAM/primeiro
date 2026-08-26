package com.portaretrato.app.call

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.ECGenParameterSpec

/**
 * Par de identidade ECDSA P-256 do aparelho, no Android Keystore.
 *
 * Conforme a Seção 7.6 / 9 da especificacao v3.1: **um unico par**, gerado uma
 * vez no onboarding, com StrongBox quando disponivel e TEE como piso minimo.
 *
 * A Secao 9 registra por que P-256 e nao X25519/Ed25519: a CDD do Android
 * garante P-256 nativo no StrongBox, enquanto as curvas Edwards exigiriam
 * geracao em software mais key-wrapping AES-GCM — complexidade que o par unico
 * elimina.
 *
 * O mesmo par serve para tres coisas:
 *  (a) compor o "numero de seguranca" do pareamento ([SdpSigner.safetyNumber]);
 *  (b) assinar cada SDP offer/answer antes de gravar no Firestore;
 *  (c) ser verificado do outro lado contra a chave publica pinada localmente.
 *
 * A chave de identidade e **distinta** da chave de cifragem do Room (Secao
 * 7.6b): propositos e aliases diferentes, nunca reaproveitados.
 */
class DeviceIdentityManager {

    /** Gera o par se ainda nao existir. Idempotente. */
    fun ensureKeyPair(): Boolean = try {
        if (keyStore().containsAlias(KEY_ALIAS)) {
            true
        } else {
            generate(useStrongBox = true) || generate(useStrongBox = false)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Falha ao preparar a chave de identidade", e)
        false
    }

    fun publicKey(): PublicKey? = try {
        keyStore().getCertificate(KEY_ALIAS)?.publicKey
    } catch (e: Exception) {
        Log.e(TAG, "Falha ao ler a chave publica", e)
        null
    }

    fun privateKey(): PrivateKey? = try {
        keyStore().getKey(KEY_ALIAS, null) as? PrivateKey
    } catch (e: Exception) {
        Log.e(TAG, "Falha ao ler a chave privada", e)
        null
    }

    /** Chave publica em X.509/SPKI Base64, para o campo `publicKeyP256`. */
    fun encodedPublicKey(): String? = publicKey()?.let(SdpSigner::encodePublicKey)

    /** Assina o SDP. `null` se a chave nao estiver disponivel. */
    fun signSdp(sdp: String): String? = privateKey()?.let { SdpSigner.sign(sdp, it) }

    /**
     * Diz se a chave ficou no StrongBox ou apenas no TEE.
     *
     * Nao muda o comportamento do app — serve para telemetria e para a tela de
     * diagnostico, ja que a Secao 10 trata StrongBox como desejavel e TEE como
     * piso aceitavel.
     */
    fun isStrongBoxBacked(): Boolean = strongBoxBacked

    private var strongBoxBacked = false

    private fun generate(useStrongBox: Boolean): Boolean = try {
        val generator = KeyPairGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_EC,
            ANDROID_KEYSTORE,
        )
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(ECGenParameterSpec(CURVE))
            .setDigests(KeyProperties.DIGEST_SHA256)
            // Sem exigir autenticacao do usuario: o porta-retrato precisa
            // assinar SDP sem ninguem desbloquear a tela.
            .setUserAuthenticationRequired(false)
            .apply { if (useStrongBox) setIsStrongBoxBacked(true) }
            .build()
        generator.initialize(spec)
        generator.generateKeyPair()
        strongBoxBacked = useStrongBox
        Log.i(TAG, "Chave de identidade gerada (strongBox=$useStrongBox)")
        true
    } catch (e: StrongBoxUnavailableException) {
        // Caminho esperado em aparelhos sem StrongBox: cai para TEE.
        Log.i(TAG, "StrongBox indisponivel; usando TEE.")
        false
    } catch (e: Exception) {
        Log.e(TAG, "Falha ao gerar a chave (strongBox=$useStrongBox)", e)
        false
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private companion object {
        const val TAG = "DeviceIdentity"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        /** Alias distinto do usado para cifrar campos do Room (Secao 7.6b). */
        const val KEY_ALIAS = "portaretrato_device_identity_p256"
        const val CURVE = "secp256r1"
    }
}
