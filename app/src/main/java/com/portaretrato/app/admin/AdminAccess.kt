package com.portaretrato.app.admin

import android.content.Context
import android.util.Base64

/** Resultado de conferir a senha digitada em "Recursos avançados". */
sealed interface AdminVerifyResult {
    data object Granted : AdminVerifyResult
    data object WrongPassword : AdminVerifyResult

    /** A senha LOCAL está certa, mas o aparelho não está na rede em que ela foi cadastrada. */
    data object WrongNetwork : AdminVerifyResult
}

/**
 * Confere a senha digitada contra as DUAS formas de entrar em "Recursos
 * avançados":
 *
 *  - a senha GLOBAL ([AdminPassword]) — a mesma em qualquer instalação
 *    deste app, funciona em qualquer rede;
 *  - a senha LOCAL ([LocalAdminAccess]) — cadastrada por este aparelho,
 *    só funciona na mesma rede Wi-Fi em que foi criada.
 *
 * Qualquer uma das duas libera exatamente o mesmo painel — nenhuma é "mais
 * premium" que a outra, só mudam quem escolhe a senha e onde ela vale.
 */
class AdminAccess(context: Context) {

    val local = LocalAdminAccess(context)

    fun verify(password: String): AdminVerifyResult = when {
        verifyGlobal(password) -> AdminVerifyResult.Granted
        local.verify(password) -> AdminVerifyResult.Granted
        local.matchesButWrongNetwork(password) -> AdminVerifyResult.WrongNetwork
        else -> AdminVerifyResult.WrongPassword
    }

    private fun verifyGlobal(password: String): Boolean {
        val hash = try {
            Base64.decode(AdminPassword.GLOBAL_HASH_BASE64, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            return false
        }
        return PasswordHashing.matches(password, hash)
    }
}
