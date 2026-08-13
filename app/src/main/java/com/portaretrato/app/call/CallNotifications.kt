package com.portaretrato.app.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.portaretrato.app.R
import com.portaretrato.app.call.ui.CallActivity

/**
 * Canais e notificações de chamada.
 *
 * O canal é criado UMA vez, no `onCreate` do service — não a cada notificação,
 * como o `FaceScanWorker` do app de produção faz por foto (ver
 * `docs/ANALISE-E-PLANO.md`, item P12).
 *
 * A notificação de chamada recebida precisa de `IMPORTANCE_HIGH` e de
 * `setFullScreenIntent` — no Android 14+ é ela que autoriza subir um foreground
 * service `camera|microphone` com o app em segundo plano, e é o que faz a tela
 * acender sozinha no porta-retrato.
 */
object CallNotifications {

    const val CHANNEL_INCOMING = "chamadas_recebidas"
    const val CHANNEL_ONGOING = "chamada_em_andamento"
    const val NOTIFICATION_ID = 4001

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_INCOMING,
                "Chamadas recebidas",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Toca quando alguém liga"
                setShowBadge(true)
                enableVibration(true)
            },
        )

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ONGOING,
                "Chamada em andamento",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Mostra a chamada ativa"
                setShowBadge(false)
            },
        )
    }

    fun incoming(context: Context, callerName: String): Notification {
        val fullScreen = pendingActivity(context, CallActivity.intentForIncoming(context))
        val answer = pendingService(context, CallService.acceptIntent(context), REQUEST_ANSWER)
        val decline = pendingService(context, CallService.declineIntent(context), REQUEST_DECLINE)

        val builder = NotificationCompat.Builder(context, CHANNEL_INCOMING)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(context.getString(R.string.incoming_call_title))
            .setContentText(callerName)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setAutoCancel(false)
            .setFullScreenIntent(fullScreen, true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // CallStyle dá a UI nativa de chamada (Android 12+), que o usuário
            // já reconhece de qualquer outro app de telefone.
            builder.setStyle(
                NotificationCompat.CallStyle.forIncomingCall(
                    androidx.core.app.Person.Builder().setName(callerName).build(),
                    decline,
                    answer,
                ),
            )
        } else {
            builder.addAction(R.drawable.ic_call_end, context.getString(R.string.decline), decline)
            builder.addAction(R.drawable.ic_call, context.getString(R.string.answer), answer)
        }
        return builder.build()
    }

    fun ongoing(context: Context, peerName: String): Notification {
        val open = pendingActivity(context, CallActivity.intentForOngoing(context))
        val hangup = pendingService(context, CallService.hangupIntent(context), REQUEST_HANGUP)

        return NotificationCompat.Builder(context, CHANNEL_ONGOING)
            .setSmallIcon(R.drawable.ic_call)
            .setContentTitle(context.getString(R.string.ongoing_call_title))
            .setContentText(peerName)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_call_end, context.getString(R.string.hang_up), hangup)
            .build()
    }

    private fun pendingActivity(context: Context, intent: Intent): PendingIntent =
        PendingIntent.getActivity(
            context,
            REQUEST_OPEN,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private fun pendingService(context: Context, intent: Intent, requestCode: Int): PendingIntent =
        PendingIntent.getService(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    private const val REQUEST_OPEN = 1
    private const val REQUEST_ANSWER = 2
    private const val REQUEST_DECLINE = 3
    private const val REQUEST_HANGUP = 4
}
