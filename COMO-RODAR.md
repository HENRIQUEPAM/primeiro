# Como compilar e testar

Porta-retrato digital com reconhecimento facial, chamada de vídeo entre dois
aparelhos e chamada pelo WhatsApp.

**Você não precisa compilar nada.** O APK sai pronto do GitHub Actions a cada
push e é publicado numa Release pública, que baixa direto no navegador do
celular:

**https://github.com/HENRIQUEPAM/primeiro/releases/download/apk-teste/porta-retrato.apk**

O endereço não muda entre versões — sempre aponta para a mais recente.

As instruções abaixo servem para quem quiser compilar na própria máquina. O SDK
do Android não é acessível no ambiente onde este código foi escrito
(`dl.google.com` bloqueado pelo proxy, HTTP 403), então o build local nunca foi
exercitado daqui — quem valida é o CI.

---

## 1. Pré-requisitos

- **JDK 17** (`java -version` deve mostrar 17.x)
- **Android SDK** com plataforma 35 — instale pelo Android Studio, ou:
  ```bash
  sdkmanager "platforms;android-35" "build-tools;35.0.0"
  ```
- Dois aparelhos Android 8.0+ **com câmera**, na mesma conta Firebase

Crie `local.properties` na raiz apontando para o SDK:

```properties
sdk.dir=/caminho/para/Android/Sdk
```

## 2. Firebase

> **Passo a passo completo, com o que mudou no console e o que custa dinheiro:
> [`docs/FIREBASE.md`](docs/FIREBASE.md).** O resumo abaixo serve para quem já
> conhece o console.

O projeto precisa de um projeto Firebase. Se você já tem o do Porta Retrato,
use o mesmo.

1. No [console](https://console.firebase.google.com), adicione um app Android
   com o package **`com.portaretrato.chamadas`**.

   > É diferente do `com.portaretrato.app` de produção **de propósito**: assim
   > este app instala lado a lado com o Porta Retrato no aparelho de teste, sem
   > substituí-lo.

2. O `google-services.json` do projeto `porta-retrato-1fb3c` **já está
   versionado** em `app/google-services.json` — nada a fazer. Ele não é segredo:
   vai dentro de todo APK e qualquer um extrai. Para apontar para OUTRO projeto
   Firebase, cadastre o segredo `GOOGLE_SERVICES_JSON`, que tem prioridade.

3. **Authentication → Sign-in method → ative "Anônimo".** Sem isso o app não
   consegue entrar e a tela mostra "Falha ao entrar".

4. **Firestore Database → criar banco** (modo produção).

5. Publique as regras e o índice:
   ```bash
   cd firebase
   firebase deploy --only firestore:rules,firestore:indexes
   ```

   Sem o índice, a consulta do `IncomingCallWatcher` falha. Se esquecer, o
   Logcat traz um link que cria o índice com um clique.

## 3. Compilar

```bash
./gradlew assembleDebug
```

O APK sai em `app/build/outputs/apk/debug/app-debug.apk`.

```bash
./gradlew installDebug          # instala no aparelho conectado
```

> Primeiro build baixa Gradle 8.11.1, o AGP e as bibliotecas — leva alguns
> minutos e ~1 GB.

## 4. Testar com dois aparelhos

1. Instale nos dois. Conceda **câmera, microfone e notificações**.
2. Cada um mostra **Meu código** na tela inicial (o `uid` anônimo).
3. No aparelho A, toque em **Copiar código** e mande para você mesmo.
4. No aparelho B, cole o código de A em "Código de quem você quer chamar" e
   toque em **Ligar**.
5. O aparelho A toca. Toque em **Atender**.

### Atendimento automático: desligado

O recurso existe no código, testado, mas está **desligado por decisão de
produto** (`AutoAnswerPolicy.FEATURE_ENABLED = false`). Toda chamada toca e
espera alguém atender, como a Seção 7.4 da especificação v3.1 determina.

Não adianta procurar a opção na tela: ela não existe, e um contato marcado como
confiável em disco continua sem efeito. A suíte varre 288 combinações
confirmando isso. Os três passos para religar estão no KDoc de
`FEATURE_ENABLED`; o registro da decisão está em
[`docs/ALINHAMENTO-SPEC-v3.1.md`](docs/ALINHAMENTO-SPEC-v3.1.md).

## 5. Chamada com o app fechado (opcional)

Os passos acima funcionam com o app **aberto**: o `IncomingCallWatcher` escuta
o Firestore direto. Com o app morto pelo sistema, nenhum listener roda — aí é
preciso o push.

```bash
cd firebase/functions && npm install
cd .. && firebase deploy --only functions
```

Requer o plano **Blaze** (pay-as-you-go). O uso deste projeto fica bem dentro
da cota gratuita.

## 6. TURN (necessário em rede móvel)

O projeto vem configurado só com **STUN** (`CallConfig.stunOnly()`), que
funciona no Wi-Fi de casa mas **falha em NAT simétrico** — comum em 4G/5G e
Wi-Fi corporativo. Na prática, algo entre 10 % e 20 % das chamadas não conecta,
e justamente o caso mais importante: a filha ligando do celular na rua.

Para produção, configure um TURN e troque em `CallService.ensureController()`:

```kotlin
config = CallConfig(
    iceServers = listOf(
        IceServerConfig(listOf("stun:stun.l.google.com:19302")),
        IceServerConfig(
            urls = listOf("turn:seu-servidor:3478?transport=udp"),
            username = usuarioEfemero,
            credential = senhaEfemera,
        ),
    ),
)
```

**Não embuta credencial fixa no APK** — quem descompactar passa a usar (e você
a pagar) o seu relay. Gere credenciais efêmeras numa Cloud Function; o método
está em [`docs/CHAMADAS.md`](docs/CHAMADAS.md).

---

## Estado da validação

O que **foi** verificado:

| Verificação | Resultado |
| --- | --- |
| Lógica de chamada (máquina de estados, protocolo, auto-atendimento) | ✅ 53 asserções em JVM |
| Pipeline de reconhecimento facial | ✅ 25 asserções em JVM |
| Índice de pessoas (nomeação em cascata, homônimos, persistência) | ✅ 76 asserções em JVM |
| Slideshow (ordem, embaralhamento, acervo mudando) | ✅ 39 asserções em JVM |
| Política de câmera, auditoria e AES-GCM | ✅ 50 asserções em JVM |
| Modelo `mobilefacenet.tflite` no APK | ✅ entrada `[1,112,112,3]`, saída `[1,192]`, ambos FLOAT32 |
| Tipagem de `recognition/`, `people/` e `photo/` contra stubs | ✅ 0 erros |
| Sintaxe Kotlin de todos os fontes | ✅ 0 erros de parse |
| XML bem formado (manifesto, layouts, valores) | ✅ |
| Referências `@string`/`@color`/`@drawable`/`@layout` resolvem | ✅ |
| Referências `R.*` no Kotlin resolvem | ✅ |
| Classes do manifesto existem em disco | ✅ |
| IDs de ViewBinding existem nos layouts | ✅ |
| Aliases `libs.*` existem no version catalog | ✅ |
| Sintaxe da Cloud Function | ✅ `node --check` |

```bash
KOTLINC=/caminho/para/bin/kotlinc tools/verification/run.sh
```

Roda as seis suítes e as 7 verificações estáticas num comando só.

O que **não** foi verificado e você deve esperar encontrar:

- **Comportamento em aparelho.** Nada rodou em Android. O CI compila contra as
  bibliotecas de verdade e o APK está assinado e instalável, mas compilar não é
  funcionar. `WebRtcEngine.kt` é o arquivo com maior chance de surpresa em
  execução.
- **Reconhecimento com fotos de verdade.** Os 76 testes do índice de pessoas
  usam embeddings sintéticos com similaridade controlada — eles verificam a
  *lógica* de decisão, não a precisão do modelo. Os limiares
  (`AUTO_LINK_THRESHOLD = 0.62`, `AUTO_LINK_MARGIN = 0.06`) são um ponto de
  partida razoável, e a calibração com o acervo real está descrita na seção
  "Como medir" de [`docs/ANALISE-E-PLANO.md`](docs/ANALISE-E-PLANO.md). Espere
  precisar ajustá-los depois de ver o app errar com fotos da sua família.
- **Rede real.** WebRTC funciona no Wi-Fi de casa e falha na rua; é o teste que
  mais consome tempo e o único que importa de verdade.

Se o build quebrar, o mais provável é uma assinatura do WebRTC. Me mande o erro
do Gradle que eu corrijo.
