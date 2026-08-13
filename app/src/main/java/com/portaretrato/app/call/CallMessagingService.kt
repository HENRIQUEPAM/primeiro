package com.portaretrato.app.call

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Recebe o push de chamada.
 *
 * Necessário para o caso em que o app foi morto pelo sistema — aí nenhum
 * listener do Firestore está rodando e só um push de **alta prioridade** fura o
 * Doze e acorda o processo.
 *
 * A Cloud Function precisa enviar **data-only** (sem bloco `notification`):
 * com bloco `notification` o Android exibe a mensagem sozinho e não chama
 * `onMessageReceived` com o app em segundo plano — a chamada nunca tocaria.
 * Ver `firebase/functions/index.js`.
 */
class CallMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        FirebaseAuth.getInstance().currentUser?.uid?.let { uid ->
            FcmTokenRegistrar().register(uid, token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val offer = SignalingProtocol.decode(data) as? SignalMessage.Offer
        if (offer == null) {
            Log.w(TAG, "Push recebido sem offer válida: ${data.keys}")
            return
        }

        // O SDP não vem no push (é grande demais para o limite de 4 KB do FCM).
        // O push só ACORDA o app; o SDP vem do Firestore.
        val sdp = offer.sdp
        if (sdp.isBlank()) {
            FirestoreOfferFetcher().fetch(offer.callId) { fetched ->
                if (fetched != null) {
                    CallService.incoming(this, offer.callId, offer.fromUid, fetched.sdp, fetched.video)
                } else {
                    Log.w(TAG, "Offer ${offer.callId} não encontrada no Firestore")
                }
            }
        } else {
            CallService.incoming(this, offer.callId, offer.fromUid, sdp, offer.video)
        }
    }

    private companion object {
        const val TAG = "CallMessagingService"
    }
}
