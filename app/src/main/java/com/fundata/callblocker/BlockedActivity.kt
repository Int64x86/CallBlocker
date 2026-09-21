package com.fundata.callblocker

import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.ArrayAdapter
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.fundata.callblocker.data.DbHelper
import com.fundata.callblocker.databinding.ActivityBlockedBinding
import com.fundata.callblocker.ui.BlockedAdapter

class BlockedActivity : AppCompatActivity() {

    private lateinit var binding: ActivityBlockedBinding
    private lateinit var db: DbHelper
    private lateinit var adapter: BlockedAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlockedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = DbHelper(this)
        adapter = BlockedAdapter(
            db,
            onChanged = { refresh() },
            onEdit = { rule -> showEditDialog(rule) }
        )

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        binding.addButton.setOnClickListener { showAddDialog() }

        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun showAddDialog() {
        val types = arrayOf("Текст", "Телефон")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
        }

        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@BlockedActivity,
                android.R.layout.simple_spinner_dropdown_item,
                types
            )
        }

        val wildcardInfo = TextView(this).apply {
            text = "* — любой текст до или после. Например: *Кол-центр, Банк*, *реклама*"
            alpha = 0.68f
            textSize = 13f
            setPadding(0, 12, 0, 8)
        }

        val input = EditText(this).apply {
            hint = "Значение"
            inputType = InputType.TYPE_CLASS_TEXT
        }

        container.addView(spinner)
        container.addView(wildcardInfo)
        container.addView(input)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Добавить блокировку")
            .setView(container)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Добавить", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                if (value.isEmpty()) {
                    input.error = "Введите значение"
                    return@setOnClickListener
                }

                val type = when (spinner.selectedItemPosition) {
                    0 -> DbHelper.TYPE_TEXT
                    else -> DbHelper.TYPE_PHONE
                }

                db.addRule(type, value)
                dialog.dismiss()
                refresh()
            }
        }

        dialog.show()
    }

    private fun showEditDialog(rule: DbHelper.Rule) {
        val types = arrayOf("Текст", "Телефон")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
        }

        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@BlockedActivity,
                android.R.layout.simple_spinner_dropdown_item,
                types
            )
            setSelection(when (rule.type) {
                DbHelper.TYPE_TEXT -> 0
                else -> 1
            })
        }

        val wildcardInfo = TextView(this).apply {
            text = "* — любой текст до или после. Например: *Кол-центр, Банк*, *реклама*"
            alpha = 0.68f
            textSize = 13f
            setPadding(0, 12, 0, 8)
        }

        val input = EditText(this).apply {
            hint = "Значение"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(rule.value)
            setSelection(text.length)
        }

        container.addView(spinner)
        container.addView(wildcardInfo)
        container.addView(input)

        val dialog = AlertDialog.Builder(this)
            .setTitle("Изменить блокировку")
            .setView(container)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = input.text.toString().trim()
                if (value.isEmpty()) {
                    input.error = "Введите значение"
                    return@setOnClickListener
                }

                val type = when (spinner.selectedItemPosition) {
                    0 -> DbHelper.TYPE_TEXT
                    else -> DbHelper.TYPE_PHONE
                }

                db.removeRule(rule.id)
                db.addRule(type, value)
                dialog.dismiss()
                refresh()
            }
        }

        dialog.show()
    }

    private fun refresh() {
        val rules = db.allRules()
        adapter.submit(rules)
        binding.emptyView.visibility =
            if (rules.isEmpty()) android.view.View.VISIBLE else android.view.View.GONE
    }
}
