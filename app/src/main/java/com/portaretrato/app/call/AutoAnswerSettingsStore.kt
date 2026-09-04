package com.portaretrato.app.call

import android.content.Context

/**
 * Interruptor mestre do atendimento automático ("babá eletrônica"), em
 * tempo de execução — sem esta classe, a única chave era
 * [AutoAnswerPolicy.FEATURE_ENABLED], uma constante compilada no APK.
 *
 * Existe porque a decisão deixou de ser "nunca, ponto" e passou a ser "só
 * atrás da senha de administrador" — ver
 * [com.portaretrato.app.admin.ui.AdminActivity]. [AutoAnswerPolicy.
 * FEATURE_ENABLED] continua sendo o valor de fábrica (desligado) usado
 * quando ninguém jamais abriu aquela tela; esta classe é o que
 * [com.portaretrato.app.call.CallService] de fato consulta a cada chamada.
 */
class AutoAnswerSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, AutoAnswerPolicy.FEATURE_ENABLED)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private companion object {
        const val PREFS = "auto_answer_settings"
        const val KEY_ENABLED = "master_enabled"
    }
}
