package com.portaretrato.app.photo

import android.graphics.Bitmap
import android.graphics.Rect
import com.portaretrato.app.recognition.OrientedImageDecoder
import com.portaretrato.app.recognition.RecognitionTuning
import java.io.File

/**
 * Recorta o rosto de uma foto para a tela de revisão.
 *
 * ## Por que o recorte é feito na hora, e não guardado
 *
 * Guardar uma miniatura por rosto significaria um segundo acervo em disco, com
 * o dado mais sensível que o app produz (um recorte de rosto) duplicado num
 * arquivo a mais para apagar. A foto original já está ali; recortar custa uma
 * decodificação, e a tela de revisão mostra um rosto por vez.
 *
 * ## Por que a decodificação usa exatamente [RecognitionTuning.DECODE_MAX_DIMENSION]
 *
 * As caixas guardadas em [com.portaretrato.app.people.PendingFace] estão no
 * sistema de coordenadas do bitmap que o pipeline decodificou — e o pipeline usa
 * esse mesmo limite. Decodificar aqui com outro tamanho deslocaria o recorte, e
 * o usuário veria um pedaço de ombro em vez do rosto. `sampleSizeFor` é
 * determinístico para o mesmo arquivo e o mesmo limite, então as coordenadas
 * batem sem precisar normalizar nada.
 */
object FaceThumbnails {

    /**
     * @param margin fração da caixa acrescentada em volta. Um rosto recortado
     *   exatamente na caixa do detector fica claustrofóbico e sem cabelo nem
     *   queixo — justamente o que ajuda alguém a reconhecer a pessoa.
     */
    fun crop(
        file: File,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        margin: Float = DEFAULT_MARGIN,
    ): Bitmap? {
        val source = OrientedImageDecoder.decode(file, RecognitionTuning.DECODE_MAX_DIMENSION)
            ?: return null
        val cropped = try {
            val box = expand(Rect(left, top, right, bottom), margin, source.width, source.height)
            if (box.width() <= 0 || box.height() <= 0) {
                null
            } else {
                Bitmap.createBitmap(source, box.left, box.top, box.width(), box.height())
            }
        } catch (e: Exception) {
            null
        }

        // Libera o original — são dezenas de MB para exibir um recorte de 200 px.
        // A comparação por identidade é obrigatória: quando o recorte cobre a
        // imagem inteira, `createBitmap` devolve o PRÓPRIO source, e reciclar
        // aqui destruiria o bitmap que está sendo devolvido.
        if (cropped !== source && !source.isRecycled()) source.recycle()
        return cropped
    }

    internal fun expand(box: Rect, margin: Float, width: Int, height: Int): Rect {
        val dx = (box.width() * margin).toInt()
        val dy = (box.height() * margin).toInt()
        return Rect(
            (box.left - dx).coerceIn(0, width),
            (box.top - dy).coerceIn(0, height),
            (box.right + dx).coerceIn(0, width),
            (box.bottom + dy).coerceIn(0, height),
        )
    }

    private const val DEFAULT_MARGIN = 0.25f
}
