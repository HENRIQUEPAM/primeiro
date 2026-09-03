package com.portaretrato.app.call

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Estado da sessão do aparelho. */
sealed interface AuthState {
    /** Ainda não se sabe — a primeira consulta ao Firebase está em voo. */
    data object SigningIn : AuthState

    /** Autenticado; chamada pelo próprio app está disponível. */
    data class SignedIn(val uid: String) : AuthState

    /** Sem Firebase configurado, ou sem rede. O app segue útil mesmo assim. */
    data object Failed : AuthState
}

/**
 * Dona da sessão do aparelho: login anônimo, escuta de chamadas recebidas e
 * registro do token de push.
 *
 * ## Por que isto mudou de dono
 *
 * Antes, `HomeActivity` fazia login E controlava o [IncomingCallWatcher] —
 * `start()` no `onCreate`, `stop()` no `onDestroy`. Funcionava enquanto
 * `HomeActivity` também era a tela inicial do app. Deixou de funcionar no dia
 * em que o porta-retrato ([com.portaretrato.app.ui.SlideshowActivity]) virou a
 * tela principal: a escuta de chamadas só ficava ativa enquanto a tela de
 * contatos estivesse aberta, ou seja, quase nunca — exatamente o oposto do que
 * um porta-retrato precisa.
 *
 * Aqui a sessão pertence ao processo, não a uma tela — o mesmo padrão já usado
 * para [com.portaretrato.app.security.CameraGuard] e para
 * [com.portaretrato.app.recognition.FaceScanCoordinator]. `start()` é chamado
 * uma única vez, em `PortaRetratoApp.onCreate()`, e a escuta de chamadas fica
 * de pé pelo tempo de vida inteiro do processo — a tela de login apenas
 * observa o resultado.
 *
 * Idempotente: chamar `start()` mais de uma vez não reautentica nem religa o
 * watcher duas vezes.
 */
class AuthSession(context: Context) {

    private val appContext = context.applicationContext
    private val watcher = IncomingCallWatcher(appContext)
    private var started = false

    private val _state = MutableStateFlow<AuthState>(AuthState.SigningIn)
    val state: StateFlow<AuthState> = _state

    fun start() {
        if (started) return
        started = true

        val auth = FirebaseAuth.getInstance()
        auth.currentUser?.let { return onSignedIn(it.uid) }

        auth.signInAnonymously()
            .addOnSuccessListener { result -> result.user?.uid?.let(::onSignedIn) ?: onFailed() }
            .addOnFailureListener {
                // Caminho esperado com google-services.json de placeholder, ou
                // sem "Anônimo" ativado no console. O app continua útil: os
                // botões de WhatsApp e telefone funcionam sem Firebase nenhum.
                onFailed()
            }
    }

    private fun onSignedIn(uid: String) {
        watcher.start(uid)
        FcmTokenRegistrar().registerCurrent(uid)
        _state.value = AuthState.SignedIn(uid)
    }

    private fun onFailed() {
        _state.value = AuthState.Failed
    }
}
