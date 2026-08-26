/**
 * Cloud Functions da chamada — Seção 8 da Documentação Técnica Consolidada v3.1.
 *
 * Estas funções são a TCB privilegiada do sistema: são as únicas que escrevem
 * campos que o cliente não pode escrever (`integrityVerified`), emitem
 * credenciais de TURN, e acordam o aparelho remoto.
 *
 *   onIncomingCall          FCM data-only de alta prioridade
 *   issueTurnCredentials    credenciais efêmeras HMAC, nunca estáticas no APK
 *   sweepStaleCallSessions  TTL / LGPD, rede de segurança (não mecanismo primário)
 *   markMissedCalls         Cloud Scheduler 1x/min, marca MISSED após 45 s
 */

const crypto = require('crypto');
const { onDocumentCreated } = require('firebase-functions/v2/firestore');
const { onCall, HttpsError } = require('firebase-functions/v2/https');
const { onSchedule } = require('firebase-functions/v2/scheduler');
const { defineSecret } = require('firebase-functions/params');
const { initializeApp } = require('firebase-admin/app');
const { getFirestore, FieldValue } = require('firebase-admin/firestore');
const { getMessaging } = require('firebase-admin/messaging');
const logger = require('firebase-functions/logger');

initializeApp();

const REGION = 'southamerica-east1';

/** Segredo compartilhado com o coturn. Nunca vai para o APK. */
const TURN_SECRET = defineSecret('TURN_SECRET');
const TURN_URLS = defineSecret('TURN_URLS');

/** Toque de 45 s antes de marcar como perdida (Seção 8). */
const RING_TIMEOUT_MS = 45000;

/** Credencial de TURN válida por 1 h — bem além da duração de uma chamada. */
const TURN_TTL_SECONDS = 3600;

// ---------------------------------------------------------------------------
// onIncomingCall
// ---------------------------------------------------------------------------
exports.onIncomingCall = onDocumentCreated(
  { document: 'pairings/{pairingId}/callSessions/{sessionId}', region: REGION },
  async (event) => {
    const snapshot = event.data;
    if (!snapshot) return;

    const session = snapshot.data();
    const { pairingId, sessionId } = event.params;

    if (!session || session.state !== 'RINGING' || !session.calleeDeviceId) {
      return;
    }

    const tokens = await fcmTokensForDevice(session.calleeDeviceId);
    if (tokens.length === 0) {
      logger.info('Destinatário sem token de FCM', { deviceId: session.calleeDeviceId });
      return;
    }

    // data-only obrigatoriamente. Com bloco `notification`, o Android exibe a
    // mensagem sozinho e NÃO chama onMessageReceived com o app em segundo
    // plano — a chamada nunca tocaria. É o ponto que a Seção 7.4 destaca.
    //
    // O SDP não vai no payload: passa dos 4 KB do FCM com facilidade. O push
    // só acorda o app, que busca a sessão no Firestore.
    const response = await getMessaging().sendEachForMulticast({
      tokens,
      data: {
        type: 'incoming_call',
        pairingId,
        sessionId,
        callerDeviceId: String(session.callerDeviceId || ''),
        video: String(session.video !== false),
      },
      android: {
        priority: 'high', // fura o Doze
        ttl: RING_TIMEOUT_MS,
      },
    });

    logger.info('Push de chamada enviado', {
      pairingId,
      sessionId,
      sucesso: response.successCount,
      falha: response.failureCount,
    });

    await pruneInvalidTokens(session.calleeDeviceId, tokens, response);
  },
);

// ---------------------------------------------------------------------------
// issueTurnCredentials
// ---------------------------------------------------------------------------
exports.issueTurnCredentials = onCall(
  { region: REGION, secrets: [TURN_SECRET, TURN_URLS], enforceAppCheck: true },
  async (request) => {
    if (!request.auth) {
      throw new HttpsError('unauthenticated', 'Login obrigatório.');
    }

    // Formato de credencial temporária do coturn: o username é a própria data
    // de expiração, e a senha é o HMAC dela. O servidor valida sem guardar
    // estado nenhum.
    const expiry = Math.floor(Date.now() / 1000) + TURN_TTL_SECONDS;
    const username = `${expiry}:${request.auth.uid}`;
    const credential = crypto
      .createHmac('sha1', TURN_SECRET.value())
      .update(username)
      .digest('base64');

    const turnUrls = TURN_URLS.value()
      .split(',')
      .map((u) => u.trim())
      .filter(Boolean);

    return {
      iceServers: [
        { urls: ['stun:stun.l.google.com:19302'] },
        { urls: turnUrls, username, credential },
      ],
      expiresAt: expiry * 1000,
    };
  },
);

// ---------------------------------------------------------------------------
// markMissedCalls
// ---------------------------------------------------------------------------
exports.markMissedCalls = onSchedule(
  { schedule: 'every 1 minutes', region: REGION },
  async () => {
    const cutoff = new Date(Date.now() - RING_TIMEOUT_MS);
    const stale = await getFirestore()
      .collectionGroup('callSessions')
      .where('state', '==', 'RINGING')
      .where('createdAt', '<', cutoff)
      .limit(200)
      .get();

    if (stale.empty) return;

    const batch = getFirestore().batch();
    stale.docs.forEach((doc) => {
      batch.update(doc.ref, {
        state: 'MISSED',
        endReason: 'NO_ANSWER',
        endedAt: FieldValue.serverTimestamp(),
      });
    });
    await batch.commit();
    logger.info('Chamadas marcadas como perdidas', { quantidade: stale.size });
  },
);

// ---------------------------------------------------------------------------
// sweepStaleCallSessions
// ---------------------------------------------------------------------------
exports.sweepStaleCallSessions = onSchedule(
  { schedule: 'every 6 hours', region: REGION },
  async () => {
    // Rede de segurança de custo de armazenamento, não mecanismo de "missed
    // call". A Seção 8 é explícita: o TTL do Firestore tem atraso documentado
    // de 24-72 h e não serve para tempo real.
    const cutoff = new Date(Date.now() - 24 * 60 * 60 * 1000);
    const old = await getFirestore()
      .collectionGroup('callSessions')
      .where('createdAt', '<', cutoff)
      .limit(500)
      .get();

    if (old.empty) return;

    for (const doc of old.docs) {
      const candidates = await doc.ref.collection('candidates').get();
      const batch = getFirestore().batch();
      candidates.docs.forEach((c) => batch.delete(c.ref));
      batch.delete(doc.ref);
      await batch.commit();
    }
    logger.info('Sessões antigas removidas', { quantidade: old.size });
  },
);

// ---------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------
async function fcmTokensForDevice(deviceId) {
  const doc = await getFirestore()
    .collection('devices')
    .doc(deviceId)
    .collection('private')
    .doc('fcm')
    .get();
  if (!doc.exists) return [];
  const tokens = doc.data().tokens;
  return Array.isArray(tokens) ? tokens.filter(Boolean) : [];
}

/**
 * Remove tokens que o FCM rejeitou. Sem isso a lista acumula aparelhos antigos
 * e cada chamada gasta envio à toa.
 */
async function pruneInvalidTokens(deviceId, tokens, response) {
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

  await getFirestore()
    .collection('devices')
    .doc(deviceId)
    .collection('private')
    .doc('fcm')
    .update({ tokens: FieldValue.arrayRemove(...invalid) });

  logger.info('Tokens inválidos removidos', { deviceId, quantidade: invalid.length });
}
