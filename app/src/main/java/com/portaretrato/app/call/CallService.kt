package com.portaretrato.app.call

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.portaretrato.app.call.ui.CallActivity
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.portaretrato.app.PortaRetratoApp
import java.util.Calendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Hospeda a chamada ativa.
 *
 * Fica num foreground service — e não na Activity — porque a chamada precisa
 * sobreviver a girar a tela, a apagar a tela e ao usuário sair do app. Colocar
 * a `PeerConnection` na Activity é o erro mais comum e produz o sintoma
 * "a chamada cai sozinha depois de alguns segundos".
 *
 * O tipo `camera|microphone` é obrigatório desde o Android 10 (e o Android 14
 * passou a exigir as permissões correspondentes no manifesto).
 */
class CallService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var controller: CallController? = null
    private lateinit var contacts: TrustedContactsStore
    private lateinit var autoAnswerPolicy: AutoAnswerPolicy

    override fun onCreate() {
        super.onCreate()
        CallNotifications.createChannels(this)
        contacts = TrustedContactsStore(this)
        // Lido uma vez, na criação do service — que é recriado a cada
        // ciclo de chamada (ver stopIfIdle/stopSelf), então uma mudança no
        // interruptor mestre feita em AdminActivity vale a partir da
        // PRÓXIMA chamada, nunca no meio de uma já em andamento.
        autoAnswerPolicy = AutoAnswerPolicy(
            trustedContacts = { contacts.all() },
            featureEnabled = AutoAnswerSettingsStore(this).isEnabled(),
        )
        instance = this
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_DIAL -> handleDial(intent)
            ACTION_INCOMING -> handleIncoming(intent)
            ACTION_ACCEPT -> controller?.accept()
            ACTION_DECLINE -> controller?.hangup(CallEndReason.REJECTED)
            ACTION_HANGUP -> controller?.hangup(CallEndReason.LOCAL_HANGUP)
            else -> Log.w(TAG, "Ação desconhecida: ${intent?.action}")
        }
        return START_NOT_STICKY
    }

    // ------------------------------------------------------------------ ações

    private fun handleDial(intent: Intent) {
        val toUid = intent.getStringExtra(EXTRA_PEER_UID) ?: return stopIfIdle()
        val toName = intent.getStringExtra(EXTRA_PEER_NAME).orEmpty()
        val video = intent.getBooleanExtra(EXTRA_VIDEO, true)

        val controller = ensureController() ?: return stopIfIdle()
        promoteToForeground(CallNotifications.ongoing(this, toName))
        controller.dial(toUid, toName, video)
    }

    private fun handleIncoming(intent: Intent) {
        val callId = intent.getStringExtra(EXTRA_CALL_ID) ?: return stopIfIdle()
        val fromUid = intent.getStringExtra(EXTRA_PEER_UID) ?: return stopIfIdle()
        val offerSdp = intent.getStringExtra(EXTRA_OFFER_SDP) ?: return stopIfIdle()
        val video = intent.getBooleanExtra(EXTRA_VIDEO, true)

        val invite = CallInvite(
            callId = callId,
            fromUid = fromUid,
            // Propositalmente vazio: o nome exibido nunca vem de quem liga.
            // Ver AutoAnswerPolicy.decide.
            fromName = "",
            fromPhone = null,
            toUid = FirebaseAuth.getInstance().currentUser?.uid.orEmpty(),
            createdAt = System.currentTimeMillis(),
            video = video,
        )

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val decision = autoAnswerPolicy.decide(
            invite = invite,
            callInProgress = controller != null,
            hourOfDay = hour,
        )

        when (decision) {
            is AutoAnswerDecision.Reject -> {
                Log.d(TAG, "Convite recusado: ${decision.reason}")
                if (controller == null) stopIfIdle()
            }

            is AutoAnswerDecision.Ring -> {
                val ctrl = ensureController() ?: return stopIfIdle()
                promoteToForeground(CallNotifications.incoming(this, decision.displayName))
                ctrl.receive(invite, offerSdp, decision.displayName, autoAnswerDelayMs = null)
            }

            is AutoAnswerDecision.Answer -> {
                val ctrl = ensureController() ?: return stopIfIdle()
                promoteToForeground(CallNotifications.incoming(this, decision.contactName))
                ctrl.receive(invite, offerSdp, decision.contactName, autoAnswerDelayMs = decision.delayMs)
                // Acende a tela para o idoso ver quem está ligando durante a
                // contagem regressiva — e conseguir recusar se quiser.
                startActivity(
                    CallActivity.intentForIncoming(this)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
    }

    // --------------------------------------------------------------- internos

    private fun ensureController(): CallController? {
        controller?.let { return it }

        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            Log.e(TAG, "Sem usuário autenticado; não é possível iniciar a chamada.")
            return null
        }

        val created = CallController(
            context = applicationContext,
            localUid = user.uid,
            localName = user.displayName.orEmpty(),
            // TODO: buscar credenciais efêmeras de TURN. Ver docs/CHAMADAS.md.
            // Só com STUN, chamadas em NAT simétrico (rede móvel) não conectam.
            config = CallConfig.stunOnly(),
            // O guarda vem da Application: unico no processo, senao a garantia
            // de camera exclusiva cai por terra.
            cameraGuard = PortaRetratoApp.from(applicationContext).cameraGuard,
        )
        controller = created

        scope.launch {
            created.uiState.collectLatest { state ->
                if (state.state == CallState.ENDED) stopIfIdle()
            }
        }
        return created
    }

    private fun promoteToForeground(notification: android.app.Notification) {
        // A partir daqui existe notificacao visivel: a politica de camera passa
        // a considerar o app "visivel ao usuario" mesmo sem Activity na tela.
        PortaRetratoApp.from(applicationContext).mediaForegroundServiceRunning = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                CallNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(CallNotifications.NOTIFICATION_ID, notification)
        }
    }

    private fun stopIfIdle() {
        PortaRetratoApp.from(applicationContext).mediaForegroundServiceRunning = false
        controller?.release()
        controller = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        PortaRetratoApp.from(applicationContext).mediaForegroundServiceRunning = false
        controller?.release()
        controller = null
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "CallService"

        const val ACTION_DIAL = "com.portaretrato.app.call.DIAL"
        const val ACTION_INCOMING = "com.portaretrato.app.call.INCOMING"
        const val ACTION_ACCEPT = "com.portaretrato.app.call.ACCEPT"
        const val ACTION_DECLINE = "com.portaretrato.app.call.DECLINE"
        const val ACTION_HANGUP = "com.portaretrato.app.call.HANGUP"

        const val EXTRA_CALL_ID = "callId"
        const val EXTRA_PEER_UID = "peerUid"
        const val EXTRA_PEER_NAME = "peerName"
        const val EXTRA_OFFER_SDP = "offerSdp"
        const val EXTRA_VIDEO = "video"

        /**
         * Referência ao service ativo, para a Activity observar o estado sem
         * `bindService`. É aceitável porque o ciclo de vida é curto e sempre
         * limpo em `onDestroy`; num app maior use um binder ou um repositório
         * em escopo de aplicação.
         */
        @Volatile
        var instance: CallService? = null
            private set

        val activeController: CallController? get() = instance?.controller

        fun dial(context: Context, toUid: String, toName: String, video: Boolean = true) {
            context.startForegroundService(
                Intent(context, CallService::class.java).apply {
                    action = ACTION_DIAL
                    putExtra(EXTRA_PEER_UID, toUid)
                    putExtra(EXTRA_PEER_NAME, toName)
                    putExtra(EXTRA_VIDEO, video)
                },
            )
        }

        fun incoming(context: Context, callId: String, fromUid: String, offerSdp: String, video: Boolean) {
            context.startForegroundService(
                Intent(context, CallService::class.java).apply {
                    action = ACTION_INCOMING
                    putExtra(EXTRA_CALL_ID, callId)
                    putExtra(EXTRA_PEER_UID, fromUid)
                    putExtra(EXTRA_OFFER_SDP, offerSdp)
                    putExtra(EXTRA_VIDEO, video)
                },
            )
        }

        fun acceptIntent(context: Context): Intent =
            Intent(context, CallService::class.java).apply { action = ACTION_ACCEPT }

        fun declineIntent(context: Context): Intent =
            Intent(context, CallService::class.java).apply { action = ACTION_DECLINE }

        fun hangupIntent(context: Context): Intent =
            Intent(context, CallService::class.java).apply { action = ACTION_HANGUP }
    }
}
