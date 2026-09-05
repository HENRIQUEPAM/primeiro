package com.portaretrato.app.admin.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.portaretrato.app.R
import com.portaretrato.app.admin.AdminAccess
import com.portaretrato.app.call.AutoAnswerSettingsStore
import com.portaretrato.app.databinding.ActivityAdminBinding

/**
 * "Recursos avançados": uma senha de administrador — a MESMA em qualquer
 * instalação deste app, ver [AdminAccess] — e atrás dela o interruptor
 * mestre do atendimento automático ("babá eletrônica" — ver
 * [com.portaretrato.app.call.AutoAnswerPolicy]).
 *
 * Sempre nasce travada, mesmo que já tenha sido destravada nesta mesma
 * sessão do app: não guarda "já entrei uma vez" em lugar nenhum.
 *
 * O interruptor, uma vez destravado, é uma preferência DESTE aparelho
 * (ver [AutoAnswerSettingsStore]) — a senha é global, mas cada aparelho
 * decide por si se quer o recurso ligado.
 */
class AdminActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAdminBinding
    private lateinit var autoAnswerSettings: AutoAnswerSettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAdminBinding.inflate(layoutInflater)
        setContentView(binding.root)

        autoAnswerSettings = AutoAnswerSettingsStore(this)

        if (AdminAccess.isConfigured()) {
            binding.loginIntro.setText(R.string.admin_intro)
            binding.loginButton.setOnClickListener { onLoginButtonTapped() }
        } else {
            // Nenhuma senha foi definida para este build ainda (ver
            // AdminPassword.HASH_BASE64) — deixa isso explícito em vez de um
            // campo de senha que nunca vai aceitar nada.
            binding.loginIntro.setText(R.string.admin_not_configured)
            binding.passwordInputLayout.visibility = View.GONE
            binding.loginButton.visibility = View.GONE
        }
    }

    // --------------------------------------------------------------- login

    private fun onLoginButtonTapped() {
        val password = binding.passwordInput.text?.toString().orEmpty()
        if (AdminAccess.verify(password)) {
            unlock()
        } else {
            binding.loginError.setText(R.string.admin_wrong_password)
            binding.loginError.visibility = View.VISIBLE
        }
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
}
