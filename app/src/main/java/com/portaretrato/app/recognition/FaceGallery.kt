package com.portaretrato.app.recognition

/** Conjunto de prototipos de uma pessoa, pronto para indexar. */
data class PersonPrototypes(
    val personId: String,
    val personName: String,
    /** Cada item tem exatamente [RecognitionTuning.EMBEDDING_SIZE] floats, L2-normalizado. */
    val prototypes: List<FloatArray>,
)

/** Resultado de uma consulta a galeria. */
data class GalleryMatch(
    val personId: String,
    val personName: String,
    val similarity: Float,
    /** Melhor similaridade obtida por uma pessoa DIFERENTE da vencedora. */
    val runnerUpSimilarity: Float,
) {
    /** Distancia para o segundo colocado. Quanto maior, mais confiavel. */
    val margin: Float get() = similarity - runnerUpSimilarity
}

/**
 * Indice de embeddings achatado em um unico array contiguo.
 *
 * O matcher antigo (`PersonMatcher.bestMatch`) fazia, para CADA pessoa e CADA
 * rosto: converter `List<Double>` -> `FloatArray` (192 unboxings + 1 alocacao),
 * criar um `Pair<Person, Float>` (mais 2 alocacoes com o boxing do float) e
 * jogar tudo no GC logo em seguida. Com 200 pessoas e 4.000 rostos isso da na
 * ordem de 800 mil alocacoes so para comparar vetores.
 *
 * Aqui os vetores sao empacotados uma unica vez num `FloatArray` continuo. A
 * consulta e um laco sobre memoria sequencial, sem alocacao nenhuma, e o
 * padrao de acesso e amigavel ao cache. Para 200 pessoas x 8 prototipos sao
 * 307 mil multiplicacoes-acumulacoes por rosto — bem abaixo de 1 ms.
 *
 * ## Multiplos prototipos por pessoa
 *
 * O modelo antigo guardava UM vetor medio por pessoa
 * (`averageEmbedding`). A media de rostos capturados em poses e iluminacoes
 * diferentes converge para um vetor generico: fica mais perto de todo mundo e
 * menos perto de si mesmo — sobem os falsos positivos E os falsos negativos ao
 * mesmo tempo. Guardar ate 8 prototipos diversos e pontuar pelo MAXIMO resolve
 * isso sem custo relevante de comparacao.
 */
class FaceGallery private constructor(
    private val data: FloatArray,
    private val ownerIndex: IntArray,
    private val personIds: Array<String>,
    private val personNames: Array<String>,
    private val vectorCount: Int,
) {

    val personCount: Int get() = personIds.size
    val prototypeCount: Int get() = vectorCount

    /**
     * Melhor pessoa e melhor pessoa distinta, numa unica passada.
     *
     * [probe] deve estar L2-normalizado e ter [RecognitionTuning.EMBEDDING_SIZE]
     * posicoes; como ambos os lados sao unitarios, o produto interno JA E a
     * similaridade de cosseno — nao ha divisao por normas no laco.
     */
    fun match(probe: FloatArray): GalleryMatch? {
        if (vectorCount == 0 || probe.size != DIM) return null

        // Top-2 por PESSOA distinta: o vice nunca pode ser outro prototipo do
        // vencedor, senao a margem mediria diversidade interna em vez de
        // separacao entre identidades.
        var bestSim = Float.NEGATIVE_INFINITY
        var bestOwner = -1
        var secondSim = Float.NEGATIVE_INFINITY
        var secondOwner = -1

        var offset = 0
        for (v in 0 until vectorCount) {
            var dot = 0f
            for (d in 0 until DIM) dot += data[offset + d] * probe[d]
            offset += DIM

            val owner = ownerIndex[v]
            when {
                owner == bestOwner -> if (dot > bestSim) bestSim = dot
                bestOwner == -1 || dot > bestSim -> {
                    // Novo lider: o lider anterior (de outra pessoa) vira vice.
                    if (bestOwner != -1) {
                        secondSim = bestSim
                        secondOwner = bestOwner
                    }
                    bestSim = dot
                    bestOwner = owner
                }
                dot > secondSim -> {
                    secondSim = dot
                    secondOwner = owner
                }
            }
        }

        if (bestOwner == -1) return null
        return GalleryMatch(
            personId = personIds[bestOwner],
            personName = personNames[bestOwner],
            similarity = bestSim,
            runnerUpSimilarity = if (secondOwner == -1) -1f else secondSim,
        )
    }

    companion object {
        private const val DIM = RecognitionTuning.EMBEDDING_SIZE

        /** Constroi o indice. Chame uma vez por varredura, nao por rosto. */
        fun build(people: List<PersonPrototypes>): FaceGallery {
            val valid = people.filter { person ->
                person.prototypes.any { it.size == DIM }
            }
            var total = 0
            for (person in valid) {
                for (proto in person.prototypes) if (proto.size == DIM) total++
            }

            val data = FloatArray(total * DIM)
            val ownerIndex = IntArray(total)
            val ids = Array(valid.size) { valid[it].personId }
            val names = Array(valid.size) { valid[it].personName }

            var vector = 0
            for ((personIdx, person) in valid.withIndex()) {
                for (proto in person.prototypes) {
                    if (proto.size != DIM) continue
                    System.arraycopy(proto, 0, data, vector * DIM, DIM)
                    ownerIndex[vector] = personIdx
                    vector++
                }
            }
            return FaceGallery(data, ownerIndex, ids, names, total)
        }

        /** Similaridade de cosseno entre dois vetores ja normalizados. */
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size) return 0f
            var dot = 0f
            for (i in a.indices) dot += a[i] * b[i]
            return dot
        }

        /**
         * Decide se [candidate] acrescenta informacao ao conjunto de
         * prototipos, e devolve a nova lista.
         *
         * Politica:
         *  - se ja e muito parecido com algum prototipo existente, descarta
         *    (nao adianta guardar a decima foto da mesma pose);
         *  - se ainda ha espaco, adiciona;
         *  - se esta cheio, substitui o prototipo mais redundante (o que tem a
         *    maior similaridade media com os demais), mantendo diversidade.
         */
        fun mergePrototype(existing: List<FloatArray>, candidate: FloatArray): List<FloatArray> {
            if (candidate.size != DIM) return existing
            if (existing.isEmpty()) return listOf(candidate)

            var maxSim = Float.NEGATIVE_INFINITY
            for (proto in existing) {
                val sim = cosineSimilarity(proto, candidate)
                if (sim > maxSim) maxSim = sim
            }
            if (maxSim >= RecognitionTuning.PROTOTYPE_REDUNDANCY_THRESHOLD) return existing

            if (existing.size < RecognitionTuning.MAX_PROTOTYPES_PER_PERSON) {
                return existing + candidate
            }

            var mostRedundant = 0
            var highestAffinity = Float.NEGATIVE_INFINITY
            for (i in existing.indices) {
                var affinity = 0f
                for (j in existing.indices) {
                    if (i != j) affinity += cosineSimilarity(existing[i], existing[j])
                }
                if (affinity > highestAffinity) {
                    highestAffinity = affinity
                    mostRedundant = i
                }
            }
            return existing.toMutableList().also { it[mostRedundant] = candidate }
        }
    }
}
