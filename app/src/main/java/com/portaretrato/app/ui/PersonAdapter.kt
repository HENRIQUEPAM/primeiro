package com.portaretrato.app.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.portaretrato.app.R
import com.portaretrato.app.databinding.ItemPersonBinding
import com.portaretrato.app.people.Person

/** Pessoa mais o número de fotos em que aparece. */
data class PersonRow(val person: Person, val photoCount: Int)

class PersonAdapter(
    private val onClick: (Person) -> Unit,
) : RecyclerView.Adapter<PersonAdapter.Holder>() {

    private var rows: List<PersonRow> = emptyList()

    fun submitList(newRows: List<PersonRow>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemPersonBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val row = rows[position]
        holder.binding.personName.text = row.person.name
        holder.binding.personPhotos.text = holder.itemView.resources.getQuantityString(
            R.plurals.appears_in_photos,
            row.photoCount,
            row.photoCount,
        )
        holder.itemView.setOnClickListener { onClick(row.person) }
    }

    class Holder(val binding: ItemPersonBinding) : RecyclerView.ViewHolder(binding.root)
}
