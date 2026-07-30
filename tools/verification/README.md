# Suítes de verificação

Duas suítes, ambas em JVM pura:

- **`Verify.kt`** — pipeline de reconhecimento facial (25 asserções). Precisa
  dos stubs em `stubs/`.
- **`VerifyCall.kt`** — módulo de chamadas (53 asserções). **Não precisa de
  stub nenhum**: `CallModels`, `CallStateMachine`, `SignalingProtocol` e
  `AutoAnswerPolicy` são Kotlin puro, sem dependência de Android.

```bash
# Módulo de chamadas
$KOTLINC -nowarn -d /tmp/out \
    app/src/main/java/com/portaretrato/app/call/CallModels.kt \
    app/src/main/java/com/portaretrato/app/call/CallStateMachine.kt \
    app/src/main/java/com/portaretrato/app/call/SignalingProtocol.kt \
    app/src/main/java/com/portaretrato/app/call/AutoAnswerPolicy.kt \
    tools/verification/VerifyCall.kt
java -cp "/tmp/out:$(dirname $KOTLINC)/../lib/kotlin-stdlib.jar" VerifyCallKt
```

`WebRtcEngine.kt` e `FirestoreSignaling.kt` ficam de fora: dependem de
bibliotecas reais e não foram compilados. Ver `docs/CHAMADAS.md`.

---

# Suíte de verificação do pipeline de reconhecimento

Roda na JVM, **sem SDK do Android e sem emulador**. Serve para validar a parte
do pipeline que é matemática pura — alinhamento, matching, política de
protótipos, codec e cálculo de `inSampleSize` — antes de integrar ao app.

`stubs/` contém assinaturas mínimas de Android, ML Kit e TensorFlow Lite,
apenas para que o `kotlinc` consiga tipar os fontes de `recognition/`. Os stubs
**não são compilados no app** e não devem ir para `app/src/`.

## Como rodar

```bash
KOTLINC=/caminho/para/kotlinc
COROUTINES=/caminho/para/kotlinx-coroutines-core-jvm-1.8.1.jar

$KOTLINC -nowarn -classpath "$COROUTINES" -d /tmp/out \
    tools/verification/stubs/*.kt \
    app/src/main/java/com/portaretrato/app/recognition/*.kt \
    tools/verification/Verify.kt

java -cp "/tmp/out:$COROUTINES:$(dirname $KOTLINC)/../lib/kotlin-stdlib.jar" VerifyKt
```

## O que é coberto

| Bloco | Verifica |
| --- | --- |
| `[1]` Alinhamento | A transformação de similaridade leva os 5 landmarks aos pontos canônicos do ArcFace (erro < 0,01 px numa entrada exata); a reordenação por x evita a inversão de 180°; ruído de 3 px degrada suavemente |
| `[2]` Galeria | Vencedor correto; o vice vem sempre de **outra pessoa**, nunca de outro protótipo do vencedor; empate produz margem pequena; galeria vazia e dimensão errada devolvem `null` |
| `[3]` Protótipos | Quase-duplicata descartada; protótipo diverso aceito; limite de 8 respeitado |
| `[4]` `inSampleSize` | O cálculo novo nunca ultrapassa `maxDimension`; imprime lado a lado o consumo de memória do cálculo antigo (4× em fotos típicas de celular) |
| `[5]` Codec | Round-trip de 768 bytes; empacotamento de múltiplos protótipos; entradas inválidas rejeitadas |
| `[6]` Custo do matching | Benchmark com 200 pessoas × 8 protótipos |

## O que **não** é coberto

- Qualquer coisa que dependa de `Bitmap`, `Canvas` ou `Matrix` reais — os stubs
  são no-ops. `ArcFaceAligner.align`, `FaceQualityGate.evaluate` e
  `FaceEmbedder.embed` precisam de teste instrumentado em aparelho.
- Precisão do reconhecimento. Isso exige o conjunto rotulado descrito em
  "Como medir", em `docs/ANALISE-E-PLANO.md`.
