package com.portaretrato.app.recognition

import android.graphics.Rect
import android.util.Log
import java.io.File

/** O que o pipeline decidiu sobre um rosto de uma foto. */
data class ScannedFace(
    val boundingBox: Rect,
    val embedding: FloatArray,
    val quality: FaceQualityResult,
    val decision: RecognitionDecision,
) {
    // data class com FloatArray precisa de equals/hashCode manuais para nao
    // comparar por referencia.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ScannedFace) return false
        return boundingBox == other.boundingBox &&
            embedding.contentEquals(other.embedding) &&
            quality == other.quality &&
            decision == other.decision
    }

    override fun hashCode(): Int {
        var result = boundingBox.hashCode()
        result = 31 * result + embedding.contentHashCode()
        result = 31 * result + quality.hashCode()
        result = 31 * result + decision.hashCode()
        return result
    }
}

/** Resultado completo de uma foto. */
data class ScannedPhoto(
    val photoId: String,
    val faces: List<ScannedFace>,
    val rejectedCount: Int,
    val error: String? = null,
)

/**
 * Orquestra decodificacao -> deteccao -> alinhamento -> qualidade -> embedding
 * -> matching para uma foto.
 *
 * Deliberadamente **sem dependencia de repositorio ou de Firestore**: recebe
 * a galeria pronta e devolve decisoes. Quem chama (o `FaceScanWorker`) e que
 * persiste, o que permite agrupar todas as escritas de uma foto num unico
 * `WriteBatch` em vez de uma escrita por rosto.
 *
 * Substitui a lambda `doWork$handled$1` do worker atual, que fazia deteccao,
 * inferencia, matching, atualizacao de pessoa e enfileiramento de revisao
 * dentro do mesmo bloco, com uma escrita de rede awaitada por rosto.
 *
 * NAO e thread-safe: guarda buffers reaproveitados. Uma instancia por worker.
 */
class PhotoScanPipeline(
    private val detectors: FaceDetectors,
    private val embedder: FaceEmbedder,
    private val qualityGate: FaceQualityGate = FaceQualityGate(),
) : AutoCloseable {

    /**
     * Processa uma foto local.
     *
     * @param gallery indice construido UMA vez por varredura (nao por foto).
     */
    suspend fun scan(photoId: String, file: File, gallery: FaceGallery): ScannedPhoto {
        val bitmap = OrientedImageDecoder.decode(file)
            ?: return ScannedPhoto(photoId, emptyList(), 0, "bitmap_indisponivel")

        return try {
            val faces = detectors.detect(bitmap)
            if (faces.isEmpty()) return ScannedPhoto(photoId, emptyList(), 0)

            val accepted = ArrayList<ScannedFace>(faces.size)
            var rejected = 0

            for (face in faces) {
                val box = face.getBoundingBox()

                // Alinha uma vez e reaproveita o mesmo crop para o gate de
                // qualidade e para a inferencia. O codigo antigo recortava
                // duas vezes (uma para o embedding, outra para a miniatura).
                val aligned = embedder.align(bitmap, face)
                if (aligned == null) {
                    rejected++
                    continue
                }

                val quality = qualityGate.evaluate(face, aligned, box.width())
                if (!quality.isUsable) {
                    // Descarta ANTES de gastar a inferencia: e a economia de
                    // CPU mais barata do pipeline inteiro.
                    rejected++
                    continue
                }

                val embedding = embedder.embedAligned(aligned)
                if (embedding == null) {
                    rejected++
                    continue
                }

                accepted += ScannedFace(
                    boundingBox = Rect(box.left, box.top, box.right, box.bottom),
                    embedding = embedding,
                    quality = quality,
                    decision = RecognitionMatcher.classify(embedding, gallery, quality),
                )
            }

            ScannedPhoto(photoId, accepted, rejected)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao processar a foto $photoId", e)
            ScannedPhoto(photoId, emptyList(), 0, e.message ?: e.javaClass.simpleName)
        } finally {
            bitmap.recycle()
        }
    }

    override fun close() {
        detectors.close()
        embedder.close()
    }

    private companion object {
        const val TAG = "PhotoScanPipeline"
    }
}
