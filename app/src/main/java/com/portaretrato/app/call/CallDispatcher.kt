package com.portaretrato.app.call

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.portaretrato.app.R
import com.portaretrato.app.call.ui.CallActivity

/**
 * Executa a forma de chamada escolhida para um contato.
 *
 * Extraído de `HomeActivity`, que era o único lugar que sabia fazer isto, no
 * dia em que [com.portaretrato.app.ui.SlideshowActivity] passou a precisar do
 * mesmo caminho — o botão de ligar direto de uma foto usa exatamente as
 * mesmas quatro formas de chamada, com o mesmo motivo escrito quando alguma
 * não está disponível. Duas cópias da mesma lógica de despacho divergiriam
 * cedo ou tarde; uma só, reaproveitada, não.
 *
 * Cada caminho falha de forma visível e explicada — nada de botão que não faz
 * nada, que para o público-alvo equivale a aparelho quebrado.
 */
object CallDispatcher {

    fun dispatch(
        activity: AppCompatActivity,
        contact: TrustedContact,
        method: CallMethod,
        appCallConfigured: Boolean,
    ) {
        val phone = contact.phone.orEmpty()
        when (method) {
            CallMethod.WHATSAPP_VIDEO ->
                if (!WhatsAppFallback.startVideoCall(activity, phone)) {
                    toast(activity, R.string.whatsapp_missing)
                }

            CallMethod.WHATSAPP_CHAT ->
                if (!WhatsAppFallback.openChat(activity, phone)) {
                    toast(activity, R.string.whatsapp_missing)
                }

            CallMethod.PHONE_DIAL -> dial(activity, phone)

            CallMethod.APP_VIDEO -> {
                if (!appCallConfigured) {
                    toast(activity, R.string.app_call_not_configured)
                    return
                }
                CallService.dial(activity, contact.uid, contact.name, video = true)
                activity.startActivity(Intent(activity, CallActivity::class.java))
            }
        }
    }

    /**
     * `ACTION_DIAL` e não `ACTION_CALL`: abre o discador com o número pronto,
     * sem exigir a permissão `CALL_PHONE`. Uma permissão perigosa a menos, e o
     * usuário ainda confirma a ligação — que é o comportamento certo quando um
     * toque errado custa dinheiro.
     */
    private fun dial(activity: AppCompatActivity, phone: String) {
        val normalized = WhatsAppFallback.normalize(phone) ?: return
        try {
            activity.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:+$normalized")))
        } catch (e: Exception) {
            toast(activity, R.string.dialer_missing)
        }
    }

    private fun toast(activity: AppCompatActivity, resId: Int) =
        Toast.makeText(activity, resId, Toast.LENGTH_LONG).show()
}
