package com.portaretrato.app.recognition

/**
 * Decisao tomada para um rosto.
 *
 * Comparado ao `MatchResult` antigo (AutoLink / Suggest / Unknown), aqui ha
 * uma quarta saida — [Rejected] — para rostos que nem deveriam ter entrado no
 * pipeline. Antes eles caiam em `Unknown` e iam para a fila de revisao, que e
 * exatamente o que faz a fila encher de miniaturas borradas que o usuario nao
 * consegue identificar.
 */
sealed interface RecognitionDecision {

    /** Confianca alta e folga sobre o segundo colocado: vincula sozinho. */
    data class AutoLink(
        val personId: String,
        val personName: String,
        val similarity: Float,
        val margin: Float,
    ) : RecognitionDecision

    /** Provavel, mas pede confirmacao humana. */
    data class Suggest(
        val personId: String,
        val personName: String,
        val similarity: Float,
        val margin: Float,
    ) : RecognitionDecision

    /** Rosto bom, pessoa desconhecida: vai para a fila de cadastro. */
    data object Unknown : RecognitionDecision

    /** Rosto de qualidade insuficiente: descartado sem incomodar o usuario. */
    data class Rejected(val reason: QualityRejection) : RecognitionDecision
}

object RecognitionMatcher {

    /**
     * Classifica um embedding contra a galeria.
     *
     * Duas condicoes precisam valer para o vinculo automatico:
     *
     *  1. `similarity >= AUTO_LINK_THRESHOLD` — confianca absoluta;
     *  2. `margin >= AUTO_LINK_MARGIN` — folga sobre a segunda pessoa.
     *
     * A segunda condicao e o que mais derruba falso positivo numa galeria de
     * familia. Irmaos e pais/filhos produzem embeddings proximos; quando dois
     * candidatos empatam, o certo e perguntar, nao chutar. O pipeline antigo
     * so olhava o valor absoluto e sempre escolhia o maior, mesmo com empate
     * tecnico.
     *
     * A qualidade do rosto tambem entra: rostos apenas medianos exigem
     * confianca um pouco maior para vincular sozinhos. Isso substitui um
     * limiar fixo por um limiar adaptativo sem precisar de tabela nenhuma.
     */
    fun classify(
        embedding: FloatArray,
        gallery: FaceGallery,
        quality: FaceQualityResult,
    ): RecognitionDecision {
        quality.rejection?.let { return RecognitionDecision.Rejected(it) }

        val match = gallery.match(embedding) ?: return RecognitionDecision.Unknown

        // Rosto no limite da qualidade paga ate +0.05 de limiar; rosto otimo
        // usa o limiar nominal.
        val qualityPenalty = (1f - quality.score) * 0.05f
        val autoThreshold = RecognitionTuning.AUTO_LINK_THRESHOLD + qualityPenalty

        return when {
            match.similarity >= autoThreshold && match.margin >= RecognitionTuning.AUTO_LINK_MARGIN ->
                RecognitionDecision.AutoLink(
                    personId = match.personId,
                    personName = match.personName,
                    similarity = match.similarity,
                    margin = match.margin,
                )

            match.similarity >= RecognitionTuning.SUGGEST_THRESHOLD ->
                RecognitionDecision.Suggest(
                    personId = match.personId,
                    personName = match.personName,
                    similarity = match.similarity,
                    margin = match.margin,
                )

            else -> RecognitionDecision.Unknown
        }
    }

    /**
     * Usado quando o usuario digita um nome que ja existe: o app precisa
     * decidir entre "e a mesma pessoa" e "e um homonimo".
     */
    fun isLikelySamePerson(embedding: FloatArray, prototypes: List<FloatArray>): Boolean {
        var best = Float.NEGATIVE_INFINITY
        for (proto in prototypes) {
            val sim = FaceGallery.cosineSimilarity(embedding, proto)
            if (sim > best) best = sim
        }
        return best >= RecognitionTuning.SAME_NAME_CONFIRM_THRESHOLD
    }
}
