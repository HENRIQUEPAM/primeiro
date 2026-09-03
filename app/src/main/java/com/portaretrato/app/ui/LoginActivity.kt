package com.portaretrato.app.ui

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.portaretrato.app.PortaRetratoApp
import com.portaretrato.app.call.AuthState
import com.portaretrato.app.databinding.ActivityLoginBinding
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tela inicial de verdade: login, depois o porta-retrato.
 *
 * Não há nada para o usuário fazer aqui — o login é anônimo
 * ([com.portaretrato.app.call.AuthSession], disparado por
 * [PortaRetratoApp.onCreate] assim que o processo começa, geralmente antes
 * mesmo desta tela terminar de aparecer, porque a sessão anônima persiste
 * entre execuções). Esta tela só espera o resultado e segue para
 * [SlideshowActivity], que é o porta-retrato propriamente dito.
 *
 * ## O prazo de segurança
 *
 * Um porta-retrato não pode ficar preso numa tela de carregamento por causa de
 * Wi-Fi lento — seria pior do que a chamada pelo app não funcionar. Depois de
 * [LOGIN_TIMEOUT_MS] a tela segue em frente de qualquer jeito, mesmo sem
 * resposta do Firebase: o app continua útil (fotos, reconhecimento,
 * WhatsApp, telefone), só a chamada pelo próprio aparelho fica indisponível
 * até a sessão resolver ao fundo.
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* reavaliado no momento de uso, por quem precisar de cada permissão */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        requestStartupPermissions()

        val session = PortaRetratoApp.from(this).authSession
        lifecycleScope.launch {
            withTimeoutOrNull(LOGIN_TIMEOUT_MS) {
                session.state.first { it !is AuthState.SigningIn }
            }
            goToSlideshow()
        }
    }

    private fun goToSlideshow() {
        startActivity(Intent(this, SlideshowActivity::class.java))
        // Sem esta tela na pilha: o botão Voltar a partir do porta-retrato sai
        // do app, não volta para uma tela de carregamento vazia.
        finish()
    }

    /**
     * Notificação e contatos, pedidos aqui — e só aqui — porque esta é a
     * primeira tela do app. Câmera e microfone NÃO são pedidos: só na primeira
     * chamada, com justificativa (ver PermissionFlow).
     */
    private fun requestStartupPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        // Habilita a videochamada direta do WhatsApp; sem ela o app cai para a
        // conversa, que funciona igual.
        permissions += Manifest.permission.READ_CONTACTS
        if (permissions.isNotEmpty()) requestPermissions.launch(permissions.toTypedArray())
    }

    private companion object {
        const val LOGIN_TIMEOUT_MS = 4_000L
    }
}
