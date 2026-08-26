package com.portaretrato.app.security

import java.security.GeneralSecurityException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Cifragem seletiva de campos com AES-256-GCM.
 *
 * Aplicada aos campos que, se o banco local vazar, causam dano real:
 *
 * - `Person.embedding` e `PendingFace.embedding` — **dado biométrico**. Um
 *   embedding facial não é reversível em foto, mas é um identificador
 *   biométrico estável: quem o obtiver pode reconhecer a mesma pessoa em
 *   qualquer outro acervo. Pela LGPD é dado pessoal sensível (art. 5º, II).
 * - `Person.phone` — permite contato direto com a família.
 *
 * O resto (nome, datas, flags) fica em claro de propósito: cifrar tudo
 * impediria consultas SQL e ordenação, e traria custo sem ganho proporcional.
 *
 * ## Formato
 *
 * ```
 * [ 1 byte versão ][ 12 bytes IV ][ ciphertext + tag GCM de 16 bytes ]
 * ```
 *
 * O byte de versão existe para permitir rotação de chave ou troca de algoritmo
 * sem quebrar o que já está gravado — sem ele, qualquer mudança futura exigiria
 * migração destrutiva do banco.
 *
 * **IV nunca é reutilizado.** Em GCM, repetir o IV com a mesma chave é uma
 * falha catastrófica: permite recuperar o XOR dos textos claros e forjar
 * autenticações. Por isso o IV é sorteado com `SecureRandom` a cada operação e
 * gravado junto do texto cifrado.
 *
 * Kotlin/JVM puro: a chave vem de fora, então isto roda em teste de unidade. O
 * acesso ao Android Keystore fica em [KeystoreKeyProvider].
 */
object FieldCrypto {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_LENGTH = 12
    private const val TAG_LENGTH_BITS = 128
    private const val VERSION_V1: Byte = 1

    private val random = SecureRandom()

    /** Cifra [plaintext]. Devolve versão + IV + texto cifrado. */
    fun encrypt(plaintext: ByteArray, key: SecretKey): ByteArray {
        val iv = ByteArray(IV_LENGTH).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
        val ciphertext = cipher.doFinal(plaintext)

        return ByteArray(1 + IV_LENGTH + ciphertext.size).also { out ->
            out[0] = VERSION_V1
            System.arraycopy(iv, 0, out, 1, IV_LENGTH)
            System.arraycopy(ciphertext, 0, out, 1 + IV_LENGTH, ciphertext.size)
        }
    }

    /**
     * Decifra. Devolve `null` em qualquer falha — dado adulterado, versão
     * desconhecida, chave errada ou entrada truncada.
     *
     * Nunca lança: um registro corrompido não pode derrubar a leitura do banco
     * inteiro. Quem chama trata `null` como "campo indisponível", e o app segue
     * funcionando com uma pessoa a menos na galeria em vez de não abrir.
     *
     * A verificação de integridade é do próprio GCM: alterar um bit do texto
     * cifrado faz `doFinal` lançar `AEADBadTagException`, que vira `null` aqui.
     */
    fun decrypt(payload: ByteArray, key: SecretKey): ByteArray? {
        if (payload.size < 1 + IV_LENGTH + TAG_LENGTH_BITS / 8) return null
        if (payload[0] != VERSION_V1) return null

        return try {
            val iv = payload.copyOfRange(1, 1 + IV_LENGTH)
            val ciphertext = payload.copyOfRange(1 + IV_LENGTH, payload.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_LENGTH_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: GeneralSecurityException) {
            null
        }
    }

    /** Conveniência para campos de texto, como o telefone. */
    fun encryptString(plaintext: String, key: SecretKey): ByteArray =
        encrypt(plaintext.toByteArray(Charsets.UTF_8), key)

    fun decryptString(payload: ByteArray, key: SecretKey): String? =
        decrypt(payload, key)?.toString(Charsets.UTF_8)

    /** Extrai o IV, para inspeção em teste. */
    internal fun ivOf(payload: ByteArray): ByteArray? =
        if (payload.size < 1 + IV_LENGTH) null else payload.copyOfRange(1, 1 + IV_LENGTH)
}
