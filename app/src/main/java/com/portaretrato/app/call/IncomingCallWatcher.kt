package com.portaretrato.app.call

import android.content.Context
import android.util.Log
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * Escuta chamadas recebidas direto no Firestore.
 *
 * Existe para que o projeto funcione **sem depender do deploy da Cloud
 * Function**: enquanto o app estiver rodando (primeiro plano ou service ativo),
 * este listener acorda a chamada sozinho. Basta dois aparelhos e o Firebase
 * configurado.
 *
 * A limitação é justamente por que o FCM também é necessário: com o app morto
 * pelo sistema, nenhum listener roda. Aí só o push de alta prioridade acorda o
 * processo. Os dois caminhos convergem para o mesmo
 * [CallService.incoming], e [AutoAnswerPolicy] descarta a entrega duplicada
 * quando ambos disparam.
 */
class IncomingCallWatcher(
    private val context: Context,
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {

    private var registration: ListenerRegistration? = null

    fun start(localUid: String) {
        stop()
        registration = firestore.collection(COLLECTION_CALLS)
            .whereEqualTo(SignalingProtocol.FIELD_TO_UID, localUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.e(TAG, "Listener de chamadas recebidas falhou", error)
                    return@addSnapshotListener
                }
                for (change in snapshot?.documentChanges.orEmpty()) {
                    if (change.type != DocumentChange.Type.ADDED) continue
                    handle(change.document.data, change.document.id)
                }
            }
    }

    private fun handle(data: Map<String, Any?>, documentId: String) {
        // Chamada já encerrada que só agora sincronizou: ignora.
        if (data["ended"] as? Boolean == true) return

        val offer = SignalingProtocol.decode(data) as? SignalMessage.Offer ?: return

        // Convite velho (o app ficou offline e sincronizou tudo de uma vez).
        val age = System.currentTimeMillis() - offer.createdAt
        if (age > MAX_INVITE_AGE_MS) {
            Log.d(TAG, "Convite expirado ignorado (${age}ms)")
            return
        }

        CallService.incoming(
            context = context,
            callId = documentId,
            fromUid = offer.fromUid,
            offerSdp = offer.sdp,
            video = offer.video,
        )
    }

    fun stop() {
        registration?.remove()
        registration = null
    }

    private companion object {
        const val TAG = "IncomingCallWatcher"
        const val COLLECTION_CALLS = "calls"

        /** Mesmo TTL do push: chamada de um minuto atrás não deve tocar. */
        const val MAX_INVITE_AGE_MS = 60_000L
    }
}
