package com.portaretrato.app.call

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import android.util.Log

/**
 * Caminho WhatsApp, **preservado** ao lado da chamada P2P.
 *
 * A Secao 7.4 e explicita: o botao "Chamar Porta-Retrato" e o **quarto** botao
 * do cartao de contato, ao lado de Ligar / Chat WhatsApp / Video WhatsApp, e
 * **nunca substitui** o fallback. Faz sentido: sem TURN funcionando, sem
 * pareamento, ou com permissoes negadas, o WhatsApp continua sendo o caminho
 * que funciona.
 *
 * Tambem e o destino automatico quando o ICE falha
 * (`ICEConnectionState.FAILED`) — fallback de um toque, sem o idoso precisar
 * entender o que deu errado.
 *
 * Reescrito a partir do `SlideshowActivity` / `WhatsAppContactHelper` da v2.9
 * com duas correcoes sobre o codigo original:
 *
 * 1. **Normalizacao E.164.** O codigo antigo so filtrava digitos. Um telefone
 *    salvo como "11 99999-9999" virava `wa.me/11999999999`, sem DDI, que nao
 *    resolve. Aqui o DDI padrao e aplicado quando falta.
 * 2. **Cursor sem vazamento.** O lookup do mimetype de videochamada usa `use {}`
 *    nos dois cursores.
 */
object WhatsAppFallback {

    private const val TAG = "WhatsAppFallback"
    private const val MIMETYPE_VIDEO_CALL = "vnd.android.cursor.item/vnd.com.whatsapp.video.call"
    private const val MIMETYPE_VOICE_CALL = "vnd.android.cursor.item/vnd.com.whatsapp.voip.call"

    /** Ver [PhoneNumbers.normalize] — extraido para ser testavel sem Android. */
    fun normalize(phone: String): String? = PhoneNumbers.normalize(phone)

    /**
     * Intent de videochamada direta, quando o contato esta na agenda e o
     * WhatsApp sincronizou a linha correspondente.
     *
     * Devolve `null` quando nao encontra — o chamador entao usa [openChat].
     */
    fun videoCallIntent(context: Context, phone: String): Intent? =
        callIntent(context, phone, MIMETYPE_VIDEO_CALL)

    fun voiceCallIntent(context: Context, phone: String): Intent? =
        callIntent(context, phone, MIMETYPE_VOICE_CALL)

    private fun callIntent(context: Context, phone: String, mimeType: String): Intent? = try {
        val digits = normalize(phone) ?: return null
        val lookupUri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(digits),
        )

        val contactId = context.contentResolver
            .query(lookupUri, arrayOf(ContactsContract.PhoneLookup._ID), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

        if (contactId == null) {
            null
        } else {
            val dataId = context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data._ID),
                "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(contactId.toString(), mimeType),
                null,
            )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }

            dataId?.let { id ->
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, id),
                        mimeType,
                    )
                }
            }
        }
    } catch (e: SecurityException) {
        // READ_CONTACTS negada: cai para o chat, que nao precisa da agenda.
        Log.w(TAG, "Sem permissao de contatos; usando o chat do WhatsApp.")
        null
    } catch (e: Exception) {
        Log.e(TAG, "Falha ao montar a intent de chamada do WhatsApp", e)
        null
    }

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

    /**
     * Caminho completo do fallback: tenta videochamada direta e, se nao houver,
     * abre a conversa.
     */
    fun startVideoCall(context: Context, phone: String): Boolean {
        videoCallIntent(context, phone)?.let { intent ->
            return try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: Exception) {
                Log.w(TAG, "Intent de videochamada recusada; abrindo a conversa.", e)
                openChat(context, phone)
            }
        }
        return openChat(context, phone)
    }
}
