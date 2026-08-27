package com.portaretrato.app.call.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.firebase.auth.FirebaseAuth
import com.portaretrato.app.R
import com.portaretrato.app.call.CallMethod
import com.portaretrato.app.call.CallOptions
import com.portaretrato.app.call.CallService
import com.portaretrato.app.call.FcmTokenRegistrar
import com.portaretrato.app.call.IncomingCallWatcher
import com.portaretrato.app.call.TrustedContact
import com.portaretrato.app.call.TrustedContactsStore
import com.portaretrato.app.call.WhatsAppFallback
import com.portaretrato.app.databinding.ActivityHomeBinding
import com.portaretrato.app.ui.PrivacyActivity

/**
 * Tela inicial: a lista de quem se pode chamar.
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
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var contacts: TrustedContactsStore
    private lateinit var adapter: ContactAdapter
    private val watcher by lazy { IncomingCallWatcher(applicationContext) }

    /** `true` quando o Firebase autenticou; gateia a opção de chamada no app. */
    private var appCallConfigured = false

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* reavaliado no momento da chamada, pelo CameraGuard */ }

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

        requestStartupPermissions()
        signIn()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    // -------------------------------------------------------------- chamadas

    /**
     * Executa a forma de chamada escolhida.
     *
     * Cada caminho falha de forma visível e explicada — nada de botão que não
     * faz nada, que para o público-alvo equivale a aparelho quebrado.
     */
    private fun startCall(contact: TrustedContact, method: CallMethod) {
        val phone = contact.phone.orEmpty()
        when (method) {
            CallMethod.WHATSAPP_VIDEO ->
                if (!WhatsAppFallback.startVideoCall(this, phone)) {
                    toast(R.string.whatsapp_missing)
                }

            CallMethod.WHATSAPP_CHAT ->
                if (!WhatsAppFallback.openChat(this, phone)) {
                    toast(R.string.whatsapp_missing)
                }

            CallMethod.PHONE_DIAL -> dial(phone)

            CallMethod.APP_VIDEO -> {
                if (!appCallConfigured) {
                    toast(R.string.app_call_not_configured)
                    return
                }
                CallService.dial(this, contact.uid, contact.name, video = true)
                startActivity(Intent(this, CallActivity::class.java))
            }
        }
    }

    /**
     * `ACTION_DIAL` e não `ACTION_CALL`: abre o discador com o número pronto,
     * sem exigir a permissão `CALL_PHONE`. Uma permissão perigosa a menos, e o
     * usuário ainda confirma a ligação — que é o comportamento certo quando um
     * toque errado custa dinheiro.
     */
    private fun dial(phone: String) {
        val normalized = WhatsAppFallback.normalize(phone) ?: return
        try {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:+$normalized")))
        } catch (e: Exception) {
            toast(R.string.dialer_missing)
        }
    }

    // --------------------------------------------------------------- estado

    private fun signIn() {
        val auth = FirebaseAuth.getInstance()
        auth.currentUser?.let { return onSignedIn(it.uid) }

        auth.signInAnonymously()
            .addOnSuccessListener { result -> result.user?.uid?.let(::onSignedIn) }
            .addOnFailureListener {
                // Caminho esperado com google-services.json de placeholder.
                // O app continua útil: os botões de WhatsApp e telefone
                // funcionam sem Firebase nenhum.
                appCallConfigured = false
                binding.statusBanner.visibility = View.VISIBLE
                binding.statusBanner.setText(R.string.sign_in_failed_hint)
                refresh()
            }
    }

    private fun onSignedIn(uid: String) {
        appCallConfigured = true
        binding.statusBanner.visibility = View.GONE
        binding.myCode.text = uid
        watcher.start(uid)
        FcmTokenRegistrar().registerCurrent(uid)
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

        AlertDialog.Builder(this)
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
        AlertDialog.Builder(this)
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

    private fun requestStartupPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        // READ_CONTACTS habilita a videochamada direta do WhatsApp; sem ela o
        // app cai para a conversa, que funciona igual. Câmera e microfone NÃO
        // são pedidos aqui: só na primeira chamada pelo app, com justificativa.
        permissions += Manifest.permission.READ_CONTACTS
        if (permissions.isNotEmpty()) requestPermissions.launch(permissions.toTypedArray())
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_LONG).show()

    override fun onDestroy() {
        watcher.stop()
        super.onDestroy()
    }
}
