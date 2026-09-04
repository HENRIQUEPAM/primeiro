package com.portaretrato.app.admin

import android.content.Context
import android.util.Base64

/**
 * Senha de administrador, local ao aparelho.
 *
 * Não é conta, não é login, não passa por servidor nenhum — só protege
 * "Recursos avançados" ([com.portaretrato.app.admin.ui.AdminActivity]) de
 * ser aberto sem querer por quem pega o aparelho emprestado. Hoje o único
 * recurso atrás dela é o atendimento automático ("babá eletrônica"): a
 * câmera e o microfone abrindo sozinhos ao receber uma chamada de alguém de
 * confiança. Pedido explícito de quem manda no produto — ver o KDoc de
 * [com.portaretrato.app.call.AutoAnswerPolicy].
 *
 * **Sem recuperação.** Esquecer a senha significa limpar os dados do app
 * (Configurações do Android → Apps → Porta Retrato → Armazenamento → Limpar
 * dados) e escolher outra — o preço aceitável de não guardar a senha em
 * nenhum servidor nem pedir e-mail de recuperação a uma pessoa idosa que
 * nem deveria saber que esta tela existe.
 */
class AdminAccess(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Já existe uma senha cadastrada? Decide se a tela pede para criar ou para entrar. */
    fun isConfigured(): Boolean = prefs.contains(KEY_HASH)

    fun setPassword(password: String) {
        val hash = PasswordHashing.hash(password)
        prefs.edit().putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP)).apply()
    }

    fun verify(password: String): Boolean {
        val stored = prefs.getString(KEY_HASH, null) ?: return false
        val hash = try {
            Base64.decode(stored, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            return false
        }
        return PasswordHashing.matches(password, hash)
    }

    private companion object {
        const val PREFS = "admin_access"
        const val KEY_HASH = "password_hash"
    }
}
