package com.portaretrato.app.call

/**
 * Schema canônico de pareamento e sinalização, conforme a Seção 9 da
 * *Documentação Técnica Consolidada v3.1*.
 *
 * O documento resolve explicitamente a contradição entre dois desenhos
 * anteriores (`devices/pairingCodes/calls` do Backend vs.
 * `devices/pairingRequests/pairings/...` do Banco de Dados) adotando **o
 * segundo como canônico**, por incluir lockout por tentativas, TTL de 2 min,
 * gate bloqueante de confirmação de fingerprint dos dois lados, e
 * denormalização de `ownerUid` em `callSessions` para permitir regras O(1) sem
 * `get()` cross-documento no caminho quente.
 *
 * ```
 * /devices/{deviceId}                                   identidade do aparelho
 * /devices/{deviceId}/private/fcm                       token FCM (nenhum cliente lê)
 * /pairingRequests/{code}                               código de pareamento, TTL 2 min
 * /pairings/{pairingId}                                 par confirmado
 * /pairings/{pairingId}/callSessions/{sessionId}        sinalização efêmera
 * ```
 *
 * Nada disso entra no Room: a Seção 9 é explícita em manter `pairingRequests` e
 * `callSessions` fora do banco local por princípio, não por decisão pontual —
 * é a primeira introdução de dado hierárquico no projeto. O único dado de
 * chamada persistido é o log ([CallSessionLog]).
 */
object PairingProtocol {

    // ------------------------------------------------------------ coleções

    const val COLLECTION_DEVICES = "devices"
    const val COLLECTION_PAIRING_REQUESTS = "pairingRequests"
    const val COLLECTION_PAIRINGS = "pairings"
    const val SUBCOLLECTION_CALL_SESSIONS = "callSessions"
    const val SUBCOLLECTION_PRIVATE = "private"
    const val DOC_FCM = "fcm"

    // -------------------------------------------------------------- devices

    const val FIELD_OWNER_UID = "ownerUid"

    /**
     * Chave pública ECDSA P-256, em X.509/SPKI Base64.
     *
     * A Seção 9 substitui os campos `publicKeyX25519`/`publicKeyEd25519` do
     * desenho antigo por **um único `publicKeyP256`**: a CDD do Android garante
     * P-256 nativo no StrongBox, enquanto X25519/Ed25519 exigiriam geração em
     * software mais key-wrapping.
     */
    const val FIELD_PUBLIC_KEY_P256 = "publicKeyP256"

    /** Só a Cloud Function `verifyDeviceIntegrity` escreve `true` aqui. */
    const val FIELD_INTEGRITY_VERIFIED = "integrityVerified"

    const val FIELD_MODEL = "model"
    const val FIELD_CREATED_AT = "createdAt"

    // ------------------------------------------------------ pairingRequests

    const val FIELD_CODE = "code"
    const val FIELD_REQUESTER_DEVICE_ID = "requesterDeviceId"
    const val FIELD_ATTEMPT_COUNT = "attemptCount"
    const val FIELD_MAX_ATTEMPTS = "maxAttempts"
    const val FIELD_LOCKED = "locked"
    const val FIELD_EXPIRE_AT = "expireAt"

    /** TTL curto do documento: 2 min, conforme a Seção 9. */
    const val PAIRING_REQUEST_TTL_MS = 2 * 60 * 1000L

    /** Lockout depois de 5 tentativas erradas. */
    const val PAIRING_MAX_ATTEMPTS = 5

    // ------------------------------------------------------------- pairings

    const val FIELD_DEVICE_A = "deviceA"
    const val FIELD_DEVICE_B = "deviceB"
    const val FIELD_DEVICE_A_FINGERPRINT_CONFIRMED = "deviceAFingerprintConfirmed"
    const val FIELD_DEVICE_B_FINGERPRINT_CONFIRMED = "deviceBFingerprintConfirmed"
    const val FIELD_STATUS = "status"

    const val PAIRING_PENDING = "PENDING"
    const val PAIRING_ACTIVE = "ACTIVE"

    // --------------------------------------------------------- callSessions

    const val FIELD_CALLER_DEVICE_ID = "callerDeviceId"
    const val FIELD_CALLEE_DEVICE_ID = "calleeDeviceId"
    const val FIELD_OFFER_SDP = "offerSdp"
    const val FIELD_OFFER_SIGNATURE = "offerSignature"
    const val FIELD_ANSWER_SDP = "answerSdp"
    const val FIELD_ANSWER_SIGNATURE = "answerSignature"
    const val FIELD_STATE = "state"
    const val FIELD_END_REASON = "endReason"
    const val FIELD_ANSWERED_AT = "answeredAt"
    const val FIELD_ENDED_AT = "endedAt"
    const val FIELD_VIDEO = "video"

    const val SESSION_RINGING = "RINGING"
    const val SESSION_ANSWERED = "ANSWERED"
    const val SESSION_ENDED = "ENDED"
    const val SESSION_MISSED = "MISSED"

    /**
     * Janela de frescor validada no lado do callee antes de tocar a campainha.
     *
     * Segunda camada da proteção anti "chamada fantasma" da Seção 7.4: protege
     * contra o app ser acordado com atraso por restrição agressiva de bateria
     * (a mesma classe de problema que o `FaceScanWorker` já enfrenta no MIUI) e
     * então tocar por uma chamada que já acabou.
     *
     * Compara-se **sempre** contra `FieldValue.serverTimestamp()`, nunca contra
     * o relógio local — dois aparelhos com relógios diferentes produziriam
     * decisões inconsistentes.
     */
    const val CALL_FRESHNESS_WINDOW_MS = 20_000L

    /** Cloud Scheduler marca `MISSED` após isto; o cliente espelha um pouco antes. */
    const val RING_TIMEOUT_MS = 45_000L
    const val CLIENT_RING_TIMEOUT_MS = 41_000L

    // ------------------------------------------------------------ presença

    /** Presença fica no Realtime Database, com `onDisconnect()` nativo. */
    const val RTDB_PRESENCE_PATH = "presence"
    const val FIELD_ONLINE = "online"
    const val FIELD_LAST_SEEN = "lastSeen"

    /**
     * Escrito pelo app a partir do sinal do firmware/MCU (Seção 7.5), lido pelo
     * par remoto para exibir "energia de backup do outro lado".
     */
    const val FIELD_POWER_MODE = "powerMode"
    const val POWER_MODE_AC = "ac"
    const val POWER_MODE_BATTERY_BACKUP = "battery_backup"

    // -------------------------------------------------------------- helpers

    /**
     * Caminho da subcoleção de sinalização de um par.
     *
     * Subcoleção por par (e não uma coleção global de chamadas) particiona a
     * carga naturalmente e mantém baixo o risco de contenção com o sync de
     * fotos e pessoas — ponto levantado na Seção 7.8.
     */
    fun callSessionsPath(pairingId: String): String =
        "$COLLECTION_PAIRINGS/$pairingId/$SUBCOLLECTION_CALL_SESSIONS"

    /**
     * Monta o documento da offer.
     *
     * `ownerUid` vai denormalizado de propósito: permite que a regra de
     * segurança decida em O(1), sem `get()` cross-documento no caminho quente
     * da sinalização (Seção 9).
     */
    fun offerDocument(
        callerDeviceId: String,
        calleeDeviceId: String,
        ownerUid: String,
        sdp: String,
        signatureBase64: String,
        video: Boolean,
    ): Map<String, Any?> = mapOf(
        FIELD_CALLER_DEVICE_ID to callerDeviceId,
        FIELD_CALLEE_DEVICE_ID to calleeDeviceId,
        FIELD_OWNER_UID to ownerUid,
        FIELD_OFFER_SDP to sdp,
        FIELD_OFFER_SIGNATURE to signatureBase64,
        FIELD_VIDEO to video,
        FIELD_STATE to SESSION_RINGING,
        // createdAt NÃO entra aqui: quem chama grava
        // FieldValue.serverTimestamp() no lugar. Ver CALL_FRESHNESS_WINDOW_MS.
    )

    fun answerDocument(sdp: String, signatureBase64: String): Map<String, Any?> = mapOf(
        FIELD_ANSWER_SDP to sdp,
        FIELD_ANSWER_SIGNATURE to signatureBase64,
        FIELD_STATE to SESSION_ANSWERED,
    )

    fun endDocument(reason: CallEndReason): Map<String, Any?> = mapOf(
        FIELD_STATE to SESSION_ENDED,
        FIELD_END_REASON to reason.name,
    )

    /**
     * Decide se um convite ainda deve tocar.
     *
     * @param createdAtServerMs `createdAt` do documento, resolvido pelo servidor.
     * @param nowServerMs referência de tempo do servidor.
     */
    fun isFresh(createdAtServerMs: Long?, nowServerMs: Long): Boolean {
        if (createdAtServerMs == null || createdAtServerMs <= 0L) return false
        val age = nowServerMs - createdAtServerMs
        // Idade negativa = relógio do servidor à frente do valor lido; aceita
        // uma folga pequena em vez de descartar a chamada.
        return age >= -CLOCK_SKEW_TOLERANCE_MS && age <= CALL_FRESHNESS_WINDOW_MS
    }

    private const val CLOCK_SKEW_TOLERANCE_MS = 2_000L
}

/**
 * Único dado de chamada persistido localmente (Seção 7.1).
 *
 * Retenção sugerida de 90 dias, implementada como **parâmetro configurável** e
 * não valor fixo, porque a Seção 9 marca o prazo como pendente de confirmação
 * jurídica (LGPD).
 */
data class CallSessionLog(
    val id: String,
    val pairingId: String,
    val personId: String?,
    val direction: Direction,
    val startedAt: Long,
    val durationSec: Int,
    val result: Result,
) {
    enum class Direction { INCOMING, OUTGOING }

    enum class Result { COMPLETED, MISSED, FALLBACK_WHATSAPP, FAILED }

    companion object {
        /** Padrão sugerido; sobrescreva quando o jurídico fechar o prazo. */
        const val DEFAULT_RETENTION_DAYS = 90
    }
}
