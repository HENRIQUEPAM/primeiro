package com.portaretrato.app.call

/** Formas de falar com alguém, da mais rica para a mais simples. */
enum class CallMethod {
    /** Vídeo P2P dentro do app. Exige Firebase configurado, pareamento e TURN. */
    APP_VIDEO,

    /** Videochamada pelo WhatsApp. Sai do app, mas não exige backend nenhum. */
    WHATSAPP_VIDEO,

    /** Conversa do WhatsApp. Funciona até sem o contato na agenda. */
    WHATSAPP_CHAT,

    /** Discador do telefone. Usa a operadora, não a internet. */
    PHONE_DIAL,
}

/** Por que uma forma de chamada não está disponível agora. */
enum class UnavailableReason {
    NO_PHONE_NUMBER,
    NO_PAIRING,
    PEER_OFFLINE,
    NOT_CONFIGURED,
    ALREADY_IN_CALL,
}

/** Uma opção de chamada apresentada ao usuário. */
data class CallOption(
    val method: CallMethod,
    val label: String,
    val available: Boolean,
    val unavailableReason: UnavailableReason? = null,
) {
    /** Mensagem curta, em português, para o botão desabilitado. */
    val explanation: String?
        get() = when (unavailableReason) {
            UnavailableReason.NO_PHONE_NUMBER -> "Sem telefone cadastrado"
            UnavailableReason.NO_PAIRING -> "Aparelho ainda não pareado"
            UnavailableReason.PEER_OFFLINE -> "Indisponível agora"
            UnavailableReason.NOT_CONFIGURED -> "Chamada pelo app ainda não configurada"
            UnavailableReason.ALREADY_IN_CALL -> "Você já está em uma chamada"
            null -> null
        }
}

/**
 * Decide o que aparece no cartão de um contato.
 *
 * ## Por que isto existe
 *
 * A chamada de vídeo dentro do app depende de três coisas fora do código:
 * projeto Firebase configurado, os dois aparelhos pareados, e um servidor TURN
 * contratado. Enquanto qualquer uma faltar, ela não funciona — e um botão que
 * não funciona é pior que um botão ausente, principalmente para uma pessoa
 * idosa, que vai concluir que "o aparelho está quebrado".
 *
 * O WhatsApp, por outro lado, funciona **hoje**, sem backend, sem custo e sem
 * configuração: a família já o tem instalado. Por isso ele não é um plano B
 * envergonhado — é o caminho principal enquanto o resto não estiver de pé, e
 * segue como reserva depois, exatamente como a Seção 7.4 da especificação
 * determina ("nunca substituindo o fallback").
 *
 * A ordem devolvida por [forContact] é a ordem em que os botões aparecem. Um
 * botão indisponível continua visível, **desabilitado e com o motivo escrito** —
 * esconder seria pior: o usuário lembraria que "ontem tinha um botão aqui".
 */
object CallOptions {

    /**
     * @param phone telefone do contato, se cadastrado.
     * @param appCallConfigured o app tem Firebase e identidade prontos.
     * @param pairedDeviceId identificador do aparelho pareado, se houver.
     * @param peerOnline presença do par, quando conhecida.
     * @param alreadyInCall já existe chamada em andamento.
     */
    fun forContact(
        phone: String?,
        appCallConfigured: Boolean,
        pairedDeviceId: String?,
        peerOnline: Boolean,
        alreadyInCall: Boolean,
    ): List<CallOption> {
        val normalizedPhone = phone?.let(PhoneNumbers::normalize)

        val appVideoReason = when {
            alreadyInCall -> UnavailableReason.ALREADY_IN_CALL
            !appCallConfigured -> UnavailableReason.NOT_CONFIGURED
            pairedDeviceId.isNullOrBlank() -> UnavailableReason.NO_PAIRING
            !peerOnline -> UnavailableReason.PEER_OFFLINE
            else -> null
        }

        val phoneReason = if (normalizedPhone == null) UnavailableReason.NO_PHONE_NUMBER else null

        return listOf(
            CallOption(
                method = CallMethod.APP_VIDEO,
                label = "Chamar pelo aparelho",
                available = appVideoReason == null,
                unavailableReason = appVideoReason,
            ),
            CallOption(
                method = CallMethod.WHATSAPP_VIDEO,
                label = "Vídeo no WhatsApp",
                available = phoneReason == null,
                unavailableReason = phoneReason,
            ),
            CallOption(
                method = CallMethod.WHATSAPP_CHAT,
                label = "Mensagem no WhatsApp",
                available = phoneReason == null,
                unavailableReason = phoneReason,
            ),
            CallOption(
                method = CallMethod.PHONE_DIAL,
                label = "Ligar por telefone",
                available = phoneReason == null,
                unavailableReason = phoneReason,
            ),
        )
    }

    /**
     * Melhor opção disponível, para o toque simples no cartão.
     *
     * O idoso não deveria precisar escolher entre quatro botões para falar com
     * a filha: um toque no rosto dela usa o melhor caminho que funciona agora.
     * Os quatro botões continuam existindo para quem quiser escolher.
     */
    fun best(options: List<CallOption>): CallOption? = options.firstOrNull { it.available }

    /** Há alguma forma de falar com este contato? */
    fun hasAnyOption(options: List<CallOption>): Boolean = options.any { it.available }
}
