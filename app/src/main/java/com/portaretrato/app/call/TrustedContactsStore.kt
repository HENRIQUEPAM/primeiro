package com.portaretrato.app.call

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Contatos de confiança, em `SharedPreferences`.
 *
 * Deliberadamente local e simples: quem pode abrir a câmera da casa
 * automaticamente é decisão do dono do aparelho, e não deve depender de
 * sincronização com a nuvem — se o Firestore ficar fora do ar ou for
 * comprometido, a lista de auto-atendimento não muda.
 */
class TrustedContactsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<TrustedContact> {
        val raw = prefs.getString(KEY_CONTACTS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                TrustedContact(
                    uid = obj.getString("uid"),
                    name = obj.getString("name"),
                    phone = obj.optString("phone").takeIf { it.isNotBlank() },
                    autoAnswerEnabled = obj.optBoolean("autoAnswer", false),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun upsert(contact: TrustedContact) {
        val updated = all().filterNot { it.uid == contact.uid } + contact
        save(updated)
    }

    fun remove(uid: String) = save(all().filterNot { it.uid == uid })

    /**
     * Encontra o contato de confiança pelo telefone, não pelo uid.
     *
     * Existe porque a mesma pessoa pode acabar com dois registros: um criado
     * em "Adicionar contato" (com código de aparelho opcional) e outro criado
     * ao vincular um telefone a um rosto reconhecido em PeopleActivity, que só
     * conhece o telefone e por isso usa um uid sintético (`TrustedContact.
     * PHONE_UID_PREFIX` + telefone). Comparar por uid faria o botão de ligar
     * do porta-retrato nunca encontrar o contato com código de aparelho de
     * alguém que também foi reconhecido numa foto — perdendo a opção de
     * chamada pelo app exatamente para quem ela mais importa. Quando há mais
     * de um registro com o mesmo telefone, prefere o que tem código de
     * aparelho.
     */
    fun findByPhone(phone: String): TrustedContact? {
        val normalized = PhoneNumbers.normalize(phone) ?: return null
        val matches = all().filter { it.phone?.let(PhoneNumbers::normalize) == normalized }
        return matches.firstOrNull { it.hasDeviceCode } ?: matches.firstOrNull()
    }

    fun setAutoAnswer(uid: String, enabled: Boolean) {
        save(all().map { if (it.uid == uid) it.copy(autoAnswerEnabled = enabled) else it })
    }

    private fun save(contacts: List<TrustedContact>) {
        val array = JSONArray()
        for (c in contacts) {
            array.put(
                JSONObject()
                    .put("uid", c.uid)
                    .put("name", c.name)
                    .put("phone", c.phone ?: "")
                    .put("autoAnswer", c.autoAnswerEnabled),
            )
        }
        prefs.edit().putString(KEY_CONTACTS, array.toString()).apply()
    }

    private companion object {
        const val PREFS = "trusted_contacts"
        const val KEY_CONTACTS = "contacts"
    }
}
