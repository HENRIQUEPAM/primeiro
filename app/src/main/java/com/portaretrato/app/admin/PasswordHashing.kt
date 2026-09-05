package com.portaretrato.app.admin

import java.security.SecureRandom
import java.security.spec.KeySpec
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Hash salgado da senha de administrador, com PBKDF2-HMAC-SHA256.
 *
 * Não é a mesma classe de ameaça que [com.portaretrato.app.security.FieldCrypto]
 * protege (dado biométrico, se um invasor levar o telefone) — aqui o objetivo é
 * só impedir que alguém pegando o aparelho emprestado tropece sem querer num
 * recurso que abre câmera e microfone sozinho. Ainda assim a senha nunca fica
 * em claro em lugar nenhum: nem gravada em disco (não há mais "por aparelho" —
 * ver [com.portaretrato.app.admin.AdminPassword], a MESMA senha vale em
 * qualquer instalação), nem no próprio código-fonte, mesmo o repositório
 * sendo aberto. "A senha de administrador está ali, de bandeja, pra quem
 * abrir o arquivo" seria um erro bobo de se cometer só porque a ameaça é
 * baixa.
 *
 * ## Formato do payload
 *
 * ```
 * [ 1 byte versão ][ 16 bytes salt ][ 32 bytes hash PBKDF2 ]
 * ```
 *
 * O byte de versão segue o mesmo motivo de [com.portaretrato.app.security.FieldCrypto]:
 * permite trocar o algoritmo depois sem quebrar uma senha já salva.
 *
 * PBKDF2WithHmacSHA256 e não bcrypt/scrypt: é o único algoritmo de
 * derivação de chave lento disponível no `javax.crypto` do próprio Android,
 * sem trazer biblioteca nova só para isto.
 *
 * Kotlin/JVM puro — testável sem Android.
 */
object PasswordHashing {

    private const val ALGORITHM = "PBKDF2WithHmacSHA256"
    private const val SALT_LENGTH_BYTES = 16
    private const val HASH_LENGTH_BITS = 256
    private const val ITERATIONS = 120_000
    private const val VERSION_V1: Byte = 1

    private val random = SecureRandom()

    /** Gera o payload salgado para gravar. Cada chamada usa um salt novo. */
    fun hash(password: String): ByteArray {
        val salt = ByteArray(SALT_LENGTH_BYTES).also(random::nextBytes)
        val digest = derive(password, salt)

        return ByteArray(1 + SALT_LENGTH_BYTES + digest.size).also { out ->
            out[0] = VERSION_V1
            System.arraycopy(salt, 0, out, 1, SALT_LENGTH_BYTES)
            System.arraycopy(digest, 0, out, 1 + SALT_LENGTH_BYTES, digest.size)
        }
    }

    /**
     * Confere [password] contra o payload gravado por [hash].
     *
     * Nunca lança: payload truncado, versão desconhecida ou corrompido só
     * devolve `false` — o mesmo tratamento de "senha errada", sem distinguir
     * o motivo para quem chama.
     */
    fun matches(password: String, stored: ByteArray): Boolean {
        if (stored.size < 1 + SALT_LENGTH_BYTES) return false
        if (stored[0] != VERSION_V1) return false

        return try {
            val salt = stored.copyOfRange(1, 1 + SALT_LENGTH_BYTES)
            val expected = stored.copyOfRange(1 + SALT_LENGTH_BYTES, stored.size)
            val actual = derive(password, salt)
            constantTimeEquals(actual, expected)
        } catch (e: Exception) {
            false
        }
    }

    private fun derive(password: String, salt: ByteArray): ByteArray {
        val spec: KeySpec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, HASH_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance(ALGORITHM)
        return factory.generateSecret(spec).encoded
    }

    /**
     * Compara byte a byte sem sair mais cedo — uma comparação normal
     * (`contentEquals`) vaza, pelo tempo de execução, até onde os dois hashes
     * batem. Improvável de ser explorável aqui (senha local, sem rede no
     * meio), mas comparar hash de senha em tempo variável é o tipo de atalho
     * que não custa nada evitar.
     */
    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }
}
