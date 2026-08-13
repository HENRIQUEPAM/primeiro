package com.portaretrato.app.call

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Publica o token do FCM em `users/{uid}/fcmTokens/{token}`, que é onde a
 * Cloud Function procura para quem enviar o push.
 *
 * Um token por documento (e não um array no documento do usuário) porque o
 * mesmo usuário pode ter vários aparelhos, e porque escrever documentos
 * separados evita corrida quando dois aparelhos registram ao mesmo tempo.
 */
class FcmTokenRegistrar(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {

    /** Busca o token atual e registra. Chame no login. */
    fun registerCurrent(uid: String) {
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token -> register(uid, token) }
            .addOnFailureListener { Log.e(TAG, "Não foi possível obter o token do FCM", it) }
    }

    fun register(uid: String, token: String) {
        firestore.collection("users").document(uid)
            .collection("fcmTokens").document(token)
            .set(mapOf("updatedAt" to System.currentTimeMillis()))
            .addOnFailureListener { Log.e(TAG, "Falha ao registrar o token", it) }
    }

    private companion object {
        const val TAG = "FcmTokenRegistrar"
    }
}

/** Busca a offer completa quando o push só traz o identificador da chamada. */
class FirestoreOfferFetcher(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    fun fetch(callId: String, onResult: (SignalMessage.Offer?) -> Unit) {
        firestore.collection("calls").document(callId).get()
            .addOnSuccessListener { doc ->
                onResult(SignalingProtocol.decode(doc.data) as? SignalMessage.Offer)
            }
            .addOnFailureListener { onResult(null) }
    }
}
