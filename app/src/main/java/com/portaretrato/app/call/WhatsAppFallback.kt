package com.portaretrato.app.call

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Caminho WhatsApp, **preservado** ao lado da chamada P2P.
 *
 * A Secao 7.4 e explicita: o botao "Chamar Porta-Retrato" e o **terceiro**
 * botao do cartao de contato, ao lado de Ligar / WhatsApp, e **nunca
 * substitui** o fallback. Faz sentido: sem TURN funcionando, sem pareamento,
 * ou com permissoes negadas, o WhatsApp continua sendo o caminho que
 * funciona.
 *
 * So abre a conversa ([openChat]) — a videochamada direta do WhatsApp (deep
 * link para o mimetype de videochamada, exigindo o contato salvo E
 * sincronizado pelo WhatsApp com aquele mimetype) foi removida depois de
 * testar no aparelho real e ela simplesmente nao abrir. Ver o KDoc de
 * [com.portaretrato.app.call.CallMethod.WHATSAPP_CHAT].
 *
 * Reescrito a partir do `SlideshowActivity` / `WhatsAppContactHelper` da v2.9
 * com uma correcao sobre o codigo original: **normalizacao E.164**. O codigo
 * antigo so filtrava digitos. Um telefone salvo como "11 99999-9999" virava
 * `wa.me/11999999999`, sem DDI, que nao resolve. Aqui o DDI padrao e
 * aplicado quando falta.
 */
object WhatsAppFallback {

    private const val TAG = "WhatsAppFallback"

    /** Ver [PhoneNumbers.normalize] — extraido para ser testavel sem Android. */
    fun normalize(phone: String): String? = PhoneNumbers.normalize(phone)

    /** Abre a conversa. Funciona sem agenda e sem permissao nenhuma. */
    fun openChat(context: Context, phone: String): Boolean {
        val digits = normalize(phone) ?: return false
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$digits"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Nao foi possivel abrir o WhatsApp", e)
            false
        }
    }
}
