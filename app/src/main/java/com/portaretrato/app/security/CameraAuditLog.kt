package com.portaretrato.app.security

/** Um evento na trilha de auditoria da câmera. */
data class CameraAuditEntry(
    val timestamp: Long,
    val purpose: CameraPurpose,
    val event: Event,
    /** Preenchido apenas em [Event.DENIED]. */
    val denialReason: CameraDenialReason? = null,
    /** Duração em milissegundos, apenas em [Event.RELEASED]. */
    val durationMs: Long? = null,
) {
    enum class Event { REQUESTED, GRANTED, DENIED, RELEASED, PREEMPTED }
}

/**
 * Trilha de auditoria de todo acesso à câmera.
 *
 * Existe por uma razão de produto, não de depuração: o aparelho fica ligado na
 * sala da casa de alguém, com uma câmera apontada para a sala. A pessoa tem o
 * direito de abrir uma tela e ver **exatamente** quando a câmera foi usada, por
 * quanto tempo e para quê — sem depender de acreditar na palavra do fabricante.
 *
 * Fica em memória com um teto fixo. Deliberadamente **não** é persistida no
 * Firestore: um log de quando há gente em casa é, ele próprio, dado sensível, e
 * mandá-lo para a nuvem criaria um risco maior do que o que resolve. Quem
 * precisar de retenção mais longa deve gravar em Room, cifrado, e nunca
 * sincronizar.
 *
 * Thread-safe: acessado pelo guarda, pela UI e pelo worker.
 */
class CameraAuditLog(private val capacity: Int = DEFAULT_CAPACITY) {

    private val entries = ArrayDeque<CameraAuditEntry>(capacity)
    private val lock = Any()

    fun record(entry: CameraAuditEntry) {
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > capacity) entries.removeFirst()
        }
    }

    /** Histórico do mais recente para o mais antigo. */
    fun recent(limit: Int = capacity): List<CameraAuditEntry> = synchronized(lock) {
        entries.toList().asReversed().take(limit)
    }

    /** Uso total da câmera desde [since], em milissegundos. */
    fun totalUsageMs(since: Long): Long = synchronized(lock) {
        entries
            .filter { it.event == CameraAuditEntry.Event.RELEASED && it.timestamp >= since }
            .sumOf { it.durationMs ?: 0L }
    }

    /**
     * Negações recentes por falta de permissão.
     *
     * Se isto for maior que zero, algo no app está tentando usar a câmera sem
     * ter pedido — que é exatamente o defeito que este pacote existe para
     * tornar impossível. A tela de diagnóstico mostra este número.
     */
    fun permissionDenialCount(): Int = synchronized(lock) {
        entries.count {
            it.event == CameraAuditEntry.Event.DENIED &&
                it.denialReason == CameraDenialReason.PERMISSION_NOT_GRANTED
        }
    }

    fun clear() = synchronized(lock) { entries.clear() }

    private companion object {
        const val DEFAULT_CAPACITY = 200
    }
}
