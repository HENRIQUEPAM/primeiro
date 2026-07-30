package com.portaretrato.app.recognition

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File

/**
 * Decodificacao de foto corrigindo dois defeitos do `PhotoStorageHelper`:
 *
 * **1. O calculo do `inSampleSize` estava errado.** O laco antigo era
 * `while (w/(sample*2) < max) return sample else sample *= 2`, ou seja, parava
 * no sample em que DOBRAR ainda caberia. Resultado: a maior dimensao final
 * ficava sempre no intervalo `[max, 2*max)` — pedindo 1024 px, o app decodava
 * ate 2048 px. Como memoria de bitmap cresce com o quadrado, isso e ate 4x o
 * consumo pretendido: 16 MB por foto em vez de 4 MB. Era a causa mais provavel
 * dos `OutOfMemoryError` que o codigo antigo engolia em silencio em varios
 * pontos.
 *
 * **2. EXIF era ignorado.** Fotos tiradas com o celular na vertical costumam
 * ser gravadas na horizontal com a orientacao so no EXIF. O bitmap ia deitado
 * para o ML Kit com `rotationDegrees = 0`, o detector perdia rostos girados
 * 90 graus, e cada rosto perdido virava um falso negativo silencioso — foto
 * marcada como revisada sem ninguem dentro.
 */
object OrientedImageDecoder {

    private const val TAG = "OrientedImageDecoder"

    /**
     * Decodifica [file] com a maior dimensao <= [maxDimension] e ja na
     * orientacao correta segundo o EXIF.
     */
    fun decode(file: File, maxDimension: Int = RecognitionTuning.DECODE_MAX_DIMENSION): Bitmap? = try {
        val path = file.absolutePath

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxDimension)
                // ARGB_8888 de proposito: RGB_565 perde 3 bits por canal e
                // degrada mediveis do embedding. A economia de memoria vem do
                // inSampleSize correto, nao de reduzir a profundidade de cor.
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            BitmapFactory.decodeFile(path, opts)?.let { applyExifOrientation(it, path) }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Falha ao decodificar ${file.name}", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.e(TAG, "Sem memoria ao decodificar ${file.name}", e)
        null
    }

    /**
     * Maior potencia de 2 tal que ambas as dimensoes fiquem <= [maxDimension].
     * Contrato oposto ao do codigo antigo: aqui o resultado nunca ultrapassa o
     * limite pedido.
     */
    internal fun sampleSizeFor(width: Int, height: Int, maxDimension: Int): Int {
        var sample = 1
        while (width / sample > maxDimension || height / sample > maxDimension) {
            sample *= 2
        }
        return sample
    }

    private fun applyExifOrientation(bitmap: Bitmap, path: String): Bitmap {
        val orientation = try {
            ExifInterface(path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (e: Exception) {
            Log.w(TAG, "EXIF ilegivel, assumindo orientacao normal")
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap // Caso comum: nada a fazer, sem copia.
        }

        return try {
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (rotated !== bitmap) bitmap.recycle()
            rotated
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Sem memoria ao rotacionar; usando bitmap original", e)
            bitmap
        }
    }
}
