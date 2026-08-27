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
import com.portaretrato.app.databinding.ActivitySlideshowBinding
import com.portaretrato.app.photo.PhotoLibrary
import com.portaretrato.app.photo.SlideshowEngine
import com.portaretrato.app.photo.SlideshowOrder
import com.portaretrato.app.photo.SlideshowSettings
import com.portaretrato.app.photo.SlideshowSettingsStore
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
 * É a tela inicial do app — o aparelho fica exibindo fotos, e tudo o mais
 * (contatos, adicionar fotos, privacidade) está a um toque dela.
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
 * - **Toque em qualquer lugar mostra os controles**, que somem sozinhos depois
 *   de 8 segundos. O idoso não precisa achar um botão escondido, e o painel
 *   não fica atrapalhando as fotos.
 */
class SlideshowActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySlideshowBinding
    private lateinit var library: PhotoLibrary
    private lateinit var settingsStore: SlideshowSettingsStore
    private lateinit var engine: SlideshowEngine

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
        binding.addPhotosButton.setOnClickListener { pickPhotos() }
        binding.callButton.setOnClickListener {
            startActivity(Intent(this, HomeActivity::class.java))
        }
        binding.privacyButton.setOnClickListener {
            startActivity(Intent(this, PrivacyActivity::class.java))
        }
        binding.settingsButton.setOnClickListener { showSettingsDialog() }
        binding.emptyAddButton.setOnClickListener { pickPhotos() }
    }

    override fun onStart() {
        super.onStart()
        reloadPhotos()
    }

    override fun onStop() {
        advanceJob?.cancel()
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
        }
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
        if (binding.controls.visibility == View.VISIBLE) hideControls() else showControls()
    }

    private fun showControls() {
        binding.controls.visibility = View.VISIBLE
        binding.photoCount.text = resources.getQuantityString(
            R.plurals.photo_count,
            library.count(),
            library.count(),
        )
        hideControlsJob?.cancel()
        hideControlsJob = lifecycleScope.launch {
            delay(CONTROLS_TIMEOUT_MS)
            if (!engine.isEmpty) hideControls()
        }
    }

    private fun hideControls() {
        hideControlsJob?.cancel()
        binding.controls.visibility = View.GONE
        goImmersive()
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
