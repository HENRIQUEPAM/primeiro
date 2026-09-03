import com.portaretrato.app.photo.PhotoFit
import com.portaretrato.app.photo.PhotoFitMode
import com.portaretrato.app.photo.SlideshowEngine
import com.portaretrato.app.photo.SlideshowOrder
import com.portaretrato.app.photo.SlideshowSettings
import kotlin.random.Random

var failures = 0

fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) println("  PASS  $name") else { failures++; println("  FAIL  $name  $detail") }
}

fun photos(n: Int) = (1..n).map { "foto$it" }

fun seqEngine(interval: Long = 12_000L) =
    SlideshowEngine(SlideshowSettings(SlideshowOrder.SEQUENTIAL, interval))

fun shuffleEngine(seed: Int = 42) =
    SlideshowEngine(SlideshowSettings(SlideshowOrder.SHUFFLE), Random(seed))

// ---------------------------------------------------------------------------
// 1. Casos de borda: onde este tipo de codigo costuma quebrar
// ---------------------------------------------------------------------------
fun testEdgeCases() {
    println("[1] Lista vazia e lista minima")

    val vazio = seqEngine()
    check("sem fotos, current e null", vazio.current == null)
    check("sem fotos, next() nao quebra", vazio.next() == null)
    check("sem fotos, previous() nao quebra", vazio.previous() == null)
    check("sem fotos, isEmpty", vazio.isEmpty)
    check("sem fotos, size zero", vazio.size == 0)

    val uma = seqEngine()
    uma.setPhotos(listOf("unica"))
    check("uma foto vira a atual", uma.current == "unica")
    check("next() com uma foto mantem a mesma", uma.next() == "unica")
    check("previous() com uma foto mantem a mesma", uma.previous() == "unica")

    val duas = seqEngine()
    duas.setPhotos(listOf("a", "b"))
    check("duas fotos alternam", duas.next() == "b" && duas.next() == "a")

    // Duplicatas nao podem inflar o acervo.
    val dup = seqEngine()
    dup.setPhotos(listOf("a", "b", "a", "b"))
    check("duplicatas sao ignoradas", dup.size == 2, "=${dup.size}")
}

// ---------------------------------------------------------------------------
// 2. Sequencial: percorre tudo e da a volta
// ---------------------------------------------------------------------------
fun testSequential() {
    println("[2] Ordem sequencial")

    val e = seqEngine()
    e.setPhotos(photos(4))
    val visto = mutableListOf(e.current!!)
    repeat(3) { visto += e.next()!! }
    check("percorre na ordem", visto == listOf("foto1", "foto2", "foto3", "foto4"), "=$visto")
    check("da a volta ao fim", e.next() == "foto1")

    check("previous volta", e.previous() == "foto4")
    check("previous do inicio vai para o fim", seqEngine().let { it.setPhotos(photos(3)); it.previous() } == "foto3")

    val j = seqEngine()
    j.setPhotos(photos(5))
    check("jumpTo funciona", j.jumpTo("foto4") && j.current == "foto4")
    check("jumpTo para foto inexistente e recusado", !j.jumpTo("nao-existe"))
    check("e nao move a posicao", j.current == "foto4")
}

// ---------------------------------------------------------------------------
// 3. Embaralhado: percorre TODAS antes de repetir
// ---------------------------------------------------------------------------
fun testShuffle() {
    println("[3] Ordem embaralhada")

    val e = shuffleEngine()
    e.setPhotos(photos(10))

    // Uma volta completa precisa mostrar as 10, sem repetir nenhuma.
    val ciclo = mutableListOf(e.current!!)
    repeat(9) { ciclo += e.next()!! }
    check("uma volta mostra as 10 fotos", ciclo.toSet().size == 10, "=${ciclo.toSet().size}")
    check("sem repeticao dentro da volta", ciclo.size == ciclo.toSet().size)

    // A ordem tem de ser diferente da sequencial (senao nao esta embaralhando).
    check("a ordem nao e a de insercao", ciclo != photos(10), "=$ciclo")

    // Segunda volta: ordem nova, e a primeira nao repete a ultima da anterior.
    val ultimaDaPrimeira = ciclo.last()
    val ciclo2 = mutableListOf(e.next()!!)
    check(
        "a volta seguinte nao comeca com a ultima da anterior",
        ciclo2.first() != ultimaDaPrimeira,
        "ultima=$ultimaDaPrimeira primeira=${ciclo2.first()}",
    )
    repeat(9) { ciclo2 += e.next()!! }
    check("a segunda volta tambem mostra as 10", ciclo2.toSet().size == 10)
    check("e numa ordem diferente da primeira", ciclo2 != ciclo)

    // Nenhuma foto pode aparecer duas vezes seguidas em 200 passos.
    val longo = shuffleEngine(seed = 7)
    longo.setPhotos(photos(6))
    var anterior = longo.current
    var repeticaoImediata = 0
    repeat(200) {
        val atual = longo.next()
        if (atual == anterior) repeticaoImediata++
        anterior = atual
    }
    check("nenhuma repeticao imediata em 200 passos", repeticaoImediata == 0, "=$repeticaoImediata")
}

// ---------------------------------------------------------------------------
// 4. O acervo muda com o slideshow rodando
// ---------------------------------------------------------------------------
fun testLibraryChanges() {
    println("[4] Acervo mudando durante a exibicao")

    val e = seqEngine()
    e.setPhotos(photos(5))
    e.next(); e.next()
    check("posicao inicial", e.current == "foto3")

    // O caso que quebra implementacoes ingenuas: adicionar uma foto NAO pode
    // fazer a tela pular para outra imagem.
    e.setPhotos(photos(5) + "foto6")
    check("adicionar foto mantem a atual na tela", e.current == "foto3", "=${e.current}")
    check("e o acervo cresce", e.size == 6)

    // Remover uma foto que nao e a atual tambem nao pode mover.
    e.setPhotos(listOf("foto1", "foto3", "foto4", "foto5", "foto6"))
    check("remover outra foto mantem a atual", e.current == "foto3", "=${e.current}")

    // Remover a foto ATUAL: segue de onde estava, sem voltar ao inicio.
    e.setPhotos(listOf("foto1", "foto4", "foto5", "foto6"))
    check("remover a atual nao volta ao comeco", e.current != "foto1", "=${e.current}")
    check("e continua numa foto valida", e.current in listOf("foto4", "foto5", "foto6"), "=${e.current}")

    // Esvaziar o acervo.
    e.setPhotos(emptyList())
    check("esvaziar deixa current null", e.current == null)
    check("e volta a isEmpty", e.isEmpty)

    // Repovoar depois de vazio.
    e.setPhotos(photos(3))
    check("repovoar retoma do inicio", e.current == "foto1")
}

// ---------------------------------------------------------------------------
// 5. Preferencias
// ---------------------------------------------------------------------------
fun testSettings() {
    println("[5] Preferencias do slideshow")

    val e = seqEngine()
    e.setPhotos(photos(5))
    check("intervalo padrao de 12s", e.intervalMs == 12_000L, "=${e.intervalMs}")

    e.updateSettings(SlideshowSettings(SlideshowOrder.SEQUENTIAL, 30_000L))
    check("intervalo pode ser mudado", e.intervalMs == 30_000L)

    // Limites: um intervalo de 100ms transformaria o porta-retrato num
    // estroboscopio, e o consumo de bateria explodiria.
    e.updateSettings(SlideshowSettings(SlideshowOrder.SEQUENTIAL, 100L))
    check("intervalo curto demais e limitado", e.intervalMs == SlideshowSettings.MIN_INTERVAL_MS, "=${e.intervalMs}")
    e.updateSettings(SlideshowSettings(SlideshowOrder.SEQUENTIAL, 999_999L))
    check("intervalo longo demais e limitado", e.intervalMs == SlideshowSettings.MAX_INTERVAL_MS, "=${e.intervalMs}")

    // Trocar a ordem tem de reordenar sem perder o acervo.
    val t = seqEngine()
    t.setPhotos(photos(8))
    t.updateSettings(SlideshowSettings(SlideshowOrder.SHUFFLE, 12_000L))
    check("trocar para embaralhado preserva o acervo", t.size == 8)
    val naTelaAntes = t.current
    val ciclo = mutableListOf(t.current!!)
    repeat(7) { ciclo += t.next()!! }
    check("a foto na tela nao muda ao trocar a ordem", ciclo.first() == naTelaAntes)
    check("e o ciclo seguinte mostra todas as 8", ciclo.toSet().size == 8, "=${ciclo.toSet().size}")

    // A invariante, agora valendo a partir de QUALQUER estado: as proximas N
    // fotos, comecando pela atual, sao sempre distintas.
    var violacoes = 0
    for (passos in 1..30) {
        val g = shuffleEngine(seed = passos)
        g.setPhotos(photos(7))
        repeat(passos) { g.next() }
        // Acervo muda no meio do caminho, como acontece de verdade.
        g.setPhotos(photos(7) + "nova")
        val janela = mutableListOf(g.current!!)
        repeat(7) { janela += g.next()!! }
        if (janela.toSet().size != 8) violacoes++
    }
    check("invariante vale apos mudanca de acervo em 30 estados", violacoes == 0, "=$violacoes")
}

// ---------------------------------------------------------------------------
// 6. Como a foto se encaixa na tela
// ---------------------------------------------------------------------------
fun testPhotoFit() {
    println("[6] Como a foto se encaixa na tela")

    check("padrao e AUTOMATICO", SlideshowSettings().photoFit == PhotoFitMode.AUTO)

    // FILL e FIT sempre decidem igual, não importa a orientação.
    for (foto in listOf(true, false)) for (tela in listOf(true, false)) {
        check(
            "FILL sempre preenche (foto=$foto tela=$tela)",
            PhotoFit.shouldFill(PhotoFitMode.FILL, foto, tela),
        )
        check(
            "FIT nunca preenche (foto=$foto tela=$tela)",
            !PhotoFit.shouldFill(PhotoFitMode.FIT, foto, tela),
        )
    }

    // AUTOMATICO: preenche quando a orientacao bate, mostra inteira quando nao bate.
    check(
        "AUTO preenche quando as duas sao deitadas",
        PhotoFit.shouldFill(PhotoFitMode.AUTO, photoIsLandscape = true, screenIsLandscape = true),
    )
    check(
        "AUTO preenche quando as duas sao em pe",
        PhotoFit.shouldFill(PhotoFitMode.AUTO, photoIsLandscape = false, screenIsLandscape = false),
    )
    check(
        "AUTO mostra inteira: foto deitada, tela em pe",
        !PhotoFit.shouldFill(PhotoFitMode.AUTO, photoIsLandscape = true, screenIsLandscape = false),
    )
    check(
        "AUTO mostra inteira: foto em pe, tela deitada",
        !PhotoFit.shouldFill(PhotoFitMode.AUTO, photoIsLandscape = false, screenIsLandscape = true),
    )
}

fun main() {
    println("=== Verificacao do slideshow ===\n")
    testEdgeCases(); println()
    testSequential(); println()
    testShuffle(); println()
    testLibraryChanges(); println()
    testSettings(); println()
    testPhotoFit(); println()
    if (failures == 0) println("TODOS OS TESTES PASSARAM")
    else { println("$failures TESTE(S) FALHARAM"); kotlin.system.exitProcess(1) }
}
