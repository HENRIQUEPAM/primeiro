package com.portaretrato.app.call.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.portaretrato.app.R
import com.portaretrato.app.call.CallService
import com.portaretrato.app.call.FcmTokenRegistrar
import com.portaretrato.app.call.IncomingCallWatcher
import com.portaretrato.app.call.TrustedContact
import com.portaretrato.app.call.TrustedContactsStore
import com.portaretrato.app.databinding.ActivityHomeBinding

/**
 * Tela inicial do projeto de demonstração.
 *
 * O pareamento é proposital de baixa cerimônia: cada aparelho faz login
 * anônimo e mostra o próprio código; para ligar, digita-se o código do outro.
 * Isso permite testar com dois aparelhos sem montar cadastro, lista de amigos
 * nem convite — que não é o objetivo aqui.
 *
 * Ao integrar no Porta Retrato, troque o código pelo `uid` que o app já tem do
 * login com Google, e a lista de contatos pela lista de `Person` existente.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var contacts: TrustedContactsStore
    private val watcher by lazy { IncomingCallWatcher(applicationContext) }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* o resultado é reavaliado na hora da chamada */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        contacts = TrustedContactsStore(this)

        requestStartupPermissions()
        signIn()

        binding.callButton.setOnClickListener { dial() }
        binding.addContactButton.setOnClickListener { addContactDialog() }
        binding.copyCodeButton.setOnClickListener { copyMyCode() }
    }

    /**
     * Login anônimo: cada instalação vira um `uid` estável, sem tela de
     * cadastro. Ative "Anônimo" em Authentication > Sign-in method no console
     * do Firebase.
     */
    private fun signIn() {
        val auth = FirebaseAuth.getInstance()
        val current = auth.currentUser
        if (current != null) {
            onSignedIn(current.uid)
            return
        }
        auth.signInAnonymously()
            .addOnSuccessListener { result ->
                result.user?.uid?.let(::onSignedIn)
            }
            .addOnFailureListener {
                binding.myCode.text = getString(R.string.sign_in_failed)
                Toast.makeText(this, R.string.sign_in_failed_hint, Toast.LENGTH_LONG).show()
            }
    }

    private fun onSignedIn(uid: String) {
        binding.myCode.text = uid
        // Caminho que funciona sem Cloud Function, com o app aberto.
        watcher.start(uid)
        // Caminho para app morto. Sem a function implantada, isto é inócuo.
        FcmTokenRegistrar().registerCurrent(uid)
        refreshContacts()
    }

    private fun requestStartupPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        requestPermissions.launch(permissions.toTypedArray())
    }

    private fun dial() {
        val code = binding.peerCode.text.toString().trim()
        if (code.isBlank()) {
            Toast.makeText(this, R.string.enter_code, Toast.LENGTH_SHORT).show()
            return
        }
        if (code == FirebaseAuth.getInstance().currentUser?.uid) {
            Toast.makeText(this, R.string.cannot_call_self, Toast.LENGTH_SHORT).show()
            return
        }
        val name = contacts.all().firstOrNull { it.uid == code }?.name
            ?: getString(R.string.unknown_caller)

        CallService.dial(this, code, name, video = true)
        startActivity(Intent(this, CallActivity::class.java))
    }

    private fun addContactDialog() {
        val view = layoutInflater.inflate(R.layout.dialog_add_contact, null)
        val nameField = view.findViewById<android.widget.EditText>(R.id.contact_name)
        val codeField = view.findViewById<android.widget.EditText>(R.id.contact_code)
        val autoAnswer = view.findViewById<android.widget.CheckBox>(R.id.contact_auto_answer)

        AlertDialog.Builder(this)
            .setTitle(R.string.add_contact)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = nameField.text.toString().trim()
                val code = codeField.text.toString().trim()
                if (name.isBlank() || code.isBlank()) {
                    Toast.makeText(this, R.string.fill_all_fields, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                contacts.upsert(
                    TrustedContact(
                        uid = code,
                        name = name,
                        phone = null,
                        autoAnswerEnabled = autoAnswer.isChecked,
                    ),
                )
                refreshContacts()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun refreshContacts() {
        val all = contacts.all()
        binding.contactsEmpty.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        binding.contactsList.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_list_item_1,
            all.map { contact ->
                val suffix = if (contact.autoAnswerEnabled) {
                    getString(R.string.auto_answer_on)
                } else {
                    ""
                }
                "${contact.name}$suffix"
            },
        )
        binding.contactsList.setOnItemClickListener { _, _, position, _ ->
            val contact = all[position]
            binding.peerCode.setText(contact.uid)
        }
        binding.contactsList.setOnItemLongClickListener { _, _, position, _ ->
            val contact = all[position]
            AlertDialog.Builder(this)
                .setTitle(contact.name)
                .setItems(
                    arrayOf(
                        getString(
                            if (contact.autoAnswerEnabled) {
                                R.string.disable_auto_answer
                            } else {
                                R.string.enable_auto_answer
                            },
                        ),
                        getString(R.string.remove_contact),
                    ),
                ) { _, which ->
                    when (which) {
                        0 -> contacts.setAutoAnswer(contact.uid, !contact.autoAnswerEnabled)
                        1 -> contacts.remove(contact.uid)
                    }
                    refreshContacts()
                }
                .show()
            true
        }
    }

    private fun copyMyCode() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val clipboard = getSystemService(android.content.ClipboardManager::class.java)
        clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("uid", uid))
        Toast.makeText(this, R.string.code_copied, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        watcher.stop()
        super.onDestroy()
    }
}
