package com.portaretrato.app.call.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.portaretrato.app.R
import com.portaretrato.app.call.CallEndReason
import com.portaretrato.app.call.CallService
import com.portaretrato.app.call.CallState
import com.portaretrato.app.call.CallUiState
import com.portaretrato.app.databinding.ActivityCallBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack

/**
 * Tela de chamada.
 *
 * A chamada em si NÃO vive aqui — vive no [CallService]. Esta Activity é só
 * uma janela para ela, e pode ser destruída e recriada (girar a tela, o sistema
 * recriar a janela) sem derrubar nada.
 *
 * ## Decisões de UI para usuário idoso
 *
 * - Botões de 96 dp com rótulo em texto abaixo do ícone. Ícone sozinho é
 *   ambíguo; "Atender" escrito não é.
 * - Verde = atender, vermelho = desligar, sem exceção e sem outras cores
 *   fortes competindo na tela.
 * - Nome de quem liga em 34 sp. Fonte grande do sistema é comum nessa faixa
 *   etária, e o layout precisa aguentar 130 % sem quebrar.
 * - `showOnLockScreen` + `turnScreenOn`: o porta-retrato acende sozinho.
 * - Durante o atendimento automático, contagem regressiva bem visível com o
 *   botão de recusar do lado — atender sem aviso seria abrir a câmera da casa
 *   sem nenhum sinal.
 */
class CallActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCallBinding
    private var renderersInitialized = false
    private var attachedRemoteTrack: VideoTrack? = null
    private var attachedLocalTrack: VideoTrack? = null

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { !it }) {
            binding.status.text = getString(R.string.permissions_required)
            CallService.activeController?.hangup(CallEndReason.PERMISSION_DENIED)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCallBinding.inflate(layoutInflater)
        setContentView(binding.root)

        showOverLockScreen()
        ensurePermissions()

        binding.answerButton.setOnClickListener { CallService.activeController?.accept() }
        binding.declineButton.setOnClickListener {
            CallService.activeController?.hangup(CallEndReason.REJECTED)
        }
        binding.hangUpButton.setOnClickListener {
            CallService.activeController?.hangup(CallEndReason.LOCAL_HANGUP)
        }
        binding.muteButton.setOnClickListener { CallService.activeController?.toggleMicrophone() }
        binding.switchCameraButton.setOnClickListener { CallService.activeController?.switchCamera() }

        observeCall()
    }

    /** Acende a tela e aparece por cima da tela de bloqueio. */
    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun ensurePermissions() {
        val needed = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) requestPermissions.launch(needed.toTypedArray())
    }

    private fun observeCall() {
        val controller = CallService.activeController ?: run {
            finish()
            return
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { controller.uiState.collectLatest(::render) }
                launch {
                    controller.remoteVideoTrack.collectLatest { track ->
                        initRenderersIfNeeded()
                        attachRemote(track)
                    }
                }
            }
        }
    }

    private fun render(state: CallUiState) {
        binding.peerName.text = state.peerName.ifBlank { getString(R.string.unknown_caller) }

        val incoming = state.state == CallState.RINGING
        binding.answerButton.visibility = if (incoming) View.VISIBLE else View.GONE
        binding.declineButton.visibility = if (incoming) View.VISIBLE else View.GONE
        binding.hangUpButton.visibility = if (incoming) View.GONE else View.VISIBLE

        val inCall = state.state == CallState.ACTIVE || state.state == CallState.RECONNECTING
        binding.muteButton.visibility = if (inCall) View.VISIBLE else View.GONE
        binding.switchCameraButton.visibility = if (inCall) View.VISIBLE else View.GONE

        binding.muteButton.setText(
            if (state.microphoneEnabled) R.string.mute else R.string.unmute,
        )

        binding.status.text = when {
            state.errorMessage != null -> state.errorMessage
            state.autoAnswerCountdown != null ->
                getString(R.string.auto_answering_in, state.autoAnswerCountdown)
            state.state == CallState.DIALING -> getString(R.string.calling)
            state.state == CallState.RINGING -> getString(R.string.incoming_call_title)
            state.state == CallState.CONNECTING -> getString(R.string.connecting)
            state.state == CallState.ACTIVE -> getString(R.string.connected)
            state.state == CallState.RECONNECTING -> getString(R.string.reconnecting)
            state.state == CallState.ENDED -> endedMessage(state.endReason)
            else -> ""
        }

        if (state.state == CallState.ENDED) {
            binding.root.postDelayed({ finish() }, END_SCREEN_DELAY_MS)
        }

        if (state.state != CallState.IDLE && state.state != CallState.RINGING) {
            initRenderersIfNeeded()
            attachLocal(CallService.activeController?.localVideoTrack)
        }
    }

    private fun endedMessage(reason: CallEndReason?): String = getString(
        when (reason) {
            CallEndReason.REMOTE_HANGUP -> R.string.ended_remote
            CallEndReason.REJECTED -> R.string.ended_rejected
            CallEndReason.NO_ANSWER -> R.string.ended_no_answer
            CallEndReason.BUSY -> R.string.ended_busy
            CallEndReason.CONNECTION_FAILED -> R.string.ended_connection_failed
            CallEndReason.PERMISSION_DENIED -> R.string.permissions_required
            else -> R.string.ended
        },
    )

    private fun initRenderersIfNeeded() {
        if (renderersInitialized) return
        val egl = CallService.activeController?.eglBase ?: return

        binding.remoteVideo.init(egl.eglBaseContext, null)
        binding.remoteVideo.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
        binding.remoteVideo.setEnableHardwareScaler(true)

        binding.localVideo.init(egl.eglBaseContext, null)
        binding.localVideo.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        binding.localVideo.setZOrderMediaOverlay(true)
        binding.localVideo.setEnableHardwareScaler(true)

        renderersInitialized = true
    }

    private fun attachRemote(track: VideoTrack?) {
        if (attachedRemoteTrack === track) return
        attachedRemoteTrack?.removeSink(binding.remoteVideo)
        attachedRemoteTrack = track
        track?.addSink(binding.remoteVideo)
        binding.remoteVideo.visibility = if (track != null) View.VISIBLE else View.GONE
    }

    private fun attachLocal(track: VideoTrack?) {
        if (attachedLocalTrack === track) return
        attachedLocalTrack?.removeSink(binding.localVideo)
        attachedLocalTrack = track
        track?.addSink(binding.localVideo)
        binding.localVideo.visibility = if (track != null) View.VISIBLE else View.GONE
    }

    /**
     * Desconecta os sinks e libera os renderers. Sem isto o WebRTC continua
     * entregando frames para uma Surface destruída e o app quebra ao girar a
     * tela durante a chamada.
     */
    override fun onDestroy() {
        attachedRemoteTrack?.removeSink(binding.remoteVideo)
        attachedLocalTrack?.removeSink(binding.localVideo)
        attachedRemoteTrack = null
        attachedLocalTrack = null
        if (renderersInitialized) {
            binding.remoteVideo.release()
            binding.localVideo.release()
            renderersInitialized = false
        }
        super.onDestroy()
    }

    companion object {
        private const val END_SCREEN_DELAY_MS = 2_000L

        val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

        fun intentForIncoming(context: Context): Intent =
            Intent(context, CallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun intentForOngoing(context: Context): Intent = intentForIncoming(context)
    }
}
