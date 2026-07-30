package com.portaretrato.app.call

/**
 * Tipos de mensagem trocados no canal de sinalização.
 *
 * Sinalização é só isto: um canal confiável para os dois lados combinarem como
 * vão se conectar. Depois que a `PeerConnection` sobe, áudio e vídeo vão
 * direto de um aparelho para o outro (ou via TURN) — não passam pelo Firestore.
 */
sealed interface SignalMessage {

    val callId: String

    /** SDP de quem liga. */
    data class Offer(
        override val callId: String,
        val sdp: String,
        val fromUid: String,
        val fromName: String,
        val fromPhone: String?,
        val video: Boolean,
        val createdAt: Long,
    ) : SignalMessage

    /** SDP de quem atende. */
    data class Answer(
        override val callId: String,
        val sdp: String,
    ) : SignalMessage

    /** Candidato ICE. Vários por chamada, dos dois lados. */
    data class Ice(
        override val callId: String,
        val sdpMid: String,
        val sdpMLineIndex: Int,
        val candidate: String,
        val fromUid: String,
    ) : SignalMessage

    /** Fim da chamada, com o motivo. */
    data class Hangup(
        override val callId: String,
        val reason: CallEndReason,
    ) : SignalMessage
}

/**
 * Serialização das mensagens de sinalização para mapas — o formato que o
 * Firestore consome direto.
 *
 * Está separado de [FirestoreSignaling] de propósito: é lógica pura, testável
 * na JVM, e permite trocar o transporte (Realtime Database, WebSocket próprio)
 * sem tocar no protocolo.
 *
 * ## Esquema no Firestore
 *
 * ```
 * calls/{callId}                        <- documento da chamada (offer + answer + estado)
 * calls/{callId}/candidates/{autoId}    <- subcoleção de candidatos ICE
 * ```
 *
 * Os candidatos ficam numa subcoleção porque chegam a dezenas por chamada e em
 * ordem imprevisível; escrever cada um como documento próprio evita corrida de
 * escrita no mesmo documento.
 */
object SignalingProtocol {

    const val FIELD_TYPE = "type"
    const val FIELD_CALL_ID = "callId"
    const val FIELD_SDP = "sdp"
    const val FIELD_FROM_UID = "fromUid"
    const val FIELD_FROM_NAME = "fromName"
    const val FIELD_FROM_PHONE = "fromPhone"
    const val FIELD_TO_UID = "toUid"
    const val FIELD_VIDEO = "video"
    const val FIELD_CREATED_AT = "createdAt"
    const val FIELD_REASON = "reason"
    const val FIELD_SDP_MID = "sdpMid"
    const val FIELD_SDP_MLINE_INDEX = "sdpMLineIndex"
    const val FIELD_CANDIDATE = "candidate"

    const val TYPE_OFFER = "offer"
    const val TYPE_ANSWER = "answer"
    const val TYPE_ICE = "ice"
    const val TYPE_HANGUP = "hangup"

    fun encode(message: SignalMessage): Map<String, Any?> = when (message) {
        is SignalMessage.Offer -> mapOf(
            FIELD_TYPE to TYPE_OFFER,
            FIELD_CALL_ID to message.callId,
            FIELD_SDP to message.sdp,
            FIELD_FROM_UID to message.fromUid,
            FIELD_FROM_NAME to message.fromName,
            FIELD_FROM_PHONE to message.fromPhone,
            FIELD_VIDEO to message.video,
            FIELD_CREATED_AT to message.createdAt,
        )

        is SignalMessage.Answer -> mapOf(
            FIELD_TYPE to TYPE_ANSWER,
            FIELD_CALL_ID to message.callId,
            FIELD_SDP to message.sdp,
        )

        is SignalMessage.Ice -> mapOf(
            FIELD_TYPE to TYPE_ICE,
            FIELD_CALL_ID to message.callId,
            FIELD_SDP_MID to message.sdpMid,
            FIELD_SDP_MLINE_INDEX to message.sdpMLineIndex.toLong(),
            FIELD_CANDIDATE to message.candidate,
            FIELD_FROM_UID to message.fromUid,
        )

        is SignalMessage.Hangup -> mapOf(
            FIELD_TYPE to TYPE_HANGUP,
            FIELD_CALL_ID to message.callId,
            FIELD_REASON to message.reason.name,
        )
    }

    /**
     * Decodifica um mapa vindo do Firestore ou do payload do FCM.
     *
     * Devolve `null` em qualquer inconsistência em vez de lançar exceção:
     * documento corrompido ou parcialmente escrito é normal em rede móvel, e
     * derrubar a chamada por causa disso seria pior que ignorar a mensagem.
     */
    fun decode(data: Map<String, Any?>?): SignalMessage? {
        if (data == null) return null
        val callId = data[FIELD_CALL_ID] as? String ?: return null

        return when (data[FIELD_TYPE] as? String) {
            TYPE_OFFER -> {
                val sdp = data[FIELD_SDP] as? String ?: return null
                val fromUid = data[FIELD_FROM_UID] as? String ?: return null
                SignalMessage.Offer(
                    callId = callId,
                    sdp = sdp,
                    fromUid = fromUid,
                    fromName = data[FIELD_FROM_NAME] as? String ?: "",
                    fromPhone = data[FIELD_FROM_PHONE] as? String,
                    video = data[FIELD_VIDEO] as? Boolean ?: true,
                    createdAt = asLong(data[FIELD_CREATED_AT]) ?: 0L,
                )
            }

            TYPE_ANSWER -> {
                val sdp = data[FIELD_SDP] as? String ?: return null
                SignalMessage.Answer(callId, sdp)
            }

            TYPE_ICE -> {
                val candidate = data[FIELD_CANDIDATE] as? String ?: return null
                val sdpMid = data[FIELD_SDP_MID] as? String ?: return null
                val index = asLong(data[FIELD_SDP_MLINE_INDEX])?.toInt() ?: return null
                SignalMessage.Ice(
                    callId = callId,
                    sdpMid = sdpMid,
                    sdpMLineIndex = index,
                    candidate = candidate,
                    fromUid = data[FIELD_FROM_UID] as? String ?: "",
                )
            }

            TYPE_HANGUP -> {
                val reason = (data[FIELD_REASON] as? String)
                    ?.let { name -> CallEndReason.entries.firstOrNull { it.name == name } }
                    ?: CallEndReason.REMOTE_HANGUP
                SignalMessage.Hangup(callId, reason)
            }

            else -> null
        }
    }

    /**
     * Números chegam como `Long` do Firestore, `Int` do código local e
     * `String` do payload do FCM (que é sempre texto). Normaliza os três.
     */
    private fun asLong(value: Any?): Long? = when (value) {
        is Long -> value
        is Int -> value.toLong()
        is Double -> value.toLong()
        is String -> value.toLongOrNull()
        else -> null
    }

    /** Converte a offer no convite que vai no push. */
    fun inviteFrom(offer: SignalMessage.Offer, toUid: String): CallInvite = CallInvite(
        callId = offer.callId,
        fromUid = offer.fromUid,
        fromName = offer.fromName,
        fromPhone = offer.fromPhone,
        toUid = toUid,
        createdAt = offer.createdAt,
        video = offer.video,
    )
}
