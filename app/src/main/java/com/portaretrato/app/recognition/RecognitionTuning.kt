package com.portaretrato.app.recognition

/**
 * Todos os parametros ajustaveis do pipeline de reconhecimento em um unico lugar.
 *
 * Os valores default assumem o pipeline novo: alinhamento ArcFace de 5 pontos +
 * MobileFaceNet 112x112 (embedding de 192 dimensoes, L2-normalizado).
 * Trocar o alinhamento ou o modelo INVALIDA estes limiares — eles precisam ser
 * remedidos, porque similaridade de cosseno nao e comparavel entre pipelines
 * de pre-processamento diferentes.
 */
object RecognitionTuning {

    // ---------------------------------------------------------------- modelo

    const val MODEL_FILE = "mobilefacenet.tflite"
    const val INPUT_SIZE = 112
    const val EMBEDDING_SIZE = 192

    /**
     * Threads do XNNPACK. 2 e o ponto otimo medido para modelos de ~1M de
     * parametros: 4 threads disputam o cluster "little" e gastam mais bateria
     * sem ganho de latencia proporcional.
     */
    const val INTERPRETER_THREADS = 2

    // ------------------------------------------------------------ decodificacao

    /** Maior dimensao do bitmap decodificado para deteccao + embedding. */
    const val DECODE_MAX_DIMENSION = 1024

    // ------------------------------------------------------------- qualidade

    /**
     * Largura minima da bounding box, em pixels do bitmap decodificado.
     * Abaixo disso o crop 112x112 e um upscale e o embedding vira ruido —
     * causa raiz de boa parte dos falsos positivos.
     */
    const val MIN_FACE_WIDTH_PX = 80

    /** Rotacao (yaw) maxima aceita: cabeca virada para o lado. */
    const val MAX_YAW_DEGREES = 32f

    /** Inclinacao (roll) maxima aceita ANTES do alinhamento corrigir. */
    const val MAX_ROLL_DEGREES = 35f

    /** Inclinacao (pitch) maxima aceita: cabeca para cima/baixo. */
    const val MAX_PITCH_DEGREES = 25f

    /** Probabilidade minima de olho aberto (media dos dois olhos). */
    const val MIN_EYE_OPEN_PROBABILITY = 0.35f

    /**
     * Variancia do Laplaciano minima sobre o crop alinhado de 112x112 em
     * escala de cinza [0..255]. Como o crop tem sempre o mesmo tamanho, este
     * limiar e comparavel entre fotos — o que nao acontece se a nitidez for
     * medida no recorte bruto.
     */
    const val MIN_SHARPNESS = 55.0

    /** Faixa de luminancia media aceita no crop alinhado. */
    const val MIN_MEAN_LUMA = 45.0
    const val MAX_MEAN_LUMA = 225.0

    /** Contraste minimo (desvio padrao da luminancia) no crop alinhado. */
    const val MIN_LUMA_STDDEV = 18.0

    /** Qualidade minima [0..1] para o rosto entrar no pipeline. */
    const val MIN_QUALITY_SCORE = 0.45f

    /** Qualidade minima para um embedding virar prototipo de uma pessoa. */
    const val MIN_QUALITY_FOR_ENROLLMENT = 0.62f

    // -------------------------------------------------------------- matching

    /**
     * Vinculo automatico. Subiu de 0.52 para 0.62: com o alinhamento correto a
     * distribuicao de similaridade entre pessoas diferentes se desloca para
     * baixo e a de mesma pessoa para cima, entao da para operar num ponto bem
     * mais conservador SEM perder recall.
     */
    const val AUTO_LINK_THRESHOLD = 0.62f

    /** Abaixo disso nem sugerimos — 0.30 era ruido estatistico. */
    const val SUGGEST_THRESHOLD = 0.45f

    /**
     * Margem exigida entre a melhor pessoa e a segunda melhor pessoa distinta.
     * Este teste e o que mais reduz falso positivo em galerias com familiares
     * parecidos: se dois candidatos empatam, ninguem e escolhido sozinho.
     */
    const val AUTO_LINK_MARGIN = 0.06f

    /** Confirmacao de "mesma pessoa" quando o nome digitado ja existe. */
    const val SAME_NAME_CONFIRM_THRESHOLD = 0.50f

    // ------------------------------------------------------------ prototipos

    /** Prototipos guardados por pessoa (max-similarity entre eles). */
    const val MAX_PROTOTYPES_PER_PERSON = 8

    /**
     * Um novo embedding so vira prototipo novo se for suficientemente
     * diferente dos que ja existem. Acima disso e redundante e apenas gasta
     * espaco e tempo de comparacao.
     */
    const val PROTOTYPE_REDUNDANCY_THRESHOLD = 0.88f
}
