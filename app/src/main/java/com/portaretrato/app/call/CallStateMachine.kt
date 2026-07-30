package com.portaretrato.app.call

/**
 * Eventos que movem uma chamada. Deliberadamente separados do WebRTC: assim a
 * máquina de estados é Kotlin puro e roda em teste de unidade na JVM, sem
 * emulador.
 */
sealed interface CallEvent {
    /** Usuário local iniciou a chamada. */
    data object LocalDial : CallEvent

    /** Chegou um convite pelo push. */
    data class RemoteInvite(val invite: CallInvite) : CallEvent

    /** Usuário local (ou o atendimento automático) aceitou. */
    data object LocalAccept : CallEvent

    /** Answer trocada com sucesso; ICE em negociação. */
    data object Negotiating : CallEvent

    /** `PeerConnection` reportou CONNECTED. */
    data object MediaConnected : CallEvent

    /** `PeerConnection` reportou DISCONNECTED — pode voltar sozinho. */
    data object MediaDisconnected : CallEvent

    /** Fim, por qualquer motivo. */
    data class Ended(val reason: CallEndReason) : CallEvent
}

/**
 * Máquina de estados de uma chamada.
 *
 * O ponto não é burocracia: é que WebRTC falha de formas silenciosas quando
 * mensagens chegam fora de ordem. Um `answer` duplicado, um `hangup` que chega
 * antes do `answer`, um ICE candidate depois do encerramento — tudo isso
 * acontece de verdade em rede móvel. Transições explícitas transformam esses
 * casos em no-ops previsíveis em vez de exceções na `PeerConnection`.
 *
 * Não é thread-safe: chame sempre da mesma thread (a main, na prática).
 */
class CallStateMachine(
    val role: CallRole,
    private val onTransition: (from: CallState, to: CallState) -> Unit = { _, _ -> },
) {

    var state: CallState = CallState.IDLE
        private set

    var endReason: CallEndReason? = null
        private set

    val isFinished: Boolean get() = state == CallState.ENDED

    /**
     * Aplica [event]. Devolve `true` se houve transição, `false` se o evento
     * era inválido no estado atual — caso em que é simplesmente ignorado.
     */
    fun handle(event: CallEvent): Boolean {
        val next = nextState(event) ?: return false
        if (next == state) return false

        val previous = state
        state = next
        if (event is CallEvent.Ended) endReason = event.reason
        onTransition(previous, next)
        return true
    }

    private fun nextState(event: CallEvent): CallState? = when (event) {
        // Encerrar é sempre válido, exceto se já encerrou.
        is CallEvent.Ended -> if (state == CallState.ENDED) null else CallState.ENDED

        CallEvent.LocalDial ->
            if (state == CallState.IDLE && role == CallRole.CALLER) CallState.DIALING else null

        is CallEvent.RemoteInvite ->
            if (state == CallState.IDLE && role == CallRole.CALLEE) CallState.RINGING else null

        // Quem liga entra em CONNECTING ao receber a answer; quem recebe, ao aceitar.
        CallEvent.LocalAccept ->
            if (state == CallState.RINGING) CallState.CONNECTING else null

        CallEvent.Negotiating -> when (state) {
            CallState.DIALING, CallState.RINGING, CallState.CONNECTING -> CallState.CONNECTING
            else -> null
        }

        CallEvent.MediaConnected -> when (state) {
            // RECONNECTING -> ACTIVE é o caminho de recuperação de rede.
            CallState.CONNECTING, CallState.RECONNECTING, CallState.DIALING -> CallState.ACTIVE
            else -> null
        }

        CallEvent.MediaDisconnected ->
            if (state == CallState.ACTIVE) CallState.RECONNECTING else null
    }
}
