# Alinhamento com a Documentação Técnica Consolidada v3.1

O documento de hardware v3.1 especifica o módulo de chamada em detalhe
(Seções 7, 8, 9 e 10). O que eu tinha construído antes de vê-lo divergia dele
em pontos importantes. Este arquivo registra o que foi realinhado, o que ainda
falta, e **um conflito de produto que preciso que você decida**.

---

## ⚠️ Primeiro: a chave que você enviou

O arquivo `portaretrato-...firebase-adminsdk-...json` é a **chave privada da
conta de serviço Admin SDK**, não o `google-services.json`.

Ela dá acesso total ao projeto `porta-retrato-1fb3c`: ler e apagar todo o
Firestore e o Storage **ignorando as regras de segurança**, e emitir token de
login de qualquer usuário. Ela trafegou por um chat.

**Decisão do dono do projeto: manter a chave, guardada.** Registrado aqui
porque a exposição não desaparece com o tempo — quem for auditar isto depois
precisa saber que foi uma escolha consciente, não um esquecimento. Se um dia
mudar de ideia: Firebase Console → Configurações do projeto → Contas de
serviço → Gerenciar chaves → apagar
`d73eda4ccf11039a2e103d2881eb56ee6ebfa09f`. Revogar não quebra o app — o APK
não usa essa chave.

Não usei essa chave para nada e ela **não está no repositório**. Verificado, não
suposto: `git grep 'BEGIN PRIVATE KEY'` em todas as revisões não encontra nada,
e nenhum arquivo `*adminsdk*`, `*serviceaccount*` ou `*.pem` jamais foi
adicionado ao histórico.

O que o app precisa é outro arquivo, o `google-services.json` — esse é seguro e
vai dentro do APK de qualquer forma. O passo a passo está em
[`docs/FIREBASE.md`](FIREBASE.md).

O que o app precisa é o `google-services.json` (Configurações do projeto → aba
Geral → seus apps → Android). Esse é seguro: vai dentro do APK de qualquer
forma.

---

## ✅ Decidido: atendimento automático fica DESLIGADO

Eu propus e implementei **atendimento automático** para contatos de confiança —
o porta-retrato atende sozinho após 3 s, sem ninguém tocar em nada. Argumentei
que era o que justificava construir chamada própria em vez de continuar
delegando ao WhatsApp.

**A especificação v3.1 não tem esse recurso.** A Seção 7.4 descreve
campainha + ação "Atender", com "botão único de encerrar, sem mute na v1".

Isso não é detalhe: atender sozinho abre câmera e microfone da casa de uma
pessoa idosa sem interação humana. É a decisão de privacidade mais forte do
produto inteiro, e não era minha para tomar.

**Decisão do dono do projeto: manter desligado.**

O que mudou no código para isso valer de verdade:

Antes, "desligado" era uma *consequência*: nenhuma tela chamava
`TrustedContactsStore.setAutoAnswer`, então nada gravava `true`. Frágil — uma
edição futura na tela de contatos religaria o recurso sem ninguém decidir nada.

Agora é um *fato único e auditável*: `AutoAnswerPolicy.FEATURE_ENABLED = false`,
consultado no topo de `decide`, **antes** de olhar o contato. Com ele em
`false`, `decide` devolve `Ring` para qualquer entrada — inclusive para um
contato com `autoAnswerEnabled = true` gravado em disco por uma versão futura
ou por um arquivo editado à mão.

É o mesmo desenho do `CameraAccessPolicy`, e é verificado do mesmo jeito: a
suíte varre **288 combinações** (contato marcado / não marcado / ausente ×
quatro janelas de horário × 24 horas do dia) e confirma que nenhuma produz
`Answer`.

As proteções que **não** são do recurso continuam ativas acima da chave —
convite duplicado do FCM e chamada já em andamento seguem sendo recusadas,
porque evitam duas telas de chamada e a derrubada de uma conversa em curso.

**Por que o código ficou em vez de ser apagado:** ele é a única razão técnica
para o porta-retrato ter chamada própria — nenhum app de terceiro (WhatsApp,
Meet, Telegram) pode ser atendido por outro aplicativo. Apagar hoje significaria
reescrever depois, e a lógica delicada (janela que cruza a meia-noite, convite
duplicado, nome vindo só da agenda local) se perderia junto. O KDoc de
`FEATURE_ENABLED` lista os três passos para religar, se um dia for o caso.

---

## O que foi realinhado

### 1. Schema de sinalização

**Antes:** `calls/{callId}` — coleção global, inventada por mim.
**Agora:** `/pairings/{pairingId}/callSessions/{sessionId}`, o canônico da
Seção 9.

A Seção 9 resolve explicitamente a contradição entre dois desenhos anteriores e
adota este por incluir lockout por tentativas, TTL de 2 min, gate bloqueante de
fingerprint dos dois lados, e `ownerUid` denormalizado. A subcoleção por par
também particiona a carga naturalmente, reduzindo contenção com o sync de fotos
(Seção 7.8).

Arquivo: `call/PairingProtocol.kt`.

### 2. Identidade do aparelho e assinatura de SDP

**Antes:** inexistente. Eu confiava só nas regras do Firestore.
**Agora:** par único **ECDSA P-256** no Android Keystore (StrongBox → TEE),
assinando cada offer/answer, verificado contra a chave **pinada no pareamento**.

A Seção 10 registra o limite que isso fecha: o canal de sinalização tem TLS,
Auth e Rules, mas não verificação de identidade forte por request. A assinatura
é a defesa real contra sinalização forjada.

Arquivos: `call/SdpSigner.kt` (puro, testado), `call/DeviceIdentityManager.kt`
(Keystore).

Também implementei o **"número de segurança"** do pareamento: 30 dígitos em 6
grupos de 5, derivados das duas chaves públicas ordenadas. Só dígitos, porque
quem vai comparar dois números em duas telas é uma pessoa idosa — hexadecimal
com letras seria bem pior. É simétrico: os dois aparelhos calculam o mesmo
número sem combinar quem é A e quem é B.

### 3. TURN com credencial efêmera

**Antes:** `CallConfig.stunOnly()` fixo, com um TODO.
**Agora:** `TurnCredentialsProvider` chama `issueTurnCredentials`, e as
credenciais **nunca são cacheadas entre sessões** (Seção 7.2 — credencial HMAC
expirada faz o ICE falhar em silêncio).

A Seção 8 é categórica: o CGNAT das operadoras residenciais brasileiras torna
TURN **obrigatório na prática**. Se a função falhar, o provider cai para STUN
em vez de impedir a chamada, e o `ICEConnectionState.FAILED` dispara o fallback
para WhatsApp.

### 4. Anti "chamada fantasma"

**Antes:** filtro de idade contra o relógio **local**.
**Agora:** validação de `createdAt` contra o **horário do servidor**, janela de
20 s, com tolerância de 2 s para desvio de relógio (Seção 7.4).

A regra do Firestore agora exige `createdAt == request.time`, ou seja,
`serverTimestamp()` obrigatório na criação — a validação do callee não pode
depender do relógio de quem liga.

### 5. Fallback WhatsApp preservado

A Seção 7.4 diz que "Chamar Porta-Retrato" é o **quarto** botão, ao lado de
Ligar / Chat / Vídeo WhatsApp, e **nunca substitui** o fallback.

`call/WhatsAppFallback.kt` reimplementa o caminho do `SlideshowActivity` v2.9
com duas correções:

- **Normalização E.164.** O código atual só filtrava dígitos: "11 99999-9999"
  virava `wa.me/11999999999`, sem DDI, que não resolve. Um teste que escrevi
  pegou também um bug **meu** na primeira versão — eu descartava o `+` ao
  filtrar, e `+1 415 555 2671` virava `5514155552671`. O `+` é o único sinal
  confiável de que o número já está completo.
- **Cursores sem vazamento** no lookup do mimetype de videochamada.

### 6. `call_in_progress` para o firmware

`call/CallStateProvider.kt` publica `content://com.portaretrato.callstate/status`
(`IDLE`/`RINGING`/`IN_CALL`), somente leitura, protegido por permissão
`signature`.

**Fonte única**, como a Seção 7.5 exige: o mesmo `CallState` que gateia o
WorkManager alimenta o provider. Firmware e agendamento da varredura nunca
podem divergir sobre se há chamada em andamento.

### 7. Cloud Functions e regras

Reescritas para o schema canônico:

| Função | Papel |
| --- | --- |
| `onIncomingCall` | FCM data-only alta prioridade, TTL 45 s |
| `issueTurnCredentials` | HMAC efêmero, App Check obrigatório, segredo fora do APK |
| `markMissedCalls` | Scheduler 1×/min, marca `MISSED` após 45 s |
| `sweepStaleCallSessions` | Limpeza de custo (não é mecanismo de missed call) |

As regras agora implementam o gate de fingerprint (transição para `ACTIVE` só
com os dois booleanos `true`), imutabilidade de `publicKeyP256`, proibição de
`list` em `pairingRequests`, e `fcmToken` em subcoleção sem leitura para
cliente nenhum.

---

## O que ainda falta

Itens da spec que **não** implementei, com o motivo:

| Item | Seção | Por quê |
| --- | --- | --- |
| Fluxo de pareamento (UI + transação) | 9 | Precisa da tela e do fluxo de onboarding do app real |
| Presença via RTDB com `onDisconnect()` | 8 | Precisa do `PersonRepository` para saber quem observar |
| Prioridade sobre `FaceScanWorker` | 7.3 | Precisa do `FaceScanWorker` real para cancelar |
| Broadcast `POWER_MODE_CHANGED` | 7.5 | Precisa do firmware do outro lado para testar |
| Cifragem seletiva em `Converters.kt` | 7.6b | Precisa do `Converters.kt` real |
| `CallSessionLogEntity` + migrations | 7.7 | Precisa do `AppDatabase` real |
| `Person.linkedDeviceId` | 7.1 | Precisa da entidade `Person` real |
| Módulo Gradle isolado `:call` | 7.1 | Faz sentido só dentro do projeto multi-módulo real |

Todos travam na mesma coisa.

---

## O bloqueio, de novo

**Não tenho o código-fonte do Porta Retrato.** Verifiquei sua conta do GitHub:
existem `primeiro` (este repositório), `html5`, `Olá-Mundo`, `html-css`,
`desktop-tutorial`, `UI-DESIGN` e `C`. Nenhum é o app.

Tudo que sei do app veio de descompilar o APK v2.9 — e o documento fala de
v2.11, ou seja, o que analisei já está duas versões atrás. Código descompilado
não recompila: o `jadx` deu 28 erros e métodos centrais saem como
`UnsupportedOperationException("Method not decompiled")`.

Sem o fonte, "unir os dois aplicativos" não é possível. O que existe é:

- **Porta Retrato v2.11** — o app de vocês, com fotos e reconhecimento
- **portaretrato-chamadas** — o APK que compilei, só com a chamada
- **este módulo `call/`** — alinhado à spec, pronto para ser colado no projeto

Para unir, preciso de **um** destes:

1. O repositório Git do app (me diga o nome que eu anexo à sessão)
2. Um zip do projeto Gradle
3. Se o fonte não existir mais, me diga — aí a conversa é reescrever o app do
   zero, que é projeto de semanas, não de dias

Com o fonte, a integração é mecânica: copiar `call/`, mesclar o manifesto,
trocar o login anônimo pelo Google que o app já tem, ligar o botão no
`SlideshowActivity`, e substituir o `FaceEmbeddingHelper` antigo pelo
`recognition/` novo. Estimo 3 a 5 dias.
