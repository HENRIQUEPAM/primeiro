# primeiro

Análise de engenharia reversa e otimização do app **Porta Retrato** (`com.portaretrato.app`, v2.9).

O repositório estava vazio quando este trabalho começou — o único insumo foi o
APK. O que está aqui foi reconstruído a partir dele.

## Onde começar

**[`COMO-RODAR.md`](COMO-RODAR.md)** — compilar o projeto de chamadas e testar
com dois aparelhos.

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
COMO-RODAR.md                                    build e teste com dois aparelhos
docs/CHAMADAS.md                                 chamada de vídeo P2P (WebRTC)
app/src/main/java/com/portaretrato/app/recognition/   pipeline de reconhecimento (10 arquivos)
app/src/main/java/com/portaretrato/app/call/          módulo de chamadas (11 arquivos)
app/src/main/res/                                layouts, temas e strings
firebase/                                        regras, índices e Cloud Function
tools/verification/                              suítes de verificação
```

### Módulo de chamadas

**Projeto Android completo e buildável.** Chamada de vídeo direta entre
aparelhos (WebRTC + sinalização por Firestore), com **atendimento automático**
para contatos de confiança — o que o WhatsApp não permite e o motivo de valer a
pena construir.

Inclui Gradle, manifesto, duas telas, foreground service, FCM, regras do
Firestore, índices e Cloud Function. Arquitetura em
[`docs/CHAMADAS.md`](docs/CHAMADAS.md), build em [`COMO-RODAR.md`](COMO-RODAR.md).

A chamada recebida tem **dois caminhos**: listener do Firestore (funciona sem
deploy nenhum, com o app aberto) e push por FCM (para app morto). Dá para testar
com dois aparelhos assim que o Firebase estiver configurado.

**Não incluo o APK compilado.** O SDK do Android não é acessível neste ambiente
(`dl.google.com` bloqueado, HTTP 403), então não houve como rodar `aapt2`, `d8`
nem `apksigner`. `./gradlew assembleDebug` gera o APK na sua máquina.

## Estado

Verificado:

| Verificação | Resultado |
| --- | --- |
| Lógica de chamada (estados, protocolo, auto-atendimento) | ✅ 53 asserções em JVM |
| Pipeline de reconhecimento facial | ✅ 25 asserções em JVM |
| Sintaxe Kotlin de todos os fontes | ✅ 0 erros de parse |
| XML, referências de recurso, classes do manifesto, ViewBinding, version catalog | ✅ 6 verificações |
| Sintaxe da Cloud Function | ✅ `node --check` |

```bash
python3 tools/verification/validate_project.py
```

Não verificado:

- **Compilação contra as bibliotecas reais.** Sem SDK, os erros do `kotlinc` são
  todos `unresolved reference` a `android.*`, `org.webrtc.*` e `com.google.*`.
  `WebRtcEngine.kt` é o arquivo com maior chance de precisar de ajuste de
  assinatura.
- **Comportamento em aparelho.** Nada rodou em Android.
- Os números de ganho de desempenho são estimativas de análise estática, salvo
  os dois marcados como medidos.

Antes de publicar o reconhecimento facial, rode a calibração de limiares em
[Como medir](docs/ANALISE-E-PLANO.md#como-medir).
