# primeiro

Análise de engenharia reversa e otimização do app **Porta Retrato** (`com.portaretrato.app`, v2.9).

O repositório estava vazio quando este trabalho começou — o único insumo foi o
APK. O que está aqui foi reconstruído a partir dele.

## Onde começar

**[`docs/ANALISE-E-PLANO.md`](docs/ANALISE-E-PLANO.md)** — o documento principal:
arquitetura do app, 28 gargalos identificados, plano priorizado, comparação de
modelos e ganhos estimados.

> **Nota de escopo:** o briefing original pedia otimizações de CameraX e cadastro
> facial ao vivo. Este app **não usa câmera** — é um porta-retrato digital que
> varre um acervo de fotos. O documento explica como cada objetivo foi traduzido
> para o pipeline que existe de fato.

## Conteúdo

```
docs/ANALISE-E-PLANO.md                          análise completa e plano
docs/CHAMADAS.md                                 chamada de vídeo P2P (WebRTC)
app/src/main/java/com/portaretrato/app/recognition/   pipeline novo (10 arquivos)
app/src/main/java/com/portaretrato/app/call/          módulo de chamadas (6 arquivos)
tools/verification/                              suítes de verificação em JVM
```

### Módulo de chamadas

Chamada de vídeo direta entre aparelhos (WebRTC + sinalização por Firestore),
com **atendimento automático** para contatos de confiança — o que o WhatsApp
não permite e o motivo de valer a pena construir. Ver
[`docs/CHAMADAS.md`](docs/CHAMADAS.md).

**Não há APK.** O repositório não tem o código-fonte do app, e o SDK do Android
não é acessível neste ambiente (`dl.google.com` bloqueado). O documento explica
o que preciso para entregar um.

### Pipeline novo

| Arquivo | Substitui | Ganho principal |
| --- | --- | --- |
| `RecognitionTuning.kt` | constantes espalhadas | Parâmetros num lugar só |
| `ArcFaceAligner.kt` | `FaceEmbeddingHelper.alignFace` | Alinhamento de 5 pontos correto, sem alocação |
| `FaceQuality.kt` | *(não existia)* | Descarta rosto ruim antes da inferência |
| `FaceEmbedder.kt` | `FaceEmbeddingHelper` | Buffers reaproveitados, libera recursos |
| `FaceGallery.kt` | `averageEmbedding` | Múltiplos protótipos, índice empacotado |
| `RecognitionMatcher.kt` | `PersonMatcher` | Teste de margem contra falso positivo |
| `OrientedImageDecoder.kt` | `decodeDownsampled` | Corrige 4× de memória + EXIF |
| `FaceDetectors.kt` | `FaceDetectionHelper` | Detecção em dois estágios |
| `EmbeddingCodec.kt` | `Converters.fromDoubleList` | BLOB em vez de JSON |
| `PhotoScanPipeline.kt` | `FaceScanWorker.doWork` | Orquestração testável |

## Estado

- **Compila:** os 10 arquivos de `recognition/` passam pelo `kotlinc` 2.0.21
  contra stubs das APIs de Android, ML Kit e TFLite. Em `call/`, os 4 arquivos
  de lógica pura compilam sem stub nenhum. Zero erros.
- **Lógica verificada:** 25 + 53 = **78 asserções** passam em
  `tools/verification`.
- **Não validado em Android:** não há projeto Gradle no repositório, então nada
  foi compilado contra o SDK real nem medido em aparelho. Os números de ganho no
  documento são estimativas de análise estática, salvo os dois marcados como
  medidos.
- **Não compilados:** `WebRtcEngine.kt` e `FirestoreSignaling.kt` dependem de
  bibliotecas indisponíveis aqui; as assinaturas precisam ser conferidas contra
  as versões fixadas no Gradle.

Antes de publicar, rode a calibração de limiares descrita em
[Como medir](docs/ANALISE-E-PLANO.md#como-medir).
