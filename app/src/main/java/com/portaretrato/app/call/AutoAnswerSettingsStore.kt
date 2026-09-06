package com.portaretrato.app.call

import android.content.Context

/**
 * Configurações do atendimento automático ("babá eletrônica"), em tempo de
 * execução — sem esta classe, os únicos valores eram constantes compiladas
 * no APK ([AutoAnswerPolicy.FEATURE_ENABLED] e
 * [AutoAnswerPolicy.DEFAULT_ANSWER_DELAY_MS]).
 *
 * Existe porque a decisão deixou de ser "nunca, ponto" e passou a ser "só
 * atrás da senha de administrador" — ver
 * [com.portaretrato.app.admin.ui.AdminActivity]. As constantes em
 * [AutoAnswerPolicy] continuam sendo o valor de fábrica, usado quando
 * ninguém jamais abriu aquela tela; esta classe é o que
 * [com.portaretrato.app.call.CallService] de fato consulta a cada chamada.
 */
class AutoAnswerSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, AutoAnswerPolicy.FEATURE_ENABLED)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Segundos de contagem regressiva antes de atender sozinho, sempre
     * dentro de [MIN_DELAY_SECONDS]..[MAX_DELAY_SECONDS] — tempo para quem
     * liga (e, na tela, quem recebe) ver quem é e recusar antes da câmera e
     * do microfone abrirem sozinhos.
     */
    fun answerDelaySeconds(): Int =
        prefs.getInt(KEY_DELAY_SECONDS, DEFAULT_DELAY_SECONDS).coerceIn(MIN_DELAY_SECONDS, MAX_DELAY_SECONDS)

    fun setAnswerDelaySeconds(seconds: Int) {
        prefs.edit()
            .putInt(KEY_DELAY_SECONDS, seconds.coerceIn(MIN_DELAY_SECONDS, MAX_DELAY_SECONDS))
            .apply()
    }

    companion object {
        const val MIN_DELAY_SECONDS = 10
        const val MAX_DELAY_SECONDS = 30
        const val DEFAULT_DELAY_SECONDS = 20

        private const val PREFS = "auto_answer_settings"
        private const val KEY_ENABLED = "master_enabled"
        private const val KEY_DELAY_SECONDS = "answer_delay_seconds"
    }
}
