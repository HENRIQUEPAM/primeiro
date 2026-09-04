package com.portaretrato.app.admin.ui

import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.portaretrato.app.R
import com.portaretrato.app.admin.AdminAccess
import com.portaretrato.app.call.AutoAnswerSettingsStore
import com.portaretrato.app.databinding.ActivityAdminBinding

/**
 * "Recursos avançados": uma senha de administrador, e atrás dela o
 * interruptor mestre do atendimento automático ("babá eletrônica" — ver
 * [com.portaretrato.app.call.AutoAnswerPolicy]).
 *
 * Sempre nasce travada, mesmo que já tenha sido destravada nesta mesma
 * sessão do app: não guarda "já entrei uma vez" em lugar nenhum. É a
 * diferença entre uma tela de administrador de verdade e uma que só finge
 * pedir senha na primeira vez.
 *
 * Duas telas em uma, trocando de seção em vez de abrir uma Activity nova:
 * sem senha cadastrada, a seção de login pede para CRIAR uma; com senha
 * cadastrada, pede para DIGITAR. Só depois de entrar aparece o painel.
 */
class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding
    private lateinit var access: AdminAccess
    private lateinit var autoAnswerSettings: AutoAnswerSettingsStore

    /** `true` enquanto ainda não existe senha — decide o texto e o comportamento da tela. */
    private var creatingPassword = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

        access = AdminAccess(this)
        autoAnswerSettings = AutoAnswerSettingsStore(this)
        creatingPassword = !access.isConfigured()

        renderLoginMode()
        binding.loginButton.setOnClickListener { onLoginButtonTapped() }
        binding.changePasswordButton.setOnClickListener { showChangePasswordDialog() }
    }

    // --------------------------------------------------------------- login

    private fun renderLoginMode() {
        if (creatingPassword) {
            binding.loginIntro.setText(R.string.admin_create_intro)
            binding.confirmPasswordInputLayout.visibility = View.VISIBLE
            binding.loginButton.setText(R.string.admin_create_button)
            binding.noRecoveryNotice.visibility = View.VISIBLE
        } else {
            binding.loginIntro.setText(R.string.admin_intro)
            binding.confirmPasswordInputLayout.visibility = View.GONE
            binding.loginButton.setText(R.string.admin_enter_button)
            binding.noRecoveryNotice.visibility = View.GONE
        }
    }

    private fun onLoginButtonTapped() {
        val password = binding.passwordInput.text?.toString().orEmpty()

        if (creatingPassword) {
            val confirm = binding.confirmPasswordInput.text?.toString().orEmpty()
            when {
                password.length < MIN_PASSWORD_LENGTH -> showLoginError(R.string.admin_password_too_short)
                password != confirm -> showLoginError(R.string.admin_passwords_dont_match)
                else -> {
                    access.setPassword(password)
                    toast(R.string.admin_new_password_set)
                    unlock()
                }
            }
        } else {
            if (access.verify(password)) {
                unlock()
            } else {
                showLoginError(R.string.admin_wrong_password)
            }
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

    // ------------------------------------------------------------- painel

    /**
     * Já dentro do painel — provou conhecer a senha atual só de estar aqui —
     * então pede só a nova, duas vezes, sem pedir a antiga de novo.
     */
    private fun showChangePasswordDialog() {
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
            .setTitle(R.string.admin_change_password_button)
            .setView(container)
            .setPositiveButton(R.string.save) { _, _ ->
                val newPassword = newPasswordField.text.toString()
                val confirm = confirmField.text.toString()
                when {
                    newPassword.length < MIN_PASSWORD_LENGTH -> toast(R.string.admin_password_too_short)
                    newPassword != confirm -> toast(R.string.admin_passwords_dont_match)
                    else -> {
                        access.setPassword(newPassword)
                        toast(R.string.admin_new_password_set)
                    }
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_LONG).show()

    private companion object {
        const val MIN_PASSWORD_LENGTH = 4
    }
}
