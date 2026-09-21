package com.fundata.callblocker.ui

import android.graphics.Paint
import android.provider.CallLog
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.RecyclerView
import com.fundata.callblocker.data.CallRecord
import com.fundata.callblocker.data.DbHelper
import com.fundata.callblocker.databinding.ItemCallBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CallsAdapter(
    private val db: DbHelper,
    private val onChanged: () -> Unit
) : RecyclerView.Adapter<CallsAdapter.Holder>() {

    private var items = emptyList<CallRecord>()

    fun submit(value: List<CallRecord>) {
        items = value
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemCallBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun getItemCount() = items.size
    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(items[position])

    inner class Holder(private val b: ItemCallBinding) : RecyclerView.ViewHolder(b.root) {

        private fun setStrikeThrough(view: android.widget.TextView, enabled: Boolean) {
            view.paintFlags = if (enabled) {
                view.paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            } else {
                view.paintFlags and Paint.STRIKE_THRU_TEXT_FLAG.inv()
            }
        }

        fun bind(item: CallRecord) {
            val title = item.cachedName ?: item.preferredDisplayName ?: item.number
            b.title.text = title

            val parts = ArrayList<String>()
            if (!item.cachedName.isNullOrBlank() && !item.preferredDisplayName.isNullOrBlank()) {
                parts += item.preferredDisplayName
            }
            if (title != item.number) parts += item.number
            b.subtitle.text = parts.joinToString(" • ").ifBlank { item.number }

            val isBlocked = db.isAnyBlocked(
                item.cachedName,
                item.preferredDisplayName,
                item.number
            )

            setStrikeThrough(b.title, isBlocked)
            setStrikeThrough(b.subtitle, isBlocked)

            val typeText = when (item.type) {
                CallLog.Calls.INCOMING_TYPE -> "Входящий"
                CallLog.Calls.OUTGOING_TYPE -> "Исходящий"
                CallLog.Calls.MISSED_TYPE -> "Пропущенный"
                CallLog.Calls.REJECTED_TYPE -> "Отклонённый"
                CallLog.Calls.BLOCKED_TYPE -> "Заблокированный"
                else -> "Звонок"
            }

            b.time.text = "$typeText • " +
                SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
                    .format(Date(item.timestamp))

            b.blockButton.text = if (isBlocked) "Заблокирован" else "Блокировать как..."
            b.blockButton.isEnabled = !isBlocked

            b.blockButton.setOnClickListener {
                if (isBlocked) return@setOnClickListener

                val options = ArrayList<Pair<String, Pair<String, String>>>()

                listOfNotNull(item.cachedName, item.preferredDisplayName)
                    .filter { it.isNotBlank() }
                    .distinct()
                    .forEach {
                        options += "Текст: $it" to (DbHelper.TYPE_TEXT to it)
                }
                item.number.takeIf { it.isNotBlank() }?.let {
                    options += "Телефон: $it" to (DbHelper.TYPE_PHONE to it)
                }

                AlertDialog.Builder(b.root.context)
                    .setTitle("Блокировать как")
                    .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                        val (type, value) = options[which].second
                        db.addRule(type, value)
                        onChanged()
                    }
                    .setNegativeButton("Отмена", null)
                    .show()
            }
        }
    }
}
