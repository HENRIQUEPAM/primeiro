package com.portaretrato.app.recognition

import android.graphics.Bitmap
import com.google.mlkit.vision.face.Face
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Motivo pelo qual um rosto foi recusado. Serve para telemetria e tambem para
 * as mensagens em portugues mostradas ao usuario.
 */
enum class QualityRejection {
    TOO_SMALL,
    BAD_POSE,
    EYES_CLOSED,
    BLURRY,
    TOO_DARK,
    TOO_BRIGHT,
    LOW_CONTRAST,
    LOW_SCORE,
}

/**
 * Resultado do gate de qualidade.
 *
 * @param score qualidade agregada em [0..1]; usada para ordenar rostos e para
 *   decidir se o embedding pode virar prototipo de uma pessoa.
 * @param rejection `null` quando o rosto passou.
 */
data class FaceQualityResult(
    val score: Float,
    val rejection: QualityRejection?,
    val sharpness: Double,
    val meanLuma: Double,
    val lumaStdDev: Double,
) {
    val isUsable: Boolean get() = rejection == null
    val isEnrollmentGrade: Boolean
        get() = isUsable && score >= RecognitionTuning.MIN_QUALITY_FOR_ENROLLMENT
}

/**
 * Gate de qualidade aplicado ANTES de gastar uma inferencia com o rosto.
 *
 * O pipeline antigo nao tinha nenhum filtro: qualquer coisa que o ML Kit
 * chamasse de rosto — inclusive uma cabeca de 18 px ao fundo, de perfil ou
 * completamente desfocada — virava embedding e ia parar na fila de revisao ou,
 * pior, era vinculada automaticamente a alguem. Embeddings de rostos ruins
 * caem numa regiao degenerada do espaco onde todo mundo fica parecido com
 * todo mundo, e e dai que vem a maior parte dos falsos positivos.
 *
 * Medir nitidez e luminancia sobre o crop JA ALINHADO de 112x112 (em vez do
 * recorte bruto) e o que torna os limiares comparaveis entre fotos: todos os
 * rostos chegam aqui no mesmo tamanho e no mesmo enquadramento.
 *
 * NAO e thread-safe (reaproveita buffers). Uma instancia por thread.
 */
class FaceQualityGate {

    private val pixels = IntArray(RecognitionTuning.INPUT_SIZE * RecognitionTuning.INPUT_SIZE)
    private val luma = FloatArray(RecognitionTuning.INPUT_SIZE * RecognitionTuning.INPUT_SIZE)

    /**
     * @param face rosto detectado pelo ML Kit (para pose, olhos e tamanho).
     * @param aligned crop 112x112 produzido pelo [ArcFaceAligner].
     * @param faceWidthPx largura da bounding box no bitmap decodificado.
     */
    fun evaluate(face: Face, aligned: Bitmap, faceWidthPx: Int): FaceQualityResult {
        // --- Checagens baratas primeiro: descartam sem tocar nos pixels. ---
        if (faceWidthPx < RecognitionTuning.MIN_FACE_WIDTH_PX) {
            return FaceQualityResult(0f, QualityRejection.TOO_SMALL, 0.0, 0.0, 0.0)
        }

        val yaw = abs(face.getHeadEulerAngleY())
        val roll = abs(face.getHeadEulerAngleZ())
        val pitch = abs(face.getHeadEulerAngleX())
        if (yaw > RecognitionTuning.MAX_YAW_DEGREES ||
            roll > RecognitionTuning.MAX_ROLL_DEGREES ||
            pitch > RecognitionTuning.MAX_PITCH_DEGREES
        ) {
            return FaceQualityResult(0f, QualityRejection.BAD_POSE, 0.0, 0.0, 0.0)
        }

        // Olhos fechados so reprovam quando o classificador realmente respondeu.
        val eyeOpen = averageEyeOpenProbability(face)
        if (eyeOpen != null && eyeOpen < RecognitionTuning.MIN_EYE_OPEN_PROBABILITY) {
            return FaceQualityResult(0f, QualityRejection.EYES_CLOSED, 0.0, 0.0, 0.0)
        }

        // --- Analise de pixels do crop alinhado. ---
        loadLuma(aligned)
        val meanLuma = mean()
        val stdDev = stdDev(meanLuma)
        val sharpness = laplacianVariance()

        if (meanLuma < RecognitionTuning.MIN_MEAN_LUMA) {
            return FaceQualityResult(0f, QualityRejection.TOO_DARK, sharpness, meanLuma, stdDev)
        }
        if (meanLuma > RecognitionTuning.MAX_MEAN_LUMA) {
            return FaceQualityResult(0f, QualityRejection.TOO_BRIGHT, sharpness, meanLuma, stdDev)
        }
        if (stdDev < RecognitionTuning.MIN_LUMA_STDDEV) {
            return FaceQualityResult(0f, QualityRejection.LOW_CONTRAST, sharpness, meanLuma, stdDev)
        }
        if (sharpness < RecognitionTuning.MIN_SHARPNESS) {
            return FaceQualityResult(0f, QualityRejection.BLURRY, sharpness, meanLuma, stdDev)
        }

        val score = aggregate(faceWidthPx, yaw, roll, pitch, eyeOpen, sharpness, meanLuma, stdDev)
        val rejection = if (score < RecognitionTuning.MIN_QUALITY_SCORE) QualityRejection.LOW_SCORE else null
        return FaceQualityResult(score, rejection, sharpness, meanLuma, stdDev)
    }

    private fun averageEyeOpenProbability(face: Face): Float? {
        val left = face.getLeftEyeOpenProbability()
        val right = face.getRightEyeOpenProbability()
        return when {
            left != null && right != null -> (left + right) * 0.5f
            left != null -> left
            right != null -> right
            else -> null
        }
    }

    private fun loadLuma(aligned: Bitmap) {
        val size = RecognitionTuning.INPUT_SIZE
        aligned.getPixels(pixels, 0, size, 0, 0, size, size)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            // Rec. 601 em inteiros; suficiente e mais rapido que ponto flutuante.
            luma[i] = ((r * 299 + g * 587 + b * 114) / 1000).toFloat()
        }
    }

    private fun mean(): Double {
        var sum = 0.0
        for (v in luma) sum += v
        return sum / luma.size
    }

    private fun stdDev(mean: Double): Double {
        var acc = 0.0
        for (v in luma) {
            val d = v - mean
            acc += d * d
        }
        return sqrt(acc / luma.size)
    }

    /**
     * Variancia do Laplaciano 3x3 — a metrica classica de nitidez.
     * Ignora a borda de 1 px para nao precisar tratar limites no laco interno.
     */
    private fun laplacianVariance(): Double {
        val size = RecognitionTuning.INPUT_SIZE
        var sum = 0.0
        var sumSq = 0.0
        var count = 0
        for (y in 1 until size - 1) {
            val row = y * size
            for (x in 1 until size - 1) {
                val i = row + x
                val lap = 4f * luma[i] - luma[i - 1] - luma[i + 1] - luma[i - size] - luma[i + size]
                sum += lap
                sumSq += lap.toDouble() * lap
                count++
            }
        }
        if (count == 0) return 0.0
        val m = sum / count
        return sumSq / count - m * m
    }

    /**
     * Combina os fatores num score unico. Os pesos favorecem tamanho e nitidez
     * porque sao os que mais degradam o embedding; pose entra com peso menor
     * porque o alinhamento ja compensa boa parte do roll.
     */
    private fun aggregate(
        faceWidthPx: Int,
        yaw: Float,
        roll: Float,
        pitch: Float,
        eyeOpen: Float?,
        sharpness: Double,
        meanLuma: Double,
        stdDev: Double,
    ): Float {
        // Tamanho: satura em 220 px de largura (rosto grande o bastante para
        // que 112x112 seja downscale, nunca upscale).
        val sizeScore = clamp01(
            (faceWidthPx - RecognitionTuning.MIN_FACE_WIDTH_PX).toFloat() /
                (220f - RecognitionTuning.MIN_FACE_WIDTH_PX),
        )
        // Nitidez: satura em 4x o limiar minimo.
        val sharpScore = clamp01((sharpness / (RecognitionTuning.MIN_SHARPNESS * 4.0)).toFloat())
        val poseScore = clamp01(
            1f - (yaw / RecognitionTuning.MAX_YAW_DEGREES) * 0.5f -
                (pitch / RecognitionTuning.MAX_PITCH_DEGREES) * 0.3f -
                (roll / RecognitionTuning.MAX_ROLL_DEGREES) * 0.2f,
        )
        // Exposicao: 1.0 no centro da faixa util, caindo para as bordas.
        val mid = (RecognitionTuning.MIN_MEAN_LUMA + RecognitionTuning.MAX_MEAN_LUMA) / 2.0
        val halfRange = (RecognitionTuning.MAX_MEAN_LUMA - RecognitionTuning.MIN_MEAN_LUMA) / 2.0
        val exposureScore = clamp01((1.0 - abs(meanLuma - mid) / halfRange).toFloat())
        val contrastScore = clamp01((stdDev / (RecognitionTuning.MIN_LUMA_STDDEV * 3.0)).toFloat())
        val eyeScore = eyeOpen ?: 0.8f // sem classificador, assume neutro-bom

        return clamp01(
            0.30f * sizeScore +
                0.28f * sharpScore +
                0.18f * poseScore +
                0.10f * exposureScore +
                0.08f * contrastScore +
                0.06f * eyeScore,
        )
    }
}

internal fun clamp01(v: Float): Float = min(1f, max(0f, v))
