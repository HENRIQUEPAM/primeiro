package com.portaretrato.app.ui

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.portaretrato.app.PortaRetratoApp
import com.portaretrato.app.R
import com.portaretrato.app.call.PhoneNumbers
import com.portaretrato.app.call.TrustedContact
import com.portaretrato.app.call.TrustedContactsStore
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
 * o nome já digitável ali mesmo — sem abrir um diálogo à parte. Quando o
 * usuário salva, o app reprocessa a fila inteira: as outras fotos da mesma
 * pessoa saem junto, e a próxima pergunta já é sobre outra pessoa. Na prática
 * o cadastro de uma família inteira são poucas perguntas, não uma por foto —
 * e é isso que decide se o recurso é usado ou abandonado.
 *
 * ## O telefone une reconhecimento e chamada
 *
 * "Usar contato do celular" busca o número na agenda do sistema (o seletor
 * nativo — nenhuma permissão de contatos é exigida para isso, o Android
 * concede acesso só ao contato escolhido) e, ao salvar, grava o telefone na
 * pessoa reconhecida E cadastra a mesma pessoa em
 * [TrustedContactsStore] — de modo que nomear um rosto com o telefone da avó
 * já deixa a avó pronta para ser chamada, sem cadastrá-la de novo na tela de
 * contatos.
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
    private lateinit var contacts: TrustedContactsStore

    /** Compartilhado com o slideshow: ver [PortaRetratoApp.faceScanner]. */
    private val coordinator: FaceScanCoordinator by lazy { PortaRetratoApp.from(this).faceScanner }
    private lateinit var adapter: PersonAdapter

    private val db: FaceDatabase get() = coordinator.database

    /** Rosto sendo perguntado agora. */
    private var reviewing: PendingFace? = null

    /** Telefone escolhido nesta rodada, em E.164 sem "+". Ainda não salvo. */
    private var pickedPhone: String? = null

    /**
     * Rostos que o usuário mandou pular NESTA sessão, sem decisão nenhuma.
     * Não é persistido de propósito — "pular por enquanto" é sobre a ordem
     * em que as perguntas aparecem agora, não sobre o que fica salvo.
     */
    private val skippedForNow = mutableSetOf<String>()

    /** Seletor nativo de contatos: dispensa a permissão READ_CONTACTS. */
    private val pickContact = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let(::loadPickedContact)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPeopleBinding.inflate(layoutInflater)
        setContentView(binding.root)

        library = PhotoLibrary(this)
        contacts = TrustedContactsStore(this)

        adapter = PersonAdapter(onClick = ::showPersonMenu)
        binding.peopleList.layoutManager = LinearLayoutManager(this)
        binding.peopleList.adapter = adapter

        binding.saveButton.setOnClickListener { save() }
        binding.skipForNowButton.setOnClickListener { skipForNow() }
        binding.discardButton.setOnClickListener { discardFace() }
        binding.pickContactButton.setOnClickListener { launchContactPicker() }
        binding.pickExistingPersonButton.setOnClickListener { pickExistingPerson() }
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

    /**
     * Escolhe o próximo rosto a perguntar.
     *
     * Ignora quem foi "pulado por enquanto" nesta sessão — mas, se todos os
     * pendentes já foram pulados, esquece os pulos e mostra de novo. Sem essa
     * válvula, a fila pareceria vazia assim que o usuário desse uma volta
     * inteira pulando, mesmo com rostos de verdade ainda esperando.
     */
    private fun pickNext(): PendingFace? {
        val pending = db.pending
        val available = pending.filterNot { it.id in skippedForNow }
        val pool = if (available.isEmpty() && pending.isNotEmpty()) {
            skippedForNow.clear()
            pending
        } else {
            available
        }
        return pool.maxByOrNull { it.quality }
    }

    private fun showNextFace() {
        // Mantém o rosto atual se ele ainda estiver na fila; trocar de pergunta
        // no meio de uma resposta é a forma mais rápida de o usuário vincular a
        // pessoa errada.
        val next = db.pending.firstOrNull { it.id == reviewing?.id } ?: pickNext()

        reviewing = next
        if (next == null) {
            binding.reviewCard.visibility = View.GONE
            binding.reviewActions.visibility = View.GONE
            return
        }

        binding.reviewCard.visibility = View.VISIBLE
        binding.reviewActions.visibility = View.VISIBLE
        binding.reviewRemaining.text =
            resources.getQuantityString(R.plurals.faces_remaining, db.pendingCount, db.pendingCount)

        val suggested = next.suggestedPersonId?.let { db.person(it) }
        if (suggested != null) {
            binding.suggestionHint.text = getString(R.string.suggestion_hint, suggested.name)
            binding.suggestionHint.visibility = View.VISIBLE
            binding.nameInput.setText(suggested.name)
        } else {
            binding.suggestionHint.visibility = View.GONE
            binding.nameInput.setText("")
        }

        // O telefone da pessoa sugerida, se já houver um: não faz sentido
        // pedir para escolher o contato de novo toda vez que ela reaparece.
        setPickedPhone(suggested?.phone)

        // Só vale a pena oferecer "escolher da lista" se já existir alguém
        // cadastrado para escolher.
        binding.pickExistingPersonButton.visibility =
            if (db.personCount > 0) View.VISIBLE else View.GONE

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

    // -------------------------------------------------------------- decisões

    /**
     * Salva o nome digitado (ou confirma a sugestão, se o texto não mudou) e,
     * se houver telefone escolhido, vincula a pessoa também na lista de quem
     * dá para chamar.
     */
    private fun save() {
        val face = reviewing ?: return
        val typed = binding.nameInput.text?.toString()?.trim().orEmpty()
        if (typed.isEmpty()) {
            toast(R.string.name_required)
            return
        }

        val suggested = face.suggestedPersonId?.let { db.person(it) }
        val result = if (suggested != null && typed.equals(suggested.name, ignoreCase = true)) {
            // Mesmo nome da sugestão: confirma o vínculo já calculado, em vez
            // de repassar pelo casamento por nome do nameFace — evita o caso
            // raro em que a similaridade fica entre o limiar de sugestão e o
            // de "mesmo nome, mesma pessoa", e um homônimo seria criado à toa.
            db.confirmSuggestion(face.id)
        } else {
            db.nameFace(face.id, typed)
        }
        applyPersonResult(result)
    }

    /**
     * "Já é alguém cadastrado?" — para quando o humano reconhece a pessoa e o
     * algoritmo não. Ao contrário de digitar o nome de novo, o vínculo aqui é
     * forçado: nunca cria homônimo, mesmo que a semelhança calculada seja
     * baixa (ângulo ruim, óculos novos, criança que mudou).
     */
    private fun pickExistingPerson() {
        val face = reviewing ?: return
        val people = db.people
        if (people.isEmpty()) return

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pick_existing_person_title)
            .setItems(people.map { it.name }.toTypedArray()) { _, which ->
                applyPersonResult(db.assignToExistingPerson(face.id, people[which].id))
            }
            .show()
    }

    /** Vincula o telefone escolhido nesta rodada, se houver, e anuncia o resultado. */
    private fun applyPersonResult(result: FaceDatabase.NameResult) {
        val person = result.person
        if (person != null) {
            pickedPhone?.let { phone -> linkPhoneAndContact(person, phone) }
        }
        announce(result)
    }

    /**
     * Grava o telefone na pessoa reconhecida e a torna chamável, sem duplicar
     * cadastro: se já existir um contato com este telefone, o vínculo é
     * atualizado nele, preservando o que já estava configurado (como
     * atendimento automático).
     */
    private fun linkPhoneAndContact(person: Person, phone: String) {
        db.linkPhone(person.id, phone)
        coordinator.persist()

        val uid = "tel:$phone"
        val existing = contacts.all().firstOrNull { it.uid == uid }
        contacts.upsert(
            TrustedContact(
                uid = uid,
                name = person.name,
                phone = phone,
                autoAnswerEnabled = existing?.autoAnswerEnabled ?: false,
            ),
        )
    }

    /**
     * Não decide nada — só tira este rosto da frente da fila por agora. Se o
     * palpite estava errado, limpa a sugestão (para não insistir nela), mas o
     * rosto continua esperando um nome depois.
     */
    private fun skipForNow() {
        val face = reviewing ?: return
        if (face.suggestedPersonId != null) {
            db.rejectSuggestion(face.id)
            coordinator.persist()
        }
        skippedForNow += face.id
        // showNextFace() reaproveita `reviewing` se ele ainda estiver na fila
        // — e como pular não remove nada de `db.pending`, o mesmo rosto seria
        // encontrado de novo antes mesmo de pickNext() rodar. Sem limpar aqui,
        // "pular por enquanto" não pulava nada.
        reviewing = null
        showNextFace()
    }

    private fun discardFace() {
        val face = reviewing ?: return
        db.dismiss(face.id)
        coordinator.persist()
        reviewing = null
        refresh()
    }

    /** Aplica o resultado e conta ao usuário o que aconteceu junto. */
    private fun announce(result: FaceDatabase.NameResult) {
        // Sempre grava — mesmo quando linkPhoneAndContact já gravou por causa
        // do telefone, persistir de novo é inofensivo, e sem isto um "Salvar"
        // sem telefone vinculado nunca chegaria a tocar o disco.
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

    // ------------------------------------------------------------- telefone

    private fun launchContactPicker() {
        pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
    }

    private fun loadPickedContact(uri: Uri) {
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.NUMBER,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        )
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return
            val number = cursor.getString(0)
            val name = cursor.getString(1)
            val normalized = PhoneNumbers.normalize(number.orEmpty())
            if (normalized == null) {
                toast(R.string.contact_without_phone)
                return
            }
            // Escolher um contato é uma decisão firme: sobrescreve o que
            // estivesse digitado, em vez de só acrescentar o telefone.
            if (!name.isNullOrBlank()) binding.nameInput.setText(name)
            setPickedPhone(normalized)
        }
    }

    private fun setPickedPhone(phone: String?) {
        pickedPhone = phone
        if (phone != null) {
            binding.linkedPhone.text = getString(R.string.linked_phone, phone)
            binding.linkedPhone.visibility = View.VISIBLE
        } else {
            binding.linkedPhone.visibility = View.GONE
        }
    }

    // ---------------------------------------------------------------- pessoas

    private fun showPersonMenu(person: Person) {
        MaterialAlertDialogBuilder(this)
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
        MaterialAlertDialogBuilder(this)
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
        MaterialAlertDialogBuilder(this)
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

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        // Ver SlideshowActivity.onStop: cancelar a varredura é do app inteiro.
        super.onDestroy()
    }
}
