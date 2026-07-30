package com.portaretrato.app.recognition

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.Rect
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.abs
import kotlin.math.max

/**
 * Alinhamento facial de 5 pontos no padrao ArcFace/InsightFace.
 *
 * Por que isto importa mais que qualquer outra mudanca de precisao:
 * MobileFaceNet foi treinado com crops gerados por uma transformacao de
 * similaridade que leva 5 landmarks para posicoes canonicas fixas dentro de
 * 112x112. Alimentar o modelo com um recorte da bounding box do detector —
 * mesmo rotacionado — coloca olhos, nariz e boca em posicoes diferentes das
 * que o modelo viu no treino. O embedding continua saindo, mas passa a
 * codificar enquadramento junto com identidade: aproxima pessoas diferentes
 * enquadradas igual e afasta a mesma pessoa enquadrada diferente.
 *
 * Substitui o par "rotacionar a foto inteira + recortar + createScaledBitmap".
 * Alem de correto, e uma unica operacao de desenho num bitmap 112x112
 * reaproveitado: zero alocacao por rosto.
 *
 * NAO e thread-safe (reaproveita buffers). Use uma instancia por thread.
 */
class ArcFaceAligner {

    private val output: Bitmap =
        Bitmap.createBitmap(
            RecognitionTuning.INPUT_SIZE,
            RecognitionTuning.INPUT_SIZE,
            Bitmap.Config.ARGB_8888,
        )
    private val canvas = Canvas(output)
    private val paint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = true
        isDither = false
    }
    private val matrix = Matrix()
    private val matrixValues = FloatArray(9)

    private val srcX = FloatArray(5)
    private val srcY = FloatArray(5)

    /**
     * Alinha [face] a partir de [source] e devolve o bitmap 112x112 interno.
     *
     * O bitmap devolvido e REAPROVEITADO na proxima chamada: consuma-o antes
     * de alinhar o proximo rosto (o [FaceEmbedder] faz exatamente isso) e
     * nunca chame `recycle()` nele.
     *
     * Devolve `null` apenas quando a bounding box e degenerada.
     */
    fun align(source: Bitmap, face: Face): Bitmap? =
        if (collectLandmarks(face)) {
            drawSimilarityTransform(source)
            output
        } else {
            drawBoundingBoxFallback(source, face)
        }

    /**
     * Preenche [srcX]/[srcY] na ordem canonica do ArcFace, em ESPACO DE IMAGEM:
     * 0 = olho a esquerda na imagem, 1 = olho a direita na imagem,
     * 2 = base do nariz, 3 = canto da boca a esquerda, 4 = canto a direita.
     *
     * Os pares olho/boca sao ordenados pela coordenada x em tempo de execucao
     * em vez de confiar na nomenclatura do ML Kit — `LEFT_EYE` e o olho
     * esquerdo DO SUJEITO, que aparece a direita na imagem. O codigo antigo
     * calculava `atan2(rightEye.y - leftEye.y, rightEye.x - leftEye.x)` com
     * esses nomes; com dx negativo o angulo dava ~180 graus e a foto era
     * rotacionada de cabeca para baixo. Ordenar por x elimina a dependencia
     * da convencao e a classe inteira de bug.
     */
    private fun collectLandmarks(face: Face): Boolean {
        val eyeA = face.getLandmark(FaceLandmark.LEFT_EYE)?.getPosition() ?: return false
        val eyeB = face.getLandmark(FaceLandmark.RIGHT_EYE)?.getPosition() ?: return false
        val nose = face.getLandmark(FaceLandmark.NOSE_BASE)?.getPosition() ?: return false
        val mouthA = face.getLandmark(FaceLandmark.MOUTH_LEFT)?.getPosition() ?: return false
        val mouthB = face.getLandmark(FaceLandmark.MOUTH_RIGHT)?.getPosition() ?: return false

        val leftEye: PointF
        val rightEye: PointF
        if (eyeA.x <= eyeB.x) { leftEye = eyeA; rightEye = eyeB } else { leftEye = eyeB; rightEye = eyeA }

        val leftMouth: PointF
        val rightMouth: PointF
        if (mouthA.x <= mouthB.x) { leftMouth = mouthA; rightMouth = mouthB } else { leftMouth = mouthB; rightMouth = mouthA }

        // Olhos coincidentes => landmarks nao confiaveis, cai no fallback.
        if (abs(rightEye.x - leftEye.x) < 1f && abs(rightEye.y - leftEye.y) < 1f) return false

        srcX[0] = leftEye.x;    srcY[0] = leftEye.y
        srcX[1] = rightEye.x;   srcY[1] = rightEye.y
        srcX[2] = nose.x;       srcY[2] = nose.y
        srcX[3] = leftMouth.x;  srcY[3] = leftMouth.y
        srcX[4] = rightMouth.x; srcY[4] = rightMouth.y
        return true
    }

    /**
     * Transformacao de similaridade de minimos quadrados (forma fechada 2D de
     * Umeyama) levando os 5 pontos detectados aos 5 pontos de referencia.
     *
     * Com os centroides removidos:
     *   a = sum(sx*dx + sy*dy),  b = sum(sx*dy - sy*dx),  den = sum(sx^2 + sy^2)
     *   c = a/den (cosseno escalado), s = b/den (seno escalado)
     * Rotacao e escala saem juntas — exatamente o que o ArcFace usa.
     */
    private fun drawSimilarityTransform(source: Bitmap) {
        var muSx = 0f; var muSy = 0f; var muDx = 0f; var muDy = 0f
        for (i in 0 until 5) {
            muSx += srcX[i]; muSy += srcY[i]
            muDx += REFERENCE_X[i]; muDy += REFERENCE_Y[i]
        }
        muSx /= 5f; muSy /= 5f; muDx /= 5f; muDy /= 5f

        var a = 0f; var b = 0f; var den = 0f
        for (i in 0 until 5) {
            val sx = srcX[i] - muSx
            val sy = srcY[i] - muSy
            val dx = REFERENCE_X[i] - muDx
            val dy = REFERENCE_Y[i] - muDy
            a += sx * dx + sy * dy
            b += sx * dy - sy * dx
            den += sx * sx + sy * sy
        }
        if (den < 1e-6f) den = 1e-6f
        val c = a / den
        val s = b / den

        // Android Matrix (row-major 3x3):
        //   x' = MSCALE_X*x + MSKEW_X*y + MTRANS_X
        //   y' = MSKEW_Y*x + MSCALE_Y*y + MTRANS_Y
        applyMatrix(
            scaleX = c, skewX = -s, transX = muDx - c * muSx + s * muSy,
            skewY = s, scaleY = c, transY = muDy - s * muSx - c * muSy,
        )
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(source, matrix, paint)
    }

    /**
     * Fallback sem landmarks: quadrado centrado na bounding box, escalado para
     * 112x112 preservando a proporcao. O codigo antigo distorcia a imagem ao
     * forcar um retangulo dentro de um quadrado via `createScaledBitmap`.
     */
    private fun drawBoundingBoxFallback(source: Bitmap, face: Face): Bitmap? {
        val box: Rect = face.getBoundingBox()
        val cx = (box.left + box.right) * 0.5f
        val cy = (box.top + box.bottom) * 0.5f
        val side = max(box.width(), box.height()) * BBOX_FALLBACK_SCALE
        if (side < 2f) return null

        val scale = RecognitionTuning.INPUT_SIZE / side
        val half = RecognitionTuning.INPUT_SIZE * 0.5f
        applyMatrix(
            scaleX = scale, skewX = 0f, transX = half - scale * cx,
            skewY = 0f, scaleY = scale, transY = half - scale * cy,
        )
        canvas.drawColor(Color.BLACK)
        canvas.drawBitmap(source, matrix, paint)
        return output
    }

    private fun applyMatrix(
        scaleX: Float, skewX: Float, transX: Float,
        skewY: Float, scaleY: Float, transY: Float,
    ) {
        matrixValues[0] = scaleX; matrixValues[1] = skewX;  matrixValues[2] = transX
        matrixValues[3] = skewY;  matrixValues[4] = scaleY; matrixValues[5] = transY
        matrixValues[6] = 0f;     matrixValues[7] = 0f;     matrixValues[8] = 1f
        matrix.setValues(matrixValues)
    }

    fun release() {
        canvas.setBitmap(null)
        if (!output.isRecycled()) output.recycle()
    }

    private companion object {
        /**
         * Pontos de referencia canonicos do ArcFace para entrada 112x112.
         * Ordem: olho esq. (imagem), olho dir., nariz, boca esq., boca dir.
         */
        val REFERENCE_X = floatArrayOf(38.2946f, 73.5318f, 56.0252f, 41.5493f, 70.7299f)
        val REFERENCE_Y = floatArrayOf(51.6963f, 51.5014f, 71.7366f, 92.3655f, 92.2041f)

        /** Folga aplicada ao lado da bounding box no fallback. */
        const val BBOX_FALLBACK_SCALE = 1.35f
    }
}
