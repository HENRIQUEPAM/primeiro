package com.portaretrato.app.call

import java.math.BigInteger
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

/**
 * Assinatura e verificação de SDP com ECDSA P-256, e o "número de segurança"
 * exibido no pareamento.
 *
 * Isto é **Kotlin/JVM puro** de propósito: não toca no Android Keystore nem em
 * nenhuma API de plataforma, então roda em teste de unidade na JVM. O acesso ao
 * Keystore (StrongBox → TEE) fica em [DeviceIdentityManager], que apenas
 * fornece as chaves para cá.
 *
 * ## Por que assinar o SDP
 *
 * A Seção 10 da especificação registra o limite: o canal de sinalização
 * (Firestore) tem TLS, Auth e Security Rules, mas **não** verificação de
 * identidade forte a cada request. A defesa real contra sinalização forjada é
 * esta assinatura — o receptor confere contra a chave pública **pinada
 * localmente no pareamento**, nunca contra a que vem dentro do próprio
 * documento (senão um atacante assinaria com a própria chave e mandaria junto).
 *
 * Isto garante **autenticidade** do SDP negociado. A **confidencialidade** da
 * mídia é do DTLS-SRTP nativo do WebRTC, que vale inclusive atravessando o
 * relay TURN — o TURN encaminha pacotes cifrados e nunca decifra.
 */
object SdpSigner {

    private const val SIGNATURE_ALGORITHM = "SHA256withECDSA"
    private const val DIGEST_ALGORITHM = "SHA-256"

    /** Assina [sdp] e devolve a assinatura DER em Base64. */
    fun sign(sdp: String, privateKey: PrivateKey): String {
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initSign(privateKey)
        signature.update(sdp.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    /**
     * Verifica [sdp] contra [signatureBase64] usando a chave **pinada**.
     *
     * Devolve `false` em qualquer falha, inclusive Base64 malformado ou
     * assinatura de tamanho inválido — nunca lança. Uma exceção aqui viraria
     * um crash no caminho de uma chamada recebida, que é exatamente onde não se
     * pode quebrar; a chamada deve ser recusada e registrada como
     * `failed/signature_mismatch`.
     */
    fun verify(sdp: String, signatureBase64: String, pinnedPublicKey: PublicKey): Boolean = try {
        val bytes = Base64.getDecoder().decode(signatureBase64)
        val signature = Signature.getInstance(SIGNATURE_ALGORITHM)
        signature.initVerify(pinnedPublicKey)
        signature.update(sdp.toByteArray(Charsets.UTF_8))
        signature.verify(bytes)
    } catch (e: Exception) {
        false
    }

    /** Chave pública em X.509/SPKI Base64, como vai para `publicKeyP256`. */
    fun encodePublicKey(publicKey: PublicKey): String =
        Base64.getEncoder().encodeToString(publicKey.encoded)

    /**
     * "Número de segurança" mostrado nas duas telas durante o pareamento.
     *
     * Propriedades que importam:
     *
     * - **Simétrico.** As duas chaves são ordenadas antes de concatenar, então
     *   os dois aparelhos calculam exatamente o mesmo número sem precisar
     *   combinar quem é A e quem é B.
     * - **Só dígitos.** Uma pessoa idosa vai comparar dois números em dois
     *   aparelhos; hexadecimal com letras é bem pior para isso.
     * - **Agrupado.** 30 dígitos em 6 grupos de 5, no estilo do "número de
     *   segurança" do Signal (que usa 60). Metade do comprimento ainda deixa
     *   ~10^30 combinações, muito além do que uma colisão prática exigiria, e a
     *   comparação visual fica bem mais fácil.
     */
    fun safetyNumber(publicKeyA: PublicKey, publicKeyB: PublicKey): String {
        val a = publicKeyA.encoded
        val b = publicKeyB.encoded
        val first: ByteArray
        val second: ByteArray
        if (compareBytes(a, b) <= 0) {
            first = a; second = b
        } else {
            first = b; second = a
        }

        val digest = MessageDigest.getInstance(DIGEST_ALGORITHM)
        digest.update(first)
        digest.update(second)
        val hash = digest.digest()

        // Interpreta o hash como inteiro sem sinal e extrai 30 dígitos decimais.
        val value = BigInteger(1, hash)
        val digits = value.mod(BigInteger.TEN.pow(SAFETY_NUMBER_DIGITS))
            .toString()
            .padStart(SAFETY_NUMBER_DIGITS, '0')

        return digits.chunked(SAFETY_NUMBER_GROUP_SIZE).joinToString(" ")
    }

    private fun compareBytes(a: ByteArray, b: ByteArray): Int {
        val common = minOf(a.size, b.size)
        for (i in 0 until common) {
            val diff = (a[i].toInt() and 0xFF) - (b[i].toInt() and 0xFF)
            if (diff != 0) return diff
        }
        return a.size - b.size
    }

    const val SAFETY_NUMBER_DIGITS = 30
    const val SAFETY_NUMBER_GROUP_SIZE = 5
}
