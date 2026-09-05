package com.portaretrato.app.admin.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.portaretrato.app.R
import com.portaretrato.app.admin.AdminAccess
import com.portaretrato.app.admin.AdminVerifyResult
import com.portaretrato.app.admin.LocalRegisterResult
import com.portaretrato.app.call.AutoAnswerSettingsStore
import com.portaretrato.app.databinding.ActivityAdminBinding

/**
 * "Recursos avançados": duas formas de entrar (ver [AdminAccess]) — a senha
 * global, igual em qualquer instalação, ou a senha local, cadastrada por
 * este aparelho e presa à rede Wi-Fi em que foi criada. As duas liberam o
 * mesmo painel, com o interruptor mestre do atendimento automático ("babá
 * eletrônica" — ver [com.portaretrato.app.call.AutoAnswerPolicy]).
 *
 * Sempre nasce travada, mesmo que já tenha sido destravada nesta mesma
 * sessão do app: não guarda "já entrei uma vez" em lugar nenhum.
 */
class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding
    private lateinit var access: AdminAccess
    private lateinit var autoAnswerSettings: AutoAnswerSettingsStore

    private val requestLocationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) showCreateLocalPasswordDialog() else toast(R.string.admin_location_permission_denied)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

        access = AdminAccess(this)
        autoAnswerSettings = AutoAnswerSettingsStore(this)

        binding.loginIntro.setText(R.string.admin_intro)
        binding.loginButton.setOnClickListener { onLoginButtonTapped() }
        binding.localPasswordButton.setOnClickListener { onLocalPasswordButtonTapped() }
        renderLocalPasswordSection()
    }

    override fun onResume() {
        super.onResume()
        // Voltar de "conceder permissão" nas Configurações do Android, ou
        // simplesmente reabrir esta tela depois de cadastrar, precisa
        // refletir o estado atual — não só o momento do onCreate.
        renderLocalPasswordSection()
    }

    // --------------------------------------------------------------- login

    private fun onLoginButtonTapped() {
        val password = binding.passwordInput.text?.toString().orEmpty()
        when (access.verify(password)) {
            AdminVerifyResult.Granted -> unlock()
            AdminVerifyResult.WrongNetwork -> showLoginError(R.string.admin_wrong_network)
            AdminVerifyResult.WrongPassword -> showLoginError(R.string.admin_wrong_password)
        }
    }

    private fun showLoginError(resId: Int) {
        binding.loginError.setText(resId)
        binding.loginError.visibility = View.VISIBLE
    }

    private fun unlock() {
        binding.loginSection.visibility = View.GONE
        binding.panelSection.visibility = View.VISIBLE

        // Sem listener enquanto o valor inicial é aplicado — sem isto, ligar
        // o interruptor programaticamente dispararia setEnabled(true) mesmo
        // quando o estado salvo já era esse, sem efeito real mas confuso de
        // depurar.
        binding.autoAnswerSwitch.setOnCheckedChangeListener(null)
        binding.autoAnswerSwitch.isChecked = autoAnswerSettings.isEnabled()
        binding.autoAnswerSwitch.setOnCheckedChangeListener { _, checked ->
            autoAnswerSettings.setEnabled(checked)
        }
    }

    // --------------------------------------------------------- senha local

    private fun renderLocalPasswordSection() {
        val network = access.local.homeNetworkLabel()
        if (network != null) {
            binding.localPasswordStatus.text = getString(R.string.admin_local_password_configured, network)
            binding.localPasswordButton.setText(R.string.admin_change_local_password_button)
        } else {
            binding.localPasswordStatus.setText(R.string.admin_local_password_not_configured)
            binding.localPasswordButton.setText(R.string.admin_register_local_password_button)
        }
    }

    private fun onLocalPasswordButtonTapped() {
        if (!access.local.isOnWifi()) {
            toast(R.string.admin_need_wifi_to_register)
            return
        }
        if (hasLocationPermission()) {
            showCreateLocalPasswordDialog()
        } else {
            showLocationPermissionRationale()
        }
    }

    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Explica ANTES do diálogo do sistema, como o resto do app já faz para
     * câmera e microfone (ver `PermissionFlow`) — "localização" alarma mais
     * do que qualquer outra permissão pedida aqui, e sem contexto pareceria
     * que o app passou a rastrear onde o aparelho anda.
     */
    private fun showLocationPermissionRationale() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.admin_location_permission_title)
            .setMessage(R.string.admin_location_permission_explanation)
            .setPositiveButton(R.string.admin_location_permission_continue) { _, _ ->
                requestLocationPermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showCreateLocalPasswordDialog() {
        val newPasswordField = EditText(this).apply {
            hint = getString(R.string.admin_password_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val confirmField = EditText(this).apply {
            hint = getString(R.string.admin_confirm_password_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(newPasswordField)
            addView(confirmField)
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.admin_register_local_password_button)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val newPassword = newPasswordField.text.toString()
                val confirm = confirmField.text.toString()
                when {
                    newPassword.length < MIN_PASSWORD_LENGTH -> toast(R.string.admin_password_too_short)
                    newPassword != confirm -> toast(R.string.admin_passwords_dont_match)
                    else -> registerLocalPassword(newPassword)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun registerLocalPassword(password: String) {
        when (access.local.register(password)) {
            LocalRegisterResult.Success -> {
                toast(R.string.admin_new_password_set)
                renderLocalPasswordSection()
            }
            LocalRegisterResult.NotOnWifi -> toast(R.string.admin_need_wifi_to_register)
            LocalRegisterResult.NetworkNameUnavailable -> toast(R.string.admin_network_name_unavailable)
        }
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_LONG).show()

    private companion object {
        const val MIN_PASSWORD_LENGTH = 4
    }
}
