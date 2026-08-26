package com.portaretrato.app.security

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.portaretrato.app.R

/**
 * Aviso visível de que a câmera está ativa.
 *
 * ## Por que existe, se o Android já mostra o ponto verde
 *
 * O indicador de privacidade do Android (API 31+) é bom, mas insuficiente para
 * este produto por três motivos concretos:
 *
 * 1. **Só existe a partir do Android 12.** O app suporta a partir do 8.0, e o
 *    aparelho dedicado do projeto roda um SoM cuja versão não é garantida.
 * 2. **É pequeno e efêmero.** Um ponto de poucos pixels que encolhe depois de
 *    alguns segundos não cumpre a função para o público-alvo: uma pessoa idosa
 *    olhando um porta-retrato do outro lado da sala.
 * 3. **Não diz para quê.** Ele informa que a câmera está ligada, não que está
 *    ligada *reconhecendo rostos nas suas fotos* — e a diferença entre essas
 *    duas frases é toda a diferença de confiança.
 *
 * Então são duas camadas independentes: a do sistema, que o app **não tenta
 * suprimir** e não conseguiria mesmo, e esta, que é explícita e legível. A
 * primeira é a garantia técnica; a segunda é a honestidade com o usuário.
 *
 * A notificação é `ONGOING` e sem botão de dispensar: o usuário não deve
 * conseguir esconder o aviso enquanto a câmera estiver aberta.
 */
interface CameraNotice {
    fun show(purpose: CameraPurpose)
    fun hide()
}

/** Implementação por notificação persistente. */
class NotificationCameraNotice(private val context: Context) : CameraNotice {

    private val manager = NotificationManagerCompat.from(context)

    init {
        createChannel()
    }

    override fun show(purpose: CameraPurpose) {
        // Sem POST_NOTIFICATIONS (Android 13+) não há como avisar. Como a
        // política deste app é "nunca em silêncio", a ausência do aviso é
        // registrada em log para aparecer no diagnóstico — e o indicador do
        // sistema continua valendo como camada independente.
        if (!manager.areNotificationsEnabled()) {
            android.util.Log.w(TAG, "Notificações desativadas: aviso de câmera não pôde ser exibido.")
            return
        }
        runCatching { manager.notify(NOTIFICATION_ID, build(purpose)) }
            .onFailure { android.util.Log.e(TAG, "Falha ao exibir o aviso de câmera", it) }
    }

    override fun hide() {
        runCatching { manager.cancel(NOTIFICATION_ID) }
    }

    /**
     * A notificação também serve ao foreground service de câmera, por isso é
     * pública: o service precisa da mesma `Notification` para chamar
     * `startForeground`, e duas notificações diferentes para o mesmo fato
     * confundiriam o usuário.
     */
    fun build(purpose: CameraPurpose): Notification {
        val open = PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            Intent().setClassName(context, PRIVACY_ACTIVITY),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_camera_active)
            .setContentTitle(context.getString(R.string.camera_notice_title))
            // O motivo, em português, e não "câmera em uso".
            .setContentText(purpose.userVisibleReason)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(true)
            .setContentIntent(open)
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        context.getString(R.string.camera_notice_detail, purpose.userVisibleReason),
                    ),
            )
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.camera_notice_channel),
            // IMPORTANCE_HIGH de propósito: este aviso não deve ficar escondido
            // na gaveta. O usuário pode baixar a importância nas configurações
            // do sistema, o que é direito dele — mas o padrão é visível.
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.camera_notice_channel_description)
            setShowBadge(true)
            enableVibration(false)
            setSound(null, null)
        }
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "camera_ativa"
        const val NOTIFICATION_ID = 7001
        private const val TAG = "CameraNotice"
        private const val REQUEST_OPEN = 70
        private const val PRIVACY_ACTIVITY = "com.portaretrato.app.ui.PrivacyActivity"
    }
}

/**
 * Implementação nula, para testes e para o caso de o aparelho não ter câmera.
 * Nunca use em produção: o app perderia a garantia de aviso.
 */
class NoOpCameraNotice : CameraNotice {
    var shownFor: CameraPurpose? = null
        private set
    var showCount: Int = 0
        private set
    var hideCount: Int = 0
        private set

    override fun show(purpose: CameraPurpose) {
        shownFor = purpose
        showCount++
    }

    override fun hide() {
        shownFor = null
        hideCount++
    }
}
