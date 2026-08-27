package com.portaretrato.app.recognition

import android.content.Context
import android.util.Log
import com.portaretrato.app.people.FaceDatabase
import com.portaretrato.app.people.FaceDatabaseStore
import com.portaretrato.app.people.ScanSummary
import com.portaretrato.app.photo.PhotoLibrary
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Progresso da varredura, para a interface mostrar. */
data class ScanProgress(
    val done: Int,
    val total: Int,
    val running: Boolean,
    val summary: ScanSummary = ScanSummary(),
) {
    val finished: Boolean get() = !running && done >= total
}

/**
 * Liga o acervo de fotos ao pipeline de reconhecimento.
 *
 * Era a peça que faltava: `recognition/` estava inteiro e testado, mas ninguém
 * o chamava — nenhuma foto era analisada e nenhum rosto virava pessoa.
 *
 * ## Por que aqui e não num WorkManager
 *
 * O caso de uso é um aparelho na tomada exibindo fotos. O app está em primeiro
 * plano praticamente o tempo todo, e uma varredura agendada para "quando o
 * aparelho estiver ocioso e carregando" rodaria com a tela ligada de qualquer
 * forma. Um `Worker` acrescentaria uma dependência (`androidx.work`), um
 * processo de fundo e um ciclo de vida próprio para resolver um problema que
 * este aparelho não tem. Se o app virar um serviço que varre com a tela
 * apagada, aí sim o Worker passa a valer.
 *
 * ## Como não atrapalhar o slideshow
 *
 * - roda em [Dispatchers.Default], nunca na main thread;
 * - processa **uma foto por vez**, com uma pausa entre elas — a decodificação
 *   de uma foto de 12 MP e a inferência competem por CPU e por banda de
 *   memória com a decodificação da próxima foto do slideshow. Sem a pausa, a
 *   troca de fotos engasga e o aparelho parece defeituoso, que é o pior
 *   defeito possível num porta-retrato;
 * - salva o banco a cada foto: uma varredura de 2.000 fotos interrompida no
 *   meio não recomeça do zero;
 * - `close()` libera o interpretador TFLite e os detectores do ML Kit, que
 *   juntos seguram dezenas de MB.
 *
 * Só uma varredura roda de cada vez ([mutex]).
 */
class FaceScanCoordinator(
    private val context: Context,
    private val library: PhotoLibrary,
    private val store: FaceDatabaseStore,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()

    @Volatile
    var database: FaceDatabase = store.load()
        private set

    @Volatile
    var progress: ScanProgress = ScanProgress(0, 0, running = false)
        private set

    private var job: Job? = null

    /**
     * Varre as fotos ainda não analisadas.
     *
     * @param onProgress chamado a cada foto, na thread da varredura.
     */
    fun scanPending(onProgress: (ScanProgress) -> Unit = {}) {
        if (job?.isActive == true) return
        job = scope.launch {
            mutex.withLock { runScan(onProgress) }
        }
    }

    fun cancel() {
        job?.cancel()
        progress = progress.copy(running = false)
    }

    private suspend fun CoroutineScope.runScan(onProgress: (ScanProgress) -> Unit) {
        val db = database
        val photos = library.all()

        // Fotos apagadas deixam vínculos órfãos e rostos na fila que não têm
        // como ser exibidos.
        db.retainOnly(photos.map { it.id }.toSet())

        val toScan = photos.filterNot { db.isScanned(it.id) }
        if (toScan.isEmpty()) {
            progress = ScanProgress(photos.size, photos.size, running = false)
            onProgress(progress)
            store.save(db)
            return
        }

        Log.i(TAG, "Varredura: ${toScan.size} foto(s) novas de ${photos.size}.")
        progress = ScanProgress(0, toScan.size, running = true)
        onProgress(progress)

        val pipeline = try {
            PhotoScanPipeline(FaceDetectors(), FaceEmbedder(context))
        } catch (e: Exception) {
            // Modelo ausente do APK ou TFLite indisponível nesta ABI. O app
            // segue sendo um porta-retrato — só não reconhece ninguém.
            Log.e(TAG, "Reconhecimento indisponível neste aparelho", e)
            progress = ScanProgress(0, toScan.size, running = false)
            onProgress(progress)
            return
        }

        var total = ScanSummary()
        try {
            for ((index, photo) in toScan.withIndex()) {
                if (!isActive) break

                val scanned = pipeline.scan(photo.id, photo.file, db.gallery())
                total += db.applyScan(scanned)

                // Persiste a cada foto: a varredura de um acervo grande leva
                // minutos, e o usuário pode desligar o aparelho a qualquer
                // momento.
                store.save(db)

                progress = ScanProgress(index + 1, toScan.size, running = true, summary = total)
                onProgress(progress)

                delay(PAUSE_BETWEEN_PHOTOS_MS)
            }
        } finally {
            pipeline.close()
            store.save(db)
            progress = progress.copy(running = false, summary = total)
            onProgress(progress)
            Log.i(TAG, "Varredura encerrada: $total")
        }
    }

    /** Persiste depois de uma edição feita pelo usuário (nomear, apagar). */
    fun persist() {
        store.save(database)
    }

    private companion object {
        const val TAG = "FaceScanCoordinator"

        /**
         * 150 ms entre fotos. Numa varredura de 2.000 fotos isso acrescenta 5
         * minutos ao total — irrelevante para algo que roda sozinho ao fundo, e
         * é o que mantém a troca de fotos fluida enquanto acontece.
         */
        const val PAUSE_BETWEEN_PHOTOS_MS = 150L
    }
}
