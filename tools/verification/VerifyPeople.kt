import android.graphics.Rect
import com.portaretrato.app.people.FaceDatabase
import com.portaretrato.app.people.FaceDatabaseCodec
import com.portaretrato.app.people.Person
import com.portaretrato.app.recognition.FaceQualityResult
import com.portaretrato.app.recognition.QualityRejection
import com.portaretrato.app.recognition.RecognitionDecision
import com.portaretrato.app.recognition.RecognitionTuning
import com.portaretrato.app.recognition.ScannedFace
import com.portaretrato.app.recognition.ScannedPhoto
import kotlin.math.sqrt
import kotlin.random.Random

var failures = 0

fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) println("  PASS  $name") else { failures++; println("  FAIL  $name  $detail") }
}

// ---------------------------------------------------------------------------
// Utilitarios: embeddings sinteticos com similaridade controlada
// ---------------------------------------------------------------------------
val DIM = RecognitionTuning.EMBEDDING_SIZE
val rng = Random(1234)

fun normalize(v: FloatArray): FloatArray {
    var s = 0.0
    for (x in v) s += x.toDouble() * x
    val n = sqrt(s).toFloat().takeIf { it > 0f } ?: 1f
    return FloatArray(v.size) { v[it] / n }
}

fun randomEmbedding(): FloatArray = normalize(FloatArray(DIM) { rng.nextFloat() - 0.5f })

/** Um vetor a distancia controlada de [base]: sim ~= cos(theta). */
fun near(base: FloatArray, similarity: Float): FloatArray {
    // Constroi um ortogonal a base e combina: v = s*base + sqrt(1-s^2)*orto.
    var orto = randomEmbedding()
    var dot = 0f
    for (i in 0 until DIM) dot += orto[i] * base[i]
    orto = normalize(FloatArray(DIM) { orto[it] - dot * base[it] })
    val k = sqrt(1f - similarity * similarity)
    return normalize(FloatArray(DIM) { similarity * base[it] + k * orto[it] })
}

/** Os tres ultimos campos sao diagnostico; nada aqui depende deles. */
fun quality(score: Float, rejection: QualityRejection? = null) =
    FaceQualityResult(score, rejection, sharpness = 120.0, meanLuma = 128.0, lumaStdDev = 55.0)

val goodQuality = quality(0.9f)

fun face(embedding: FloatArray, decision: RecognitionDecision, quality: FaceQualityResult = goodQuality) =
    ScannedFace(Rect(10, 10, 110, 110), embedding, quality, decision)

fun photo(id: String, vararg faces: ScannedFace, rejected: Int = 0) =
    ScannedPhoto(id, faces.toList(), rejected)

fun unknownFace(embedding: FloatArray, quality: Float = 0.9f) =
    face(embedding, RecognitionDecision.Unknown, quality(quality))

// ---------------------------------------------------------------------------
// 1. Aplicar varredura
// ---------------------------------------------------------------------------
fun testApplyScan() {
    println("[1] Registrar o resultado de uma foto")

    val db = FaceDatabase()
    val ana = randomEmbedding()

    val summary = db.applyScan(
        photo("f1", unknownFace(ana), face(randomEmbedding(), RecognitionDecision.Rejected(QualityRejection.LOW_SCORE))),
        )
    check("conta o desconhecido", summary.unknown == 1, "=${summary.unknown}")
    check("conta o recusado", summary.rejected == 1, "=${summary.rejected}")
    check("a foto fica marcada como varrida", db.isScanned("f1"))
    check("o rosto bom entra na fila", db.pendingCount == 1, "=${db.pendingCount}")
    check("o rosto recusado NAO entra na fila", db.pending.none { it.quality < 0.5f })
    check("ninguem foi criado sozinho", db.personCount == 0)

    // Idempotencia: a varredura interrompida e retomada reprocessa fotos.
    db.applyScan(photo("f1", unknownFace(ana)))
    check("reprocessar a mesma foto nao duplica a fila", db.pendingCount == 1, "=${db.pendingCount}")

    // Foto sem rosto nenhum.
    db.applyScan(photo("f2"))
    check("foto sem rosto e marcada como varrida", db.isScanned("f2"))
    check("e nao mexe na fila", db.pendingCount == 1)
}

// ---------------------------------------------------------------------------
// 2. O ganho central: nomear UM rosto resolve os outros
// ---------------------------------------------------------------------------
fun testNamingCascade() {
    println("[2] Nomear um rosto resolve os demais da mesma pessoa")

    val db = FaceDatabase()
    val ana = randomEmbedding()
    val outra = randomEmbedding()

    // 6 fotos da Ana (variacoes proximas) + 3 de outra pessoa.
    for (i in 1..6) db.applyScan(photo("ana$i", unknownFace(near(ana, 0.88f))))
    for (i in 1..3) db.applyScan(photo("outra$i", unknownFace(near(outra, 0.88f))))
    check("9 rostos na fila", db.pendingCount == 9, "=${db.pendingCount}")

    val alvo = db.pending.first { it.photoId.startsWith("ana") }
    val result = db.nameFace(alvo.id, "Vó Ana")

    check("a pessoa foi criada", result.person?.name == "Vó Ana", "=${result.person?.name}")
    check("outras fotos dela foram resolvidas junto", result.alsoResolved >= 4, "=${result.alsoResolved}")
    check("todas as 6 fotos da Ana ficaram vinculadas", db.photosOf(result.person!!.id).size == 6,
        "=${db.photosOf(result.person!!.id).size}")
    check("as 3 da outra pessoa continuam na fila", db.pendingCount == 3, "=${db.pendingCount}")
    check("e nenhuma delas foi vinculada a Ana",
        db.photosOf(result.person!!.id).none { it.startsWith("outra") })

    // O nome aparece na foto.
    check("namesIn devolve o nome", db.namesIn("ana1") == listOf("Vó Ana"), "=${db.namesIn("ana1")}")
    check("foto de outra pessoa nao tem nome", db.namesIn("outra1").isEmpty())

    // peopleIn devolve a pessoa inteira — e o telefone, quando houver — para
    // quem precisa saber se dá para ligar direto da foto (o porta-retrato).
    check("peopleIn devolve a pessoa", db.peopleIn("ana1").map { it.name } == listOf("Vó Ana"))
    check("sem telefone vinculado, peopleIn devolve null", db.peopleIn("ana1").first().phone == null)
    db.linkPhone(result.person!!.id, "5511999998888")
    check("apos linkPhone, peopleIn ja mostra o telefone",
        db.peopleIn("ana1").first().phone == "5511999998888",
        "=${db.peopleIn("ana1").first().phone}")
    check("foto de outra pessoa continua sem ninguem", db.peopleIn("outra1").isEmpty())
}

// ---------------------------------------------------------------------------
// 3. Sugestoes
// ---------------------------------------------------------------------------
fun testSuggestions() {
    println("[3] Sugestao, confirmacao e recusa")

    val db = FaceDatabase()
    val base = randomEmbedding()
    db.applyScan(photo("p1", unknownFace(base)))
    db.nameFace(db.pending.first().id, "Joao")
    val joao = db.people.first()

    // Um rosto so parecido: sugestao, nao vinculo.
    db.applyScan(
        photo("p2", face(near(base, 0.50f), RecognitionDecision.Suggest(joao.id, joao.name, 0.50f, 0.10f))),
    )
    val sugerido = db.pending.first { it.photoId == "p2" }
    check("a sugestao foi guardada", sugerido.suggestedPersonId == joao.id)
    check("mas a foto NAO foi vinculada", db.namesIn("p2").isEmpty(), "=${db.namesIn("p2")}")

    // Recusar limpa a sugestao sem tirar da fila.
    db.rejectSuggestion(sugerido.id)
    check("recusar mantem o rosto na fila", db.pending.any { it.photoId == "p2" })
    check("e apaga a sugestao", db.pending.first { it.photoId == "p2" }.suggestedPersonId == null)
    check("recusar nao vincula nada", db.namesIn("p2").isEmpty())

    // Confirmar vincula.
    db.applyScan(
        photo("p3", face(near(base, 0.50f), RecognitionDecision.Suggest(joao.id, joao.name, 0.50f, 0.10f))),
    )
    val outro = db.pending.first { it.photoId == "p3" }
    db.confirmSuggestion(outro.id)
    check("confirmar vincula a foto", db.namesIn("p3") == listOf("Joao"), "=${db.namesIn("p3")}")
    check("e tira o rosto da fila", db.pending.none { it.photoId == "p3" })

    // Confirmar um id inexistente nao pode explodir.
    check("confirmar id inexistente e inofensivo", db.confirmSuggestion("nao#0").person == null)
    check("nomear id inexistente e inofensivo", db.nameFace("nao#0", "X").person == null)
    check("nome vazio e recusado", db.nameFace(db.pending.first().id, "   ").person == null)

    // A distincao que so o campo `suggestionRejected` permite: um rosto que
    // NUNCA teve palpite deve receber um quando uma pessoa nova e cadastrada;
    // um cujo palpite foi RECUSADO nao pode voltar a ser palpitado. Os dois tem
    // `suggestedPersonId == null`.
    val db2 = FaceDatabase()
    val pedro = randomEmbedding()

    db2.applyScan(photo("q1", unknownFace(pedro)))                 // vai virar Pedro
    db2.applyScan(photo("q2", unknownFace(near(pedro, 0.50f))))     // parecido, nunca palpitado
    db2.applyScan(photo("q3", unknownFace(near(pedro, 0.50f))))     // parecido, vai recusar

    db2.rejectSuggestion(db2.pending.first { it.photoId == "q3" }.id)
    db2.nameFace(db2.pending.first { it.photoId == "q1" }.id, "Pedro")

    val nuncaPalpitado = db2.pending.firstOrNull { it.photoId == "q2" }
    val recusado = db2.pending.firstOrNull { it.photoId == "q3" }
    check("rosto nunca palpitado ganha sugestao apos cadastro",
        nuncaPalpitado == null || nuncaPalpitado.suggestedPersonId != null,
        "=${nuncaPalpitado?.suggestedPersonId}")
    check("rosto com palpite recusado NAO volta a ser palpitado",
        recusado == null || recusado.suggestedPersonId == null,
        "=${recusado?.suggestedPersonId}")

    // E a recusa tem de sobreviver a gravacao em disco.
    val relido = FaceDatabaseCodec.fromBytes(FaceDatabaseCodec.toBytes(db2))
    check("a recusa sobrevive ao round-trip",
        relido?.pending?.firstOrNull { it.photoId == "q3" }?.suggestionRejected != false,
        "=${relido?.pending?.firstOrNull { it.photoId == "q3" }?.suggestionRejected}")
}

// ---------------------------------------------------------------------------
// 4. Homonimos e acentos
// ---------------------------------------------------------------------------
fun testNames() {
    println("[4] Nomes iguais, pessoas diferentes")

    val db = FaceDatabase()
    val maria = randomEmbedding()
    val outraMaria = randomEmbedding()

    db.applyScan(photo("m1", unknownFace(maria)))
    db.nameFace(db.pending.first().id, "Maria")

    // Mesmo nome, rosto MUITO diferente: tem de virar outra pessoa.
    db.applyScan(photo("m2", unknownFace(outraMaria)))
    val r2 = db.nameFace(db.pending.first { it.photoId == "m2" }.id, "Maria")
    check("rosto diferente com nome igual vira homonimo", db.personCount == 2, "=${db.personCount}")
    check("e o nome e desambiguado", r2.person?.name == "Maria (2)", "=${r2.person?.name}")

    // Mesmo nome, MESMO rosto: junta na pessoa existente.
    db.applyScan(photo("m3", unknownFace(near(maria, 0.85f))))
    val pendente = db.pending.firstOrNull { it.photoId == "m3" }
    if (pendente != null) {
        val r3 = db.nameFace(pendente.id, "maria")  // caixa diferente de proposito
        check("mesmo rosto e mesmo nome nao cria duplicata", db.personCount == 2, "=${db.personCount}")
        check("e cai na Maria original", r3.person?.name == "Maria", "=${r3.person?.name}")
    } else {
        // Ja foi vinculada automaticamente, que e o resultado ainda melhor.
        check("mesmo rosto foi reconhecido sozinho", db.namesIn("m3") == listOf("Maria"), "=${db.namesIn("m3")}")
    }

    // Acento e caixa nao podem criar pessoas diferentes.
    val db2 = FaceDatabase()
    val vo = randomEmbedding()
    db2.applyScan(photo("v1", unknownFace(vo)))
    db2.nameFace(db2.pending.first().id, "Vó Ana")
    db2.applyScan(photo("v2", unknownFace(near(vo, 0.80f))))
    db2.pending.firstOrNull { it.photoId == "v2" }?.let { db2.nameFace(it.id, "vo ana") }
    check("acento e caixa nao criam pessoa nova", db2.personCount == 1, "=${db2.personCount}")
}

// ---------------------------------------------------------------------------
// 5. Fila limitada
// ---------------------------------------------------------------------------
fun testPendingCap() {
    println("[5] A fila de revisao tem teto")

    val db = FaceDatabase()
    // 400 rostos ruins entram primeiro.
    for (i in 1..400) db.applyScan(photo("ruim$i", unknownFace(randomEmbedding(), quality = 0.30f)))
    check("a fila nao passa de 300", db.pendingCount <= 300, "=${db.pendingCount}")

    // Um rosto otimo chega depois: tem de entrar, substituindo um ruim.
    db.applyScan(photo("otimo", unknownFace(randomEmbedding(), quality = 0.99f)))
    check("rosto nitido entra mesmo com a fila cheia", db.pending.any { it.photoId == "otimo" })
    check("e a fila continua no teto", db.pendingCount <= 300, "=${db.pendingCount}")

    // Um rosto pior que todos os guardados nao entra.
    val antes = db.pendingCount
    db.applyScan(photo("pessimo", unknownFace(randomEmbedding(), quality = 0.01f)))
    check("rosto pior que a fila inteira e descartado", db.pending.none { it.photoId == "pessimo" })
    check("e a fila nao cresce", db.pendingCount == antes, "=${db.pendingCount} antes=$antes")
}

// ---------------------------------------------------------------------------
// 6. Fotos apagadas
// ---------------------------------------------------------------------------
fun testPhotoRemoval() {
    println("[6] Fotos apagadas do acervo")

    val db = FaceDatabase()
    val ana = randomEmbedding()
    db.applyScan(photo("a", unknownFace(ana)))
    db.nameFace(db.pending.first().id, "Ana")
    db.applyScan(photo("b", unknownFace(randomEmbedding())))
    db.applyScan(photo("c", unknownFace(randomEmbedding())))

    check("estado inicial", db.isScanned("a") && db.pendingCount == 2)

    db.retainOnly(setOf("a"))
    check("varridas ficam so com as existentes", db.scannedPhotoIds == setOf("a"), "=${db.scannedPhotoIds}")
    check("a fila perde os rostos das fotos apagadas", db.pendingCount == 0, "=${db.pendingCount}")
    check("o vinculo da foto que sobrou continua", db.namesIn("a") == listOf("Ana"))
    check("a pessoa nao e apagada junto", db.personCount == 1)

    // Apagar a ultima foto de alguem nao apaga a pessoa: ela ainda e util para
    // reconhecer fotos futuras.
    db.retainOnly(emptySet())
    check("acervo vazio nao apaga as pessoas", db.personCount == 1)
    check("mas os vinculos somem", db.namesIn("a").isEmpty())
}

// ---------------------------------------------------------------------------
// 7. Apagar e renomear pessoas
// ---------------------------------------------------------------------------
fun testPersonEditing() {
    println("[7] Editar pessoas")

    val db = FaceDatabase()
    val ana = randomEmbedding()
    db.applyScan(photo("f1", unknownFace(ana)))
    db.nameFace(db.pending.first().id, "Ana")
    val id = db.people.first().id

    check("renomear funciona", db.renamePerson(id, "Tia Ana") && db.people.first().name == "Tia Ana")
    check("o nome novo aparece na foto", db.namesIn("f1") == listOf("Tia Ana"))
    check("nome vazio e recusado", !db.renamePerson(id, "  "))
    check("renomear id inexistente e recusado", !db.renamePerson("zzz", "X"))

    // Uma sugestao apontando para a pessoa apagada nao pode sobreviver.
    db.applyScan(
        photo("f2", face(near(ana, 0.50f), RecognitionDecision.Suggest(id, "Tia Ana", 0.50f, 0.10f))),
    )
    check("existe uma sugestao para ela", db.pending.any { it.suggestedPersonId == id })

    check("apagar funciona", db.removePerson(id))
    check("os vinculos somem", db.namesIn("f1").isEmpty())
    check("as sugestoes penduradas somem", db.pending.none { it.suggestedPersonId == id })
    check("apagar de novo e recusado", !db.removePerson(id))
    check("a galeria fica vazia", db.gallery().personCount == 0)
}

// ---------------------------------------------------------------------------
// 7b. Vincular a rosto humano-mente reconhecido, ignorando a semelhanca
// ---------------------------------------------------------------------------
fun testAssignToExistingPerson() {
    println("[7b] Escolher pessoa ja cadastrada (o humano acerta, o algoritmo nao)")

    val db = FaceDatabase()
    db.applyScan(photo("f1", unknownFace(randomEmbedding())))
    db.nameFace(db.pending.first().id, "Zé")
    val zeId = db.people.first().id

    // Um rosto SEM sugestao nenhuma e com embedding bem diferente do de Zé —
    // exatamente o caso em que nameFace(mesmo nome) criaria um homonimo.
    db.applyScan(photo("f2", unknownFace(randomEmbedding())))
    val pendente = db.pending.first { it.photoId == "f2" }
    check("o rosto novo nao tem sugestao", pendente.suggestedPersonId == null)

    val result = db.assignToExistingPerson(pendente.id, zeId)
    check("o vinculo forcado devolve a pessoa certa", result.person?.name == "Zé", "=${result.person?.name}")
    check("NENHUM homonimo foi criado", db.personCount == 1, "=${db.personCount}")
    check("a foto fica vinculada a Zé", db.namesIn("f2") == listOf("Zé"), "=${db.namesIn("f2")}")
    check("o rosto sai da fila", db.pending.none { it.id == pendente.id })

    // Entradas invalidas nao quebram nada.
    check("pendingId inexistente e recusado", db.assignToExistingPerson("nao#0", zeId).person == null)
    db.applyScan(photo("f3", unknownFace(randomEmbedding())))
    val outro = db.pending.first { it.photoId == "f3" }
    check("personId inexistente e recusado", db.assignToExistingPerson(outro.id, "zzz").person == null)
    check("e o rosto continua na fila", db.pending.any { it.id == outro.id })
}

// ---------------------------------------------------------------------------
// 8. Persistencia
// ---------------------------------------------------------------------------
fun testCodec() {
    println("[8] Gravar e ler o banco")

    val db = FaceDatabase()
    val ana = randomEmbedding()
    for (i in 1..4) db.applyScan(photo("ana$i", unknownFace(near(ana, 0.90f))))
    db.nameFace(db.pending.first().id, "Vó Ana")
    db.applyScan(photo("x", unknownFace(randomEmbedding())))

    val bytes = FaceDatabaseCodec.toBytes(db)
    val lido = FaceDatabaseCodec.fromBytes(bytes)
    check("le de volta", lido != null)
    if (lido == null) return

    check("mesmas pessoas", lido.personCount == db.personCount, "=${lido.personCount}")
    check("mesmo nome", lido.people.first().name == "Vó Ana", "=${lido.people.first().name}")
    check("mesma fila", lido.pendingCount == db.pendingCount, "=${lido.pendingCount}")
    check("mesmas varridas", lido.scannedPhotoIds == db.scannedPhotoIds)
    check("mesmos vinculos", lido.allLinks == db.allLinks)

    val original = db.people.first().prototypes
    val recarregado = lido.people.first().prototypes
    check("mesmo numero de prototipos", recarregado.size == original.size, "=${recarregado.size}")
    var maxDiff = 0f
    for (i in original.indices) {
        for (d in 0 until DIM) {
            val diff = kotlin.math.abs(original[i][d] - recarregado[i][d])
            if (diff > maxDiff) maxDiff = diff
        }
    }
    check("embeddings identicos bit a bit", maxDiff == 0f, "maxDiff=$maxDiff")

    // A galeria recarregada tem de reconhecer a mesma pessoa.
    val match = lido.gallery().match(near(ana, 0.90f))
    check("a galeria recarregada reconhece", match?.personName == "Vó Ana", "=${match?.personName}")

    // Telefone: por padrao ninguem tem, e o round-trip preserva o que foi vinculado.
    check("pessoa nasce sem telefone", db.people.first().phone == null, "=${db.people.first().phone}")
    check("lida de volta tambem sem telefone", lido.people.first().phone == null)

    val comTelefone = FaceDatabase()
    comTelefone.applyScan(photo("t1", unknownFace(randomEmbedding())))
    comTelefone.nameFace(comTelefone.pending.first().id, "Pedro")
    val pedroId = comTelefone.people.first().id
    check("linkPhone vincula", comTelefone.linkPhone(pedroId, "5511999998888"))
    check("linkPhone em id inexistente e recusado", !comTelefone.linkPhone("zzz", "551100000000"))

    val relidoComTelefone = FaceDatabaseCodec.fromBytes(FaceDatabaseCodec.toBytes(comTelefone))
    check("telefone sobrevive ao round-trip",
        relidoComTelefone?.people?.first()?.phone == "5511999998888",
        "=${relidoComTelefone?.people?.first()?.phone}")

    check("linkPhone(null) desvincula", comTelefone.linkPhone(pedroId, null))
    check("e o round-trip preserva o desvinculo",
        FaceDatabaseCodec.fromBytes(FaceDatabaseCodec.toBytes(comTelefone))?.people?.first()?.phone == null)

    // Um arquivo da versao 1 (sem o campo telefone) e recusado, nao lido com o
    // campo errado: melhor recomecar vazio do que interpretar bytes de
    // telefone como se fossem outra coisa.
    val versao1 = byteArrayOf(
        'P'.code.toByte(), 'R'.code.toByte(), 'F'.code.toByte(), 'D'.code.toByte(),
        0, 0, 0, 1, // versao antiga, big-endian
    )
    check("arquivo da versao antiga e recusado", FaceDatabaseCodec.fromBytes(versao1) == null)

    // Tamanho: o argumento que justifica o formato binario.
    val protoBytes = lido.people.sumOf { it.prototypes.size } * DIM * 4
    println("      arquivo=${bytes.size} bytes, dos quais $protoBytes em embeddings")

    // Arquivos invalidos.
    check("arquivo vazio e recusado", FaceDatabaseCodec.fromBytes(ByteArray(0)) == null)
    check("lixo e recusado", FaceDatabaseCodec.fromBytes(ByteArray(64) { 0x41 }) == null)
    check("truncado e recusado", FaceDatabaseCodec.fromBytes(bytes.copyOf(bytes.size / 2)) == null)
    val corrompido = bytes.copyOf().also { it[3] = 0 }
    check("magic errado e recusado", FaceDatabaseCodec.fromBytes(corrompido) == null)
    check("banco vazio faz round-trip", FaceDatabaseCodec.fromBytes(FaceDatabaseCodec.toBytes(FaceDatabase()))?.personCount == 0)

    // Ids de pessoa nao podem colidir depois de recarregar.
    lido.applyScan(photo("novo", unknownFace(randomEmbedding())))
    val novoResult = lido.nameFace(lido.pending.first { it.photoId == "novo" }.id, "Pedro")
    check("id da pessoa nova nao colide", novoResult.person != null &&
        lido.people.map { it.id }.toSet().size == lido.personCount,
        "ids=${lido.people.map { it.id }}")
}

// ---------------------------------------------------------------------------
// 9. Galeria e cache
// ---------------------------------------------------------------------------
fun testGalleryCache() {
    println("[9] Cache da galeria")

    val db = FaceDatabase()
    val ana = randomEmbedding()
    db.applyScan(photo("f1", unknownFace(ana)))
    db.nameFace(db.pending.first().id, "Ana")

    val g1 = db.gallery()
    check("a mesma instancia e reaproveitada", db.gallery() === g1)

    db.applyScan(photo("f2", unknownFace(randomEmbedding())))
    db.nameFace(db.pending.first { it.photoId == "f2" }.id, "Beto")
    check("criar pessoa invalida o cache", db.gallery() !== g1)
    check("e a galeria nova tem as duas", db.gallery().personCount == 2, "=${db.gallery().personCount}")

    val g2 = db.gallery()
    db.renamePerson(db.people.first().id, "Ana Maria")
    check("renomear invalida o cache", db.gallery() !== g2)
    check("e o nome novo sai na consulta",
        db.gallery().match(ana)?.personName == "Ana Maria",
        "=${db.gallery().match(ana)?.personName}")
}

fun main() {
    println("=== Verificacao do indice de pessoas ===\n")
    testApplyScan(); println()
    testNamingCascade(); println()
    testSuggestions(); println()
    testNames(); println()
    testPendingCap(); println()
    testPhotoRemoval(); println()
    testPersonEditing(); println()
    testAssignToExistingPerson(); println()
    testCodec(); println()
    testGalleryCache(); println()
    if (failures == 0) println("TODOS OS TESTES PASSARAM")
    else { println("$failures TESTE(S) FALHARAM"); kotlin.system.exitProcess(1) }
}
