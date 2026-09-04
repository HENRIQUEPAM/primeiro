package com.portaretrato.app.call

/** Contato autorizado a ser atendido automaticamente. */
data class TrustedContact(
    val uid: String,
    val name: String,
    /** Telefone em E.164, quando houver. */
    val phone: String?,
    val autoAnswerEnabled: Boolean,
) {
    /**
     * Tem um código de aparelho de verdade (não o `tel:` sintético que
     * [uid] recebe quando o contato só tem telefone) — e portanto pode ser
     * chamado pelo próprio app.
     *
     * Ver `HomeActivity.showAddContactDialog`: sem um código digitado no
     * campo "Código do aparelho", `uid` vira `"tel:$phone"`; com um código,
     * `uid` é exatamente o que foi digitado (o uid do Firebase Auth do outro
     * aparelho, mostrado como "Meu código" na tela dele).
     */
    val hasDeviceCode: Boolean get() = uid.isNotBlank() && !uid.startsWith(PHONE_UID_PREFIX)

    companion object {
        const val PHONE_UID_PREFIX = "tel:"
    }
}

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
    /**
     * Chave-mestra do recurso. Ver [FEATURE_ENABLED].
     *
     * É parâmetro, e não uma leitura direta da constante, para que a suíte
     * consiga exercitar a lógica de decisão com o recurso ligado sem que isso
     * mude o comportamento do app. O padrão é o que vale no aparelho.
     */
    private val featureEnabled: Boolean = FEATURE_ENABLED,
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

        // Chave-mestra: desligada, NENHUMA combinação de entrada atende sozinho.
        // Vem antes de olhar o contato de propósito — assim um `autoAnswerEnabled`
        // gravado em disco por uma versão futura, ou uma tela nova que chame
        // `TrustedContactsStore.setAutoAnswer`, continua sem efeito. O desligado
        // deixa de depender de "ninguém escreve true" e passa a ser um fato só.
        //
        // As checagens de convite duplicado e de chamada em andamento ficam
        // ACIMA desta linha por não terem a ver com o recurso: elas evitam duas
        // telas de chamada e a derrubada de uma conversa em curso, e valem
        // sempre.
        if (!featureEnabled) {
            return AutoAnswerDecision.Ring(displayName)
        }

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
         * **Valor de fábrica: desligado.** Abrir câmera e microfone da casa de
         * alguém sem nenhuma interação humana não é uma decisão que o código
         * deveria tomar sozinho por padrão.
         *
         * Isto NÃO é mais a chave-mestra única — desde que o dono do produto
         * pediu o recurso de volta ("babá eletrônica"), quem decide em tempo
         * de execução é [AutoAnswerSettingsStore], atrás da senha de
         * [com.portaretrato.app.admin.AdminAccess]
         * ([com.portaretrato.app.admin.ui.AdminActivity]). Este `const val`
         * agora serve só de dois jeitos:
         *
         *  1. o valor que [AutoAnswerSettingsStore.isEnabled] devolve **antes**
         *     de alguém jamais abrir "Recursos avançados" (nenhum aparelho
         *     novo nasce com isto ligado, mesmo sem querer);
         *  2. o padrão do parâmetro `featureEnabled` abaixo, para quem
         *     constrói um [AutoAnswerPolicy] sem passar nada — o caso de teste,
         *     e qualquer chamador futuro que esqueça de pensar nisso.
         *
         * A janela de horário (`quietHoursStart`/`quietHoursEnd`) continua nula
         * — sem restrição — porque ainda não tem tela para o dono escolher uma.
         * Enquanto isto for `false` e [AutoAnswerSettingsStore] nunca tiver
         * sido tocado, `decide` devolve `Ring` para qualquer entrada — inclusive
         * para um contato com `autoAnswerEnabled = true` gravado em disco. A
         * suíte varre todas as combinações para garantir.
         */
        const val FEATURE_ENABLED = false

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
