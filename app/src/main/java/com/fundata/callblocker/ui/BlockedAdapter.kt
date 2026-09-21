package com.fundata.callblocker.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import androidx.recyclerview.widget.RecyclerView
import com.fundata.callblocker.data.DbHelper
import com.fundata.callblocker.databinding.ItemBlockedBinding

class BlockedAdapter(
    private val db: DbHelper,
    private val onChanged: () -> Unit,
    private val onEdit: (DbHelper.Rule) -> Unit
) : RecyclerView.Adapter<BlockedAdapter.Holder>() {

    private var items = emptyList<DbHelper.Rule>()

    fun submit(value: List<DbHelper.Rule>) {
        items = value
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemBlockedBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val b: ItemBlockedBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(item: DbHelper.Rule) {
            b.type.text = when (item.type) {
                DbHelper.TYPE_TEXT -> "Текст"
                DbHelper.TYPE_PHONE -> "Телефон"
                else -> item.type
            }
            b.value.text = item.value
            b.moreButton.setOnClickListener { showActions(b.moreButton, item) }
            b.root.setOnLongClickListener {
                showActions(b.moreButton, item)
                true
            }
        }

        private fun showActions(anchor: View, item: DbHelper.Rule) {
            PopupMenu(anchor.context, anchor).apply {
                menu.add("Изменить")
                menu.add("Удалить")
                setOnMenuItemClickListener { menuItem ->
                    when (menuItem.title.toString()) {
                        "Изменить" -> {
                            onEdit(item)
                            true
                        }
                        "Удалить" -> {
                            db.removeRule(item.id)
                            onChanged()
                            true
                        }
                        else -> false
                    }
                }
                show()
            }
        }
    }
}
