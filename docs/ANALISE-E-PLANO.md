# Porta Retrato 2.9 — análise de engenharia reversa e plano de otimização

APK analisado: `PortaRetratov2.9.apk` · `com.portaretrato.app` · versão 2.9
minSdk 26 (Android 8.0) · targetSdk 34 · 9 arquivos DEX · 22,9 MB

---

## Antes de tudo: o escopo do pedido não bate com o app

O briefing pede otimizações de **CameraX**, captura de frames ao vivo, "o usuário
apenas olha para a câmera", seleção do melhor frame e cadastro em menos de 1
segundo diante da câmera.

**Este aplicativo não usa câmera.** Não há CameraX, Camera2, nem
`android.hardware.camera` no APK, e o manifesto sequer declara
`android.permission.CAMERA`. As permissões são: `INTERNET`, `ACCESS_NETWORK_STATE`,
`READ_CONTACTS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`,
`POST_NOTIFICATIONS`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`.

O que o app realmente é: um **porta-retrato digital**. Fotos são enviadas
(`HomeViewModel.uploadPhotos`), sincronizadas com o Firebase, varridas em
segundo plano por um `WorkManager` que detecta e reconhece rostos, e exibidas
num slideshow que oferece chamada de vídeo por WhatsApp para a pessoa
reconhecida (`SlideshowActivity.startVideoCall`, `WhatsAppContactHelper`).

"Cadastro de rosto", aqui, é o fluxo `ReviewFacesActivity`: o app mostra o
recorte de um rosto encontrado numa foto e o usuário digita o nome. Não há
sessão de captura ao vivo para acelerar.

**Como tratei isso.** Os objetivos de fundo — mais precisão, menos falso
positivo e falso negativo, cadastro rápido, menos CPU/bateria/memória,
experiência melhor para idosos — são todos válidos e aplicáveis. Traduzi cada
etapa do briefing para o pipeline que existe:

| Pedido original | Equivalente neste app |
| --- | --- |
| Escolher o melhor frame da câmera | Escolher o melhor **rosto entre as fotos** da pessoa (política de protótipos) |
| Descartar frame tremido / escuro / olho fechado | Descartar **rosto** tremido / escuro / olho fechado (gate de qualidade) |
| Cadastro em < 1 s diante da câmera | Salvar o nome em < 1 s (hoje trava no round-trip do Firestore) |
| Otimizar CameraX | Otimizar a varredura em lote: decodificação, detecção, inferência |
| Reconhecimento quase instantâneo | Reconhecimento no slideshow e varredura de acervo muito mais rápida |

Se o plano for **adicionar** uma tela de cadastro por câmera, isso é trabalho
novo (esforço alto) e está fora do que entreguei — mas as peças aqui
(`ArcFaceAligner`, `FaceQualityGate`, `FaceEmbedder`, `FaceGallery`) foram
escritas para servir aos dois casos sem alteração. A seção
[Se um dia houver câmera](#se-um-dia-houver-câmera) explica como ligar.

---

## Etapa 1 — Arquitetura

### Pilha

| Camada | Tecnologia |
| --- | --- |
| Linguagem | Kotlin (Java só no código gerado do Room/ViewBinding) |
| UI | Views + ViewBinding + Material. **Não usa Jetpack Compose** |
| Arquitetura | MVVM: Activity → ViewModel (`AndroidViewModel`) → Repository → Room + Firestore |
| Assíncrono | Coroutines + Flow/StateFlow |
| Persistência local | Room `porta_retrato.db` (`people`, `photos`, `pendingFaces`) |
| Nuvem | Firebase Auth (Google Sign-In + Credential Manager), Cloud Firestore, Firebase Storage |
| Background | WorkManager `CoroutineWorker` (`FaceScanWorker`, trabalho único `face_scan`) |
| Imagens | Glide |
| Detecção facial | ML Kit `play-services-mlkit-face-detection` 17.1.0 (modelo via Play Services) |
| Embedding facial | TensorFlow Lite 2.18.0 + `assets/mobilefacenet.tflite` (5,2 MB, float32) |
| ABIs | `arm64-v8a`, `armeabi-v7a` — só `libtensorflowlite_jni.so` (sem delegate de GPU) |

### Modelo de dados

```
Person(id, name, phone, hidden, embedding: List<Double>, embeddingSamples: Long)
PendingFace(id, photoId, left, top, right, bottom, embedding: List<Double>,
            suggestedPersonId, suggestedPersonName, createdAt)
Photo(id, url, personIds, reviewed, ...)
```

Firestore: `users/{uid}/people`, `users/{uid}/pendingFaces`, `users/{uid}/photos`.
Room espelha tudo; cada repositório mantém um `addSnapshotListener` que
reescreve o cache local. Os embeddings são gravados como **JSON de texto** pelo
Gson (`Converters.fromDoubleList`).

### Pipeline de reconhecimento (como está hoje)

```
FaceScanWorker.doWork()
  ├─ auth → uid; senão Result.failure()
  ├─ setForeground(notificação)
  ├─ personRepo.getAllFresh(uid)     → workingPeople: MutableList<Person>
  ├─ photoRepo.getUnreviewedOnce()   → candidates: List<Photo>
  └─ para cada foto:
       ├─ setForeground(progresso)                    ← 2 chamadas Binder POR FOTO
       ├─ PhotoStorageHelper.decodeDownsampled(file, 1024)
       └─ withTimeoutOrNull(20 s) {
            ├─ FaceDetectionHelper.detectFaces(bitmap)   ML Kit ACCURATE + LANDMARK_ALL
            ├─ se vazio → markReviewed(); fim
            └─ para cada rosto:
                 ├─ FaceRecognitionEngine.computeEmbedding()
                 │    └─ FaceEmbeddingHelper.getEmbeddingAligned()
                 │         ├─ alignFace()      ← copia a FOTO INTEIRA rotacionada
                 │         ├─ cropFace()       ← bbox + 25 % de margem
                 │         ├─ createScaledBitmap(112, 112)   ← distorce a proporção
                 │         ├─ bitmapToByteBuffer()  ← 37 632 putFloat()
                 │         ├─ Interpreter.run()
                 │         └─ normalize()  → L2
                 ├─ PersonMatcher.classify(embedding, workingPeople)
                 │    └─ cosseno contra a MÉDIA de cada pessoa
                 ├─ ≥ 0,52 → refine(): média corrida + escrita Firestore awaitada
                 ├─ ≥ 0,30 → PendingFace "sugestão"  + escrita Firestore awaitada
                 └─ < 0,30 → PendingFace "desconhecido" + escrita Firestore awaitada
            ├─ markReviewed()  ← mais uma escrita awaitada
            └─ bitmap.recycle()
       }
```

Limiares atuais: `MATCH_THRESHOLD = 0.52`, `SUGGESTION_THRESHOLD = 0.30`,
`SAME_NAME_CONFIRM_THRESHOLD = 0.35`. Embedding de 192 dimensões, L2-normalizado,
comparado por cosseno (o produto interno já é o cosseno porque ambos são
unitários — isso o código antigo acertou).

### Fluxo de cadastro (`ReviewFacesActivity` / `ReviewFacesViewModel`)

`pendingFaces` (StateFlow) → miniatura via `loadThumbnail` (decodifica a foto de
novo, em `Dispatchers.IO`) → usuário digita o nome → `saveName` → se o nome já
existe, `confirmSamePerson` compara com 0,35 → `linkOrCreate` → `resolveFace` →
`sweepQueueFor` (revarre a fila inteira) → `finalizePhotoIfDone`.

---

## Etapa 2 — Gargalos encontrados

Ordenados por impacto. Cada item traz o arquivo de origem no APK decompilado.

### Precisão

**P1 — Alinhamento errado antes da inferência.** `FaceEmbeddingHelper.alignFace`
só corrige rotação (roll) a partir dos dois olhos e depois recorta a bounding
box com 25 % de margem. MobileFaceNet foi treinado com crops gerados por uma
transformação de similaridade de **5 pontos** para posições canônicas fixas
dentro de 112×112. Sem isso, o embedding codifica enquadramento junto com
identidade: aproxima pessoas diferentes enquadradas igual e afasta a mesma
pessoa enquadrada diferente. É o maior gargalo de precisão de todos.

**P2 — Possível inversão de 180° no alinhamento.** O código faz:

```kotlin
val leftEye  = face.getLandmark(FaceLandmark.LEFT_EYE)!!.position   // tipo 4
val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)!!.position  // tipo 10
val angle = Math.toDegrees(atan2(rightEye.y - leftEye.y, rightEye.x - leftEye.x))
matrix.postRotate(-angle, pivotX, pivotY)
```

No ML Kit, `LEFT_EYE` é o olho esquerdo **do sujeito**, que aparece à **direita**
na imagem. Se essa convenção valer, `rightEye.x - leftEye.x` é negativo, o
ângulo fica perto de 180° e a foto é rotacionada de cabeça para baixo antes do
recorte — o que degradaria drasticamente todos os embeddings.

**Não consegui confirmar a convenção só pelo bytecode** (a biblioteca vem
ofuscada e sem documentação embutida), então trato isto como *suspeita forte, a
confirmar em aparelho*. O código novo é imune de qualquer forma: ordena os
pontos pela coordenada x em tempo de execução em vez de confiar no nome. Se a
suspeita se confirmar, este item sozinho explica a maior parte da imprecisão
relatada. **Como verificar:** logar `rightEye.x - leftEye.x` para 20 rostos
frontais; se for consistentemente negativo, o bug é real.

**P3 — Distorção de proporção.**
`Bitmap.createScaledBitmap(faceCrop, 112, 112, true)` força um recorte retangular
dentro de um quadrado. Rostos em fotos verticais são esticados na horizontal.

**P4 — Nenhum filtro de qualidade.** Qualquer coisa que o ML Kit chame de rosto
entra no pipeline: cabeça de 15 px ao fundo de uma festa, rosto de perfil,
completamente desfocado, contraluz total. Embeddings de rostos ruins caem numa
região degenerada do espaço onde todo mundo se parece com todo mundo — é a
principal fonte de **falsos positivos**. E como cada um vira um `PendingFace`,
também é o que enche a fila de revisão de miniaturas ilegíveis.

**P5 — Limiar de vínculo automático baixo demais.** 0,52 de cosseno para
MobileFaceNet é uma operação frouxa, especialmente com o alinhamento errado
empurrando as distribuições para perto. Sem contexto de margem, sempre escolhe
o maior, mesmo em empate técnico.

**P6 — Limiar de sugestão em 0,30 é ruído.** Para MobileFaceNet, 0,30 está na
faixa de pares aleatórios. Toda "sugestão" nesse nível é chute e treina o
usuário a ignorar as sugestões.

**P7 — Um único vetor médio por pessoa.** `averageEmbedding` mantém uma média
corrida. A média de rostos em poses e iluminações diferentes converge para um
vetor genérico: fica mais perto de todo mundo e menos perto de si mesmo — sobem
os falsos positivos **e** os falsos negativos ao mesmo tempo. Pior: a média é
atualizada com **qualquer** rosto auto-vinculado, então um único falso positivo
contamina o protótipo permanentemente e piora todas as comparações seguintes.

**P8 — EXIF ignorado.** `decodeDownsampled` não lê `ExifInterface`, e
`InputImage.fromBitmap(bitmap, 0)` afirma rotação zero. Fotos de celular
gravadas deitadas chegam giradas 90° ao detector, que perde os rostos. Cada
rosto perdido é um **falso negativo silencioso**: a foto é marcada como revisada
sem ninguém dentro.

**P9 — Sem verificação de tamanho mínimo.** Um rosto de 20×20 px é ampliado
para 112×112 e produz um embedding de pura interpolação — que ainda assim pode
passar de 0,52 contra alguém.

### Desempenho e recursos

**P10 — Cópia da foto inteira para alinhar.** `alignFace` faz
`Bitmap.createBitmap(w, h, ARGB_8888)` do tamanho **da foto toda**, só para
rotacionar e depois recortar 112×112 dela. Com o bug P11 abaixo, isso chega a
12 MB alocados e descartados **por rosto**.

**P11 — `inSampleSize` calculado errado.** O laço de `decodeDownsampled` é:

```kotlin
var sample = 1
while (true) {
    if (bounds.outWidth / (sample*2) < max && bounds.outHeight / (sample*2) < max) {
        /* usa `sample` */
    }
    sample *= 2
}
```

Ele para no `sample` em que **dobrar ainda caberia**, então a dimensão final
fica sempre em `[max, 2*max)`. Pedindo 1024 px, o app decodifica até 2048 px —
**4× a memória pretendida**. Verificado numericamente:

```
4032x3024: correto sample=4 → 1008x756  | atual sample=2 → 2016x1512  (4,0x mais memória)
3000x4000: correto sample=4 → 750x1000  | atual sample=2 → 1500x2000  (4,0x mais memória)
1920x1080: correto sample=2 → 960x540   | atual sample=1 → 1920x1080  (4,0x mais memória)
6000x4000: correto sample=8 → 750x500   | atual sample=4 → 1500x1000  (4,0x mais memória)
```

É a explicação mais provável dos `OutOfMemoryError` que o código antigo captura
e engole em silêncio em seis lugares diferentes.

**P12 — `setForeground()` por foto.** Cada iteração chama
`createForegroundInfo()`, que faz `createNotificationChannel()` **de novo** e
constrói uma `Notification`, seguida de `setForeground()`. São duas travessias
de Binder por foto; em 2 000 fotos, 4 000 IPCs só para atualizar uma barra de
progresso que o olho humano não acompanha.

**P13 — Uma escrita de rede awaitada por rosto.** Cada `AutoLink` faz
`updateEmbedding` e cada `Suggest`/`Unknown` faz `enqueue`, ambos com
`TasksKt.await(...)` sobre a `Task` do Firestore. `await` numa escrita do
Firestore só completa com a **confirmação do servidor**. Numa foto com 4 rostos
são 5 round-trips de rede em série antes de passar para a foto seguinte. Rádio
ligado o tempo todo é o maior consumo de bateria de toda a varredura.

**P14 — Alocações no laço de comparação.** `PersonMatcher.bestMatch` faz, para
**cada pessoa** e **cada rosto**: `toFloatArray(person.embedding)` (192
unboxings de `Double` + 1 `FloatArray`), um `Pair` e um `Float` boxeado. Com 200
pessoas e 4 000 rostos são centenas de milhares de alocações só para comparar
vetores.

**P15 — Embeddings como JSON via Gson.** `Converters.fromDoubleList` grava 192
doubles como texto: mais de 3 KB por pessoa em disco contra 768 bytes de BLOB, e
toda leitura roda o parser reflexivo do Gson.

**P16 — `List<Double>` em memória.** 192 `java.lang.Double` boxeados + o
`ArrayList` custam ~4 KB por pessoa contra 768 bytes de `FloatArray`. Nada se
ganha em precisão: o modelo produz `float`.

**P17 — Buffers realocados por rosto.** `IntArray(12544)`,
`ByteBuffer.allocateDirect(150528)` e `Array(1){FloatArray(192)}` são criados a
cada chamada de `getEmbeddingFromCrop`.

**P18 — 37 632 chamadas a `putFloat`.** `bitmapToByteBuffer` preenche o tensor
com um `putFloat` por canal por pixel, cada um com verificação de limites.

**P19 — Detector sempre em modo preciso.** `PERFORMANCE_MODE_ACCURATE` custa
tipicamente 2–4× o `FAST` e roda em **todas** as fotos, inclusive nos retratos
fáceis que são a maioria de um acervo de porta-retrato. Sem `setMinFaceSize`, o
detector ainda varre escalas minúsculas.

**P20 — `CLASSIFICATION_MODE` desligado.** Por isso `leftEyeOpenProbability`
vem nulo e não há como descartar foto de olho fechado — recurso pedido
explicitamente no briefing e que já está disponível de graça no detector.

**P21 — 4 threads no interpretador.** `setNumThreads(4)` num modelo de ~1 M de
parâmetros satura o cluster "little" e gasta bateria sem ganho proporcional.

**P22 — Recursos nunca liberados.** O `FaceDetector` é um `by lazy` num
`object` e nunca recebe `close()`. O `Interpreter` é um singleton estático que
nunca é fechado; o `MappedByteBuffer` de 5 MB fica mapeado pelo resto da vida do
processo. `getInterpreter` também vaza um `AssetFileDescriptor` e um
`FileInputStream` a cada criação.

**P23 — Corrida na criação do interpretador.** `getInterpreter` faz
check-then-act sem sincronização. Duas threads podem criar dois interpretadores
e mapear o modelo duas vezes.

**P24 — Miniatura decodifica a foto de novo.** `ReviewFacesViewModel.loadThumbnail`
decodifica o arquivo inteiro outra vez para recortar um rosto que a varredura já
tinha em mãos.

**P25 — `WorkManager` exige rede para trabalho local.** `setRequiredNetworkType(CONNECTED)`.
Detecção e inferência são 100 % locais; só a persistência precisa de rede. Com a
persistência offline do Firestore, a varredura poderia rodar sem conexão e
sincronizar depois.

**P26 — `fallbackToDestructiveMigration()`.** Qualquer bump de versão do schema
apaga o cache local inteiro e força ressincronização completa do Firestore.

**P27 — Timeout de 20 s por foto.** Alto demais: mascara travamentos em vez de
falhar rápido e seguir.

**P28 — `FaceScanWorker.doWork` é um método gigante.** Aproximadamente 1 324
instruções num único corpo, com deteção, inferência, matching, persistência e
notificação misturados. Impossível de testar em unidade.

---

## Etapa 3 — Velocidade do cadastro

O gargalo do cadastro **não é** captura de imagem — é rede.

`saveName` → `linkOrCreate` → `PersonRepository.createPersonWithEmbedding` →
`TasksKt.await(collection.document(id).set(...))`. Esse `await` só retorna com a
confirmação do servidor. Numa rede móvel ruim são facilmente 2–5 segundos com a
UI bloqueada em `_isSaving = true`. Depois ainda vem `sweepQueueFor`, que
revarre a fila pendente inteira no mesmo caminho.

**Como chegar a menos de 1 segundo (na prática, a menos de 100 ms):**

1. **Não aguarde o servidor.** O Firestore com persistência offline aplica a
   escrita no cache local **imediatamente** e sincroniza sozinho depois. Troque
   `await(task)` por `task.addOnFailureListener { … }` e devolva o controle à UI
   assim que a gravação local no Room terminar. Isto sozinho resolve o problema.
2. **Commit otimista no Room.** Grave a pessoa e o vínculo localmente, avance a
   UI para o próximo rosto e deixe a sincronização para o listener do Firestore.
3. **Tire `sweepQueueFor` do caminho da UI.** Ele deve rodar em background com
   *debounce*; o resultado chega pelo StateFlow quando estiver pronto.
4. **Agrupe as escritas.** `resolveFace` + `finalizePhotoIfDone` + remoção do
   `PendingFace` são três documentos: um `WriteBatch` só.
5. **Pré-carregue a próxima miniatura** enquanto o usuário digita, e recorte a
   partir do bitmap já decodificado em vez de decodificar de novo (P24).

Ganho estimado: de 2–5 s para menos de 100 ms no caso comum — a percepção passa
a ser instantânea. Esforço: **médio** (mexe em `ReviewFacesViewModel` e nos
repositórios). Impacto: **alto**.

---

## Etapa 4 — Precisão

### Método de comparação

Cosseno é a escolha certa e já está em uso. Com vetores L2-normalizados,
distância euclidiana e cosseno são monotonicamente equivalentes
(`d² = 2 − 2·cos`), então trocar não muda nada — mudar o **pré-processamento** e
a **política de decisão** muda tudo.

### As quatro mudanças que importam

**1. Alinhamento ArcFace de 5 pontos** (resolve P1, P2, P3). Transformação de
similaridade por mínimos quadrados levando olho-esquerdo, olho-direito, base do
nariz e os dois cantos da boca às coordenadas canônicas do InsightFace em
112×112. É o mesmo pré-processamento usado no treino do modelo. Implementado em
`ArcFaceAligner.kt`, com a matemática verificada numericamente (erro < 0,01 px
quando a entrada é uma similaridade exata).

**2. Gate de qualidade** (resolve P4, P9, P20). Antes de gastar uma inferência:
tamanho mínimo, pose (yaw/pitch/roll), olhos abertos, nitidez por variância do
Laplaciano, luminância média e contraste. Medidos sobre o crop **já alinhado**
de 112×112 — é isso que torna os limiares comparáveis entre fotos, já que todos
os rostos chegam ao gate no mesmo tamanho e enquadramento. Implementado em
`FaceQuality.kt`.

**3. Múltiplos protótipos por pessoa** (resolve P7). Até 8 vetores diversos por
pessoa, pontuados pelo **máximo**, com política de diversidade: um candidato
muito parecido com um protótipo existente é descartado; com a lista cheia,
substitui o protótipo mais redundante. Só rostos com qualidade acima de
`MIN_QUALITY_FOR_ENROLLMENT` viram protótipo — o que impede que um falso
positivo contamine a identidade. Implementado em `FaceGallery.kt`.

**4. Teste de margem** (resolve P5, P6). Vínculo automático exige **duas**
condições: similaridade ≥ 0,62 **e** folga ≥ 0,06 sobre a melhor pessoa
*diferente*. Em galeria de família — onde irmãos e pais/filhos produzem
embeddings próximos —, é o teste que mais derruba falso positivo: em empate
técnico o app pergunta em vez de chutar. Implementado em
`RecognitionMatcher.kt`; a lógica de "vice de outra pessoa" tem teste dedicado.

### Novos limiares

| Constante | Antes | Depois | Justificativa |
| --- | --- | --- | --- |
| `AUTO_LINK_THRESHOLD` | 0,52 | **0,62** | Com alinhamento correto, a distribuição de pares "mesma pessoa" sobe e a de "pessoas diferentes" desce; dá para operar mais conservador sem perder recall |
| `SUGGEST_THRESHOLD` | 0,30 | **0,45** | 0,30 é nível de par aleatório; sugerir ali destrói a confiança do usuário |
| `SAME_NAME_CONFIRM_THRESHOLD` | 0,35 | **0,50** | Mesmo raciocínio |
| `AUTO_LINK_MARGIN` | — | **0,06** | Novo: separação mínima entre 1º e 2º colocados |

> **Estes números são um ponto de partida fundamentado, não um resultado
> medido.** Eles pressupõem o pipeline novo. Antes de publicar, rode a
> calibração descrita em [Como medir](#como-medir) — é meia hora de trabalho e
> substitui palpite por dado.

---

## Etapa 5 — Velocidade

| Mudança | Resolve | Efeito |
| --- | --- | --- |
| Detecção em dois estágios (FAST, e ACCURATE só quando FAST não acha nada) | P19 | A maioria das fotos nunca paga o modo preciso |
| `setMinFaceSize(0.08f)` | P19 | O detector não gasta tempo em escalas que o gate recusaria |
| Gate de qualidade antes da inferência | P4 | Rosto ruim custa ~0,2 ms de análise de pixels em vez de uma inferência inteira |
| Uma única operação de desenho no alinhamento | P10 | Elimina a cópia da foto inteira por rosto |
| `inSampleSize` corrigido | P11 | 4× menos pixels para decodificar, detectar e percorrer |
| Buffers reaproveitados | P17 | Zero alocação por rosto no caminho quente |
| `FloatBuffer.put(FloatArray)` em bloco | P18 | Troca 37 632 chamadas por uma cópia de memória |
| Galeria empacotada em `FloatArray` contíguo | P14, P16 | Laço sequencial sem alocação; **medido em 293 µs** para 200 pessoas × 8 protótipos numa JVM de desktop — mesmo 10× mais lento num celular, é 3 ms |
| `WriteBatch` por foto | P13 | De N+1 round-trips para 1 |
| `setForeground` limitado a 1×/500 ms | P12 | ~99 % menos IPC |
| `NotificationChannel` criado uma vez | P12 | Some do laço |
| 2 threads no XNNPACK | P21 | Menos disputa e menos bateria |
| `close()` em detector e interpretador | P22 | Libera 5 MB + o modelo do ML Kit |

### Sobre GPU e NNAPI

**Não recomendo nenhum dos dois aqui**, e o motivo é concreto:

- **GPU**: o APK nem embarca `libtensorflowlite_gpu_jni.so`. Adicionar significa
  ~2 MB a mais e, para um modelo de 1 M de parâmetros, o custo de transferir o
  tensor para a GPU e trazer de volta come boa parte do ganho. Em varredura de
  lote a GPU ainda compete com a composição da UI.
- **NNAPI**: o custo de compilar o grafo (centenas de ms na primeira inferência)
  não se paga num modelo deste tamanho, e vários drivers de fornecedor
  quantizam internamente — o que **muda o embedding** e invalidaria os limiares
  calibrados. O código novo passa `setUseNNAPI(false)` explicitamente.

**XNNPACK em CPU com 2 threads é a escolha certa** para este tamanho de modelo.
Já está ativo por padrão no TFLite 2.18 para float; o código novo deixa
explícito para não depender do default da versão.

---

## Etapas 6 e 7 — Fluxo de cadastro e experiência para idosos

Como não há câmera, "detectar automaticamente quando o rosto estiver ideal"
vira **escolher automaticamente o melhor rosto entre as fotos que a pessoa já
tem**. É o que a combinação gate + protótipos faz: fotos ruins nunca chegam ao
usuário, e as boas viram protótipo sozinhas.

O que sugiro na `ReviewFacesActivity`:

1. **Ordene a fila por qualidade decrescente.** O usuário nomeia primeiro os
   rostos mais nítidos e grandes; cada nome dado melhora o reconhecimento dos
   próximos, e a fila encolhe sozinha.
2. **Não mostre rostos reprovados pelo gate.** Hoje eles entram na fila e o
   usuário precisa descartar um a um.
3. **Mostre o recorte alinhado, não a bounding box crua.** O rosto sai
   centralizado, na vertical e sempre do mesmo tamanho — muito mais fácil de
   reconhecer numa tela pequena.
4. **Mensagens em vez de números.** `QualityRejection` já mapeia direto:

   | Motivo | Mensagem |
   | --- | --- |
   | `TOO_SMALL` | "Rosto muito pequeno nesta foto" |
   | `BLURRY` | "Foto tremida" |
   | `TOO_DARK` | "Luz insuficiente" |
   | `TOO_BRIGHT` | "Foto muito clara" |
   | `EYES_CLOSED` | "Olhos fechados" |
   | `BAD_POSE` | "Rosto de lado" |

   E no fluxo positivo: "Quem é esta pessoa?" → "Pronto! Reconheci mais 4 fotos
   dela." — que é a confirmação que dá sentido ao esforço.
5. **Alvos de toque de 56 dp e fonte a partir de 20 sp**, respeitando o tamanho
   de fonte do sistema. Idoso costuma usar fonte grande: teste a tela em 130 %.
6. **Confirmação em vez de "Salvar".** Botões grandes "É a Maria" / "Não é" em
   vez de campo de texto, sempre que houver sugestão acima do limiar.

Esforço: **baixo a médio** (só UI). Impacto: **alto** na percepção de qualidade.

---

## Etapa 8 — Vale trocar o modelo?

Primeiro, um esclarecimento: **o ML Kit não faz reconhecimento facial.** Ele faz
*detecção* (achar o rosto, landmarks, pose, olhos, sorriso). Não existe API de
embedding no ML Kit Face Detection, então ele não é alternativa ao MobileFaceNet
— os dois são estágios diferentes do mesmo pipeline. Manter o ML Kit na
detecção é a decisão certa: é rápido, atualiza pelo Play Services, não pesa no
APK e entrega landmarks e pose que o gate de qualidade usa.

Para o estágio de **embedding**:

| Opção | Precisão (LFW) | Inferência (CPU móvel) | Tamanho | Integração | Veredito |
| --- | --- | --- | --- | --- | --- |
| **MobileFaceNet (atual, float32)** | ~99,5 % | ~8–15 ms | 5,2 MB | Já integrado | **Manter** |
| MobileFaceNet int8 | ~99,3 % | ~4–8 ms | ~1,3 MB | Requantizar + recalibrar | Otimização futura |
| MediaPipe Face Embedder | ~99,3 % | ~10–20 ms | ~6 MB | Dependência nova | Sem ganho que justifique |
| ArcFace / InsightFace R50 | ~99,8 % | ~150–300 ms | 100+ MB | Conversão manual | Inviável para varrer acervo |
| FaceNet (Inception-ResNet) | ~99,6 % | ~80–150 ms | ~90 MB | Conversão manual | Grande e lento demais |

**Recomendação: manter o MobileFaceNet.** O modelo não é o gargalo — o
pré-processamento é. O ganho de trocar para ArcFace R50 (+0,3 pp em benchmark)
é muito menor que o ganho de alinhar corretamente, e custaria 20× mais tempo de
inferência e 20× mais APK. Corrija o alinhamento, a política de decisão e o gate
de qualidade primeiro; **depois** meça e reconsidere. Se em algum momento a
troca for necessária, o único ponto que muda é `FaceEmbedder.kt`.

Quantização int8 é a evolução natural quando o resto estiver estável: ~2×
mais rápida e 4× menor, com perda mínima — mas exige recalibrar todos os
limiares e reprocessar os embeddings, então não é para agora.

---

## Etapa 9 — Refatoração

| Problema | Correção |
| --- | --- |
| `FaceScanWorker.doWork` monolítico (P28) | Extraído para `PhotoScanPipeline`, que não conhece repositório nem Firestore: recebe a galeria, devolve decisões. Testável em unidade |
| `object` com estado mutável e sem `close()` (P22) | `FaceEmbedder`, `FaceDetectors` e `PhotoScanPipeline` são classes `AutoCloseable` |
| Corrida na criação do interpretador (P23) | Interpretador criado no construtor; instância por worker |
| Descritores vazados (P22) | `FileInputStream.use { }` + `afd.close()` |
| Chamadas bloqueantes no caminho da UI (P13) | Escritas de rede sem `await`, `WriteBatch` por foto |
| `OutOfMemoryError` engolido em silêncio | Todo caminho de falha loga com contexto |
| Constantes de precisão espalhadas em 2 classes | Tudo em `RecognitionTuning` |

### Arquivos entregues

Em `app/src/main/java/com/portaretrato/app/recognition/`:

| Arquivo | Substitui | O que faz |
| --- | --- | --- |
| `RecognitionTuning.kt` | constantes de `FaceEmbeddingHelper` e `PersonMatcher` | Todos os parâmetros num lugar só |
| `ArcFaceAligner.kt` | `FaceEmbeddingHelper.alignFace` + `cropFace` | Alinhamento de 5 pontos, buffer reaproveitado |
| `FaceQuality.kt` | *(não existia)* | Gate de qualidade + score |
| `FaceEmbedder.kt` | `FaceEmbeddingHelper` | Inferência sem alocação, com `close()` |
| `FaceGallery.kt` | `Person.embedding` + `averageEmbedding` | Índice empacotado, múltiplos protótipos |
| `RecognitionMatcher.kt` | `PersonMatcher` | Decisão com margem e limiar adaptativo |
| `OrientedImageDecoder.kt` | `PhotoStorageHelper.decodeDownsampled` | `inSampleSize` correto + EXIF |
| `FaceDetectors.kt` | `FaceDetectionHelper` | Dois estágios, classificação ligada, `close()` |
| `EmbeddingCodec.kt` | `Converters.fromDoubleList` | BLOB binário em vez de JSON |
| `PhotoScanPipeline.kt` | `FaceScanWorker$doWork$handled$1` | Orquestração testável |

Suíte de verificação em `tools/verification/`.

### Estado de validação — leia antes de integrar

- **Compila:** sim. Todos os 10 arquivos passam pelo `kotlinc` 2.0.21 contra
  stubs das APIs de Android, ML Kit e TFLite. Zero erros.
- **Lógica verificada:** 25 asserções passam, cobrindo a matemática do
  alinhamento, a seleção de vice na galeria, a política de protótipos, o
  `inSampleSize`, o codec e o custo do matching.
- **Não validado:** nada disto rodou em Android. Não tenho o projeto Gradle
  (o repositório só continha o `README.md`), então não há como compilar contra o
  SDK real nem medir em aparelho. Os stubs cobrem só as assinaturas que uso.
- **Não medido:** todos os percentuais da Etapa 10 são **estimativas derivadas
  da análise estática**, não medições. As duas exceções, marcadas como tal, são
  o fator de memória do decodificador e o custo do matching.

---

## Etapa 10 — Entrega

### Prioridade

| # | Item | Resolve | Esforço | Impacto |
| --- | --- | --- | --- | --- |
| 1 | **Confirmar P2 em aparelho** (log do sinal de `rightEye.x − leftEye.x`) | P2 | Baixo | **Crítico** |
| 2 | Alinhamento ArcFace de 5 pontos | P1, P2, P3 | Médio | **Alto** |
| 3 | Gate de qualidade | P4, P9, P20 | Médio | **Alto** |
| 4 | Corrigir `inSampleSize` + EXIF | P8, P11 | Baixo | **Alto** |
| 5 | Parar de aguardar o Firestore no cadastro | P13 | Médio | **Alto** (percepção) |
| 6 | Margem + limiares novos | P5, P6 | Baixo | **Alto** |
| 7 | Múltiplos protótipos | P7 | Médio | **Alto** |
| 8 | Galeria empacotada + `FloatArray` | P14, P16 | Médio | Médio |
| 9 | Detecção em dois estágios + `minFaceSize` | P19 | Baixo | Médio |
| 10 | Buffers reaproveitados + preenchimento em bloco | P17, P18 | Baixo | Médio |
| 11 | `WriteBatch` por foto | P13 | Médio | Médio (bateria) |
| 12 | Limitar `setForeground` | P12 | Baixo | Médio |
| 13 | `close()` em detector e interpretador | P22, P23 | Baixo | Médio |
| 14 | BLOB em vez de JSON no Room | P15 | Médio | Baixo |
| 15 | Miniatura a partir do bitmap já decodificado | P24 | Baixo | Baixo |
| 16 | Tirar a exigência de rede do worker | P25 | Baixo | Baixo |
| 17 | Migração real do Room | P26 | Médio | Baixo |

Itens 1 a 6 entregam a maior parte do ganho. Se houver tempo para só um dia de
trabalho, faça 1, 2, 4 e 6.

### Ganhos estimados

Estimativas de análise estática, com o raciocínio explícito. Onde há medição,
está marcado.

| Dimensão | Estimativa | De onde vem |
| --- | --- | --- |
| **Precisão** | Falsos positivos −60 a −80 %; falsos negativos −40 a −60 % | Gate de qualidade tira a maior fonte de FP; margem tira o empate técnico; alinhamento correto separa as distribuições; EXIF recupera fotos hoje totalmente perdidas. Se P2 se confirmar, o ganho é maior |
| **Velocidade da varredura** | 2,5× a 4× mais rápida por foto | 4× menos pixels (P11) + FAST na maioria (P19) + gate poupando inferência (P4) + sem cópia da foto por rosto (P10) |
| **Latência do reconhecimento** | Matching de ~ms para **293 µs** em desktop (**medido**) — na ordem de 3 ms em celular, para 200 pessoas × 8 protótipos | Benchmark em `tools/verification` |
| **Cadastro** | 2–5 s → **< 100 ms** | Remove o round-trip do servidor do caminho da UI |
| **Memória** | Pico por foto ~4× menor (**medido** para o decodificador); cópia de até 12 MB por rosto eliminada | Tabela de `inSampleSize` acima + `ArcFaceAligner` |
| **Bateria** | Consumo da varredura −40 a −60 % | Menos CPU, 2 threads em vez de 4, e principalmente o rádio: de N+1 escritas por foto para 1 |
| **Armazenamento** | ~4× menos por pessoa (3 KB de JSON → 768 B de BLOB) | `EmbeddingCodec` |
| **Tamanho do APK** | Sem mudança | Nenhuma dependência nova; `androidx.exifinterface` provavelmente já vem pelo Glide |

### Como medir

Não publique os limiares novos sem calibrar. O procedimento:

1. Junte 200–300 fotos representativas do acervo real, com os rostos rotulados
   à mão.
2. Rode o pipeline novo e grave, para cada par de rostos, a similaridade e se
   são a mesma pessoa.
3. Plote as duas distribuições (mesma pessoa × pessoas diferentes). Elas devem
   ficar visivelmente mais separadas que com o pipeline atual — se não ficarem,
   pare e investigue antes de seguir.
4. Escolha `AUTO_LINK_THRESHOLD` no ponto de ~0,1 % de falsa aceitação, e
   `SUGGEST_THRESHOLD` onde a curva de "pessoas diferentes" começa a subir.
5. Ajuste `AUTO_LINK_MARGIN` observando quantos empates técnicos aparecem na
   sua galeria específica — famílias com muitos parentes precisam de margem
   maior.
6. Para desempenho, use `Trace.beginSection()` em decodificação, detecção,
   alinhamento, gate e inferência, e leia no Perfetto.

### Migração dos dados existentes

**Embeddings antigos não são compatíveis com os novos.** Foram gerados com um
pré-processamento diferente e não são comparáveis — misturar os dois na mesma
galeria produziria resultados piores que qualquer um dos dois sozinho.

Caminho seguro:

1. Suba a versão do schema e adicione a coluna `prototypes` (BLOB), mantendo a
   antiga.
2. Preserve **os vínculos pessoa↔foto que o usuário já confirmou** — esse é o
   dado valioso, e não depende do embedding.
3. Marque todas as fotos como não revisadas e reprocesse em background.
4. Para cada pessoa, os rostos das fotos já vinculadas geram os protótipos
   novos, sem nenhuma pergunta ao usuário.
5. Só então apague a coluna antiga.

O usuário não perde nada e não precisa recadastrar ninguém. E troque
`fallbackToDestructiveMigration()` por uma `Migration` de verdade (P26), senão
o passo 1 apaga justamente o que se quer preservar.

### Se um dia houver câmera

O pedido original faz total sentido para uma tela de cadastro ao vivo, e as
peças aqui já servem:

- `FaceDetectors` → troque para `enableTracking()` e alimente com
  `InputImage.fromMediaImage` do `ImageAnalysis` do CameraX
  (`STRATEGY_KEEP_ONLY_LATEST`).
- `ArcFaceAligner` e `FaceEmbedder` → funcionam sem alteração nenhuma.
- `FaceQualityGate` → vira exatamente o "detectar automaticamente quando o rosto
  está ideal": rode a cada frame, guarde o de maior `score` numa janela de ~1 s,
  e dispare o cadastro quando um frame passar de `MIN_QUALITY_FOR_ENROLLMENT`.
  Sem contagem regressiva e sem pedir para o usuário ficar parado.
- `QualityRejection` → as mensagens da Etapa 7 viram o guia ao vivo
  ("Aproxime um pouco", "Luz insuficiente", "Mantenha o rosto reto").

Um cadastro em menos de 1 segundo é perfeitamente alcançável assim: a 15 fps,
bastam ~15 frames para encontrar um bom, e a inferência é de ~10 ms.
