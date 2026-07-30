import com.portaretrato.app.recognition.EmbeddingCodec
import com.portaretrato.app.recognition.FaceGallery
import com.portaretrato.app.recognition.OrientedImageDecoder
import com.portaretrato.app.recognition.PersonPrototypes
import com.portaretrato.app.recognition.RecognitionTuning
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

var failures = 0

fun check(name: String, ok: Boolean, detail: String = "") {
    if (ok) println("  PASS  $name") else { failures++; println("  FAIL  $name  $detail") }
}

// ---------------------------------------------------------------------------
// 1. Transformacao de similaridade (mesma formula do ArcFaceAligner).
//    Verifica que os pontos de origem, apos a transformacao, caem sobre os
//    pontos de referencia do ArcFace.
// ---------------------------------------------------------------------------
val REF_X = floatArrayOf(38.2946f, 73.5318f, 56.0252f, 41.5493f, 70.7299f)
val REF_Y = floatArrayOf(51.6963f, 51.5014f, 71.7366f, 92.3655f, 92.2041f)

/** Devolve os 6 valores afins na ordem do android.graphics.Matrix. */
fun similarityTransform(srcX: FloatArray, srcY: FloatArray): FloatArray {
    var muSx = 0f; var muSy = 0f; var muDx = 0f; var muDy = 0f
    for (i in 0 until 5) {
        muSx += srcX[i]; muSy += srcY[i]; muDx += REF_X[i]; muDy += REF_Y[i]
    }
    muSx /= 5f; muSy /= 5f; muDx /= 5f; muDy /= 5f
    var a = 0f; var b = 0f; var den = 0f
    for (i in 0 until 5) {
        val sx = srcX[i] - muSx; val sy = srcY[i] - muSy
        val dx = REF_X[i] - muDx; val dy = REF_Y[i] - muDy
        a += sx * dx + sy * dy
        b += sx * dy - sy * dx
        den += sx * sx + sy * sy
    }
    if (den < 1e-6f) den = 1e-6f
    val c = a / den
    val s = b / den
    return floatArrayOf(
        c, -s, muDx - c * muSx + s * muSy,
        s, c, muDy - s * muSx - c * muSy,
    )
}

fun applyTransform(m: FloatArray, x: Float, y: Float): Pair<Float, Float> =
    Pair(m[0] * x + m[1] * y + m[2], m[3] * x + m[4] * y + m[5])

fun testAlignment() {
    println("[1] Transformacao de similaridade ArcFace")

    // Caso A: os pontos de origem sao os proprios pontos de referencia
    // transformados por uma similaridade conhecida (rotacao 20 graus, escala
    // 2.5, translacao). A inversa deve recuperar os pontos de referencia.
    val theta = Math.toRadians(20.0)
    val scale = 2.5f
    val tx = 300f
    val ty = 180f
    val srcX = FloatArray(5)
    val srcY = FloatArray(5)
    for (i in 0 until 5) {
        val c = (scale * cos(theta)).toFloat()
        val s = (scale * sin(theta)).toFloat()
        srcX[i] = c * REF_X[i] - s * REF_Y[i] + tx
        srcY[i] = s * REF_X[i] + c * REF_Y[i] + ty
    }
    val m = similarityTransform(srcX, srcY)
    var maxErr = 0f
    for (i in 0 until 5) {
        val (px, py) = applyTransform(m, srcX[i], srcY[i])
        maxErr = maxOf(maxErr, abs(px - REF_X[i]), abs(py - REF_Y[i]))
    }
    check("similaridade exata recupera os pontos de referencia", maxErr < 0.01f, "erro max=$maxErr px")

    // Caso B: rosto girado 180 graus (o bug que a ordenacao por x evita).
    // Se os olhos fossem trocados, a transformacao aprenderia um giro de 180.
    val flipX = FloatArray(5); val flipY = FloatArray(5)
    for (i in 0 until 5) { flipX[i] = 200f - srcX[i]; flipY[i] = 200f - srcY[i] }
    // Reordena como o aligner faz: olhos e boca por coordenada x crescente.
    val ordX = floatArrayOf(flipX[1], flipX[0], flipX[2], flipX[4], flipX[3])
    val ordY = floatArrayOf(flipY[1], flipY[0], flipY[2], flipY[4], flipY[3])
    val m2 = similarityTransform(ordX, ordY)
    // A escala deve ser positiva e o mapeamento razoavel: os 5 pontos caem
    // dentro do quadro 112x112 (nao invertidos para fora).
    var inFrame = true
    for (i in 0 until 5) {
        val (px, py) = applyTransform(m2, ordX[i], ordY[i])
        if (px < -5f || px > 117f || py < -5f || py > 117f) inFrame = false
    }
    check("rosto invertido reordenado cai dentro do quadro 112x112", inFrame)

    // Caso C: pontos com ruido — a solucao de minimos quadrados deve degradar
    // suavemente, nao explodir.
    val noisyX = FloatArray(5) { srcX[it] + (if (it % 2 == 0) 3f else -3f) }
    val noisyY = FloatArray(5) { srcY[it] + (if (it % 3 == 0) 2f else -2f) }
    val m3 = similarityTransform(noisyX, noisyY)
    var noisyErr = 0f
    for (i in 0 until 5) {
        val (px, py) = applyTransform(m3, noisyX[i], noisyY[i])
        noisyErr = maxOf(noisyErr, abs(px - REF_X[i]), abs(py - REF_Y[i]))
    }
    check("ruido de 3 px produz erro limitado", noisyErr < 6f, "erro max=$noisyErr px")
}

// ---------------------------------------------------------------------------
// 2. FaceGallery: melhor pessoa + vice DE OUTRA PESSOA.
// ---------------------------------------------------------------------------
fun unit(vararg seed: Float): FloatArray {
    val v = FloatArray(RecognitionTuning.EMBEDDING_SIZE)
    for (i in v.indices) v[i] = seed[i % seed.size] + (i % 7) * 0.001f
    var n = 0.0
    for (x in v) n += x.toDouble() * x
    val inv = (1.0 / sqrt(n)).toFloat()
    for (i in v.indices) v[i] *= inv
    return v
}

fun blend(a: FloatArray, b: FloatArray, t: Float): FloatArray {
    val v = FloatArray(a.size) { a[it] * (1 - t) + b[it] * t }
    var n = 0.0
    for (x in v) n += x.toDouble() * x
    val inv = (1.0 / sqrt(n)).toFloat()
    for (i in v.indices) v[i] *= inv
    return v
}

fun testGallery() {
    println("[2] FaceGallery")

    val ana = unit(1f, 0f, 0f, 0f)
    val bruno = unit(0f, 1f, 0f, 0f)
    val carla = unit(0f, 0f, 1f, 0f)

    val gallery = FaceGallery.build(
        listOf(
            PersonPrototypes("ana", "Ana", listOf(ana, blend(ana, carla, 0.15f))),
            PersonPrototypes("bruno", "Bruno", listOf(bruno)),
            PersonPrototypes("carla", "Carla", listOf(carla)),
        ),
    )
    check("indice tem 3 pessoas", gallery.personCount == 3, "=${gallery.personCount}")
    check("indice tem 4 prototipos", gallery.prototypeCount == 4, "=${gallery.prototypeCount}")

    // Sonda identica a Ana: vence com folga; o vice NAO pode ser o segundo
    // prototipo da propria Ana.
    val m1 = gallery.match(ana)!!
    check("vencedor correto", m1.personId == "ana", "=${m1.personId}")
    check("similaridade ~1.0", abs(m1.similarity - 1f) < 1e-3f, "=${m1.similarity}")
    val anaSecondProtoSim = FaceGallery.cosineSimilarity(ana, blend(ana, carla, 0.15f))
    check(
        "vice vem de outra pessoa (nao do 2o prototipo da Ana)",
        abs(m1.runnerUpSimilarity - anaSecondProtoSim) > 1e-3f,
        "vice=${m1.runnerUpSimilarity} protoAna=$anaSecondProtoSim",
    )
    check("margem alta em caso facil", m1.margin > 0.5f, "=${m1.margin}")

    // Caso ambiguo: sonda no meio do caminho entre Bruno e Carla. Similaridade
    // pode ate ser alta, mas a margem tem de ser pequena -> nao auto-vincula.
    val ambiguous = blend(bruno, carla, 0.5f)
    val m2 = gallery.match(ambiguous)!!
    check("empate produz margem pequena", m2.margin < 0.05f, "margem=${m2.margin} sim=${m2.similarity}")

    // Galeria vazia.
    check("galeria vazia devolve null", FaceGallery.build(emptyList()).match(ana) == null)

    // Dimensao errada.
    check("vetor de dimensao errada devolve null", gallery.match(FloatArray(10)) == null)
}

// ---------------------------------------------------------------------------
// 3. Politica de prototipos.
// ---------------------------------------------------------------------------
fun testPrototypes() {
    println("[3] Politica de prototipos")

    val base = unit(1f, 0f, 0f, 0f)
    val other = unit(0f, 1f, 0f, 0f)

    check("primeiro prototipo entra", FaceGallery.mergePrototype(emptyList(), base).size == 1)

    val nearDuplicate = blend(base, other, 0.02f)
    check(
        "quase-duplicata e descartada",
        FaceGallery.mergePrototype(listOf(base), nearDuplicate).size == 1,
    )

    val diverse = blend(base, other, 0.45f)
    check(
        "prototipo diverso e adicionado",
        FaceGallery.mergePrototype(listOf(base), diverse).size == 2,
    )

    // Enche ate o limite e confirma que nao cresce alem disso.
    var protos = listOf(base)
    for (i in 1..20) {
        protos = FaceGallery.mergePrototype(protos, blend(base, unit(i.toFloat(), 2f, 3f, 4f), 0.5f))
    }
    check(
        "limite de ${RecognitionTuning.MAX_PROTOTYPES_PER_PERSON} prototipos respeitado",
        protos.size <= RecognitionTuning.MAX_PROTOTYPES_PER_PERSON,
        "=${protos.size}",
    )
}

// ---------------------------------------------------------------------------
// 4. inSampleSize — o bug do decodificador antigo.
// ---------------------------------------------------------------------------
fun legacySampleSize(w: Int, h: Int, max: Int): Int {
    var sample = 1
    while (true) {
        if (w / (sample * 2) < max && h / (sample * 2) < max) return sample
        sample *= 2
    }
}

fun testSampleSize() {
    println("[4] inSampleSize")

    data class Case(val w: Int, val h: Int)
    val cases = listOf(Case(4032, 3024), Case(3000, 4000), Case(1920, 1080), Case(800, 600), Case(6000, 4000))
    val max = 1024

    var allWithin = true
    for (c in cases) {
        val s = OrientedImageDecoder.sampleSizeFor(c.w, c.h, max)
        val dw = c.w / s
        val dh = c.h / s
        if (dw > max || dh > max) allWithin = false
        val ls = legacySampleSize(c.w, c.h, max)
        val ratio = (c.w.toDouble() / ls * (c.h.toDouble() / ls)) / ((dw.toDouble()) * dh)
        println(
            "      ${c.w}x${c.h}: novo sample=$s -> ${dw}x$dh | antigo sample=$ls -> " +
                "${c.w / ls}x${c.h / ls}  (antigo usa ${"%.1f".format(ratio)}x mais memoria)",
        )
    }
    check("novo decodificador nunca ultrapassa maxDimension", allWithin)

    val legacyOver = cases.count { c ->
        val ls = legacySampleSize(c.w, c.h, max)
        c.w / ls > max || c.h / ls > max
    }
    check("decodificador antigo ultrapassa o limite em $legacyOver/${cases.size} casos", legacyOver > 0)
}

// ---------------------------------------------------------------------------
// 5. EmbeddingCodec.
// ---------------------------------------------------------------------------
fun testCodec() {
    println("[5] EmbeddingCodec")

    val v = unit(0.3f, -0.7f, 0.1f, 0.9f)
    val bytes = EmbeddingCodec.toBytes(v)
    check("BLOB tem 768 bytes", bytes.size == 768, "=${bytes.size}")
    val back = EmbeddingCodec.fromBytes(bytes)!!
    check("round-trip preserva os valores", back.contentEquals(v))
    check("tamanho invalido devolve null", EmbeddingCodec.fromBytes(ByteArray(100)) == null)
    check("null devolve null", EmbeddingCodec.fromBytes(null) == null)

    val protos = listOf(v, unit(1f, 1f, 0f, 0f), unit(0f, 0f, 1f, 1f))
    val packed = EmbeddingCodec.packPrototypes(protos)
    val unpacked = EmbeddingCodec.unpackPrototypes(packed)
    check("pack/unpack preserva 3 prototipos", unpacked.size == 3, "=${unpacked.size}")
    check("conteudo dos prototipos preservado", unpacked[1].contentEquals(protos[1]))
    check("BLOB truncado devolve lista vazia", EmbeddingCodec.unpackPrototypes(ByteArray(769)).isEmpty())

    val legacy = List(RecognitionTuning.EMBEDDING_SIZE) { it * 0.01 }
    check("ponte legada converte", EmbeddingCodec.fromLegacyDoubles(legacy)?.size == 192)
    check("ponte legada rejeita tamanho errado", EmbeddingCodec.fromLegacyDoubles(listOf(1.0)) == null)
}

// ---------------------------------------------------------------------------
// 6. Custo do matching: quanto tempo leva comparar contra uma galeria grande.
// ---------------------------------------------------------------------------
fun benchMatching() {
    println("[6] Custo do matching (galeria empacotada)")

    val people = (0 until 200).map { i ->
        PersonPrototypes("p$i", "Pessoa $i", (0 until 8).map { j -> unit(i.toFloat(), j.toFloat(), 1f, 2f) })
    }
    val gallery = FaceGallery.build(people)
    val probe = unit(42f, 3f, 1f, 2f)

    repeat(2000) { gallery.match(probe) } // aquecimento do JIT
    val start = System.nanoTime()
    val iterations = 20000
    repeat(iterations) { gallery.match(probe) }
    val perMatchUs = (System.nanoTime() - start) / iterations / 1000.0
    println("      200 pessoas x 8 prototipos = ${gallery.prototypeCount} vetores")
    println("      %.1f us por rosto (JVM de desktop; num celular espere ~5-10x)".format(perMatchUs))
    check("matching bem abaixo de 1 ms por rosto", perMatchUs < 1000.0, "=${perMatchUs}us")
}

fun main() {
    println("=== Verificacao do pipeline de reconhecimento ===\n")
    testAlignment(); println()
    testGallery(); println()
    testPrototypes(); println()
    testSampleSize(); println()
    testCodec(); println()
    benchMatching(); println()
    if (failures == 0) println("TODOS OS TESTES PASSARAM")
    else { println("$failures TESTE(S) FALHARAM"); kotlin.system.exitProcess(1) }
}
