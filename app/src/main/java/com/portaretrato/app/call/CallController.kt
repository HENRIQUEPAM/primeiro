package com.portaretrato.app.call

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

/** Tudo que a UI precisa saber sobre a chamada corrente. */
data class CallUiState(
    val state: CallState = CallState.IDLE,
    val peerName: String = "",
    val video: Boolean = true,
    val microphoneEnabled: Boolean = true,
    val cameraEnabled: Boolean = true,
    val endReason: CallEndReason? = null,
    val errorMessage: String? = null,
    /** Segundos restantes do atendimento automático; `null` quando não se aplica. */
    val autoAnswerCountdown: Int? = null,
)

/**
 * Junta máquina de estados, sinalização e WebRTC numa única fachada.
 *
 * Vive dentro do [CallService], não da Activity — se a chamada morar na
 * Activity, ela cai ao girar a tela ou ao o Android recriar a janela, que é o
 * bug clássico de app de chamada.
 *
 * Todos os callbacks chegam em threads diferentes (Firestore, WebRTC, timers),
 * então tudo é serializado no [scope] de thread única antes de tocar em estado.
 */
class CallController(
    private val context: Context,
    private val localUid: String,
    private val localName: String,
    private val config: CallConfig,
    firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val signaling = FirestoreSignaling(firestore, localUid)

    private var engine: WebRtcEngine? = null
    private var machine: CallStateMachine? = null
    private var callId: String? = null
    private var peerUid: String? = null
    private var ringTimeoutJob: Job? = null
    private var reconnectTimeoutJob: Job? = null
    private var autoAnswerJob: Job? = null
    private var isCaller = false

    private val _uiState = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _uiState.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    val localVideoTrack: VideoTrack? get() = engine?.localVideoTrack
    val eglBase: EglBase? get() = engine?.eglBase

    // ------------------------------------------------------------- iniciar

    /** Liga para [toUid]. */
    fun dial(toUid: String, toName: String, video: Boolean = true) {
        if (machine != null) {
            Log.w(TAG, "Já existe uma chamada; ignorando nova discagem.")
            return
        }
        val id = UUID.randomUUID().toString()
        callId = id
        peerUid = toUid
        isCaller = true
        machine = CallStateMachine(CallRole.CALLER, ::onTransition)
        _uiState.value = CallUiState(peerName = toName, video = video)

        startEngine(video)
        machine?.handle(CallEvent.LocalDial)

        engine?.createOffer { sdp ->
            signaling.sendOffer(
                SignalMessage.Offer(
                    callId = id,
                    sdp = sdp,
                    fromUid = localUid,
                    fromName = localName,
                    fromPhone = null,
                    video = video,
                    createdAt = System.currentTimeMillis(),
                ),
                toUid = toUid,
            )
        }

        observeSignaling(id)
        startRingTimeout()
    }

    /**
     * Recebe um convite. Não conecta ainda: espera [accept], que pode vir do
     * usuário ou do atendimento automático.
     */
    fun receive(invite: CallInvite, offerSdp: String, displayName: String, autoAnswerDelayMs: Long?) {
        if (machine != null) {
            // Já em chamada: recusa sem derrubar a que está em andamento.
            signaling.sendHangup(invite.callId, CallEndReason.BUSY)
            return
        }
        callId = invite.callId
        peerUid = invite.fromUid
        isCaller = false
        machine = CallStateMachine(CallRole.CALLEE, ::onTransition)
        pendingOfferSdp = offerSdp
        _uiState.value = CallUiState(peerName = displayName, video = invite.video)

        machine?.handle(CallEvent.RemoteInvite(invite))
        observeSignaling(invite.callId)
        startRingTimeout()

        if (autoAnswerDelayMs != null) scheduleAutoAnswer(autoAnswerDelayMs)
    }

    private var pendingOfferSdp: String? = null

    /**
     * Contagem regressiva visível antes de atender sozinho. O usuário vê quem
     * é e tem chance de recusar — atender sem aviso seria abrir a câmera da
     * casa de alguém sem nenhum sinal.
     */
    private fun scheduleAutoAnswer(delayMs: Long) {
        autoAnswerJob = scope.launch {
            var remaining = (delayMs / 1000).toInt().coerceAtLeast(1)
            while (remaining > 0) {
                _uiState.value = _uiState.value.copy(autoAnswerCountdown = remaining)
                delay(1_000)
                remaining--
            }
            _uiState.value = _uiState.value.copy(autoAnswerCountdown = null)
            accept()
        }
    }

    /** Aceita a chamada recebida. */
    fun accept() {
        val id = callId ?: return
        val offerSdp = pendingOfferSdp ?: return
        val current = machine ?: return
        if (!current.handle(CallEvent.LocalAccept)) return

        autoAnswerJob?.cancel()
        cancelRingTimeout()
        pendingOfferSdp = null

        startEngine(_uiState.value.video)
        engine?.setRemoteDescription(SessionDescription.Type.OFFER, offerSdp)
        engine?.createAnswer { sdp -> signaling.sendAnswer(SignalMessage.Answer(id, sdp)) }
    }

    /** Recusa (antes de atender) ou desliga (durante). */
    fun hangup(reason: CallEndReason = CallEndReason.LOCAL_HANGUP) {
        val id = callId
        if (id != null) signaling.sendHangup(id, reason)
        finish(reason)
    }

    // ------------------------------------------------------------- controles

    fun toggleMicrophone() {
        val enabled = !_uiState.value.microphoneEnabled
        engine?.setMicrophoneEnabled(enabled)
        _uiState.value = _uiState.value.copy(microphoneEnabled = enabled)
    }

    fun toggleCamera() {
        val enabled = !_uiState.value.cameraEnabled
        engine?.setCameraEnabled(enabled)
        _uiState.value = _uiState.value.copy(cameraEnabled = enabled)
    }

    fun switchCamera() = engine?.switchCamera()

    // -------------------------------------------------------------- internos

    private fun startEngine(video: Boolean) {
        val created = WebRtcEngine(context, config, EngineListener())
        engine = created
        created.start(video)
    }

    private fun observeSignaling(id: String) {
        signaling.observe(
            callId = id,
            onAnswer = { sdp ->
                scope.launch {
                    cancelRingTimeout()
                    machine?.handle(CallEvent.Negotiating)
                    engine?.setRemoteDescription(SessionDescription.Type.ANSWER, sdp)
                }
            },
            onRemoteIce = { ice ->
                scope.launch {
                    engine?.addRemoteIceCandidate(
                        IceCandidate(ice.sdpMid, ice.sdpMLineIndex, ice.candidate),
                    )
                }
            },
            onEnded = { reason -> scope.launch { finish(reason) } },
        )
    }

    private fun startRingTimeout() {
        ringTimeoutJob = scope.launch {
            delay(config.ringTimeoutMs)
            hangup(CallEndReason.NO_ANSWER)
        }
    }

    private fun cancelRingTimeout() {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = null
    }

    private fun onTransition(from: CallState, to: CallState) {
        Log.d(TAG, "Chamada: $from -> $to")
        _uiState.value = _uiState.value.copy(state = to)

        // Perdeu a conexão: dá um prazo para voltar antes de encerrar. Trocar
        // de Wi-Fi para 4G no meio da chamada cai aqui e costuma se recuperar.
        if (to == CallState.RECONNECTING) {
            reconnectTimeoutJob = scope.launch {
                delay(config.reconnectTimeoutMs)
                hangup(CallEndReason.CONNECTION_FAILED)
            }
        } else {
            reconnectTimeoutJob?.cancel()
            reconnectTimeoutJob = null
        }
    }

    private fun finish(reason: CallEndReason) {
        val current = machine ?: return
        if (current.isFinished) return
        current.handle(CallEvent.Ended(reason))

        cancelRingTimeout()
        autoAnswerJob?.cancel()
        reconnectTimeoutJob?.cancel()
        signaling.stop()
        engine?.release()
        engine = null
        _remoteVideoTrack.value = null

        // Só quem ligou limpa, para os dois lados não apagarem ao mesmo tempo.
        val id = callId
        if (isCaller && id != null) signaling.cleanup(id)

        _uiState.value = _uiState.value.copy(state = CallState.ENDED, endReason = reason)
    }

    /** Libera tudo. Chame no `onDestroy` do service. */
    fun release() {
        finish(_uiState.value.endReason ?: CallEndReason.LOCAL_HANGUP)
        machine = null
        callId = null
        peerUid = null
        scope.cancel()
    }

    private inner class EngineListener : WebRtcEngine.Listener {

        override fun onLocalIceCandidate(candidate: IceCandidate) {
            val id = callId ?: return
            signaling.sendIceCandidate(
                SignalMessage.Ice(
                    callId = id,
                    sdpMid = candidate.sdpMid ?: return,
                    sdpMLineIndex = candidate.sdpMLineIndex,
                    candidate = candidate.sdp,
                    fromUid = localUid,
                ),
            )
        }

        override fun onRemoteVideoTrack(track: VideoTrack) {
            scope.launch { _remoteVideoTrack.value = track }
        }

        override fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState) {
            scope.launch {
                when (state) {
                    PeerConnection.PeerConnectionState.CONNECTED ->
                        machine?.handle(CallEvent.MediaConnected)
                    PeerConnection.PeerConnectionState.DISCONNECTED ->
                        machine?.handle(CallEvent.MediaDisconnected)
                    PeerConnection.PeerConnectionState.FAILED ->
                        hangup(CallEndReason.CONNECTION_FAILED)
                    PeerConnection.PeerConnectionState.CLOSED ->
                        finish(CallEndReason.REMOTE_HANGUP)
                    else -> Unit
                }
            }
        }

        override fun onError(error: CallError) {
            scope.launch {
                _uiState.value = _uiState.value.copy(errorMessage = error.message)
                hangup(error.reason)
            }
        }
    }

    private companion object {
        const val TAG = "CallController"
    }
}
