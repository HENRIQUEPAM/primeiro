package com.portaretrato.app.call

import android.content.Context
import android.util.Log
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/**
 * Encapsula tudo que é WebRTC. Quem usa vê apenas offer/answer/ICE.
 *
 * ## Aviso sobre a API
 *
 * As assinaturas aqui seguem `io.getstream:stream-webrtc-android` (fork mantido
 * do `org.webrtc` do Google; o pacote continua sendo `org.webrtc`). A API do
 * WebRTC muda entre versões — em especial `DefaultVideoEncoderFactory`,
 * `addTrack` e a assinatura de `onTrack`. **Confira contra a versão que você
 * fixar no Gradle**; este arquivo não foi compilado contra a biblioteca real.
 *
 * ## Ordem que importa
 *
 * O erro clássico é adicionar candidatos ICE antes de ter a descrição remota.
 * Eles chegam pelo Firestore assim que o outro lado começa a gerar, o que é
 * quase sempre antes da answer. [addRemoteIceCandidate] enfileira nesse caso e
 * drena depois de [setRemoteDescription] — sem isso a chamada conecta às vezes
 * e falha às vezes, dependendo da latência da rede.
 */
class WebRtcEngine(
    private val context: Context,
    private val config: CallConfig,
    private val listener: Listener,
) {

    interface Listener {
        fun onLocalIceCandidate(candidate: IceCandidate)
        fun onRemoteVideoTrack(track: VideoTrack)
        fun onConnectionStateChanged(state: PeerConnection.PeerConnectionState)
        fun onError(error: CallError)
    }

    val eglBase: EglBase = EglBase.create()

    private var factory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null

    private var videoCapturer: CameraVideoCapturer? = null
    private var videoSource: VideoSource? = null
    private var audioSource: AudioSource? = null
    private var surfaceHelper: SurfaceTextureHelper? = null

    var localVideoTrack: VideoTrack? = null
        private set
    private var localAudioTrack: AudioTrack? = null

    /** Candidatos que chegaram antes da descrição remota. Ver nota da classe. */
    private val pendingRemoteCandidates = mutableListOf<IceCandidate>()
    private var hasRemoteDescription = false
    private var released = false

    // ------------------------------------------------------------------ setup

    /**
     * Inicializa fábrica, mídia local e `PeerConnection`.
     * Chame depois de já ter as permissões de câmera e microfone.
     */
    fun start(withVideo: Boolean) {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context)
                .createInitializationOptions(),
        )

        val encoder = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoder = DefaultVideoDecoderFactory(eglBase.eglBaseContext)
        val peerFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoder)
            .setVideoDecoderFactory(decoder)
            .createPeerConnectionFactory()
        factory = peerFactory

        val rtcConfig = PeerConnection.RTCConfiguration(config.iceServers.map(::toNativeIceServer)).apply {
            // Unified Plan é obrigatório: Plan B está removido nas versões atuais.
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            // Junta todo o tráfego numa porta só: menos candidatos, conexão mais rápida.
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            // Mantém a conexão viva quando muda de Wi-Fi para 4G.
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerFactory.createPeerConnection(rtcConfig, PeerObserver())
            ?: run {
                listener.onError(CallError(CallEndReason.ERROR, "Não foi possível criar a conexão."))
                return
            }

        createLocalTracks(peerFactory, withVideo)
    }

    private fun createLocalTracks(peerFactory: PeerConnectionFactory, withVideo: Boolean) {
        val connection = peerConnection ?: return

        audioSource = peerFactory.createAudioSource(MediaConstraints())
        localAudioTrack = peerFactory.createAudioTrack(AUDIO_TRACK_ID, audioSource)
            .also { connection.addTrack(it, listOf(STREAM_ID)) }

        if (!withVideo) return

        val capturer = createCameraCapturer()
        if (capturer == null) {
            // Sem câmera a chamada continua, só em áudio — melhor que falhar.
            Log.w(TAG, "Nenhuma câmera disponível; seguindo apenas com áudio.")
            return
        }
        videoCapturer = capturer

        val helper = SurfaceTextureHelper.create(CAPTURE_THREAD, eglBase.eglBaseContext)
        surfaceHelper = helper
        val source = peerFactory.createVideoSource(false)
        videoSource = source
        capturer.initialize(helper, context, source.capturerObserver)
        capturer.startCapture(CAPTURE_WIDTH, CAPTURE_HEIGHT, CAPTURE_FPS)

        localVideoTrack = peerFactory.createVideoTrack(VIDEO_TRACK_ID, source)
            .also { connection.addTrack(it, listOf(STREAM_ID)) }
    }

    /**
     * Prefere a câmera frontal — num porta-retrato é a única que faz sentido.
     */
    private fun createCameraCapturer(): CameraVideoCapturer? {
        val enumerator = Camera2Enumerator(context)
        val names = enumerator.deviceNames
        val front = names.firstOrNull { enumerator.isFrontFacing(it) }
        val chosen = front ?: names.firstOrNull() ?: return null
        return enumerator.createCapturer(chosen, null)
    }

    // -------------------------------------------------------------- negociação

    /** Cria a offer (lado de quem liga). */
    fun createOffer(onReady: (String) -> Unit) {
        val connection = peerConnection ?: return
        connection.createOffer(
            object : SimpleSdpObserver("createOffer") {
                override fun onCreateSuccess(description: SessionDescription) {
                    connection.setLocalDescription(SimpleSdpObserver("setLocalOffer"), description)
                    onReady(description.description)
                }
            },
            MediaConstraints(),
        )
    }

    /** Cria a answer (lado de quem atende). Chame após [setRemoteDescription]. */
    fun createAnswer(onReady: (String) -> Unit) {
        val connection = peerConnection ?: return
        connection.createAnswer(
            object : SimpleSdpObserver("createAnswer") {
                override fun onCreateSuccess(description: SessionDescription) {
                    connection.setLocalDescription(SimpleSdpObserver("setLocalAnswer"), description)
                    onReady(description.description)
                }
            },
            MediaConstraints(),
        )
    }

    /** Aplica o SDP remoto e drena os candidatos que chegaram antes. */
    fun setRemoteDescription(type: SessionDescription.Type, sdp: String) {
        val connection = peerConnection ?: return
        if (hasRemoteDescription) {
            // Reentrega do Firestore: aplicar duas vezes quebra a PeerConnection.
            Log.w(TAG, "Descrição remota duplicada ignorada.")
            return
        }
        connection.setRemoteDescription(
            object : SimpleSdpObserver("setRemoteDescription") {
                override fun onSetSuccess() {
                    hasRemoteDescription = true
                    drainPendingCandidates()
                }
            },
            SessionDescription(type, sdp),
        )
    }

    /** Adiciona candidato remoto, enfileirando se a descrição ainda não chegou. */
    fun addRemoteIceCandidate(candidate: IceCandidate) {
        if (!hasRemoteDescription) {
            pendingRemoteCandidates += candidate
            return
        }
        peerConnection?.addIceCandidate(candidate)
    }

    private fun drainPendingCandidates() {
        val connection = peerConnection ?: return
        for (candidate in pendingRemoteCandidates) connection.addIceCandidate(candidate)
        Log.d(TAG, "Drenados ${pendingRemoteCandidates.size} candidatos enfileirados.")
        pendingRemoteCandidates.clear()
    }

    // ------------------------------------------------------------------ mídia

    fun setMicrophoneEnabled(enabled: Boolean) { localAudioTrack?.setEnabled(enabled) }

    fun setCameraEnabled(enabled: Boolean) { localVideoTrack?.setEnabled(enabled) }

    fun switchCamera() { videoCapturer?.switchCamera(null) }

    // ---------------------------------------------------------------- teardown

    /**
     * Libera tudo. Ordem importa: parar a captura antes de dispensar a fonte,
     * e fechar a `PeerConnection` antes da fábrica. Fora dessa ordem, o
     * WebRTC trava em nativo ou vaza a câmera — que é o bug que deixa o LED
     * aceso depois da chamada.
     */
    fun release() {
        if (released) return
        released = true

        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        runCatching { surfaceHelper?.dispose() }
        runCatching { videoSource?.dispose() }
        runCatching { audioSource?.dispose() }
        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        runCatching { factory?.dispose() }
        runCatching { eglBase.release() }

        videoCapturer = null
        surfaceHelper = null
        videoSource = null
        audioSource = null
        localVideoTrack = null
        localAudioTrack = null
        peerConnection = null
        factory = null
        pendingRemoteCandidates.clear()
    }

    // --------------------------------------------------------------- internos

    private fun toNativeIceServer(server: IceServerConfig): PeerConnection.IceServer =
        PeerConnection.IceServer.builder(server.urls)
            .apply {
                server.username?.let { setUsername(it) }
                server.credential?.let { setPassword(it) }
            }
            .createIceServer()

    private inner class PeerObserver : PeerConnection.Observer {

        override fun onIceCandidate(candidate: IceCandidate) {
            listener.onLocalIceCandidate(candidate)
        }

        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>) = Unit

        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
            listener.onConnectionStateChanged(newState)
        }

        override fun onTrack(transceiver: org.webrtc.RtpTransceiver) {
            val track = transceiver.receiver?.track()
            if (track is VideoTrack) listener.onRemoteVideoTrack(track)
        }

        override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
        override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
        override fun onAddStream(stream: MediaStream) = Unit
        override fun onRemoveStream(stream: MediaStream) = Unit
        override fun onDataChannel(channel: org.webrtc.DataChannel) = Unit
        override fun onRenegotiationNeeded() = Unit
        override fun onAddTrack(receiver: RtpReceiver, streams: Array<out MediaStream>) = Unit
    }

    private open inner class SimpleSdpObserver(private val tag: String) : SdpObserver {
        override fun onCreateSuccess(description: SessionDescription) = Unit
        override fun onSetSuccess() = Unit
        override fun onCreateFailure(error: String?) {
            Log.e(TAG, "$tag falhou ao criar: $error")
            listener.onError(CallError(CallEndReason.CONNECTION_FAILED, "Falha ao negociar a chamada."))
        }
        override fun onSetFailure(error: String?) {
            Log.e(TAG, "$tag falhou ao aplicar: $error")
            listener.onError(CallError(CallEndReason.CONNECTION_FAILED, "Falha ao negociar a chamada."))
        }
    }

    private companion object {
        const val TAG = "WebRtcEngine"
        const val STREAM_ID = "portaretrato"
        const val AUDIO_TRACK_ID = "audio0"
        const val VIDEO_TRACK_ID = "video0"
        const val CAPTURE_THREAD = "CaptureThread"

        /**
         * 640x480 a 24 fps. Num porta-retrato o que importa é a chamada não
         * cair: resolução menor gasta menos CPU, menos bateria e sobrevive a
         * conexões ruins. O WebRTC ainda reduz sozinho se a banda apertar.
         */
        const val CAPTURE_WIDTH = 640
        const val CAPTURE_HEIGHT = 480
        const val CAPTURE_FPS = 24
    }
}
