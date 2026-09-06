package com.portaretrato.app.call

import android.content.Context

/**
 * Duração máxima de QUALQUER chamada, ajustável em "Recursos avançados"
 * entre [MIN_MINUTES] e [MAX_MINUTES]. Pedido explícito: nenhuma chamada
 * deve ficar aberta indefinidamente — inclusive a que o atendimento
 * automático ("babá eletrônica") aceita sozinho, sem ninguém do lado de cá
 * decidindo desligar.
 *
 * Guardado em minutos inteiros, não milissegundos: é o que a tela mostra e
 * ajusta. A conversão fica a cargo de quem consome ([CallService], ao
 * montar o [CallConfig] de cada chamada) — mesmo padrão de
 * [AutoAnswerSettingsStore], lido de novo a cada chamada, então uma
 * mudança feita em "Recursos avançados" vale a partir da PRÓXIMA chamada,
 * nunca no meio de uma já em andamento.
 */
class CallDurationSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Sempre dentro de [MIN_MINUTES]..[MAX_MINUTES], mesmo se o valor salvo for antigo/inválido. */
    fun maxDurationMinutes(): Int =
        prefs.getInt(KEY_MINUTES, DEFAULT_MINUTES).coerceIn(MIN_MINUTES, MAX_MINUTES)

    fun setMaxDurationMinutes(minutes: Int) {
        prefs.edit().putInt(KEY_MINUTES, minutes.coerceIn(MIN_MINUTES, MAX_MINUTES)).apply()
    }

    companion object {
        const val MIN_MINUTES = 1
        const val MAX_MINUTES = 5
        const val DEFAULT_MINUTES = 3

        private const val PREFS = "call_duration_settings"
        private const val KEY_MINUTES = "max_duration_minutes"
    }
}
