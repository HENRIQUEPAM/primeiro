/**
 * Push de chamada recebida.
 *
 * Só é necessário para o caso do app estar MORTO no aparelho que recebe.
 * Com o app aberto, o `IncomingCallWatcher` já escuta o Firestore direto e a
 * chamada toca sem esta função. Os dois caminhos convergem para
 * `CallService.incoming`, e a `AutoAnswerPolicy` descarta a entrega duplicada
 * quando ambos disparam.
 */

const { onDocumentCreated } = require('firebase-functions/v2/firestore');
const { initializeApp } = require('firebase-admin/app');
const { getFirestore } = require('firebase-admin/firestore');
const { getMessaging } = require('firebase-admin/messaging');
const logger = require('firebase-functions/logger');

initializeApp();

/** Uma chamada mais velha que isto não deve mais tocar no aparelho. */
const CALL_TTL_MS = 45000;

exports.notifyIncomingCall = onDocumentCreated('calls/{callId}', async (event) => {
  const snapshot = event.data;
  if (!snapshot) return;

  const call = snapshot.data();
  const callId = event.params.callId;

  if (!call || !call.toUid || !call.fromUid) {
    logger.warn('Documento de chamada incompleto', { callId });
    return;
  }

  const age = Date.now() - (call.createdAt || 0);
  if (age > CALL_TTL_MS) {
    logger.info('Chamada velha demais para notificar', { callId, age });
    return;
  }

  const tokens = await tokensFor(call.toUid);
  if (tokens.length === 0) {
    logger.info('Destinatário sem token de FCM registrado', { toUid: call.toUid });
    return;
  }

  // IMPORTANTE: data-only, sem bloco `notification`.
  // Com bloco `notification`, o Android exibe a mensagem sozinho e NÃO chama
  // onMessageReceived com o app em segundo plano — a chamada nunca tocaria.
  //
  // O SDP não vai no payload: passa de 4 KB com frequência e estouraria o
  // limite do FCM. O push só acorda o app, que então busca a offer no
  // Firestore (ver FirestoreOfferFetcher).
  const message = {
    tokens,
    data: {
      type: 'offer',
      callId: callId,
      sdp: '',
      fromUid: String(call.fromUid),
      fromName: String(call.fromName || ''),
      video: String(call.video !== false),
      createdAt: String(call.createdAt || Date.now()),
    },
    android: {
      priority: 'high', // fura o Doze
      ttl: CALL_TTL_MS, // não adianta entregar uma chamada que já expirou
    },
  };

  const response = await getMessaging().sendEachForMulticast(message);
  logger.info('Push de chamada enviado', {
    callId,
    sucesso: response.successCount,
    falha: response.failureCount,
  });

  await pruneInvalidTokens(call.toUid, tokens, response);
});

async function tokensFor(uid) {
  const snapshot = await getFirestore()
    .collection('users')
    .doc(uid)
    .collection('fcmTokens')
    .get();
  return snapshot.docs.map((doc) => doc.id);
}

/**
 * Remove tokens que o FCM rejeitou. Sem isso a coleção acumula tokens de
 * aparelhos antigos e cada chamada gasta envio à toa.
 */
async function pruneInvalidTokens(uid, tokens, response) {
  const invalid = [];
  response.responses.forEach((result, index) => {
    if (result.success) return;
    const code = result.error && result.error.code;
    if (
      code === 'messaging/registration-token-not-registered' ||
      code === 'messaging/invalid-registration-token'
    ) {
      invalid.push(tokens[index]);
    }
  });
  if (invalid.length === 0) return;

  const batch = getFirestore().batch();
  const collection = getFirestore().collection('users').doc(uid).collection('fcmTokens');
  invalid.forEach((token) => batch.delete(collection.doc(token)));
  await batch.commit();
  logger.info('Tokens inválidos removidos', { uid, quantidade: invalid.length });
}
