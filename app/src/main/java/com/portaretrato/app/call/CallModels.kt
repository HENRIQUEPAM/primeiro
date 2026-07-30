package com.portaretrato.app.call

/** Quem iniciou a chamada nesta ponta. */
enum class CallRole {
    /** Este aparelho ligou: cria a offer. */
    CALLER,

    /** Este aparelho recebeu: responde com answer. */
    CALLEE,
}

/**
 * Estados de uma chamada. A ordem importa: o [CallStateMachine] só aceita
 * transições declaradas, o que impede a classe de bugs mais comum em WebRTC —
 * aplicar uma answer duas vezes, adicionar ICE antes da descrição remota, ou
 * tentar reconectar uma chamada que o outro lado já encerrou.
 */
enum class CallState {
    /** Nada acontecendo. */
    IDLE,

    /** Chamando: offer publicada, aguardando answer. */
    DIALING,

    /** Tocando: offer recebida, aguardando aceite local. */
    RINGING,

    /** Answer trocada, negociando ICE. */
    CONNECTING,

    /** Mídia fluindo. */
    ACTIVE,

    /** Perdeu conectividade, tentando restabelecer sem derrubar a chamada. */
    RECONNECTING,

    /** Terminada. Estado final. */
    ENDED,
}

/** Por que a chamada terminou. Vai para telemetria e para a mensagem na tela. */
enum class CallEndReason {
    LOCAL_HANGUP,
    REMOTE_HANGUP,
    REJECTED,
    NO_ANSWER,
    BUSY,
    CONNECTION_FAILED,
    PERMISSION_DENIED,
    ERROR,
}

/**
 * Convite de chamada. É o que trafega no push (FCM) e o que identifica o
 * documento de sinalização no Firestore.
 */
data class CallInvite(
    val callId: String,
    val fromUid: String,
    val fromName: String,
    /** Telefone normalizado (E.164) de quem liga, quando conhecido. */
    val fromPhone: String?,
    val toUid: String,
    val createdAt: Long,
    val video: Boolean,
)

/**
 * Servidor ICE. STUN é gratuito e resolve a maioria dos casos; TURN é o relay
 * pago, necessário quando os dois lados estão atrás de NAT simétrico.
 *
 * Não embuta credenciais de TURN de longa duração no APK: qualquer um que
 * descompacte o app passa a usar (e pagar) o seu relay. Use credenciais
 * efêmeras — ver `docs/CHAMADAS.md`.
 */
data class IceServerConfig(
    val urls: List<String>,
    val username: String? = null,
    val credential: String? = null,
)

/** Configuração de rede da chamada. */
data class CallConfig(
    val iceServers: List<IceServerConfig>,
    /** Quanto tempo o telefone toca antes de desistir. */
    val ringTimeoutMs: Long = 45_000,
    /** Quanto tempo tentar reconectar antes de encerrar. */
    val reconnectTimeoutMs: Long = 20_000,
) {
    companion object {
        /**
         * Só STUN. Funciona para a maioria das redes domésticas, mas falha em
         * NAT simétrico (comum em operadoras móveis e em Wi-Fi corporativo).
         * Serve para desenvolvimento; em produção acrescente TURN.
         */
        fun stunOnly(): CallConfig = CallConfig(
            iceServers = listOf(
                IceServerConfig(
                    listOf(
                        "stun:stun.l.google.com:19302",
                        "stun:stun1.l.google.com:19302",
                    ),
                ),
            ),
        )
    }
}

/** Erro de chamada com causa legível para a tela. */
data class CallError(
    val reason: CallEndReason,
    val message: String,
    val cause: Throwable? = null,
)
