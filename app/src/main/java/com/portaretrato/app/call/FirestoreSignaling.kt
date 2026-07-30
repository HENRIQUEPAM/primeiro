package com.portaretrato.app.call

import android.util.Log
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration

/**
 * Canal de sinalização sobre o Cloud Firestore, que o app já usa.
 *
 * Não é preciso backend novo: o Firestore entrega mudanças em tempo real via
 * `addSnapshotListener`, com latência típica de 100–300 ms — mais que
 * suficiente, já que sinalização são umas poucas dezenas de mensagens no
 * início da chamada. Depois que a `PeerConnection` sobe, áudio e vídeo vão
 * direto entre os aparelhos e o Firestore sai do caminho.
 *
 * ## Estrutura
 *
 * ```
 * calls/{callId}                      offer, answer, estado, participantes
 * calls/{callId}/candidates/{autoId}  candidatos ICE dos dois lados
 * ```
 *
 * Cada candidato é um documento próprio porque chegam dezenas, em ordem
 * imprevisível e dos dois lados ao mesmo tempo — escrever todos no mesmo
 * documento causaria corrida de escrita.
 *
 * Os candidatos trazem `fromUid` para cada lado ignorar os próprios; sem isso
 * a `PeerConnection` recebe os candidatos que ela mesma gerou.
 */
class FirestoreSignaling(
    private val firestore: FirebaseFirestore,
    private val localUid: String,
) {

    private var callListener: ListenerRegistration? = null
    private var candidateListener: ListenerRegistration? = null

    private fun callDoc(callId: String) = firestore.collection(COLLECTION_CALLS).document(callId)
    private fun candidates(callId: String) = callDoc(callId).collection(COLLECTION_CANDIDATES)

    /**
     * Publica a offer e cria o documento da chamada.
     *
     * Sem `await`: o Firestore aplica no cache local imediatamente e
     * sincroniza sozinho. Bloquear aqui atrasaria o início da chamada pelo
     * tempo de ida e volta até o servidor.
     */
    fun sendOffer(offer: SignalMessage.Offer, toUid: String) {
        val payload = SignalingProtocol.encode(offer).toMutableMap()
        payload[SignalingProtocol.FIELD_TO_UID] = toUid
        callDoc(offer.callId).set(payload)
            .addOnFailureListener { Log.e(TAG, "Falha ao publicar a offer", it) }
    }

    /** Publica a answer no documento existente. */
    fun sendAnswer(answer: SignalMessage.Answer) {
        callDoc(answer.callId)
            .update(
                mapOf(
                    FIELD_ANSWER_SDP to answer.sdp,
                    FIELD_ANSWERED_AT to System.currentTimeMillis(),
                ),
            )
            .addOnFailureListener { Log.e(TAG, "Falha ao publicar a answer", it) }
    }

    /** Publica um candidato ICE local. */
    fun sendIceCandidate(candidate: SignalMessage.Ice) {
        candidates(candidate.callId)
            .add(SignalingProtocol.encode(candidate))
            .addOnFailureListener { Log.e(TAG, "Falha ao publicar candidato ICE", it) }
    }

    /** Sinaliza o encerramento. */
    fun sendHangup(callId: String, reason: CallEndReason) {
        callDoc(callId)
            .update(
                mapOf(
                    FIELD_ENDED to true,
                    SignalingProtocol.FIELD_REASON to reason.name,
                    FIELD_ENDED_AT to System.currentTimeMillis(),
                ),
            )
            .addOnFailureListener { Log.e(TAG, "Falha ao sinalizar encerramento", it) }
    }

    /**
     * Observa a chamada.
     *
     * @param onAnswer disparado uma única vez, quando a answer aparecer.
     * @param onRemoteIce candidatos do outro lado (os próprios são filtrados).
     * @param onEnded o outro lado desligou.
     */
    fun observe(
        callId: String,
        onAnswer: (String) -> Unit,
        onRemoteIce: (SignalMessage.Ice) -> Unit,
        onEnded: (CallEndReason) -> Unit,
    ) {
        var answerDelivered = false

        callListener = callDoc(callId).addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Listener da chamada falhou", error)
                return@addSnapshotListener
            }
            val data = snapshot?.data ?: return@addSnapshotListener

            if (data[FIELD_ENDED] as? Boolean == true) {
                val reason = (data[SignalingProtocol.FIELD_REASON] as? String)
                    ?.let { name -> CallEndReason.entries.firstOrNull { it.name == name } }
                    ?: CallEndReason.REMOTE_HANGUP
                onEnded(reason)
                return@addSnapshotListener
            }

            val answerSdp = data[FIELD_ANSWER_SDP] as? String
            if (!answerDelivered && !answerSdp.isNullOrBlank()) {
                answerDelivered = true
                onAnswer(answerSdp)
            }
        }

        candidateListener = candidates(callId).addSnapshotListener { snapshot, error ->
            if (error != null) {
                Log.e(TAG, "Listener de candidatos falhou", error)
                return@addSnapshotListener
            }
            for (change in snapshot?.documentChanges.orEmpty()) {
                if (change.type != DocumentChange.Type.ADDED) continue
                val ice = SignalingProtocol.decode(change.document.data) as? SignalMessage.Ice ?: continue
                // Ignora os próprios candidatos.
                if (ice.fromUid == localUid) continue
                onRemoteIce(ice)
            }
        }
    }

    /**
     * Remove os listeners. **Obrigatório** ao encerrar: um listener do
     * Firestore esquecido mantém uma conexão aberta e continua consumindo cota
     * e bateria pelo resto da vida do processo.
     */
    fun stop() {
        callListener?.remove()
        candidateListener?.remove()
        callListener = null
        candidateListener = null
    }

    /**
     * Apaga o documento da chamada e seus candidatos.
     *
     * Chame no lado de quem ligou, depois de encerrar. Sem isso a coleção
     * `calls` cresce sem limite. A alternativa — e mais confiável, porque não
     * depende do app estar vivo — é uma TTL policy no Firestore sobre
     * `createdAt`; ver `docs/CHAMADAS.md`.
     */
    fun cleanup(callId: String) {
        candidates(callId).get()
            .addOnSuccessListener { snapshot ->
                val batch = firestore.batch()
                for (doc in snapshot.documents) batch.delete(doc.reference)
                batch.delete(callDoc(callId))
                batch.commit().addOnFailureListener { Log.e(TAG, "Falha na limpeza da chamada", it) }
            }
            .addOnFailureListener { Log.e(TAG, "Falha ao listar candidatos para limpeza", it) }
    }

    private companion object {
        const val TAG = "FirestoreSignaling"
        const val COLLECTION_CALLS = "calls"
        const val COLLECTION_CANDIDATES = "candidates"
        const val FIELD_ANSWER_SDP = "answerSdp"
        const val FIELD_ANSWERED_AT = "answeredAt"
        const val FIELD_ENDED = "ended"
        const val FIELD_ENDED_AT = "endedAt"
    }
}
