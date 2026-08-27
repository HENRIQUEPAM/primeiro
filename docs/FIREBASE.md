# Ligar a chamada do próprio app

Passo a passo para sair de "os botões de WhatsApp e telefone funcionam, o do app
não" para "a família liga e o porta-retrato toca".

São **quatro etapas**. As três primeiras são de graça e levam uns 20 minutos.
A quarta (TURN) é a única que custa dinheiro, e é a que decide se a chamada
funciona fora do Wi-Fi de casa.

> **Você não precisa da chave Admin SDK para nada disto.** Aquele arquivo
> `...firebase-adminsdk-....json` serve para um servidor seu falar com o
> Firebase ignorando as regras de segurança. O app usa outro arquivo, o
> `google-services.json`, que é público por natureza — ele vai dentro do APK e
> qualquer um consegue extrair. Segurança de verdade vem das regras do Firestore
> (`firebase/firestore.rules`), não do sigilo desse arquivo.

---

## Etapa 1 — Registrar o app Android no projeto

O projeto `porta-retrato-1fb3c` já existe. Falta registrar **este** app dentro
dele, porque o `applicationId` é diferente do app de produção:

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

1. Menu lateral → **Criação** (ou **Build**) → **Authentication**
2. Se for a primeira vez: **Vamos começar**
3. Aba **Sign-in method** (Método de login)
4. Na lista de provedores, **Anônimo** → clique → chave **Ativar** → **Salvar**

Por que anônimo: o porta-retrato não deve pedir e-mail e senha a uma pessoa
idosa. A identidade do aparelho é a chave ECDSA no Android Keystore
(`call/DeviceIdentityManager.kt`), que é bem mais forte do que uma senha que
alguém anotaria num papel colado no aparelho. O login anônimo serve só para o
Firestore saber que a requisição vem de *algum* aparelho autenticado.

---

## Etapa 3 — Publicar as regras e os índices do Firestore

O repositório já traz as regras escritas. Sem publicá-las, ou o banco fica
aberto para qualquer um, ou fechado para todos — nos dois casos, errado.

1. Menu lateral → **Criação** → **Firestore Database** → **Criar banco de
   dados** (se ainda não existir)
2. Modo: **produção** (as regras do repositório substituem tudo em seguida)
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

Se preferir sem instalar nada: aba **Regras** no console, cole o conteúdo de
`firebase/firestore.rules`, **Publicar**. Os índices de
`firebase/firestore.indexes.json` também podem ser criados à mão, mas é bem mais
trabalhoso — o Firestore avisa no log qual índice falta quando uma consulta
falha.

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

1. `github.com/HENRIQUEPAM/primeiro` → **Settings** (do repositório, não da sua
   conta) → **Secrets and variables** → **Actions**
2. **New repository secret**
3. Nome: `GOOGLE_SERVICES_JSON` — exatamente assim, maiúsculas e sublinhados
4. Valor: **o conteúdo inteiro** do arquivo baixado na Etapa 1. Abra num editor
   de texto, selecione tudo, cole. Não é o caminho do arquivo, é o texto dele.
5. **Add secret**

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
