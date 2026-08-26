package com.portaretrato.app.call

/**
 * Normalizacao de telefone para E.164 (sem o "+").
 *
 * Separado de [WhatsAppFallback] por nao depender de nada do Android: assim
 * roda em teste de unidade na JVM, e e justamente a parte com regra de negocio
 * de verdade.
 *
 * Corrige um defeito concreto do app atual (v2.9): `openWhatsApp` e
 * `findVideoCallIntent` apenas filtravam digitos do telefone salvo. Um contato
 * gravado como "11 99999-9999" — formato absolutamente normal na agenda
 * brasileira — virava `wa.me/11999999999`, sem DDI, que o WhatsApp nao resolve.
 * O usuario via a conversa nao abrir e nao havia nenhuma mensagem explicando.
 */
object PhoneNumbers {

    /** DDI aplicado quando o numero salvo nao tem codigo de pais. */
    const val DEFAULT_COUNTRY_CODE = "55"

    /**
     * Devolve o numero em E.164 sem "+", ou `null` quando nao da para
     * determinar com seguranca.
     *
     * **O prefixo internacional e informacao, nao ruido.** Filtrar so os
     * digitos — como o app v2.9 faz — descarta o unico sinal confiavel de que
     * o numero ja esta completo. "+1 415 555 2671" e "11 99999-9999" ambos
     * viram 11 digitos, e sem o "+" nao ha como distinguir um numero
     * americano de um celular paulista. Por isso o "+" (ou "00") e detectado
     * **antes** da filtragem.
     *
     * Com prefixo internacional (`+` ou `00`): os digitos ja formam o E.164.
     *
     * Sem prefixo, assume-se Brasil, que e o publico do produto:
     *  - ja comeca com 55 e tem 12-13 digitos -> mantem;
     *  - 10 ou 11 digitos (DDD + numero)      -> prefixa o DDI;
     *  - 8 ou 9 digitos (sem DDD)             -> `null`, porque chutar o DDD
     *    levaria a ligar para a pessoa errada;
     *  - qualquer outro tamanho               -> `null`.
     */
    fun normalize(phone: String, countryCode: String = DEFAULT_COUNTRY_CODE): String? {
        val trimmed = phone.trim()
        val isInternational = trimmed.startsWith("+") || trimmed.startsWith("00")
        val digits = trimmed.filter(Char::isDigit)
        if (digits.isEmpty()) return null

        if (isInternational) {
            // "00" e o prefixo de discagem internacional, nao parte do numero.
            val e164 = if (trimmed.startsWith("00")) digits.removePrefix("00") else digits
            return e164.takeIf { it.length in MIN_E164_DIGITS..MAX_E164_DIGITS }
        }

        return when {
            digits.startsWith(countryCode) && digits.length in 12..13 -> digits
            digits.length in 10..11 -> countryCode + digits
            else -> null
        }
    }

    private const val MIN_E164_DIGITS = 8
    private const val MAX_E164_DIGITS = 15
}
