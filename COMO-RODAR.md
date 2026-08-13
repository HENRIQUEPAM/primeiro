# Como compilar e testar

Projeto Android completo de chamada de vídeo entre dois aparelhos, com
atendimento automático para contatos de confiança.

**Não incluo o APK.** O SDK do Android não é acessível no ambiente onde este
código foi escrito (`dl.google.com` bloqueado pelo proxy, HTTP 403), então não
houve como rodar `aapt2`, `d8` nem `apksigner`. O projeto está completo e
validado estaticamente; o build roda na sua máquina.

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

O projeto precisa de um projeto Firebase. Se você já tem o do Porta Retrato,
use o mesmo.

1. No [console](https://console.firebase.google.com), adicione um app Android
   com o package **`com.portaretrato.chamadas`**.

   > É diferente do `com.portaretrato.app` de produção **de propósito**: assim
   > este app instala lado a lado com o Porta Retrato no aparelho de teste, sem
   > substituí-lo.

2. Baixe o `google-services.json` e coloque em **`app/google-services.json`**.
   (Está no `.gitignore` — não versione.)

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

### Testar o atendimento automático

No aparelho A (o "porta-retrato"):

1. **Adicionar contato** → nome, o código do aparelho B, e marque
   **Atender automaticamente**.
2. Ligue de B para A.
3. A tela de A acende sozinha, mostra o nome, faz a contagem regressiva de 3 s
   e atende — sem ninguém tocar em nada. O botão **Recusar** fica disponível
   durante a contagem.

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
| Sintaxe Kotlin de todos os fontes | ✅ 0 erros de parse |
| XML bem formado (manifesto, layouts, valores) | ✅ |
| Referências `@string`/`@color`/`@drawable`/`@layout` resolvem | ✅ |
| Referências `R.*` no Kotlin resolvem | ✅ |
| Classes do manifesto existem em disco | ✅ |
| IDs de ViewBinding existem nos layouts | ✅ |
| Aliases `libs.*` existem no version catalog | ✅ |
| Sintaxe da Cloud Function | ✅ `node --check` |

```bash
python3 tools/verification/validate_project.py     # 6 verificações do projeto
```

O que **não** foi verificado e você deve esperar encontrar:

- **Compilação real contra as bibliotecas.** Sem SDK, os 1063 erros do
  `kotlinc` são todos `unresolved reference` a `android.*`, `org.webrtc.*`,
  `com.google.*` e `kotlinx.*` — esperados. Zero erros de sintaxe, mas isso
  **não garante** que as assinaturas do WebRTC batem com a versão 1.3.8.
  `WebRtcEngine.kt` é o arquivo com maior chance de precisar de ajuste:
  `DefaultVideoEncoderFactory`, `addTrack` e `onTrack` mudaram entre versões.
- **Comportamento em aparelho.** Nada rodou em Android.
- **Rede real.** WebRTC funciona no Wi-Fi de casa e falha na rua; é o teste que
  mais consome tempo e o único que importa de verdade.

Se o build quebrar, o mais provável é uma assinatura do WebRTC. Me mande o erro
do Gradle que eu corrijo.
