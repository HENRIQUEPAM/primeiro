package com.portaretrato.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.portaretrato.app.security.AppVisibility
import com.portaretrato.app.security.CameraGuard
import com.portaretrato.app.security.NotificationCameraNotice
import java.util.concurrent.atomic.AtomicInteger

/**
 * Application do Porta Retrato.
 *
 * Dona do [CameraGuard]. Ele é único no processo de propósito: dois guardas
 * significariam dois donos possíveis da câmera ao mesmo tempo, e a garantia de
 * exclusividade cairia por terra.
 */
class PortaRetratoApp : Application() {

    /** Quantas Activities estão visíveis agora. */
    private val startedActivities = AtomicInteger(0)

    /**
     * Ligado pelos foreground services de mídia enquanto estiverem no ar.
     *
     * Existe porque "visível ao usuário" tem duas formas: uma tela aberta, ou
     * uma notificação persistente de service. As duas são aceitáveis para a
     * política de câmera; nenhuma outra é.
     */
    @Volatile
    var mediaForegroundServiceRunning: Boolean = false

    val cameraGuard: CameraGuard by lazy {
        CameraGuard(
            context = this,
            notice = NotificationCameraNotice(this),
            visibilityProvider = ::currentVisibility,
        )
    }

    private fun currentVisibility(): AppVisibility = when {
        startedActivities.get() > 0 -> AppVisibility.FOREGROUND
        mediaForegroundServiceRunning -> AppVisibility.FOREGROUND_SERVICE
        else -> AppVisibility.BACKGROUND
    }

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(VisibilityTracker())
    }

    private inner class VisibilityTracker : ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedActivities.incrementAndGet()
        }

        override fun onActivityStopped(activity: Activity) {
            startedActivities.updateAndGet { if (it > 0) it - 1 else 0 }
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
        override fun onActivityResumed(activity: Activity) = Unit
        override fun onActivityPaused(activity: Activity) = Unit
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
        override fun onActivityDestroyed(activity: Activity) = Unit
    }

    companion object {
        fun from(context: android.content.Context): PortaRetratoApp =
            context.applicationContext as PortaRetratoApp
    }
}
