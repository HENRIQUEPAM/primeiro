package com.portaretrato.app.call

/** Contato autorizado a ser atendido automaticamente. */
data class TrustedContact(
    val uid: String,
    val name: String,
    /** Telefone em E.164, quando houver. */
    val phone: String?,
    val autoAnswerEnabled: Boolean,
)

/** O que fazer com um convite. */
sealed interface AutoAnswerDecision {
    /** Atende sozinho depois de [delayMs]. */
    data class Answer(val contactName: String, val delayMs: Long) : AutoAnswerDecision

    /** Toca e espera o usuário. */
    data class Ring(val displayName: String) : AutoAnswerDecision

    /** Recusa sem tocar. */
    data class Reject(val reason: CallEndReason) : AutoAnswerDecision
}

/**
 * Decide se o porta-retrato atende sozinho.
 *
 * **É isto que justifica construir chamada própria em vez de continuar
 * delegando ao WhatsApp.** O WhatsApp não permite atendimento automático — o
 * idoso precisa achar o aparelho, desbloquear e acertar o botão verde, que é
 * exatamente onde a coisa falha na prática. Aqui a filha liga, a foto sai da
 * tela e ela aparece. Nenhum toque.
 *
 * Como isso é abrir o microfone e a câmera da casa de alguém sem interação, as
 * regras são propositalmente restritivas:
 *
 *  - só contatos que o dono do aparelho marcou **explicitamente** como de
 *    confiança (`autoAnswerEnabled`), nunca por padrão;
 *  - nunca durante uma chamada em andamento;
 *  - opcionalmente só dentro de uma janela de horário, para não tocar de
 *    madrugada;
 *  - sempre com um atraso curto e visível, com aviso na tela e som, para que
 *    haja chance de recusar;
 *  - convite duplicado (retransmissão do FCM) nunca atende duas vezes.
 *
 * Lógica pura: roda em teste de unidade na JVM.
 */
class AutoAnswerPolicy(
    private val trustedContacts: () -> List<TrustedContact>,
    /** Atraso antes de atender. Dá tempo de ver quem é e recusar. */
    private val answerDelayMs: Long = DEFAULT_ANSWER_DELAY_MS,
    /** Hora inicial da janela permitida, 0..23. `null` desliga a restrição. */
    private val quietHoursStart: Int? = null,
    /** Hora final da janela permitida, 0..23. */
    private val quietHoursEnd: Int? = null,
) {

    private val handledCallIds = LinkedHashSet<String>()

    /**
     * @param invite convite recebido.
     * @param callInProgress se já existe chamada ativa.
     * @param hourOfDay hora local 0..23 no momento do convite.
     */
    fun decide(
        invite: CallInvite,
        callInProgress: Boolean,
        hourOfDay: Int,
    ): AutoAnswerDecision {
        // Convite repetido: o FCM reentrega, e atender duas vezes derrubaria a
        // chamada que já está de pé.
        if (!markHandled(invite.callId)) {
            return AutoAnswerDecision.Reject(CallEndReason.BUSY)
        }

        if (callInProgress) {
            return AutoAnswerDecision.Reject(CallEndReason.BUSY)
        }

        val contact = trustedContacts().firstOrNull { it.uid == invite.fromUid }

        // O nome exibido vem SEMPRE da agenda local, nunca de `invite.fromName`.
        // Esse campo é preenchido por quem liga e portanto é entrada não
        // confiável: um estranho poderia se anunciar como "Ana, sua filha" na
        // tela de um idoso. Contato não cadastrado aparece como "Desconhecido",
        // e ponto.
        val displayName = contact?.name ?: UNKNOWN_CALLER_LABEL

        // Desconhecido nunca atende sozinho — mas toca, para o usuário decidir.
        if (contact == null || !contact.autoAnswerEnabled) {
            return AutoAnswerDecision.Ring(displayName)
        }

        if (!withinAllowedHours(hourOfDay)) {
            return AutoAnswerDecision.Ring(displayName)
        }

        return AutoAnswerDecision.Answer(contact.name, answerDelayMs)
    }

    /** Esquece um convite, para permitir rediscagem do mesmo contato. */
    fun forget(callId: String) {
        handledCallIds.remove(callId)
    }

    /**
     * Registra o convite. Devolve `false` se já tinha sido visto.
     * O conjunto é limitado para não crescer sem fim num aparelho que fica
     * ligado por meses.
     */
    private fun markHandled(callId: String): Boolean {
        if (!handledCallIds.add(callId)) return false
        if (handledCallIds.size > MAX_REMEMBERED_CALLS) {
            val oldest = handledCallIds.first()
            handledCallIds.remove(oldest)
        }
        return true
    }

    private fun withinAllowedHours(hourOfDay: Int): Boolean {
        val start = quietHoursStart ?: return true
        val end = quietHoursEnd ?: return true
        // Janela que cruza a meia-noite (ex.: 22h às 7h) é tratada corretamente.
        return if (start <= end) hourOfDay in start..end else hourOfDay >= start || hourOfDay <= end
    }

    companion object {
        /**
         * 3 segundos: tempo suficiente para ler o nome na tela e recusar, sem
         * fazer quem ligou achar que ninguém vai atender.
         */
        const val DEFAULT_ANSWER_DELAY_MS = 3_000L

        const val MAX_REMEMBERED_CALLS = 64

        /** Rótulo para quem não está na agenda local. Ver [decide]. */
        const val UNKNOWN_CALLER_LABEL = "Desconhecido"
    }
}
