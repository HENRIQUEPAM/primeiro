package com.portaretrato.app.ui

import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.portaretrato.app.R
import com.portaretrato.app.call.AuthState
import com.portaretrato.app.call.CallDispatcher
import com.portaretrato.app.call.CallOptions
import com.portaretrato.app.call.CallService
import com.portaretrato.app.call.TrustedContact
import com.portaretrato.app.call.TrustedContactsStore
import com.portaretrato.app.call.ui.HomeActivity
import com.portaretrato.app.PortaRetratoApp
import com.portaretrato.app.databinding.ActivitySlideshowBinding
import com.portaretrato.app.people.Person
import com.portaretrato.app.photo.PhotoFit
import com.portaretrato.app.photo.PhotoFitMode
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

    /** Quem, na foto atual, tem telefone vinculado — e portanto pode ser chamado. */
    private var photoCallable: List<Person> = emptyList()

    /** Texto da legenda de nomes da foto atual, ou `null` se ninguém foi reconhecido. */
    private var currentNamesText: String? = null

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
        binding.photoCallButtonCompact.setOnClickListener { onPhotoCallTapped() }
        binding.photoCallButtonExpanded.setOnClickListener { onPhotoCallTapped() }
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
        currentNamesText = null
        photoCallable = emptyList()
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
            updateNames(id)
            updateCallable(id)
            applyControlsVisibility()
        }
    }

    /**
     * Calcula a legenda de quem foi reconhecido na foto — é o que transforma o
     * reconhecimento facial em algo visível para quem apenas olha o
     * porta-retrato. Só guarda o texto; [applyControlsVisibility] decide
     * quando mostrar (junto com o menu, ao tocar a tela — ver seu KDoc).
     *
     * `null` quando ninguém foi reconhecido — uma faixa vazia sobre a foto só
     * atrapalharia, e "Desconhecido" seria pior ainda.
     */
    private fun updateNames(photoId: String) {
        val names = scanner.database.namesIn(photoId)
        currentNamesText = if (names.isEmpty()) {
            null
        } else {
            when (names.size) {
                1 -> names[0]
                // "Ana, João e Maria" — a lista com "e" no fim lê melhor em voz
                // alta do que uma sequência de vírgulas, e alguém sempre lê em
                // voz alta.
                else -> names.dropLast(1).joinToString(", ") + " e " + names.last()
            }
        }
    }

    // ------------------------------------------------------------- ligação

    /**
     * Quem, na foto atual, pode ser chamado — só guarda a lista;
     * [applyControlsVisibility] decide o tamanho do botão.
     *
     * Só conta quem tem telefone vinculado — sem isso o botão apareceria
     * sempre, para qualquer rosto conhecido, sem ter para onde ligar, que é o
     * mesmo erro que o resto do app evita: um botão que não funciona é pior
     * que um botão ausente.
     */
    private fun updateCallable(photoId: String) {
        photoCallable = scanner.database.peopleIn(photoId).filter { it.phone != null }
    }

    private fun onPhotoCallTapped() {
        when (photoCallable.size) {
            0 -> Unit
            1 -> showCallOptions(photoCallable.first())
            else -> {
                // Foto com mais de uma pessoa reconhecida (um casal, os dois
                // filhos): pergunta para quem antes de mostrar as opções.
                val names = photoCallable.map { it.name }.toTypedArray()
                MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.call_people_in_photo)
                    .setItems(names) { _, which -> showCallOptions(photoCallable[which]) }
                    .show()
            }
        }
    }

    /**
     * As mesmas três opções de chamada da tela de contatos ([CallOptions]),
     * para a pessoa escolhida. Reaproveita o [CallDispatcher] que a tela de
     * contatos usa — ver o KDoc dele para o porquê.
     */
    private fun showCallOptions(person: Person) {
        val contact = contactFor(person)
        val appCallConfigured =
            PortaRetratoApp.from(this).authSession.state.value is AuthState.SignedIn
        val options = CallOptions.forContact(
            phone = contact.phone,
            appCallConfigured = appCallConfigured,
            pairedDeviceId = null,
            peerOnline = false,
            alreadyInCall = CallService.activeController != null,
        )
        val labels = options.map { option ->
            if (option.available) option.label else "${option.label} — ${option.explanation}"
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(person.name)
            .setItems(labels) { _, which ->
                val option = options[which]
                if (option.available) CallDispatcher.dispatch(this, contact, option.method, appCallConfigured)
            }
            .show()
    }

    /**
     * O telefone vinculado a um rosto já vira um [TrustedContact] no momento
     * em que é vinculado (ver `PeopleActivity.linkPhoneAndContact`); aqui só
     * se busca o contato já existente, para reaproveitar o que o usuário já
     * configurou nele (como atendimento automático) em vez de recriar do zero.
     */
    private fun contactFor(person: Person): TrustedContact {
        val phone = person.phone.orEmpty()
        val uid = "tel:$phone"
        return TrustedContactsStore(this).all().firstOrNull { it.uid == uid }
            ?: TrustedContact(uid = uid, name = person.name, phone = phone, autoAnswerEnabled = false)
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

        incoming.scaleType = fitScaleType(bitmap)
        incoming.setImageBitmap(bitmap)
        incoming.alpha = 0f
        incoming.visibility = View.VISIBLE
        incoming.animate().alpha(1f).setDuration(FADE_MS).start()
        outgoing.animate().alpha(0f).setDuration(FADE_MS).start()

        showingFirst = !showingFirst
    }

    /**
     * [ImageView.ScaleType] certo para esta foto, segundo [PhotoFitMode]
     * escolhido em "Como exibir as fotos".
     *
     * `CENTER_CROP` preenche a tela recortando o excesso; `FIT_CENTER` mostra
     * a foto inteira, com a borda preta do fundo sobrando dos lados — a
     * decisão em si (quando cada uma faz sentido) é [PhotoFit.shouldFill],
     * testada sem depender de Bitmap/ImageView.
     */
    private fun fitScaleType(bitmap: Bitmap): ImageView.ScaleType {
        val metrics = resources.displayMetrics
        val fill = PhotoFit.shouldFill(
            mode = engine.currentSettings.photoFit,
            photoIsLandscape = bitmap.width >= bitmap.height,
            screenIsLandscape = metrics.widthPixels >= metrics.heightPixels,
        )
        return if (fill) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
    }

    // -------------------------------------------------------------- controles

    private fun toggleControls() {
        if (binding.menuButton.visibility == View.VISIBLE) hideControls() else showControls()
    }

    private fun showControls() {
        binding.menuButton.visibility = View.VISIBLE
        applyControlsVisibility()
        hideControlsJob?.cancel()
        hideControlsJob = lifecycleScope.launch {
            delay(CONTROLS_TIMEOUT_MS)
            if (!engine.isEmpty) hideControls()
        }
    }

    private fun hideControls() {
        hideControlsJob?.cancel()
        binding.menuButton.visibility = View.GONE
        applyControlsVisibility()
        goImmersive()
    }

    /**
     * Aplica, de uma vez, tudo que muda de tamanho ou aparece/some junto com
     * o menu: a legenda de nomes e o botão de ligar.
     *
     * Antes, o nome ficava sempre visível e o botão de ligar sempre grande —
     * os dois competindo com a foto o tempo todo, mesmo em repouso. Agora só
     * aparecem (nome) ou crescem (botão, de ícone pequeno para ícone com
     * "Ligar" escrito) junto com o toque na tela, como o menu — e encolhem
     * de volta sozinhos depois de [CONTROLS_TIMEOUT_MS]. Continuam
     * independentes um do outro: mesmo com o menu escondido, o ícone pequeno
     * de ligar continua ali, discreto, sempre que há para quem ligar.
     */
    private fun applyControlsVisibility() {
        val shown = binding.menuButton.visibility == View.VISIBLE

        val names = currentNamesText
        if (shown && names != null) {
            binding.photoNames.text = names
            binding.photoNames.visibility = View.VISIBLE
        } else {
            binding.photoNames.visibility = View.GONE
        }

        if (photoCallable.isEmpty()) {
            binding.photoCallButtonCompact.visibility = View.GONE
            binding.photoCallButtonExpanded.visibility = View.GONE
        } else if (shown) {
            binding.photoCallButtonCompact.visibility = View.GONE
            binding.photoCallButtonExpanded.visibility = View.VISIBLE
        } else {
            binding.photoCallButtonCompact.visibility = View.VISIBLE
            binding.photoCallButtonExpanded.visibility = View.GONE
        }
    }

    /**
     * O menu único: chamar alguém, adicionar fotos, quem está nas fotos, tempo
     * de cada foto, como exibir as fotos e privacidade — tudo atrás de um só
     * botão, para a tela principal do porta-retrato não competir com vários
     * botões pela atenção de quem só quer ver as fotos passando.
     */
    private fun showMenu() {
        val options = arrayOf(
            getString(R.string.call_someone),
            getString(R.string.add_photos),
            getString(R.string.who_is_in_photos),
            getString(R.string.slideshow_interval),
            getString(R.string.photo_fit_title),
            getString(R.string.privacy_title),
        )
        MaterialAlertDialogBuilder(this)
            .setTitle(
                resources.getQuantityString(R.plurals.photo_count, library.count(), library.count()),
            )
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startActivity(Intent(this, HomeActivity::class.java))
                    1 -> pickPhotos()
                    2 -> startActivity(Intent(this, PeopleActivity::class.java))
                    3 -> showSettingsDialog()
                    4 -> showPhotoFitDialog()
                    5 -> startActivity(Intent(this, PrivacyActivity::class.java))
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

        MaterialAlertDialogBuilder(this)
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

    /**
     * Como as fotos se encaixam na tela — ver [PhotoFitMode] para a lógica de
     * cada opção. Muda a foto que já está na tela na hora (`showCurrent()`),
     * não só as próximas — sem isso pareceria que o toque não fez nada até a
     * próxima troca automática.
     */
    private fun showPhotoFitDialog() {
        val current = settingsStore.load()
        val modes = arrayOf(PhotoFitMode.AUTO, PhotoFitMode.FILL, PhotoFitMode.FIT)
        val labels = arrayOf(
            getString(R.string.photo_fit_auto),
            getString(R.string.photo_fit_fill),
            getString(R.string.photo_fit_fit),
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.photo_fit_title)
            .setSingleChoiceItems(labels, modes.indexOf(current.photoFit)) { dialog, which ->
                val updated = current.copy(photoFit = modes[which])
                settingsStore.save(updated)
                engine.updateSettings(updated)
                showCurrent()
                dialog.dismiss()
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
