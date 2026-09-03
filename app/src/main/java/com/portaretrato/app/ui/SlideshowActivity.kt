package com.portaretrato.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.portaretrato.app.R
import com.portaretrato.app.call.ui.HomeActivity
import com.portaretrato.app.PortaRetratoApp
import com.portaretrato.app.databinding.ActivitySlideshowBinding
import com.portaretrato.app.photo.PhotoLibrary
import com.portaretrato.app.photo.SlideshowEngine
import com.portaretrato.app.photo.SlideshowOrder
import com.portaretrato.app.photo.SlideshowSettings
import com.portaretrato.app.photo.SlideshowSettingsStore
import com.portaretrato.app.recognition.FaceScanCoordinator
import com.portaretrato.app.recognition.OrientedImageDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * O porta-retrato em si: fotos em tela cheia, trocando sozinhas.
 *
 * É para onde [LoginActivity] leva assim que a sessão do aparelho resolve — a
 * tela em que o aparelho passa o dia inteiro. Tudo o mais (contatos, adicionar
 * fotos, quem está nas fotos, privacidade) está atrás de um único botão, que
 * só aparece quando alguém toca a tela.
 *
 * ## Decisões que o formato exige
 *
 * - **Tela sempre acesa.** `FLAG_KEEP_SCREEN_ON` enquanto o slideshow roda; um
 *   porta-retrato que apaga depois de 30 segundos não é um porta-retrato.
 * - **Imersivo.** Sem barra de status nem de navegação: a foto ocupa tudo.
 * - **Decodificação fora da main thread**, com o `OrientedImageDecoder` já
 *   escrito para o reconhecimento — que corrige EXIF e dimensiona
 *   corretamente. Decodificar na main thread trava a troca e faz o aparelho
 *   parecer lento.
 * - **Transição por fade entre dois ImageViews.** Trocar o bitmap de um único
 *   ImageView pisca; com dois, a foto nova aparece por cima da antiga.
 * - **Toque em qualquer lugar mostra um único botão de menu**, que some
 *   sozinho depois de 8 segundos. O idoso não precisa escolher entre vários
 *   botões, e o menu não fica atrapalhando as fotos.
 */
class SlideshowActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySlideshowBinding
    private lateinit var library: PhotoLibrary
    private lateinit var settingsStore: SlideshowSettingsStore
    private lateinit var engine: SlideshowEngine
    /** Compartilhado com a tela de pessoas: ver [PortaRetratoApp.faceScanner]. */
    private val scanner: FaceScanCoordinator by lazy { PortaRetratoApp.from(this).faceScanner }

    private var advanceJob: Job? = null
    private var hideControlsJob: Job? = null
    private var showingFirst = true

    /** Seletor do sistema: não exige permissão de armazenamento. */
    private val pickPhotos = registerForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(MAX_PHOTOS_PER_PICK),
    ) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        lifecycleScope.launch {
            val added = withContext(Dispatchers.IO) { library.import(uris) }
            reloadPhotos()
            showToast(
                if (added > 0) {
                    resources.getQuantityString(R.plurals.photos_added, added, added)
                } else {
                    getString(R.string.photos_already_added)
                },
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySlideshowBinding.inflate(layoutInflater)
        setContentView(binding.root)

        library = PhotoLibrary(this)
        settingsStore = SlideshowSettingsStore(this)
        engine = SlideshowEngine(settingsStore.load())

        // Restos de uma importação interrompida por reinício do aparelho.
        library.cleanupTemporaries()

        goImmersive()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.root.setOnClickListener { toggleControls() }
        binding.menuButton.setOnClickListener { showMenu() }
        binding.emptyAddButton.setOnClickListener { pickPhotos() }
    }

    override fun onStart() {
        super.onStart()
        reloadPhotos()
        // A varredura roda ao fundo enquanto as fotos passam. É aqui porque é
        // este o momento em que o acervo pode ter mudado — o usuário acabou de
        // adicionar fotos, ou de voltar da tela de pessoas.
        scanner.scanPending()
    }

    override fun onStop() {
        advanceJob?.cancel()
        // A varredura NÃO é cancelada aqui: ela é do app, não desta tela, e
        // parar ao ir para a tela de pessoas mataria justamente a varredura que
        // aquela tela acabou de pedir. Quem cancela é o PortaRetratoApp, quando
        // a última tela some — aí sim o interpretador TFLite e os detectores do
        // ML Kit, dezenas de MB, deixam de fazer sentido.
        super.onStop()
    }

    // ------------------------------------------------------------- slideshow

    private fun reloadPhotos() {
        engine.setPhotos(library.ids())
        if (engine.isEmpty) {
            showEmptyState()
        } else {
            binding.emptyState.visibility = View.GONE
            showCurrent()
            restartAdvanceLoop()
        }
    }

    private fun showEmptyState() {
        advanceJob?.cancel()
        binding.emptyState.visibility = View.VISIBLE
        binding.imageA.setImageDrawable(null)
        binding.imageB.setImageDrawable(null)
        binding.photoNames.visibility = View.GONE
        showControls()
    }

    /**
     * Laço de avanço.
     *
     * O `delay` fica antes do avanço para a primeira foto ficar o intervalo
     * inteiro na tela — invertendo a ordem, ela trocaria imediatamente ao
     * entrar na tela, o que parece defeito.
     */
    private fun restartAdvanceLoop() {
        advanceJob?.cancel()
        advanceJob = lifecycleScope.launch {
            while (isActive && !engine.isEmpty) {
                delay(engine.intervalMs)
                if (!isActive) break
                engine.next()
                showCurrent()
            }
        }
    }

    private fun showCurrent() {
        val id = engine.current ?: return
        val file = library.fileFor(id) ?: run {
            // Arquivo sumiu entre a listagem e a exibição: recarrega e segue.
            reloadPhotos()
            return
        }

        lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                OrientedImageDecoder.decode(file, maxDimension = decodeSize())
            } ?: return@launch

            crossFadeTo(bitmap)
            showNames(id)
        }
    }

    /**
     * Mostra quem foi reconhecido na foto.
     *
     * É o que transforma o reconhecimento facial em algo visível para quem
     * apenas olha o porta-retrato: a foto aparece e o nome aparece junto. Sem
     * isto, todo o pipeline seria trabalho invisível.
     *
     * Some quando ninguém foi reconhecido — uma faixa vazia sobre a foto só
     * atrapalharia, e "Desconhecido" seria pior ainda.
     */
    private fun showNames(photoId: String) {
        val names = scanner.database.namesIn(photoId)
        if (names.isEmpty()) {
            binding.photoNames.visibility = View.GONE
            return
        }
        binding.photoNames.text = when (names.size) {
            1 -> names[0]
            // "Ana, João e Maria" — a lista com "e" no fim lê melhor em voz alta
            // do que uma sequência de vírgulas, e alguém sempre lê em voz alta.
            else -> names.dropLast(1).joinToString(", ") + " e " + names.last()
        }
        binding.photoNames.visibility = View.VISIBLE
    }

    /**
     * Dimensão de decodificação: a maior da tela.
     *
     * Decodificar na resolução original de uma foto de 12 MP para exibir numa
     * tela de 1080p gastaria ~48 MB por imagem sem nenhum ganho visível.
     */
    private fun decodeSize(): Int {
        val metrics = resources.displayMetrics
        return maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(MIN_DECODE_SIZE)
    }

    private fun crossFadeTo(bitmap: Bitmap) {
        val incoming = if (showingFirst) binding.imageB else binding.imageA
        val outgoing = if (showingFirst) binding.imageA else binding.imageB

        incoming.setImageBitmap(bitmap)
        incoming.alpha = 0f
        incoming.visibility = View.VISIBLE
        incoming.animate().alpha(1f).setDuration(FADE_MS).start()
        outgoing.animate().alpha(0f).setDuration(FADE_MS).start()

        showingFirst = !showingFirst
    }

    // -------------------------------------------------------------- controles

    private fun toggleControls() {
        if (binding.menuButton.visibility == View.VISIBLE) hideControls() else showControls()
    }

    private fun showControls() {
        binding.menuButton.visibility = View.VISIBLE
        // O botão ocupa a mesma borda inferior que a legenda: deixar as duas
        // visíveis sobreporia o nome ao botão.
        binding.photoNames.visibility = View.GONE
        hideControlsJob?.cancel()
        hideControlsJob = lifecycleScope.launch {
            delay(CONTROLS_TIMEOUT_MS)
            if (!engine.isEmpty) hideControls()
        }
    }

    private fun hideControls() {
        hideControlsJob?.cancel()
        binding.menuButton.visibility = View.GONE
        engine.current?.let { showNames(it) }
        goImmersive()
    }

    /**
     * O menu único: chamar alguém, adicionar fotos, quem está nas fotos, tempo
     * de cada foto e privacidade — tudo atrás de um só botão, para a tela
     * principal do porta-retrato não competir com cinco botões pela atenção de
     * quem só quer ver as fotos passando.
     */
    private fun showMenu() {
        val options = arrayOf(
            getString(R.string.call_someone),
            getString(R.string.add_photos),
            getString(R.string.who_is_in_photos),
            getString(R.string.slideshow_interval),
            getString(R.string.privacy_title),
        )
        AlertDialog.Builder(this)
            .setTitle(
                resources.getQuantityString(R.plurals.photo_count, library.count(), library.count()),
            )
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, HomeActivity::class.java))
                    1 -> pickPhotos()
                    2 -> startActivity(Intent(this, PeopleActivity::class.java))
                    3 -> showSettingsDialog()
                    4 -> startActivity(Intent(this, PrivacyActivity::class.java))
                }
            }
            .show()
    }

    private fun pickPhotos() {
        pickPhotos.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
        )
    }

    private fun showSettingsDialog() {
        val current = settingsStore.load()
        val intervals = longArrayOf(5_000, 12_000, 30_000, 60_000, 300_000)
        val labels = intervals.map { getString(R.string.seconds_format, it / 1000) }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle(R.string.slideshow_interval)
            .setSingleChoiceItems(labels, intervals.indexOf(current.intervalMs)) { dialog, which ->
                val updated = current.copy(intervalMs = intervals[which])
                settingsStore.save(updated)
                engine.updateSettings(updated)
                restartAdvanceLoop()
                dialog.dismiss()
            }
            .setNeutralButton(
                if (current.order == SlideshowOrder.SHUFFLE) {
                    R.string.order_sequential
                } else {
                    R.string.order_shuffle
                },
            ) { _, _ ->
                val flipped = current.copy(
                    order = if (current.order == SlideshowOrder.SHUFFLE) {
                        SlideshowOrder.SEQUENTIAL
                    } else {
                        SlideshowOrder.SHUFFLE
                    },
                )
                settingsStore.save(flipped)
                engine.updateSettings(flipped)
                showCurrent()
                restartAdvanceLoop()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, binding.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showToast(message: String) =
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show()

    private companion object {
        const val FADE_MS = 700L
        const val CONTROLS_TIMEOUT_MS = 8_000L
        const val MAX_PHOTOS_PER_PICK = 100
        const val MIN_DECODE_SIZE = 1080
    }
}
