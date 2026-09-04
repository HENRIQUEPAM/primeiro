# Ligar a chamada do próprio app

Passo a passo para sair de "os botões de WhatsApp e telefone funcionam, o do app
não" para "a família liga e o porta-retrato toca".

**Sobram duas coisas para você fazer: as Etapas 2 e 3.** As Etapas 1 e 5 já
estão prontas, e a 4 (TURN) só importa quando você for testar com um aparelho
fora de casa.

As duas que faltam são de graça e levam uns 10 minutos, tudo pelo navegador.

## Atalhos: links diretos

O console do Firebase esconde tudo em menus que mudam de lugar entre versões.
Como o projeto é o `porta-retrato-1fb3c`, estes links caem **exatamente** na
tela certa, sem navegar:

| Etapa | Link direto |
| --- | --- |
| 1. Apps registrados | https://console.firebase.google.com/project/porta-retrato-1fb3c/settings/general |
| 2. Ligar login anônimo | https://console.firebase.google.com/project/porta-retrato-1fb3c/authentication/providers |
| 3. Criar o Firestore | https://console.firebase.google.com/project/porta-retrato-1fb3c/firestore |
| 3. Colar as regras | https://console.firebase.google.com/project/porta-retrato-1fb3c/firestore/rules |
| 3. Criar o índice | https://console.firebase.google.com/project/porta-retrato-1fb3c/firestore/indexes |
| 5. Cadastrar o segredo | *não é mais necessário — ver Etapa 5* |

> **As Etapas 1 e 5 já estão feitas.** O app `com.portaretrato.chamadas` está
> registrado no projeto, e o `google-services.json` real está versionado em
> `app/google-services.json`. **Sobram as Etapas 2 e 3.**

> **Você não precisa da chave Admin SDK para nada disto.** Aquele arquivo
> `...firebase-adminsdk-....json` serve para um servidor seu falar com o
> Firebase ignorando as regras de segurança. O app usa outro arquivo, o
> `google-services.json`, que é público por natureza — ele vai dentro do APK e
> qualquer um consegue extrair. Segurança de verdade vem das regras do Firestore
> (`firebase/firestore.rules`), não do sigilo desse arquivo.

---

## Etapa 1 — Registrar o app Android no projeto ✅ FEITA

Conferido no `google-services.json` do projeto: os dois apps já estão
registrados, `com.portaretrato.app` (produção) e `com.portaretrato.chamadas`
(este). **Pule para a Etapa 2.** O passo abaixo fica registrado para o caso de
alguém precisar refazer.

O `applicationId` é diferente do app de produção:

```
com.portaretrato.chamadas
```

(É diferente de propósito: assim ele instala **lado a lado** com o Porta Retrato
v2.9 no mesmo aparelho de teste, em vez de substituí-lo.)

1. [console.firebase.google.com](https://console.firebase.google.com) → projeto
   **porta-retrato-1fb3c**
2. Ícone de engrenagem ⚙ ao lado de "Visão geral do projeto" →
   **Configurações do projeto**
3. Aba **Geral**, role até **Seus apps** → botão **Adicionar app** → ícone do
   Android
4. **Nome do pacote Android:** `com.portaretrato.chamadas`
   — exatamente assim, sem espaço, sem maiúscula. Se errar, o app compila e
   falha em silêncio no login.
5. Apelido: qualquer coisa (`Porta Retrato — chamadas`)
6. **Certificado SHA-1: deixe em branco.** Ele só é necessário para login com
   Google ou Dynamic Links, e não usamos nenhum dos dois. O login é anônimo.
7. **Registrar app** → **Baixar google-services.json**

Guarde esse arquivo. As etapas 2 e 3 usam ele.

---

## Etapa 2 — Ligar a autenticação anônima

Sem isto o app sobe, mostra "Falha ao entrar" e cai para WhatsApp/telefone.

**Abra direto:**
https://console.firebase.google.com/project/porta-retrato-1fb3c/authentication/providers

O que você vai ver, em ordem:

- Se for a primeira vez no Authentication, aparece uma tela de boas-vindas com o
  botão **Vamos começar** (ou **Get started**). Clique.
- Cai numa lista chamada **Provedores de login** (ou **Sign-in providers**), com
  Google, E-mail/senha, Telefone, Facebook e assim por diante.
- **Anônimo** costuma ficar no fim da lista, embaixo de um grupo separado. Se não
  achar, role até o fim — ele não está junto dos provedores populares.
- Clique em **Anônimo** → aparece uma chavinha **Ativar** (**Enable**) → ligue →
  **Salvar**.

Feito, a linha "Anônimo" passa a mostrar **Ativado** na lista.

Por que anônimo: o porta-retrato não deve pedir e-mail e senha a uma pessoa
idosa. A identidade do aparelho é a chave ECDSA no Android Keystore
(`call/DeviceIdentityManager.kt`), que é bem mais forte do que uma senha que
alguém anotaria num papel colado no aparelho. O login anônimo serve só para o
Firestore saber que a requisição vem de *algum* aparelho autenticado.

---

## Etapa 3 — Publicar as regras e os índices do Firestore

O repositório já traz as regras escritas. Sem publicá-las, ou o banco fica
aberto para qualquer um, ou fechado para todos — nos dois casos, errado.

**Abra direto:**
https://console.firebase.google.com/project/porta-retrato-1fb3c/firestore

1. Se o banco ainda não existe, o botão é **Criar banco de dados**.
2. Modo: **produção** (as regras do repositório substituem tudo em seguida).
3. Local: **`southamerica-east1`** (São Paulo). Escolha com cuidado — **o local
   não pode ser mudado depois**, e a latência da sinalização é o que faz a
   chamada demorar a começar.
4. Publique os arquivos do repositório:

```bash
npm install -g firebase-tools
firebase login
firebase use porta-retrato-1fb3c
firebase deploy --only firestore:rules,firestore:indexes
```

### Sem instalar nada (mais simples)

**Regras** — abra
https://console.firebase.google.com/project/porta-retrato-1fb3c/firestore/rules
, apague tudo o que estiver no editor, cole o conteúdo inteiro de
`firebase/firestore.rules` e clique em **Publicar**.

**Índice** — abra
https://console.firebase.google.com/project/porta-retrato-1fb3c/firestore/indexes
→ **Criar índice** e preencha:

| Campo | Valor |
| --- | --- |
| ID da coleção | `callSessions` |
| Escopo da consulta | **Grupo de coleções** (não "Coleção") |
| Campo 1 | `calleeDeviceId` — Crescente |
| Campo 2 | `state` — Crescente |
| Campo 3 | `createdAt` — **Decrescente** |

O escopo **Grupo de coleções** é o detalhe que costuma passar batido: as sessões
de chamada ficam em subcoleções de cada pareamento, e a consulta atravessa
todas. Com escopo "Coleção" o índice é criado, mas não serve.

Se preferir, dá para pular o índice agora: quando a consulta falhar, o Logcat
traz um link que cria exatamente este índice com um clique.

### As Cloud Functions são opcionais no começo

`firebase/functions/index.js` faz duas coisas: entrega o push que faz o outro
aparelho tocar, e emite as credenciais temporárias de TURN. Elas exigem o
**plano Blaze** (pagamento por uso). Para dois aparelhos numa família o custo
real fica praticamente em zero, mas o Blaze pede cartão cadastrado.

Sem elas você ainda consegue testar a chamada com **os dois aparelhos com o app
aberto**, porque a sinalização passa direto pelo Firestore. O que não funciona é
tocar com o app fechado.

---

## Etapa 4 — TURN (a etapa que custa dinheiro)

**Esta é a que decide se funciona fora de casa.**

As operadoras residenciais brasileiras usam CGNAT: vários assinantes atrás do
mesmo IP público. Nessa situação os dois aparelhos não conseguem se enxergar
diretamente, e um servidor TURN precisa repassar o áudio e o vídeo no meio.

Sem TURN o comportamento é traiçoeiro: **funciona no Wi-Fi de casa** (os dois na
mesma rede) e **falha na rua**. É por isso que dá para achar que está tudo certo
e descobrir o contrário no dia em que importa.

Duas saídas:

| Caminho | Custo aproximado | Observação |
| --- | --- | --- |
| **`coturn` num VPS pequeno** | R$ 25 a R$ 30/mês | Controle total; exige configurar e manter. Prefira um VPS no Brasil — cada milissegundo aqui é milissegundo de atraso na voz |
| **TURN gerenciado** | há planos com franquia gratuita mensal | Mais rápido de pôr de pé; confira a franquia, vídeo consome bem mais que áudio |

Servidores STUN públicos (o `stun.l.google.com` da vida) **não substituem
TURN**: STUN só descobre o seu IP, não repassa mídia.

Onde configurar: `call/TurnCredentialsProvider.kt` busca as credenciais na Cloud
Function `issueTurnCredentials`. As credenciais são de vida curta e **nunca são
cacheadas entre chamadas** de propósito — um HMAC vencido faz o ICE falhar em
silêncio, que é o pior modo de falha possível.

---

## Etapa 5 — Fazer o APK sair ligado ao Firebase ✅ FEITA

O `google-services.json` real está versionado em `app/google-services.json`, e o
build já o usa. **Nada a fazer.**

**Por que ele pode ficar no repositório:** esse arquivo não é segredo. Ele vai
dentro de todo APK, e qualquer pessoa que baixe o aplicativo consegue abrir e
ler o conteúdo. A Google documenta isso explicitamente. A segurança do projeto
vem das regras do Firestore (Etapa 3) e do login (Etapa 2) — é por isso que
aquelas duas etapas são as que realmente importam.

O que **não** pode ir para o repositório é a chave da conta de serviço
(`...firebase-adminsdk-....json`). Essa sim ignora todas as regras. Ela não está
aqui e não é usada por nada.

### Ordem de prioridade no build

O workflow resolve nesta ordem:

1. **Segredo `GOOGLE_SERVICES_JSON`**, se existir — serve para apontar o build
   para outro projeto Firebase sem mexer no repositório. Cadastre em
   https://github.com/HENRIQUEPAM/primeiro/settings/secrets/actions/new
2. **`app/google-services.json` versionado** — o caso normal hoje.
3. **Placeholder** — só para um fork sem Firebase nenhum compilar.

Em seguida o workflow confere que o arquivo contém `com.portaretrato.chamadas` e
**falha o build com mensagem clara** se não contiver. Sem essa checagem o
sintoma seria o app compilar normalmente e falhar em silêncio no login, que é
caro de diagnosticar.

### Uma proteção opcional, para depois

A chave de API dentro desse arquivo pode ser restringida para só funcionar a
partir deste aplicativo. Não é urgente e não muda nada do que está acima, mas
fecha a porta para alguém usar a chave em outro lugar:
https://console.cloud.google.com/apis/credentials?project=porta-retrato-1fb3c
→ clique na chave → **Restrições de aplicativo** → **Apps Android** → adicione o
pacote `com.portaretrato.chamadas` com a impressão digital SHA-1 do certificado
de assinatura.

Depois de cadastrar, dispare um build novo (qualquer commit serve, ou
**Actions** → **Build APK** → **Run workflow**) e reinstale o APK. O aviso
"Falha ao entrar" some, e um código aparece no rodapé da tela de contatos — é o
identificador do aparelho, usado para parear.

---

## Como saber se deu certo

Em ordem, porque cada passo depende do anterior:

| Sinal | O que significa |
| --- | --- |
| A faixa laranja "Falha ao entrar" sumiu | Etapas 1, 2 e 5 corretas |
| Um código aparece no rodapé da tela de contatos | O aparelho autenticou e tem identidade |
| Chamada entre dois aparelhos **no mesmo Wi-Fi** conecta | Etapa 3 correta |
| Chamada com **um aparelho no 4G** conecta | Etapa 4 correta — este é o teste que importa |
| O porta-retrato toca com o **app fechado** | Cloud Functions no ar |

Se travar em algum, o log do Gradle ou o `adb logcat` dizem qual. Me mande o
erro que eu leio.

---

## Como parear dois aparelhos, hoje

Existem dois protocolos de pareamento no código, e só um está de pé:

- `call/PairingProtocol.kt` é o desenho elaborado da Seção 9 — código de
  convite com TTL, confirmação de fingerprint dos dois lados, chave pública
  P-256. Está escrito e testado, mas **nenhuma tela o usa**. Fica aqui pronto
  para quando esse fluxo for construído.
- O que a chamada pelo app **realmente usa** hoje é mais simples: cada
  aparelho tem um "código" (o uid anônimo do Firebase Auth), mostrado num
  cartão no rodapé da tela de contatos ("Meu código", com botão para copiar).
  Para o aparelho A poder chamar o B pelo app, alguém abre "Adicionar
  contato" no aparelho A e cola o código do B no campo "Código do aparelho".
  A chamada então escreve a offer em `/calls/{callId}`, endereçada por esse
  código — ver `SignalingProtocol.kt`/`FirestoreSignaling.kt`.

**Isto só funciona publicando as regras certas.** Até esta versão,
`firebase/firestore.rules` só continha regras para o schema elaborado acima
(`/devices`, `/pairingRequests`, `/pairings`) — nada para `/calls`, que é o
que o app de fato escreve. Sem uma regra que permita, o Firestore recusa a
escrita da offer, `sendOffer()` só registra o erro no log (`Log.e`), e o
sintoma para quem usa o app é "toquei em Ligar e não aconteceu nada". As
regras já foram corrigidas no repositório — a Etapa 3 acima (publicar
`firebase/firestore.rules`) agora publica as duas gerações de schema, a que
está em uso e a que ainda não está.

### Resumindo o que era código e já foi corrigido

- Regras do Firestore sem cobertura para `/calls` (acima).
- `HomeActivity`/`SlideshowActivity` liam `pairedDeviceId`/`peerOnline`
  como constantes fixas (`null`/`false`), então o botão "Chamar pelo
  aparelho" ficava **sempre** indisponível, mesmo com um código digitado
  certo. Agora `pairedDeviceId` vem do código digitado no contato, e
  `peerOnline` é otimista — sem um sistema de presença de verdade (exigiria
  Realtime Database, não só Firestore), a chamada tenta e cai em "Ninguém
  atendeu" se o código não corresponder a ninguém, do mesmo jeito que ligar
  para um telefone desligado.
- Vincular um telefone a um rosto reconhecido (tela "Quem está nas fotos")
  sempre criava um contato novo, sintético, mesmo quando aquela pessoa já
  tinha um contato com código de aparelho cadastrado — a chamada pelo app
  nunca aparecia para quem foi reconhecido numa foto. Agora a busca é pelo
  telefone (`TrustedContactsStore.findByPhone`), então os dois caminhos
  (contato manual e rosto reconhecido) convergem no mesmo registro.

### O que ainda falta

Sendo honesto sobre o que resta:

- **Etapas 2 e 3 continuam manuais**, no console do Firebase — nenhuma delas
  é algo que dá para automatizar sem acesso à sua conta.
- **TURN (Etapa 4)** continua sem Cloud Function deployada — decisão sua, de
  custo e de infraestrutura. `TurnCredentialsProvider.kt` já existe e cai
  para STUN sozinho se a função não responder, então nada quebra por causa
  disso; simplesmente calls fora do Wi-Fi de casa não vão conectar até essa
  etapa acontecer.
- **O fluxo de pareamento com fingerprint da Seção 9** (`PairingProtocol.kt`)
  segue sem tela — o pareamento por código copiado e colado é mais simples e
  já funciona, mas não tem a confirmação de identidade dos dois lados que
  aquele desenho mais elaborado tinha em mente.
