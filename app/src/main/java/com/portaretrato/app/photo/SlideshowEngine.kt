package com.portaretrato.app.photo

import kotlin.random.Random

/** Como as fotos se sucedem. */
enum class SlideshowOrder {
    /** Ordem em que foram adicionadas. Previsível, boa para narrativa. */
    SEQUENTIAL,

    /**
     * Aleatória, mas percorrendo todas antes de repetir qualquer uma.
     * Sortear a cada passo faria a mesma foto reaparecer duas vezes seguidas e
     * outras sumirem por horas — num porta-retrato ligado o dia inteiro, a
     * diferença é visível e incomoda.
     */
    SHUFFLE,
}

/** Como uma foto é encaixada na tela. */
enum class PhotoFitMode {
    /** Sempre preenche a tela inteira, cortando o que não couber. */
    FILL,

    /** Sempre mostra a foto inteira, com borda preta se sobrar espaço. */
    FIT,

    /**
     * Decide por foto: quando a orientação da foto bate com a da tela (as
     * duas em pé, ou as duas deitadas), preenche — é o caso comum, retrato
     * tirado com o celular em pé exibido num porta-retrato em pé, e a foto
     * ocupa a tela toda sem sobrar quase nada de fora. Quando não bate (foto
     * deitada num porta-retrato em pé, ou vice-versa), preencher cortaria
     * lados inteiros da foto — muitas vezes rostos — então mostra a foto
     * inteira em vez de arriscar isso.
     */
    AUTO,
}

/**
 * Decide se uma foto deve preencher a tela ou ser mostrada inteira.
 *
 * Função pura — testável sem Bitmap/ImageView do Android — separada da
 * decisão de *como* aplicar isso (scaleType), que fica na Activity.
 */
object PhotoFit {
    fun shouldFill(mode: PhotoFitMode, photoIsLandscape: Boolean, screenIsLandscape: Boolean): Boolean =
        when (mode) {
            PhotoFitMode.FILL -> true
            PhotoFitMode.FIT -> false
            PhotoFitMode.AUTO -> photoIsLandscape == screenIsLandscape
        }
}

/** Preferências do slideshow. */
data class SlideshowSettings(
    val order: SlideshowOrder = SlideshowOrder.SHUFFLE,
    val intervalMs: Long = DEFAULT_INTERVAL_MS,
    val photoFit: PhotoFitMode = PhotoFitMode.AUTO,
) {
    companion object {
        /**
         * 12 segundos. Tempo suficiente para uma pessoa idosa reconhecer quem
         * está na foto e lembrar do momento, sem virar um mural parado.
         */
        const val DEFAULT_INTERVAL_MS = 12_000L
        const val MIN_INTERVAL_MS = 3_000L
        const val MAX_INTERVAL_MS = 300_000L
    }
}

/**
 * Decide qual foto aparece a seguir.
 *
 * Lógica pura, sem Android: roda em teste de unidade. O que parece trivial e
 * não é:
 *
 * - **A lista muda enquanto o slideshow roda.** O usuário adiciona fotos pelo
 *   celular, ou a varredura apaga uma. Se a posição fosse um índice cru, cada
 *   mudança faria a foto pular para outra sem motivo aparente. Aqui a posição é
 *   guardada pela **identidade da foto**, não pelo índice.
 * - **Embaralhar não é sortear.** Ver a mesma foto duas vezes seguidas num
 *   aparelho ligado o dia todo é notado na hora.
 * - **Lista vazia e lista de uma foto só** são os estados em que este tipo de
 *   código costuma quebrar com divisão por zero ou índice inválido.
 *
 * Não é thread-safe: use na main thread.
 */
class SlideshowEngine(
    private var settings: SlideshowSettings = SlideshowSettings(),
    private val random: Random = Random.Default,
) {

    private var photos: List<String> = emptyList()

    /** Ordem de exibição já resolvida. Em SEQUENTIAL, igual a [photos]. */
    private var playOrder: List<String> = emptyList()
    private var position: Int = 0

    /** Foto atual, ou `null` se não há fotos. */
    val current: String?
        get() = playOrder.getOrNull(position)

    val size: Int get() = photos.size
    val isEmpty: Boolean get() = photos.isEmpty()
    val intervalMs: Long get() = settings.intervalMs

    /** Preferências atuais — para quem exibe (a Activity) precisar de mais que o intervalo. */
    val currentSettings: SlideshowSettings get() = settings

    /**
     * Atualiza o acervo preservando a foto em exibição.
     *
     * Se a foto atual ainda existir, ela continua na tela e a posição é
     * recalculada em torno dela. Se tiver sido removida, o slideshow segue da
     * posição equivalente em vez de voltar ao começo — reiniciar do zero a cada
     * alteração seria muito pior num aparelho que fica ligado por horas.
     */
    fun setPhotos(newPhotos: List<String>) {
        val previous = current
        photos = newPhotos.distinct()

        if (photos.isEmpty()) {
            playOrder = emptyList()
            position = 0
            return
        }

        when (settings.order) {
            SlideshowOrder.SEQUENTIAL -> {
                playOrder = photos
                position = when {
                    previous == null -> 0
                    else -> playOrder.indexOf(previous).takeIf { it >= 0 }
                        ?: position.coerceIn(0, playOrder.lastIndex)
                }
            }

            SlideshowOrder.SHUFFLE -> {
                // A foto em exibição vai para o INÍCIO da nova ordem, e a
                // posição volta a zero.
                //
                // Preservar a posição no meio de uma ordem recém-embaralhada
                // parecia mais natural, mas quebra a garantia principal: o
                // ciclo passaria a cruzar duas embaralhadas diferentes e uma
                // foto poderia repetir antes de as outras aparecerem. Assim a
                // imagem na tela não muda E as próximas N são todas distintas.
                val shuffled = photos.shuffled(random).toMutableList()
                position = 0
                if (previous != null && shuffled.remove(previous)) {
                    shuffled.add(0, previous)
                }
                playOrder = shuffled
            }
        }
    }

    fun updateSettings(newSettings: SlideshowSettings) {
        val orderChanged = newSettings.order != settings.order
        settings = newSettings.copy(
            intervalMs = newSettings.intervalMs.coerceIn(
                SlideshowSettings.MIN_INTERVAL_MS,
                SlideshowSettings.MAX_INTERVAL_MS,
            ),
        )
        if (orderChanged) setPhotos(photos)
    }

    /**
     * Avança. Ao chegar ao fim, reembaralha antes de recomeçar — assim a
     * segunda volta não repete a ordem da primeira.
     *
     * @return a nova foto atual.
     */
    fun next(): String? {
        if (playOrder.isEmpty()) return null
        if (position >= playOrder.lastIndex) {
            reshuffleForNextCycle()
            position = 0
        } else {
            position++
        }
        return current
    }

    fun previous(): String? {
        if (playOrder.isEmpty()) return null
        position = if (position <= 0) playOrder.lastIndex else position - 1
        return current
    }

    /** Salta para uma foto específica, se ela existir. */
    fun jumpTo(photoId: String): Boolean {
        val index = playOrder.indexOf(photoId)
        if (index < 0) return false
        position = index
        return true
    }

    /**
     * Reembaralha mantendo a foto que acabou de sair fora da primeira posição,
     * para a última da volta anterior não ser a primeira da próxima.
     */
    private fun reshuffleForNextCycle() {
        if (settings.order != SlideshowOrder.SHUFFLE || playOrder.size < 2) return
        val last = playOrder.last()
        var reshuffled = photos.shuffled(random)
        // Com 2 fotos só, alternar é o melhor possível: qualquer permutação
        // coloca uma das duas na frente. Só evitamos a repetição imediata.
        var attempts = 0
        while (reshuffled.first() == last && attempts < MAX_RESHUFFLE_ATTEMPTS) {
            reshuffled = photos.shuffled(random)
            attempts++
        }
        playOrder = reshuffled
    }

    private companion object {
        const val MAX_RESHUFFLE_ATTEMPTS = 8
    }
}
