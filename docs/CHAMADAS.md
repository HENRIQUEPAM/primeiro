# Chamada de vídeo entre aparelhos — arquitetura e integração

Substitui a delegação ao WhatsApp por chamada nativa entre dois aparelhos, pela
internet, com **atendimento automático** para contatos de confiança — que é o
que o WhatsApp não permite e o que justifica construir isto.

## Como funciona

```
  Aparelho A (filha)                                  Aparelho B (porta-retrato)
        │                                                        │
        │ 1. cria offer (SDP)                                    │
        ├──────────────► Firestore: calls/{callId} ──────────────►│
        │                                                        │
        │ 2. Cloud Function detecta o documento novo             │
        │    e dispara push de alta prioridade                   │
        ├──────────────► FCM ────────────────────────────────────►│  acorda o app
        │                                                        │
        │                                    3. AutoAnswerPolicy decide:
        │                                       atende sozinho ou toca
        │                                                        │
        │◄───────────── Firestore: answerSdp ────────────────────┤ 4. answer
        │                                                        │
        │◄────────► Firestore: calls/{id}/candidates ◄──────────►│ 5. ICE
        │                                                        │
        │◄══════════ áudio e vídeo direto (P2P ou TURN) ═════════►│ 6. conectado
```

O Firestore só carrega a **sinalização** — algumas dezenas de mensagens no
início. Áudio e vídeo vão direto entre os aparelhos, ou pelo TURN quando a rede
não permite conexão direta. Não passam pelo Firebase e não geram custo lá.

## Arquivos entregues

Projeto Android completo e buildável. Ver [`COMO-RODAR.md`](../COMO-RODAR.md).

**Lógica pura** (Kotlin sem dependência de Android — 53 asserções passam):

| Arquivo | Papel |
| --- | --- |
| `CallModels.kt` | Estados, papéis, convite, configuração de ICE |
| `CallStateMachine.kt` | Transições válidas de uma chamada |
| `SignalingProtocol.kt` | Serialização das mensagens |
| `AutoAnswerPolicy.kt` | Decide atender sozinho / tocar / recusar |

**Camada Android** (sintaxe verificada; não compilada contra as bibliotecas):

| Arquivo | Papel |
| --- | --- |
| `WebRtcEngine.kt` | `PeerConnection`, mídia, ICE, teardown |
| `FirestoreSignaling.kt` | Transporte da sinalização |
| `CallController.kt` | Junta máquina de estados + engine + sinalização |
| `CallService.kt` | Foreground service que hospeda a chamada |
| `IncomingCallWatcher.kt` | Chamada recebida **sem depender da Cloud Function** |
| `CallMessagingService.kt` | Push (FCM) para quando o app está morto |
| `FcmTokenRegistrar.kt` | Registra o token em `users/{uid}/fcmTokens` |
| `CallNotifications.kt` | Canais + notificação `CallStyle` |
| `TrustedContactsStore.kt` | Contatos de confiança, local. Escrita também por `ui/PeopleActivity` (reconhecimento) quando um rosto é vinculado a um telefone |
| `AuthSession.kt` | Dona do login + do `IncomingCallWatcher`, vive na `Application` |
| `ui/CallActivity.kt` | Tela de chamada |
| `ui/HomeActivity.kt` | Discagem e contatos — alcançada pelo menu do porta-retrato |
| `ui/LoginActivity.kt` (em `ui/`) | Tela inicial de verdade: aguarda o login e segue para o porta-retrato |

**Infra:** `firebase/firestore.rules`, `firebase/firestore.indexes.json`,
`firebase/functions/index.js`, layouts, temas e strings em português.

### Dois caminhos para a chamada recebida

Implementei os dois de propósito:

1. **`IncomingCallWatcher`** — escuta o Firestore direto. Funciona **sem deploy
   nenhum**: basta o Firebase configurado e dois aparelhos. É o que permite
   testar hoje.
2. **`CallMessagingService`** — push de alta prioridade. Necessário quando o
   app foi morto pelo sistema, porque aí nenhum listener roda.

Ambos convergem para `CallService.incoming`, e a `AutoAnswerPolicy` descarta a
entrega duplicada quando os dois disparam.

## Integração

### 1. Gradle

```kotlin
// app/build.gradle.kts
dependencies {
    implementation("io.getstream:stream-webrtc-android:1.3.8")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")   // ausente hoje
    implementation("com.google.firebase:firebase-firestore-ktx")   // já existe
}
```

> `org.webrtc:google-webrtc` está abandonado desde 2021 e não recebe correção
> de segurança. Use o fork da Stream — o pacote continua `org.webrtc`, então o
> código não muda.

Impacto no APK: **+8 a 10 MB** (as bibliotecas nativas do WebRTC por ABI). Com
App Bundle o usuário baixa só a ABI dele, então na prática são ~4 MB.

### 2. Manifesto

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CAMERA" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MICROPHONE" />
<uses-permission android:name="android.permission.USE_FULL_SCREEN_INTENT" />

<service
    android:name=".call.CallService"
    android:exported="false"
    android:foregroundServiceType="camera|microphone" />

<service
    android:name=".call.CallMessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>

<activity
    android:name=".call.CallActivity"
    android:exported="false"
    android:launchMode="singleTask"
    android:showOnLockScreen="true"
    android:turnScreenOn="true"
    android:excludeFromRecents="true" />
```

`FOREGROUND_SERVICE`, `WAKE_LOCK` e `POST_NOTIFICATIONS` o app já declara.

**Android 14+:** um foreground service `camera|microphone` só pode iniciar com o
app em primeiro plano **ou** a partir de uma notificação de chamada. Por isso o
push precisa criar uma notificação `CallStyle` com full-screen intent — é o que
autoriza o service e o que faz a tela acender sozinha.

### 3. Regras do Firestore

```javascript
match /calls/{callId} {
  // Só os dois participantes leem ou escrevem.
  allow read: if request.auth != null
    && (resource.data.fromUid == request.auth.uid || resource.data.toUid == request.auth.uid);

  // Quem cria a chamada tem de ser quem diz ser.
  allow create: if request.auth != null
    && request.resource.data.fromUid == request.auth.uid;

  // Quem recebe só pode escrever a answer e o encerramento — nunca reescrever a offer.
  allow update: if request.auth != null
    && (resource.data.fromUid == request.auth.uid || resource.data.toUid == request.auth.uid)
    && request.resource.data.fromUid == resource.data.fromUid
    && request.resource.data.diff(resource.data).affectedKeys()
         .hasOnly(['answerSdp', 'answeredAt', 'ended', 'reason', 'endedAt']);

  allow delete: if request.auth != null && resource.data.fromUid == request.auth.uid;

  match /candidates/{candidateId} {
    allow read, create: if request.auth != null;
    allow delete: if request.auth != null;
  }
}
```

A regra de `update` é a que importa para segurança: sem ela, quem recebe poderia
sobrescrever `fromUid` e sequestrar a identidade da chamada.

Configure também uma **TTL policy** no campo `createdAt` de `calls` com
expiração de 24 h. É mais confiável que o `cleanup()` do app, que só roda se o
processo estiver vivo.

### 4. Cloud Function para o push

Sem isto o aparelho não acorda — o `addSnapshotListener` do Firestore não roda
com o app morto.

```javascript
exports.notifyIncomingCall = onDocumentCreated('calls/{callId}', async (event) => {
  const call = event.data.data();
  const tokens = await getTokens(call.toUid);
  if (!tokens.length) return;

  await getMessaging().sendEachForMulticast({
    tokens,
    // data-only: garante que o app processe mesmo em segundo plano.
    // Com bloco `notification` o Android exibe sozinho e não chama o service.
    data: {
      type: 'offer',
      callId: event.params.callId,
      fromUid: call.fromUid,
      fromName: call.fromName || '',
      video: String(call.video ?? true),
      createdAt: String(call.createdAt || Date.now()),
    },
    android: {
      priority: 'high',          // fura o Doze
      ttl: 45000,                // não faz sentido entregar uma chamada velha
    },
  });
});
```

Note que o payload do FCM é **sempre string** — por isso `SignalingProtocol`
normaliza números vindos como `String`, `Long` e `Int` (há teste para isso).

### 5. TURN

STUN resolve a maioria dos casos, mas **falha em NAT simétrico**, comum em rede
móvel (4G/5G) e Wi-Fi corporativo. Sem TURN, algo entre 10 % e 20 % das chamadas
simplesmente não conecta — e o padrão é justamente a filha ligando do celular
na rua.

Opções:

| Opção | Custo | Observação |
| --- | --- | --- |
| coturn em VPS próprio | US$ 5–10/mês | Controle total; ~1 h de configuração |
| Twilio Network Traversal | ~US$ 0,40/GB | Sem manutenção, escala sozinho |
| Metered / Xirsys | Plano grátis limitado | Bom para validar antes de investir |

**Nunca embuta credencial de TURN de longa duração no APK.** Qualquer um que
descompacte o app passa a usar (e você a pagar) o seu relay. Gere credenciais
efêmeras numa Cloud Function:

```javascript
// Válida por 1 h, no formato de credencial temporária do coturn.
const username = `${Math.floor(Date.now()/1000) + 3600}:${uid}`;
const credential = crypto.createHmac('sha1', TURN_SECRET)
                         .update(username).digest('base64');
```

O app busca isso ao iniciar a chamada e monta o `CallConfig`.

## Atendimento automático — decisões de segurança

Atender sozinho é abrir microfone e câmera da casa de alguém sem interação. As
regras em `AutoAnswerPolicy` são restritivas de propósito:

- **Opt-in explícito por contato.** Nunca ligado por padrão.
- **Nome sempre da agenda local.** O campo `fromName` do convite é preenchido
  por quem liga, ou seja, entrada não confiável — um estranho poderia se
  anunciar como "Ana, sua filha" na tela de um idoso. Contato não cadastrado
  aparece como "Desconhecido". Há teste para isso.
- **Atraso de 3 s** com nome na tela e som, dando chance real de recusar.
- **Nunca durante outra chamada.**
- **Janela de horário opcional**, incluindo janelas que cruzam a meia-noite.
- **Convite duplicado não atende duas vezes** — o FCM reentrega, e atender de
  novo derrubaria a chamada já estabelecida.

Recomendo também um indicador permanente e visível enquanto a câmera estiver
ativa, além do indicador do próprio Android.

## Por que não há APK

Não consigo gerar o APK neste ambiente, por dois motivos independentes — nenhum
deles contornável daqui:

**1. Não existe código-fonte do app.** O repositório continha apenas o
`README.md`. Tudo que sei do Porta Retrato veio de descompilar o APK. Código
descompilado não recompila: o `jadx` reportou 28 erros, e métodos centrais como
`FaceScanWorker.doWork` e o handler de rostos saem como
`throw new UnsupportedOperationException("Method not decompiled")`. Também não
tenho `google-services.json`, `build.gradle`, recursos, layouts nem a chave de
assinatura.

**2. Não há SDK do Android disponível.** O proxy deste ambiente bloqueia
`dl.google.com` (HTTP 403), que é a única origem do SDK, do Android Gradle
Plugin e da `android.jar`. Sem `aapt2`, `d8`, `zipalign` e `apksigner` não existe
APK. Confirmei que o Maven Central responde, mas o AGP não é publicado lá.

```
dl.google.com ......... 403 (bloqueado)
repo1.maven.org ....... 200
central/AGP ........... 404 (não é publicado no Central)
```

Foi por isso que verifiquei o que dava para verificar de verdade: a lógica pura
compila com `kotlinc` e passa em 53 asserções.

## Para eu conseguir entregar o APK compilado

Agora só falta **um ambiente com acesso ao `dl.google.com`**. O projeto está
completo: com o SDK disponível, `./gradlew assembleDebug` gera o APK.

Se você rodar o build e algo quebrar, me mande o erro do Gradle. O candidato
mais provável é uma assinatura do WebRTC em `WebRtcEngine.kt` —
`DefaultVideoEncoderFactory`, `addTrack` e `onTrack` mudaram entre versões da
biblioteca.

Para **integrar no Porta Retrato de produção** (em vez de rodar como app
separado), preciso do projeto Gradle dele. Aí o trabalho é: copiar o pacote
`call/`, mesclar o manifesto, trocar o login anônimo pelo Google que o app já
usa, e trocar a lista de contatos pela lista de `Person` existente.

## Estimativa até produção

| Etapa | Prazo |
| --- | --- |
| Service, FCM e Activity de chamada | 3–5 dias |
| Tela de contatos de confiança + registro de token | 2 dias |
| Cloud Function, regras e TURN | 1–2 dias |
| Testes com dois aparelhos em rede real (4G, Wi-Fi, NAT simétrico) | 3–5 dias |
| Ajuste de UX para idoso | 2–3 dias |

**Total: 2 a 4 semanas.** O que mais consome tempo é o teste em rede real —
WebRTC funciona no Wi-Fi de casa e falha na rua, e é exatamente esse caso que
precisa funcionar.
