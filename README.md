# Fone Amplificador

Protótipo que transforma um fone de ouvido comum em amplificador auricular: capta o
ambiente pelo microfone, realça os agudos, nivela o volume e devolve o som no fone.

Abra o `index.html` — é um arquivo só, sem dependências, roda no navegador do celular
ou do computador.

---

## Dá pra fazer isso mesmo?

Dá, e não é gambiarra: é o mesmo princípio de recursos que já existem de fábrica.

- **iPhone + AirPods / EarPods**: "Escuta ao Vivo" (Central de Controle → ícone da orelha)
  usa o microfone do celular e joga no fone. Nos AirPods Pro 2 existe ainda o recurso
  oficial de aparelho auditivo, com teste de audição no próprio iPhone — aprovado pelo FDA
  nos EUA para perda leve a moderada.
- **Android**: app *Amplificador de som* (Sound Amplifier), do Google, faz exatamente isso,
  inclusive com ajuste de agudos separado por ouvido.
- **Este repositório**: a mesma cadeia de processamento, aberta, para você mexer nos números.

O que muda de um aparelho auditivo de verdade é a qualidade da execução, não o conceito.

## Como funciona a cadeia de áudio

```
microfone → corta rumble (100 Hz)
          → divide em 3 faixas ─┬─ graves   (< 750 Hz)   → ganho → compressor ─┐
                                ├─ médios   (0,75–3 kHz) → ganho → compressor ─┤→ soma
                                └─ agudos   (> 3 kHz)    → ganho → compressor ─┘
          → volume geral → limitador (−6 dBFS) → clipe suave (−3 dBFS) → fone
```

Os pontos que importam:

- **Divisão em 3 faixas.** Perda auditiva quase nunca é parelha. A mais comum
  (presbiacusia, ligada à idade) derruba justamente os agudos — é por isso que a pessoa
  diz "eu ouço, mas não entendo": as consoantes /s/, /f/, /ch/, /t/ vivem entre 2 e 8 kHz.
  Aumentar o volume geral não resolve, só deixa os graves ainda mais dominantes. Por isso o
  ganho de agudos é separado.
- **Compressão por faixa.** É o núcleo do que um aparelho auditivo faz de verdade
  (chama-se WDRC, *wide dynamic range compression*): dar muito ganho no som fraco e pouco
  ganho no som forte. Sem isso, o sussurro continua inaudível e a porta batendo vira um
  estampido. Aqui cada banda tem seu próprio compressor.
- **Limitador na saída**, com ataque de 1 ms, seguido de um clipe suave (`y = A·tanh(x/A)`).
  O limitador sozinho deixa escapar overshoot no ataque — medido em teste automatizado, o
  pico batia em 0 dBFS; com o clipe, fica em −4. Nenhum estouro chega ao ouvido.
- **Filtros Linkwitz-Riley** de 4ª ordem (dois biquads em cascata) nos cortes entre faixas,
  para as bandas somarem sem buraco na região de cruzamento.

## Usar o microfone do próprio fone

Dá, e o app deixa escolher: no cartão **Dispositivos** você seleciona a entrada e a saída.
A troca de entrada reabre o microfone na hora, e a escolha fica salva para a próxima vez.

O detalhe que decide a qualidade é **como o fone está conectado**:

**Com fio (plugue TRRS de 4 polos, padrão CTIA).** É o melhor caso. O celular passa a usar
o microfone do próprio fone assim que você pluga, o áudio continua em banda larga, e a
latência é a menor possível. O microfone fica no cabo, na altura do peito — pega bem quem
conversa de frente com você e pega mal o que vem de trás, o que aqui até ajuda. Atrito do
cabo na roupa vira ruído, então prenda o fio.

**Bluetooth: aqui mora o problema.** No momento em que o navegador abre o microfone do
fone, o Bluetooth troca do perfil de música (A2DP) para o perfil de mãos-livres (HFP) — o
mesmo de uma ligação. Isso derruba o áudio **nos dois sentidos** para mono em banda
estreita: 8 kHz com CVSD, 16 kHz com mSBC. Ou seja, o canal corta tudo acima de 4 kHz (ou
8 kHz), que é exatamente a faixa que você quer realçar. Você acaba amplificando agudos que
já foram jogados fora antes de chegar aqui. O LE Audio com codec LC3 resolve isso de
verdade, mas exige fone, celular e sistema compatíveis.

Por isso o app mede e avisa: ele mostra a taxa de amostragem real que o navegador entregou,
e se vier abaixo de 32 kHz aparece um alerta dizendo qual foi a frequência máxima que
sobrou. Se você ver "16 kHz" ali, o Bluetooth cobrou o pedágio.

**Microfonia fica mais fácil**, porque microfone e alto-falante passam a estar no mesmo
corpo, a centímetros um do outro. Duas medidas ajudam: ponteira que vede bem o canal (o
isolamento é o que impede o som de voltar) e o cancelamento de eco ligado. Aparelho auditivo
de verdade convive com essa mesma geometria — microfone e receptor no mesmo aparelhinho —
usando supressão adaptativa de realimentação, que estima o caminho de retorno e o cancela.
É o item 3 da lista de melhorias mais abaixo.

**Escolher a saída** depende do navegador: o Chrome no desktop permite direcionar o áudio
para um dispositivo específico (`AudioContext.setSinkId`). No celular esse seletor fica
desativado e o som segue o roteamento do sistema — que já manda para o fone conectado,
então na prática não faz falta.

## Perfis

| Perfil   | Para que serve                                      |
| -------- | --------------------------------------------------- |
| Conversa | Padrão. Realça fala, tira peso dos graves.          |
| TV       | Mais ganho e menos compressão, som um pouco mais cheio. |
| Rua      | Corta graves com força e comprime muito: ruído de trânsito e motor. |
| Neutro   | Sem EQ e sem compressão, só amplificação — para comparar. |

## Limites honestos

**Latência.** É o problema central de fazer isso em navegador. Um aparelho auditivo real
trabalha em torno de 5 ms; o navegador entrega tipicamente 20–60 ms no desktop e pode passar
de 100 ms no celular. Acima de ~20 ms você começa a notar que a voz da pessoa está
dessincronizada da boca dela; acima de ~40 ms, sua própria voz volta com um eco que incomoda
bastante. O app mostra a latência estimada na tela. Aplicativo nativo (AAudio no Android,
AudioUnit no iOS) chega a números bem melhores.

**Microfonia.** Se o som escapa do fone e volta para o microfone com ganho, o sistema
oscila e apita. Por isso: fone com fio, bem encaixado, e volume subindo aos poucos. O app
tem duas proteções — se o limitador ficar grudado no teto por mais de 1,5 s, ele reduz o
volume sozinho; e se você desconectar o fone no meio do uso, ele silencia na hora (som indo
para o alto-falante = microfonia garantida).

**Microfone no lugar errado.** Usando o microfone do celular, ele fica no bolso ou na mão,
não na sua orelha: você perde a noção de direção e ele capta melhor o que está perto dele,
não quem fala com você. O microfone do próprio fone melhora isso (veja a seção acima), mas
ainda é um microfone só, omnidirecional. Aparelho auditivo tem microfone em cada orelha,
muitas vezes direcional — é isso que faz diferença em mesa de restaurante.

**Sem prescrição.** Aparelho auditivo é ajustado a partir de uma audiometria: ganho medido
por frequência, para cada ouvido. Aqui você ajusta no ouvido, no chute. Serve para
experimentar e para situações pontuais.

**Ganho demais machuca.** Amplificar sem controle é jeito conhecido de piorar audição.
Exposição contínua acima de ~85 dB(A) causa dano cumulativo. O limitador daqui limita o
sinal digital, não o volume acústico real — isso depende do seu fone e do volume do
aparelho. Regra prática: se você não consegue conversar normalmente com o app ligado sem
gritar, está alto demais.

## Se for evoluir isso

Na ordem de retorno:

1. **Ganho independente por ouvido** — perda auditiva costuma ser assimétrica. Basta
   duplicar a cadeia e usar um `ChannelMergerNode`.
2. **Mais bandas** — 6 a 8 faixas, com curva vinda de uma audiometria de verdade
   (fórmula NAL-NL2 ou DSL v5) em vez de três sliders.
3. **Supressão de microfonia adaptativa** — filtro adaptativo (LMS) estimando o caminho de
   retorno, em `AudioWorklet`, no lugar do corte bruto de volume.
4. **App nativo** — o único jeito de resolver a latência de verdade.

## Aviso

Experimento de áudio, não dispositivo médico. Não substitui avaliação de fonoaudiólogo nem
aparelho auditivo prescrito. Suspeita de perda auditiva se investiga com audiometria.
