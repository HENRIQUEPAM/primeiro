package com.portaretrato.app.call.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.portaretrato.app.PortaRetratoApp
import com.portaretrato.app.R
import com.portaretrato.app.call.AuthState
import com.portaretrato.app.call.CallDispatcher
import com.portaretrato.app.call.CallMethod
import com.portaretrato.app.call.CallOptions
import com.portaretrato.app.call.CallService
import com.portaretrato.app.call.TrustedContact
import com.portaretrato.app.call.TrustedContactsStore
import com.portaretrato.app.databinding.ActivityHomeBinding
import com.portaretrato.app.ui.PrivacyActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Tela de quem se pode chamar. Alcançada a partir do menu do porta-retrato —
 * não é mais a tela inicial do app, ver [com.portaretrato.app.ui.LoginActivity].
 *
 * ## O caminho que funciona hoje
 *
 * A chamada de vídeo dentro do app depende de Firebase configurado, pareamento
 * e TURN — nada disso é código, é infraestrutura. Enquanto faltar, o botão
 * aparece **desabilitado com o motivo escrito**, e o WhatsApp assume: ele
 * funciona sem backend, sem custo e sem configuração, e a família já o tem.
 *
 * Isso não é um remendo. A Seção 7.4 da especificação determina que o WhatsApp
 * seja preservado ao lado da chamada nativa, nunca substituído — aqui ele
 * apenas assume a frente enquanto o resto não está de pé.
 *
 * ## Login
 *
 * Quem loga é o [com.portaretrato.app.call.AuthSession] da Application, desde
 * antes de qualquer tela abrir. Esta Activity só observa o resultado — não
 * chama `signInAnonymously` nem controla a escuta de chamadas recebidas, que
 * fica de pé pelo tempo de vida do processo, e não apenas enquanto esta tela
 * está aberta.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var contacts: TrustedContactsStore
    private lateinit var adapter: ContactAdapter

    /** `true` quando o Firebase autenticou; gateia a opção de chamada no app. */
    private var appCallConfigured = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        contacts = TrustedContactsStore(this)

        adapter = ContactAdapter(onCall = ::startCall, onLongPress = ::showContactMenu)
        binding.contactsList.layoutManager = LinearLayoutManager(this)
        binding.contactsList.adapter = adapter

        binding.addContactButton.setOnClickListener { showAddContactDialog() }
        binding.privacyButton.setOnClickListener {
            startActivity(Intent(this, PrivacyActivity::class.java))
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                PortaRetratoApp.from(this@HomeActivity).authSession.state.collectLatest(::onAuthState)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // -------------------------------------------------------------- chamadas

    /** Ver [CallDispatcher] — o mesmo despacho é usado pelo botão de ligar do porta-retrato. */
    private fun startCall(contact: TrustedContact, method: CallMethod) {
        CallDispatcher.dispatch(this, contact, method, appCallConfigured)
    }

    // --------------------------------------------------------------- estado

    private fun onAuthState(state: AuthState) {
        when (state) {
            is AuthState.SignedIn -> {
                appCallConfigured = true
                binding.statusBanner.visibility = View.GONE
                binding.myCode.text = state.uid
            }

            AuthState.Failed -> {
                // Caminho esperado com google-services.json de placeholder, ou
                // sem "Anônimo" ativado no console. O app continua útil: os
                // botões de WhatsApp e telefone funcionam sem Firebase nenhum.
                appCallConfigured = false
                binding.statusBanner.visibility = View.VISIBLE
                binding.statusBanner.setText(R.string.sign_in_failed_hint)
            }

            AuthState.SigningIn -> {
                // A LoginActivity já esperou por isto antes de abrir o app;
                // chegar aqui ainda "SigningIn" só acontece se o prazo de
                // segurança dela estourou. Gateia a chamada pelo app sem
                // mostrar o aviso de falha — ainda pode resolver sozinho.
                appCallConfigured = false
            }
        }
        refresh()
    }

    private fun refresh() {
        val cards = contacts.all().map { contact ->
            ContactCard(
                contact = contact,
                options = CallOptions.forContact(
                    phone = contact.phone,
                    appCallConfigured = appCallConfigured,
                    // Pareamento e presença chegam quando o fluxo de pareamento
                    // existir; até lá a opção do app fica corretamente indisponível.
                    pairedDeviceId = null,
                    peerOnline = false,
                    alreadyInCall = CallService.activeController != null,
                ),
            )
        }
        adapter.submitList(cards)
        binding.emptyState.visibility = if (cards.isEmpty()) View.VISIBLE else View.GONE
    }

    // -------------------------------------------------------------- contatos

    private fun showAddContactDialog(existing: TrustedContact? = null) {
        val view = layoutInflater.inflate(R.layout.dialog_add_contact, null)
        val nameField = view.findViewById<android.widget.EditText>(R.id.contact_name)
        val phoneField = view.findViewById<android.widget.EditText>(R.id.contact_phone)
        val codeField = view.findViewById<android.widget.EditText>(R.id.contact_code)

        existing?.let {
            nameField.setText(it.name)
            phoneField.setText(it.phone.orEmpty())
            codeField.setText(it.uid)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_contact)
            .setView(view)
            .setPositiveButton(R.string.save) { _, _ ->
                val name = nameField.text.toString().trim()
                val phone = phoneField.text.toString().trim()
                val code = codeField.text.toString().trim()
                if (name.isBlank()) {
                    toast(R.string.fill_all_fields)
                    return@setPositiveButton
                }
                contacts.upsert(
                    TrustedContact(
                        // Sem código de pareamento, o telefone identifica o
                        // contato: dá para cadastrar a família e ligar hoje,
                        // sem depender de nada estar configurado.
                        uid = code.ifBlank { existing?.uid ?: "tel:$phone" },
                        name = name,
                        phone = phone.ifBlank { null },
                        autoAnswerEnabled = existing?.autoAnswerEnabled ?: false,
                    ),
                )
                refresh()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showContactMenu(contact: TrustedContact) {
        MaterialAlertDialogBuilder(this)
            .setTitle(contact.name)
            .setItems(
                arrayOf(getString(R.string.edit_contact), getString(R.string.remove_contact)),
            ) { _, which ->
                when (which) {
                    0 -> showAddContactDialog(contact)
                    1 -> {
                        contacts.remove(contact.uid)
                        refresh()
                    }
                }
            }
            .show()
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_LONG).show()
}
