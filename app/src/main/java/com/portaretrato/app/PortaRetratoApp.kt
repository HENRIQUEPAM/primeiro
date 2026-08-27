package com.portaretrato.app

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.portaretrato.app.people.FaceDatabaseStore
import com.portaretrato.app.photo.PhotoLibrary
import com.portaretrato.app.recognition.FaceScanCoordinator
import com.portaretrato.app.security.AppVisibility
import com.portaretrato.app.security.CameraGuard
import com.portaretrato.app.security.NotificationCameraNotice
import java.util.concurrent.atomic.AtomicInteger

/**
 * Application do Porta Retrato.
 *
 * Dona do [CameraGuard] e do [FaceScanCoordinator]. Os dois são únicos no
 * processo de propósito: dois guardas significariam dois donos possíveis da
 * câmera ao mesmo tempo, e a garantia de exclusividade cairia por terra; dois
 * índices de rostos significariam duas cópias do banco disputando o mesmo
 * arquivo, e a última a gravar apagaria o trabalho da outra.
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

    /**
     * Dono do índice de rostos — pelo mesmo motivo do [cameraGuard].
     *
     * Cada tela criando o seu carregaria uma cópia própria do banco em memória,
     * e a última a gravar venceria: o usuário nomearia a avó na tela de
     * pessoas, voltaria ao porta-retrato, e a varredura salvaria a cópia antiga
     * por cima — os nomes recém-digitados sumiriam sem nenhum sinal.
     *
     * Uma instância só no processo elimina a classe inteira de problema, em vez
     * de tentar sincronizar duas cópias.
     */
    private val faceScannerDelegate = lazy {
        FaceScanCoordinator(this, PhotoLibrary(this), FaceDatabaseStore(this))
    }
    val faceScanner: FaceScanCoordinator by faceScannerDelegate

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
            val visible = startedActivities.updateAndGet { if (it > 0) it - 1 else 0 }
            // A varredura para quando o APP sai da tela, nao quando uma tela
            // sai. Cancelar no onStop de cada Activity mataria a varredura que
            // a tela seguinte acabou de iniciar — o onStop da tela antiga roda
            // DEPOIS do onCreate da nova.
            //
            // `isInitialized` evita construir o coordenador (e carregar o banco
            // do disco) so para cancelar algo que nunca comecou.
            if (visible == 0 && faceScannerDelegate.isInitialized()) {
                faceScanner.cancel()
            }
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
