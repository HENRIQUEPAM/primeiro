package com.portaretrato.app.recognition

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.google.mlkit.vision.face.Face
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.channels.FileChannel
import kotlin.math.sqrt

/**
 * Extrator de embeddings MobileFaceNet.
 *
 * Substitui `FaceEmbeddingHelper`. Diferencas que importam:
 *
 * 1. **Zero alocacao no caminho quente.** O helper antigo alocava, POR ROSTO:
 *    um bitmap do tamanho da foto inteira (rotacao), um bitmap de crop, um
 *    bitmap 112x112, um `IntArray(12544)`, um `ByteBuffer` direto de 150 KB e
 *    um `Array(1){FloatArray(192)}`. Numa varredura de 2.000 fotos com 2
 *    rostos cada isso e ~4.000 ciclos de alocacao/GC de buffers grandes, e o
 *    bitmap de rotacao sozinho podia passar de 12 MB. Aqui todos esses
 *    buffers sao campos reaproveitados.
 *
 * 2. **Preenchimento do tensor em bloco.** Trocamos 37.632 chamadas a
 *    `ByteBuffer.putFloat` por um `FloatBuffer.put(FloatArray)`, que compila
 *    para uma copia de memoria.
 *
 * 3. **XNNPACK explicito e 2 threads.** Ver [RecognitionTuning].
 *
 * 4. **Fecha o interpretador.** O singleton antigo nunca liberava o modelo nem
 *    o `MappedByteBuffer` de 5 MB.
 *
 * NAO e thread-safe: `Interpreter` tambem nao e. Uma instancia por thread de
 * trabalho, ou serialize as chamadas.
 */
class FaceEmbedder(
    context: Context,
    private val aligner: ArcFaceAligner = ArcFaceAligner(),
) : AutoCloseable {

    private val interpreter: Interpreter = createInterpreter(context)

    private val pixels = IntArray(RecognitionTuning.INPUT_SIZE * RecognitionTuning.INPUT_SIZE)
    private val floats = FloatArray(RecognitionTuning.INPUT_SIZE * RecognitionTuning.INPUT_SIZE * 3)
    private val inputBuffer: ByteBuffer =
        ByteBuffer.allocateDirect(floats.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder())
    private val inputFloats: FloatBuffer = inputBuffer.asFloatBuffer()
    private val output: Array<FloatArray> = arrayOf(FloatArray(RecognitionTuning.EMBEDDING_SIZE))

    /**
     * Alinha e extrai o embedding de [face] dentro de [source].
     * Devolve um `FloatArray` novo, L2-normalizado, ou `null` em falha.
     *
     * O array de saida e copiado porque vai para a galeria/banco; e a unica
     * alocacao por rosto que sobra (768 bytes).
     */
    fun embed(source: Bitmap, face: Face): FloatArray? {
        val aligned = aligner.align(source, face) ?: return null
        return embedAligned(aligned)
    }

    /** Extrai o embedding de um crop 112x112 ja alinhado. */
    fun embedAligned(aligned: Bitmap): FloatArray? = try {
        fillInput(aligned)
        interpreter.run(inputBuffer, output)
        normalized(output[0])
    } catch (e: Exception) {
        Log.e(TAG, "Falha na inferencia do embedding", e)
        null
    }

    /**
     * Alinha o rosto e devolve o crop 112x112 interno para inspecao (gate de
     * qualidade, miniatura de revisao). O bitmap e reaproveitado — copie antes
     * de guardar.
     */
    fun align(source: Bitmap, face: Face): Bitmap? = aligner.align(source, face)

    private fun fillInput(bitmap: Bitmap) {
        val size = RecognitionTuning.INPUT_SIZE
        bitmap.getPixels(pixels, 0, size, 0, 0, size, size)
        var j = 0
        for (i in pixels.indices) {
            val p = pixels[i]
            // Mesma normalizacao do treino: (x - 127.5) / 128, canais em RGB.
            floats[j++] = (((p shr 16) and 0xFF) - 127.5f) * INV_128
            floats[j++] = (((p shr 8) and 0xFF) - 127.5f) * INV_128
            floats[j++] = ((p and 0xFF) - 127.5f) * INV_128
        }
        inputFloats.clear()
        inputFloats.put(floats)
        inputBuffer.rewind()
    }

    private fun normalized(raw: FloatArray): FloatArray {
        var sumSquares = 0.0
        for (v in raw) sumSquares += v.toDouble() * v
        val norm = sqrt(sumSquares).toFloat()
        val scale = if (norm > 0f) 1f / norm else 1f
        val result = FloatArray(raw.size)
        for (i in raw.indices) result[i] = raw[i] * scale
        return result
    }

    override fun close() {
        interpreter.close()
        aligner.release()
    }

    private companion object {
        const val TAG = "FaceEmbedder"
        const val INV_128 = 1f / 128f

        fun createInterpreter(context: Context): Interpreter {
            val afd = context.getAssets().openFd(RecognitionTuning.MODEL_FILE)
            // `use` garante o fechamento do descritor — o codigo antigo vazava
            // um AssetFileDescriptor e um FileInputStream por criacao.
            val model = FileInputStream(afd.getFileDescriptor()).use { stream ->
                stream.channel.map(FileChannel.MapMode.READ_ONLY, afd.getStartOffset(), afd.getDeclaredLength())
            }
            afd.close()
            val options = Interpreter.Options()
                .setNumThreads(RecognitionTuning.INTERPRETER_THREADS)
                // XNNPACK ja e default para float em TFLite 2.x, mas deixamos
                // explicito para nao depender do default da versao.
                .setUseXNNPACK(true)
                // NNAPI fica desligado de proposito: em muitos aparelhos o
                // custo de compilacao do grafo (centenas de ms na primeira
                // inferencia) nao se paga num modelo deste tamanho, e alguns
                // drivers quantizam internamente e mudam o embedding.
                .setUseNNAPI(false)
            return Interpreter(model, options)
        }
    }
}
