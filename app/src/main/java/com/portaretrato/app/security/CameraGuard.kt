package com.portaretrato.app.security

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Concessão de uso da câmera.
 *
 * Só o [CameraGuard] cria. Enquanto uma concessão estiver ativa, o aviso ao
 * usuário está visível. Fechar a concessão é obrigatório — use `use { }`.
 */
class CameraLease internal constructor(
    val id: String,
    val purpose: CameraPurpose,
    val grantedAt: Long,
    private val onRelease: (CameraLease) -> Unit,
) : AutoCloseable {

    @Volatile
    var isActive: Boolean = true
        private set

    override fun close() {
        if (!isActive) return
        isActive = false
        onRelease(this)
    }
}

/** O que a UI precisa saber sobre o estado da câmera. */
data class CameraStatus(
    val inUse: Boolean,
    val purpose: CameraPurpose?,
    val since: Long?,
) {
    val reason: String? get() = purpose?.userVisibleReason
}

/**
 * Único ponto de acesso à câmera do aplicativo.
 *
 * **Nenhuma outra classe abre a câmera.** `CameraX`, `Camera2` e o
 * `VideoCapturer` do WebRTC só são construídos com uma [CameraLease] em mãos.
 * Isso transforma "não acessar a câmera sem permissão" de uma promessa em uma
 * propriedade estrutural: não existe caminho no código que chegue à câmera sem
 * passar por aqui.
 *
 * ## As três garantias
 *
 * **1. Nunca sem permissão.** [acquire] chama `checkSelfPermission` no momento
 * do uso, não no início do app. A diferença importa: no Android 11+ a permissão
 * pode ser revogada com o app rodando, e um valor guardado em cache ficaria
 * mentindo. Se não houver permissão, não há concessão.
 *
 * **2. Nunca em silêncio.** Toda concessão liga um aviso persistente
 * ([CameraNotice]) antes de a câmera abrir, e só o desliga depois de fechada.
 * Some-se a isso o indicador de privacidade do próprio Android (o ponto verde,
 * API 31+), que o app **não tenta suprimir** — são duas camadas independentes,
 * uma delas fora do controle do aplicativo, que é justamente o ponto.
 *
 * **3. Nunca invisível.** Com o app em segundo plano e sem foreground service,
 * o acesso é negado pela política antes mesmo de o sistema opinar.
 *
 * Tudo fica registrado em [CameraAuditLog], que alimenta a tela onde o dono do
 * aparelho vê quando a câmera foi usada.
 */
class CameraGuard(
    private val context: Context,
    private val notice: CameraNotice,
    val auditLog: CameraAuditLog = CameraAuditLog(),
    /** Preferências do usuário por propósito. Default: tudo ligado. */
    private val isPurposeEnabled: (CameraPurpose) -> Boolean = { true },
    /** Visibilidade atual do app. Injetado para ser testável. */
    private val visibilityProvider: () -> AppVisibility,
) {

    private val lock = Any()
    private var activeLease: CameraLease? = null

    private val _status = MutableStateFlow(CameraStatus(inUse = false, purpose = null, since = null))

    /** Estado observável, para o banner permanente na tela. */
    val status: StateFlow<CameraStatus> = _status.asStateFlow()

    /**
     * Pede a câmera.
     *
     * @return a concessão, ou `null` se negada. O motivo da negação vai para o
     *   log de auditoria e pode ser lido com [lastDenial].
     */
    fun acquire(purpose: CameraPurpose): CameraLease? {
        val now = System.currentTimeMillis()
        auditLog.record(CameraAuditEntry(now, purpose, CameraAuditEntry.Event.REQUESTED))

        synchronized(lock) {
            val decision = CameraAccessPolicy.evaluate(
                purpose = purpose,
                // Consultado agora, nunca em cache: a permissão pode ter sido
                // revogada com o app rodando.
                permissionGranted = hasCameraPermission(),
                visibility = visibilityProvider(),
                currentHolder = activeLease?.purpose,
                userEnabled = isPurposeEnabled(purpose),
                hasCameraHardware = hasCameraHardware(),
            )

            return when (decision) {
                is CameraDecision.Deny -> {
                    lastDenial = decision.reason
                    auditLog.record(
                        CameraAuditEntry(now, purpose, CameraAuditEntry.Event.DENIED, decision.reason),
                    )
                    Log.w(TAG, "Câmera negada para $purpose: ${decision.reason}")
                    null
                }

                is CameraDecision.Allow -> {
                    lastDenial = null
                    // O aviso sobe ANTES de a câmera abrir. Se subir depois,
                    // existe uma janela — curta, mas real — em que a câmera
                    // está ativa sem nada na tela.
                    notice.show(purpose)

                    val lease = CameraLease(UUID.randomUUID().toString(), purpose, now, ::release)
                    activeLease = lease
                    _status.value = CameraStatus(inUse = true, purpose = purpose, since = now)
                    auditLog.record(CameraAuditEntry(now, purpose, CameraAuditEntry.Event.GRANTED))
                    Log.i(TAG, "Câmera concedida para $purpose")
                    lease
                }
            }
        }
    }

    /**
     * Toma a câmera de um consumidor de menor prioridade.
     *
     * O caso real é chamada chegando durante a varredura de rostos: a câmera é
     * uma só, e a chamada tem prioridade. A varredura é reenfileirada por quem
     * chama.
     *
     * @return a concessão nova, ou `null` se a preempção não se aplica.
     */
    fun preemptFor(purpose: CameraPurpose): CameraLease? {
        synchronized(lock) {
            val current = activeLease
            if (current != null && CameraAccessPolicy.shouldPreempt(current.purpose, purpose)) {
                auditLog.record(
                    CameraAuditEntry(
                        System.currentTimeMillis(),
                        current.purpose,
                        CameraAuditEntry.Event.PREEMPTED,
                    ),
                )
                Log.i(TAG, "Preempção: ${current.purpose} cede para $purpose")
                current.close()
            }
        }
        return acquire(purpose)
    }

    private fun release(lease: CameraLease) {
        synchronized(lock) {
            if (activeLease?.id != lease.id) return
            activeLease = null
            val now = System.currentTimeMillis()
            auditLog.record(
                CameraAuditEntry(
                    timestamp = now,
                    purpose = lease.purpose,
                    event = CameraAuditEntry.Event.RELEASED,
                    durationMs = now - lease.grantedAt,
                ),
            )
            _status.value = CameraStatus(inUse = false, purpose = null, since = null)
            // O aviso só cai depois de a câmera ter sido efetivamente liberada.
            notice.hide()
            Log.i(TAG, "Câmera liberada por ${lease.purpose}")
        }
    }

    /** Motivo da última negação, para a UI explicar ao usuário. */
    @Volatile
    var lastDenial: CameraDenialReason? = null
        private set

    fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

    fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasCameraHardware(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    private companion object {
        const val TAG = "CameraGuard"
    }
}

/**
 * Executa [block] com a câmera, garantindo a liberação mesmo em caso de
 * exceção. É a forma preferida de usar o guarda: fechar a concessão à mão é
 * fácil de esquecer, e uma concessão vazada mantém o aviso na tela para sempre.
 *
 * @return `null` se a câmera foi negada.
 */
inline fun <T> CameraGuard.withCamera(purpose: CameraPurpose, block: (CameraLease) -> T): T? =
    acquire(purpose)?.use(block)
