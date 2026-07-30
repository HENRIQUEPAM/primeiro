package com.portaretrato.app.recognition

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.OnCanceledListener
import com.google.android.gms.tasks.OnFailureListener
import com.google.android.gms.tasks.OnSuccessListener
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Deteccao de rostos em dois estagios.
 *
 * O helper antigo usava um unico detector em `PERFORMANCE_MODE_ACCURATE`, com
 * `LANDMARK_MODE_ALL` e sem `setMinFaceSize`. Tres problemas:
 *
 *  - ACCURATE custa tipicamente 2x a 4x o tempo do FAST e roda em TODAS as
 *    fotos, inclusive nas faceis (retrato com um rosto grande e centralizado),
 *    que sao a maioria do acervo de um porta-retrato;
 *  - sem `minFaceSize` o detector varre escalas minusculas e devolve cabecas
 *    de 15 px de fundo de festa, que so servem para poluir a fila de revisao;
 *  - `CLASSIFICATION_MODE` ficava desligado, entao `leftEyeOpenProbability`
 *    vinha nulo e nao dava para descartar foto de olho fechado.
 *
 * Aqui roda-se FAST primeiro; ACCURATE so entra nas fotos em que o FAST nao
 * achou nada. Em um acervo tipico isso e uma minoria das fotos, entao o custo
 * medio por foto cai bastante sem perder recall.
 */
class FaceDetectors : AutoCloseable {

    private val fast: FaceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setMinFaceSize(MIN_FACE_SIZE_RATIO)
                .build(),
        )
    }

    private val accurate: FaceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_NONE)
                .setMinFaceSize(MIN_FACE_SIZE_RATIO)
                .build(),
        )
    }

    /**
     * Detecta rostos em [bitmap]. O bitmap ja deve estar na orientacao
     * correta (ver [OrientedImageDecoder]), por isso `rotationDegrees = 0`.
     *
     * @param allowAccurateFallback quando `true` e o passe rapido nao acha
     *   nada, repete em modo preciso.
     */
    suspend fun detect(bitmap: Bitmap, allowAccurateFallback: Boolean = true): List<Face> {
        val image = InputImage.fromBitmap(bitmap, 0)
        val quick = runDetector(fast, image)
        if (quick.isNotEmpty() || !allowAccurateFallback) return quick
        return runDetector(accurate, image)
    }

    private suspend fun runDetector(detector: FaceDetector, image: InputImage): List<Face> =
        suspendCancellableCoroutine { cont ->
            try {
                detector.process(image)
                    .addOnSuccessListener(
                        object : OnSuccessListener<MutableList<Face>> {
                            override fun onSuccess(result: MutableList<Face>) {
                                if (cont.isActive) cont.resume(result)
                            }
                        },
                    )
                    .addOnFailureListener(
                        object : OnFailureListener {
                            override fun onFailure(e: Exception) {
                                Log.e(TAG, "Deteccao falhou (Play Services/modelo indisponivel?)", e)
                                if (cont.isActive) cont.resumeWithException(e)
                            }
                        },
                    )
                    .addOnCanceledListener(
                        object : OnCanceledListener {
                            override fun onCanceled() {
                                if (cont.isActive) cont.resume(emptyList())
                            }
                        },
                    )
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao iniciar a deteccao", e)
                if (cont.isActive) cont.resumeWithException(e)
            }
        }

    /**
     * O helper antigo era um `object` com o detector num `by lazy` e nunca
     * chamava `close()`: o modelo do ML Kit ficava carregado pelo resto da
     * vida do processo, inclusive depois que a varredura terminava.
     */
    override fun close() {
        runCatching { fast.close() }
        runCatching { accurate.close() }
    }

    private companion object {
        const val TAG = "FaceDetectors"

        /**
         * Fracao minima da menor dimensao da imagem que um rosto precisa
         * ocupar. 0.08 num bitmap de 1024 px equivale a ~82 px de largura, na
         * mesma ordem de [RecognitionTuning.MIN_FACE_WIDTH_PX], entao o
         * detector nem gasta tempo com o que o gate de qualidade recusaria.
         */
        const val MIN_FACE_SIZE_RATIO = 0.08f
    }
}
