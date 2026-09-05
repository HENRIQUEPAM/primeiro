package com.portaretrato.app.admin

import android.util.Base64

/**
 * Confere a senha digitada contra [AdminPassword] — a mesma senha, para
 * qualquer instalação deste app, não uma por aparelho.
 *
 * Sem estado: nada aqui é gravado em disco (o hash já mora em
 * [AdminPassword], compilado no APK), então não precisa de `Context`.
 */
object AdminAccess {

    /** Existe uma senha configurada para este build? Ver [AdminPassword.HASH_BASE64]. */
    fun isConfigured(): Boolean = AdminPassword.HASH_BASE64 != null

    fun verify(password: String): Boolean {
        val stored = AdminPassword.HASH_BASE64 ?: return false
        val hash = try {
            Base64.decode(stored, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            return false
        }
        return PasswordHashing.matches(password, hash)
    }
}
