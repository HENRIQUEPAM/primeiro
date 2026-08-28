# Ligar a chamada do próprio app

Passo a passo para sair de "os botões de WhatsApp e telefone funcionam, o do app
não" para "a família liga e o porta-retrato toca".

São **cinco etapas**. As quatro primeiras são de graça e levam uns 20 minutos.
A de TURN é a única que custa dinheiro, e é a que decide se a chamada funciona
fora do Wi-Fi de casa.

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
| 5. Cadastrar o segredo | https://github.com/HENRIQUEPAM/primeiro/settings/secrets/actions/new |

> **A Etapa 1 já está feita.** O `google-services.json` do projeto mostra os dois
> apps registrados — `com.portaretrato.app` (produção) e
> `com.portaretrato.chamadas` (este). Não precisa refazer.

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

## Etapa 5 — Fazer o APK sair ligado ao Firebase

O APK é compilado pelo GitHub Actions. Ele precisa do `google-services.json`, e
esse arquivo **não vai para o repositório** (o `.gitignore` cobre). Entra como
segredo:

**Abra direto:**
https://github.com/HENRIQUEPAM/primeiro/settings/secrets/actions/new

Esse link já abre o formulário de segredo novo — não precisa achar
Settings → Secrets and variables → Actions. São só dois campos:

| Campo | O que pôr |
| --- | --- |
| **Name** | `GOOGLE_SERVICES_JSON` — exatamente assim, maiúsculas e sublinhados |
| **Secret** | **O conteúdo inteiro** do `google-services.json`. Abra o arquivo num editor de texto, `Ctrl+A`, `Ctrl+C`, cole aqui. É o texto do arquivo, não o caminho dele. |

Depois, **Add secret**.

Se a página abrir em branco ou pedir login de outra conta, é porque o botão
Settings só aparece para quem é dono do repositório — confira em qual conta do
GitHub você está.

O workflow já sabe o que fazer: com o segredo presente, ele escreve o arquivo
antes de compilar; sem o segredo, usa um `google-services.json` de exemplo, que
compila e instala mas nunca autentica.

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

## O que ainda falta em código

Sendo honesto sobre o estado: mesmo com tudo acima configurado, **falta o fluxo
de pareamento** — a tela onde os dois aparelhos trocam o código e pinam a chave
pública um do outro. `call/PairingProtocol.kt` tem o protocolo escrito e
testado, mas nenhuma tela o usa ainda, do mesmo jeito que o reconhecimento
facial ficou sem quem o chamasse até agora.

Ou seja: as etapas deste documento são necessárias, e ainda não são suficientes.
Elas tiram o bloqueio de infraestrutura; sobra o pareamento, que é código meu.
