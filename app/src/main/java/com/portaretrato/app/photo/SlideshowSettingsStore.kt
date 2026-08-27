package com.portaretrato.app.photo

import android.content.Context

/** Persiste as preferencias do slideshow. */
class SlideshowSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): SlideshowSettings = SlideshowSettings(
        order = runCatching {
            SlideshowOrder.valueOf(prefs.getString(KEY_ORDER, null) ?: SlideshowOrder.SHUFFLE.name)
        }.getOrDefault(SlideshowOrder.SHUFFLE),
        intervalMs = prefs.getLong(KEY_INTERVAL, SlideshowSettings.DEFAULT_INTERVAL_MS),
    )

    fun save(settings: SlideshowSettings) {
        prefs.edit()
            .putString(KEY_ORDER, settings.order.name)
            .putLong(KEY_INTERVAL, settings.intervalMs)
            .apply()
    }

    private companion object {
        const val PREFS = "slideshow"
        const val KEY_ORDER = "order"
        const val KEY_INTERVAL = "interval"
    }
}
