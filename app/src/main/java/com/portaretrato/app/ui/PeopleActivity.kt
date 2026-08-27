package com.portaretrato.app.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.portaretrato.app.PortaRetratoApp
import com.portaretrato.app.R
import com.portaretrato.app.databinding.ActivityPeopleBinding
import com.portaretrato.app.people.FaceDatabase
import com.portaretrato.app.people.PendingFace
import com.portaretrato.app.people.Person
import com.portaretrato.app.photo.FaceThumbnails
import com.portaretrato.app.photo.PhotoLibrary
import com.portaretrato.app.recognition.FaceScanCoordinator
import com.portaretrato.app.recognition.ScanProgress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Quem está nas fotos.
 *
 * ## O fluxo que faz o cadastro terminar
 *
 * A tela pergunta por **um rosto de cada vez**, com o rosto grande na tela e
 * duas respostas possíveis. Quando o usuário dá um nome, o app reprocessa a
 * fila inteira: as outras fotos da mesma pessoa saem junto, e a próxima
 * pergunta já é sobre outra pessoa. Na prática o cadastro de uma família
 * inteira são poucas perguntas, não uma por foto — e é isso que decide se o
 * recurso é usado ou abandonado.
 *
 * ## O que a tela nunca faz
 *
 * Não abre a câmera. Nem aqui nem no resto do reconhecimento: tudo parte de
 * fotos que o usuário escolheu no seletor do sistema. A câmera continua atrás
 * do [com.portaretrato.app.security.CameraGuard], usada só em chamada.
 */
class PeopleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPeopleBinding
    private lateinit var library: PhotoLibrary
    /** Compartilhado com o slideshow: ver [PortaRetratoApp.faceScanner]. */
    private val coordinator: FaceScanCoordinator by lazy { PortaRetratoApp.from(this).faceScanner }
    private lateinit var adapter: PersonAdapter

    private val db: FaceDatabase get() = coordinator.database

    /** Rosto sendo perguntado agora. */
    private var reviewing: PendingFace? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPeopleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        library = PhotoLibrary(this)

        adapter = PersonAdapter(onClick = ::showPersonMenu)
        binding.peopleList.layoutManager = LinearLayoutManager(this)
        binding.peopleList.adapter = adapter

        binding.confirmButton.setOnClickListener { confirmSuggestion() }
        binding.rejectButton.setOnClickListener { rejectSuggestion() }
        binding.nameButton.setOnClickListener { askForName() }
        binding.skipButton.setOnClickListener { skipFace() }
        binding.rescanButton.setOnClickListener { startScan() }

        refresh()
        startScan()
    }

    // --------------------------------------------------------------- varredura

    private fun startScan() {
        coordinator.scanPending { progress ->
            // O callback vem da thread de varredura.
            runOnUiThread { showProgress(progress) }
        }
    }

    private fun showProgress(progress: ScanProgress) {
        when {
            progress.running -> {
                binding.scanStatus.visibility = View.VISIBLE
                binding.scanStatus.text =
                    getString(R.string.scanning_progress, progress.done, progress.total)
            }

            progress.total == 0 -> binding.scanStatus.visibility = View.GONE

            else -> {
                binding.scanStatus.visibility = View.VISIBLE
                binding.scanStatus.text = getString(R.string.scan_finished, progress.total)
                refresh()
            }
        }
    }

    // ----------------------------------------------------------------- revisão

    private fun refresh() {
        adapter.submitList(
            db.people.map { PersonRow(it, db.photosOf(it.id).size) }
                .sortedByDescending { it.photoCount },
        )
        binding.emptyState.visibility = if (db.personCount == 0) View.VISIBLE else View.GONE
        showNextFace()
    }

    private fun showNextFace() {
        // Mantém o rosto atual se ele ainda estiver na fila; trocar de pergunta
        // no meio de uma resposta é a forma mais rápida de o usuário vincular a
        // pessoa errada.
        val next = db.pending.firstOrNull { it.id == reviewing?.id }
            ?: db.pending.maxByOrNull { it.quality }

        reviewing = next
        if (next == null) {
            binding.reviewCard.visibility = View.GONE
            return
        }

        binding.reviewCard.visibility = View.VISIBLE
        binding.reviewRemaining.text =
            resources.getQuantityString(R.plurals.faces_remaining, db.pendingCount, db.pendingCount)

        val suggested = next.suggestedPersonId?.let { db.person(it) }
        if (suggested != null) {
            binding.reviewQuestion.text = getString(R.string.is_this_person, suggested.name)
            binding.confirmButton.visibility = View.VISIBLE
            binding.rejectButton.visibility = View.VISIBLE
            binding.nameButton.setText(R.string.someone_else)
        } else {
            binding.reviewQuestion.setText(R.string.who_is_this)
            binding.confirmButton.visibility = View.GONE
            binding.rejectButton.visibility = View.GONE
            binding.nameButton.setText(R.string.who_is_this_action)
        }

        loadFace(next)
    }

    private fun loadFace(face: PendingFace) {
        binding.faceImage.setImageDrawable(null)
        lifecycleScope.launch {
            val file = library.fileFor(face.photoId)
            val bitmap = if (file == null) {
                null
            } else {
                withContext(Dispatchers.IO) {
                    FaceThumbnails.crop(file, face.left, face.top, face.right, face.bottom)
                }
            }
            if (bitmap == null) {
                // A foto sumiu entre a varredura e a revisão: descarta o rosto
                // em vez de mostrar um quadro vazio sem explicação.
                db.dismiss(face.id)
                coordinator.persist()
                refresh()
                return@launch
            }
            // A resposta assíncrona pode chegar depois de o usuário já ter
            // respondido e a tela ter trocado de rosto.
            if (reviewing?.id == face.id) binding.faceImage.setImageBitmap(bitmap)
        }
    }

    private fun confirmSuggestion() {
        val face = reviewing ?: return
        announce(db.confirmSuggestion(face.id))
    }

    private fun rejectSuggestion() {
        val face = reviewing ?: return
        db.rejectSuggestion(face.id)
        coordinator.persist()
        showNextFace()
    }

    private fun skipFace() {
        val face = reviewing ?: return
        db.dismiss(face.id)
        coordinator.persist()
        reviewing = null
        refresh()
    }

    private fun askForName() {
        val face = reviewing ?: return
        val input = EditText(this).apply {
            hint = getString(R.string.person_name_hint)
            setSingleLine()
            textSize = 20f
        }

        val existing = db.people.map { it.name }.toTypedArray()
        val builder = AlertDialog.Builder(this)
            .setTitle(R.string.who_is_this)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                announce(db.nameFace(face.id, input.text.toString()))
            }
            .setNegativeButton(R.string.cancel, null)

        // Atalho para quem já existe: digitar de novo o nome da avó a cada
        // rosto é o tipo de atrito que faz o cadastro parar na metade.
        if (existing.isNotEmpty()) {
            builder.setNeutralButton(R.string.pick_existing) { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle(R.string.pick_existing)
                    .setItems(existing) { _, which ->
                        announce(db.nameFace(face.id, existing[which]))
                    }
                    .show()
            }
        }
        builder.show()
    }

    /** Aplica o resultado e conta ao usuário o que aconteceu junto. */
    private fun announce(result: FaceDatabase.NameResult) {
        coordinator.persist()
        reviewing = null

        val person = result.person
        if (person != null && result.alsoResolved > 0) {
            Toast.makeText(
                this,
                resources.getQuantityString(
                    R.plurals.also_recognized,
                    result.alsoResolved,
                    person.name,
                    result.alsoResolved,
                ),
                Toast.LENGTH_LONG,
            ).show()
        }
        refresh()
    }

    // ---------------------------------------------------------------- pessoas

    private fun showPersonMenu(person: Person) {
        AlertDialog.Builder(this)
            .setTitle(person.name)
            .setItems(
                arrayOf(getString(R.string.rename_person), getString(R.string.remove_person)),
            ) { _, which ->
                when (which) {
                    0 -> renameDialog(person)
                    1 -> confirmRemoval(person)
                }
            }
            .show()
    }

    private fun renameDialog(person: Person) {
        val input = EditText(this).apply {
            setText(person.name)
            setSingleLine()
            textSize = 20f
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_person)
            .setView(input)
            .setPositiveButton(R.string.save) { _, _ ->
                db.renamePerson(person.id, input.text.toString())
                coordinator.persist()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmRemoval(person: Person) {
        AlertDialog.Builder(this)
            .setTitle(R.string.remove_person)
            .setMessage(getString(R.string.remove_person_warning, person.name))
            .setPositiveButton(R.string.remove_person) { _, _ ->
                db.removePerson(person.id)
                coordinator.persist()
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        // Ver SlideshowActivity.onStop: cancelar a varredura é do app inteiro.
        super.onDestroy()
    }
}
