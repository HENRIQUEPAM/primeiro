package com.portaretrato.app.call.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.portaretrato.app.R
import com.portaretrato.app.call.CallMethod
import com.portaretrato.app.call.CallOption
import com.portaretrato.app.call.CallOptions
import com.portaretrato.app.call.TrustedContact
import com.portaretrato.app.databinding.ItemContactBinding

/** Um contato com as opcoes de chamada ja resolvidas. */
data class ContactCard(
    val contact: TrustedContact,
    val options: List<CallOption>,
)

/**
 * Cartao de contato do porta-retrato.
 *
 * Decisoes de UI para o publico idoso:
 *
 * - **Nome grande (28sp) e o cartao inteiro clicavel.** Um toque em qualquer
 *   lugar do cartao usa a melhor forma de chamada disponivel. Ninguem deveria
 *   precisar escolher entre tres botoes para falar com a filha.
 * - **Os tres botoes continuam visiveis** para quem quiser escolher, com 56dp
 *   de altura e rotulo em texto.
 * - **Botao indisponivel fica desabilitado com o motivo escrito**, nunca
 *   escondido. Esconder faria o usuario lembrar que "ontem tinha um botao aqui".
 */
class ContactAdapter(
    private val onCall: (TrustedContact, CallMethod) -> Unit,
    private val onLongPress: (TrustedContact) -> Unit,
) : ListAdapter<ContactCard, ContactAdapter.Holder>(Diff) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemContactBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(getItem(position))

    inner class Holder(private val binding: ItemContactBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(card: ContactCard) {
            binding.contactName.text = card.contact.name

            val best = CallOptions.best(card.options)
            binding.root.setOnClickListener {
                best?.let { onCall(card.contact, it.method) }
            }
            binding.root.setOnLongClickListener {
                onLongPress(card.contact)
                true
            }

            binding.bestHint.text = if (best != null) {
                itemView.context.getString(R.string.tap_to_call, best.label)
            } else {
                itemView.context.getString(R.string.no_way_to_call)
            }

            wire(binding.appVideoButton, card, CallMethod.APP_VIDEO)
            wire(binding.whatsappChatButton, card, CallMethod.WHATSAPP_CHAT)
            wire(binding.phoneButton, card, CallMethod.PHONE_DIAL)
        }

        private fun wire(button: Button, card: ContactCard, method: CallMethod) {
            val option = card.options.firstOrNull { it.method == method } ?: return
            button.isEnabled = option.available
            button.alpha = if (option.available) 1f else DISABLED_ALPHA
            button.text = if (option.available) {
                option.label
            } else {
                // Motivo no proprio botao: o usuario nao precisa tocar para
                // descobrir por que nao funciona.
                "${option.label}\n${option.explanation.orEmpty()}"
            }
            button.setOnClickListener {
                if (option.available) onCall(card.contact, method)
            }
        }
    }

    private companion object {
        const val DISABLED_ALPHA = 0.45f

        val Diff = object : DiffUtil.ItemCallback<ContactCard>() {
            override fun areItemsTheSame(a: ContactCard, b: ContactCard) =
                a.contact.uid == b.contact.uid

            override fun areContentsTheSame(a: ContactCard, b: ContactCard) = a == b
        }
    }
}
