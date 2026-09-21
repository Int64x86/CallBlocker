package com.fundata.callblocker

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.telephony.TelephonyManager
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import com.fundata.callblocker.access.CallUiAccessibilityService
import com.fundata.callblocker.data.AppSettings
import com.fundata.callblocker.data.DbHelper
import com.fundata.callblocker.data.DefaultDialerResolver
import com.fundata.callblocker.data.DiagnosticsStore
import com.fundata.callblocker.data.SystemCallLogReader
import com.fundata.callblocker.databinding.ActivityMainBinding
import com.fundata.callblocker.ringtone.RingtonePreparer
import com.fundata.callblocker.ui.BlockedAdapter
import com.fundata.callblocker.ui.CallsAdapter
import com.fundata.callblocker.vibration.CallVibrationController
import com.google.android.material.tabs.TabLayout
import com.google.android.material.button.MaterialButton
import java.text.DateFormat
import java.util.Date
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var db: DbHelper
    private lateinit var callsAdapter: CallsAdapter
    private lateinit var blockedAdapter: BlockedAdapter
    private lateinit var reader: SystemCallLogReader
    private val uiPrefs by lazy { getSharedPreferences("ui_state", MODE_PRIVATE) }
    private var tabsUnlocked = false
    private var ignoreTabCallback = false
    private var ignoreRingtoneSwitch = false
    private var diagnosticsTapCount = 0
    private var lastDiagnosticsTapAt = 0L
    private var diagnosticsUnlocked = false

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshCalls()
            updateStatus()
            requestBatteryOptimizationOnce()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = DbHelper(this)
        reader = SystemCallLogReader(this)

        callsAdapter = CallsAdapter(db) {
            refreshCalls()
            refreshBlocked()
        }
        blockedAdapter = BlockedAdapter(
            db,
            onChanged = {
                refreshBlocked()
                refreshCalls()
            },
            onEdit = { rule -> showEditDialog(rule) }
        )

        binding.callsList.layoutManager = LinearLayoutManager(this)
        binding.callsList.adapter = callsAdapter
        binding.callsSwipeRefresh.setOnRefreshListener {
            refreshCalls()
            binding.callsSwipeRefresh.isRefreshing = false
        }
        binding.callsSwipeRefresh.setOnChildScrollUpCallback { _, _ ->
            binding.callsList.canScrollVertically(-1)
        }
        binding.blockedList.layoutManager = LinearLayoutManager(this)
        binding.blockedList.adapter = blockedAdapter

        setupTabs()
        binding.settingsTitle.setOnClickListener { registerDiagnosticsTap() }
        binding.diagnosticsSwipeRefresh.setOnRefreshListener {
            refreshDiagnostics()
            binding.diagnosticsSwipeRefresh.isRefreshing = false
        }
        binding.diagnosticsSwipeRefresh.setOnChildScrollUpCallback { _, _ ->
            binding.diagnosticsText.canScrollVertically(-1)
        }
        binding.diagnosticsText.keyListener = null
        binding.clearDiagnosticsButton.setOnClickListener {
            DiagnosticsStore.clear(this)
            refreshDiagnostics()
            Toast.makeText(this, "Диагностика очищена", Toast.LENGTH_SHORT).show()
        }
        binding.copyDiagnosticsButton.setOnClickListener {
            val value = binding.diagnosticsText.text?.toString().orEmpty()
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Диагностика", value))
            Toast.makeText(this, "Диагностика скопирована", Toast.LENGTH_SHORT).show()
        }

        val setupComplete = uiPrefs.getBoolean(KEY_SETUP_COMPLETE, false)
        if (!setupComplete) {
            AppSettings.setProtectionEnabled(this, false)
        }
        binding.protectionSwitch.isChecked = AppSettings.isProtectionEnabled(this)
        binding.protectionSwitch.isEnabled = setupComplete
        binding.protectionSwitch.setOnCheckedChangeListener { _, checked ->
            AppSettings.setProtectionEnabled(this, checked)
            CallVibrationController.syncPersistentState(this)
            updateStatus()
        }

        val initialCallUiPackage = AppSettings.callUiPackage(this)
        AppSettings.setCallUiPackage(this, initialCallUiPackage)
        binding.callUiPackageEdit.setText(initialCallUiPackage)
        binding.callUiPackageEdit.doAfterTextChanged { value ->
            AppSettings.setCallUiPackage(this, value?.toString().orEmpty())
        }
        binding.callUiPackageHelpButton.setOnClickListener {
            showHelp(
                "Пакет окна звонка",
                "Пакет приложения, из окна которого Accessibility читает текст входящего звонка. " +
                    "Он определяется по системной звонилке и сохраняется автоматически. " +
                    "Если в диагностике всплывающее окно принадлежит другому пакету, " +
                    "укажите этот пакет вручную."
            )
        }

        binding.vibrationDelaySwitch.isChecked = AppSettings.isVibrationDelayEnabled(this)
        binding.vibrationDelaySwitch.setOnCheckedChangeListener { _, checked ->
            AppSettings.setVibrationDelayEnabled(this, checked)
            CallVibrationController.syncPersistentState(this)
            if (checked) {
                showSoundSettingsPrompt(
                    "Отключите «Вибрация при звонке» вручную в настройках телефона. " +
                        "Приложение будет запускать собственную вибрацию с задержкой " +
                        "только для разрешённых звонков."
                )
            }
        }

        updateRingtoneSwitch()
        binding.ringtoneDelaySwitch.setOnCheckedChangeListener { _, checked ->
            if (ignoreRingtoneSwitch) return@setOnCheckedChangeListener
            if (checked) prepareDelayedRingtone()
        }

        binding.addBlockedButton.setOnClickListener { showAddDialog() }
        binding.accessibilityButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.ringtoneHelpButton.setOnClickListener {
            showHelp(
                "Задерживать рингтон",
                "Создаёт копию текущего рингтона с 1 секундой тишины в начале. " +
                    "Это даёт приложению время проверить и отклонить звонок до начала мелодии. " +
                    "После создания выберите CallBlocker_Delayed.wav вручную в настройках " +
                    "звука для каждой нужной SIM-карты."
            )
        }
        binding.vibrationHelpButton.setOnClickListener {
            showHelp(
                "Задерживать вибрацию",
                "Запускает собственную вибрацию через 1 секунду. " +
                        "Это даёт приложению время проверить и отклонить звонок до начала вибрации"
            )
        }
        binding.checkPermissionsButton.setOnClickListener { checkPermissions() }

        requestInitialPermissions()
        refreshCalls()
        refreshBlocked()
        updateStatus()

        tabsUnlocked = uiPrefs.getBoolean(KEY_SETUP_COMPLETE, false) && isAccessibilityEnabled()
        setTabsEnabled(tabsUnlocked)
        showPage(if (tabsUnlocked) 1 else 0)
    }

    override fun onResume() {
        super.onResume()
        CallVibrationController.syncPersistentState(this)
        val savedCallUiPackage = AppSettings.callUiPackage(this)
        if (binding.callUiPackageEdit.text?.toString() != savedCallUiPackage) {
            binding.callUiPackageEdit.setText(savedCallUiPackage)
        }
        refreshCalls()
        refreshBlocked()
        updateStatus()

        if (!isAccessibilityEnabled()) {
            tabsUnlocked = false
            setTabsEnabled(false)
            if (binding.tabLayout.selectedTabPosition != DIAGNOSTICS_TAB) showPage(0)
        }
        if (diagnosticsUnlocked) refreshDiagnostics()
    }

    private fun setupTabs() {
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Настройки"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Вызовы"))
        binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Блок"))

        binding.tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                if (ignoreTabCallback) return
                when (tab.position) {
                    0 -> {
                        showPage(0)
                        registerDiagnosticsTap()
                    }
                    1 -> if (tabsUnlocked) {
                        showPage(1)
                        refreshCalls()
                    } else lockedMessage()
                    2 -> if (tabsUnlocked) {
                        showPage(2)
                        refreshBlocked()
                    } else lockedMessage()
                    DIAGNOSTICS_TAB -> {
                        showPage(DIAGNOSTICS_TAB)
                        refreshDiagnostics()
                    }
                }
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) {
                if (tab.position == 0) registerDiagnosticsTap()
                if (tab.position == 1) refreshCalls()
                if (tab.position == 2) refreshBlocked()
            }
        })
    }

    private fun lockedMessage() {
        Toast.makeText(
            this,
            "Сначала включите AccessibilityService и нажмите «Проверить разрешения»",
            Toast.LENGTH_SHORT
        ).show()
        selectTab(0)
    }

    private fun setTabsEnabled(enabled: Boolean) {
        for (i in 0 until binding.tabLayout.tabCount) {
            binding.tabLayout.getTabAt(i)?.view?.apply {
                val available = i == 0 || i == DIAGNOSTICS_TAB || enabled
                isEnabled = available
                alpha = if (available) 1f else 0.38f
            }
        }
    }

    private fun showPage(page: Int) {
        binding.settingsPage.visibility = if (page == 0) View.VISIBLE else View.GONE
        binding.callsPage.visibility = if (page == 1) View.VISIBLE else View.GONE
        binding.blockedPage.visibility = if (page == 2) View.VISIBLE else View.GONE
        binding.diagnosticsPage.visibility =
            if (page == DIAGNOSTICS_TAB) View.VISIBLE else View.GONE
        selectTab(page)
    }

    private fun selectTab(index: Int) {
        if (binding.tabLayout.selectedTabPosition == index) return
        ignoreTabCallback = true
        binding.tabLayout.getTabAt(index)?.select()
        ignoreTabCallback = false
    }

    private fun checkPermissions() {
        when {
            missingRequiredPermissions().isNotEmpty() -> {
                Toast.makeText(this, "Разрешите доступ к звонкам и телефону", Toast.LENGTH_LONG).show()
                permissionLauncher.launch(missingRequiredPermissions().toTypedArray())
            }
            !isAccessibilityEnabled() -> {
                Toast.makeText(this, "AccessibilityService не включён", Toast.LENGTH_LONG).show()
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            !isNotificationAccessEnabled() -> {
                Toast.makeText(
                    this,
                    "Разрешите доступ к уведомлениям для обработки всплывающего звонка",
                    Toast.LENGTH_LONG
                ).show()
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            !isBatteryOptimizationDisabled() -> {
                Toast.makeText(
                    this,
                    "Отключите энергосбережение для приложения",
                    Toast.LENGTH_LONG
                ).show()
                requestBatteryOptimizationExemption()
            }
            else -> {
                tabsUnlocked = true
                uiPrefs.edit().putBoolean(KEY_SETUP_COMPLETE, true).apply()
                AppSettings.setProtectionEnabled(this, true)
                binding.protectionSwitch.isEnabled = true
                binding.protectionSwitch.isChecked = true
                setTabsEnabled(true)
                refreshCalls()
                refreshBlocked()
                updateStatus()
                showPage(1)
                Toast.makeText(this, "Готово", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun showAddDialog() {
        val types = arrayOf("Текст", "Телефон")

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 8, 48, 0)
        }

        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
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
                refreshBlocked()
                refreshCalls()
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
                this@MainActivity,
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
                refreshBlocked()
                refreshCalls()
            }
        }

        dialog.show()
    }

    private fun requestInitialPermissions() {
        val missing = missingRequiredPermissions()

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            binding.root.post { requestBatteryOptimizationOnce() }
        }
    }

    private fun missingRequiredPermissions(): List<String> =
        listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.ANSWER_PHONE_CALLS
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

    private fun requestBatteryOptimizationOnce() {
        if (isBatteryOptimizationDisabled() ||
            uiPrefs.getBoolean(KEY_BATTERY_REQUEST_SHOWN, false)
        ) return

        uiPrefs.edit().putBoolean(KEY_BATTERY_REQUEST_SHOWN, true).apply()
        requestBatteryOptimizationExemption()
    }

    private fun requestBatteryOptimizationExemption() {
        if (isBatteryOptimizationDisabled()) {
            return
        }

        try {
            startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
            )
        } catch (t: Throwable) {
            DiagnosticsStore.saveError("Settings: request battery optimization exemption", t)
            openBatteryOptimizationSettings()
        }
    }

    private fun openBatteryOptimizationSettings() {
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        } catch (t: Throwable) {
            DiagnosticsStore.saveError("Settings: open battery optimization settings", t)
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    private fun isBatteryOptimizationDisabled(): Boolean = try {
        val power = getSystemService(POWER_SERVICE) as PowerManager
        power.isIgnoringBatteryOptimizations(packageName)
    } catch (t: Throwable) {
        DiagnosticsStore.saveError("Settings: check battery optimization status", t)
        false
    }

    private fun prepareDelayedRingtone() {
        updateRingtoneSwitch(enabled = false, checked = true)

        val progressLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            val pad = (24 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)

            addView(ProgressBar(this@MainActivity).apply {
                isIndeterminate = true
            }, LinearLayout.LayoutParams(
                (32 * resources.displayMetrics.density).toInt(),
                (32 * resources.displayMetrics.density).toInt()
            ))

            addView(TextView(this@MainActivity).apply {
                text = "Подготовка рингтона..."
                textSize = 16f
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = (16 * resources.displayMetrics.density).toInt()
            })
        }

        val progressDialog = AlertDialog.Builder(this)
            .setView(progressLayout)
            .create()
            .apply {
                setCancelable(false)
                setCanceledOnTouchOutside(false)
                show()
            }

        thread {
            try {
                val result = RingtonePreparer.prepare(this, 1000)
                runOnUiThread {
                    if (!isFinishing && progressDialog.isShowing) {
                        progressDialog.dismiss()
                    }
                    updateRingtoneSwitch(enabled = true, checked = true)
                    showSoundSettingsPrompt(
                        if (result.created) {
                            "Рингтон CallBlocker_Delayed.wav создан. Установите его вручную " +
                                "для каждой нужной SIM-карты."
                        } else {
                            "Рингтон CallBlocker_Delayed.wav уже существует. Установите его " +
                                "вручную для каждой нужной SIM-карты."
                        }
                    )
                }
            } catch (t: Throwable) {
                DiagnosticsStore.saveError("Ringtone: prepare delayed ringtone", t)
                runOnUiThread {
                    if (!isFinishing && progressDialog.isShowing) {
                        progressDialog.dismiss()
                    }
                    updateRingtoneSwitch()
                    Toast.makeText(
                        this,
                        "Ошибка подготовки: ${t.message ?: t.javaClass.simpleName}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    private fun updateRingtoneSwitch(
        enabled: Boolean = true,
        checked: Boolean = try {
            RingtonePreparer.isDelayedRingtoneActive(this)
        } catch (t: Throwable) {
            DiagnosticsStore.saveError("Ringtone: check delayed ringtone state", t)
            false
        }
    ) {
        ignoreRingtoneSwitch = true
        binding.ringtoneDelaySwitch.isChecked = checked
        binding.ringtoneDelaySwitch.isEnabled = enabled
        ignoreRingtoneSwitch = false
    }

    private fun openSoundSettings() {
        try {
            startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
        } catch (t: Throwable) {
            DiagnosticsStore.saveError("Settings: open sound settings", t)
            startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }

    private fun showSoundSettingsPrompt(message: String) {
        val density = resources.displayMetrics.density
        val padding = (24 * density).toInt()
        val primaryColor = ContextCompat.getColor(this, R.color.seed)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        container.addView(TextView(this).apply {
            text = message
            textSize = 16f
        })

        val dialog = AlertDialog.Builder(this).setView(container).create()
        fun button(title: String, onClick: () -> Unit): MaterialButton =
            MaterialButton(this).apply {
                text = title
                setTextColor(android.graphics.Color.WHITE)
                backgroundTintList = ColorStateList.valueOf(primaryColor)
                setOnClickListener { onClick() }
            }

        container.addView(
            button("Открыть настройки") {
                dialog.dismiss()
                openSoundSettings()
            },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (20 * density).toInt() }
        )
        container.addView(
            button("Позже") { dialog.dismiss() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = (8 * density).toInt() }
        )
        dialog.show()
    }

    private fun showHelp(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Понятно", null)
            .show()
    }

    private fun refreshCalls() {
        val calls = reader.read(300)
        callsAdapter.submit(calls)
        binding.emptyView.visibility = if (calls.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun refreshBlocked() {
        val rules = db.allRules()
        blockedAdapter.submit(rules)
        binding.blockedEmptyView.visibility = if (rules.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun registerDiagnosticsTap() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDiagnosticsTapAt > DIAGNOSTICS_TAP_TIMEOUT_MS) {
            diagnosticsTapCount = 0
        }
        lastDiagnosticsTapAt = now
        diagnosticsTapCount++
        if (diagnosticsTapCount < 3) return

        diagnosticsTapCount = 0
        if (!diagnosticsUnlocked) {
            diagnosticsUnlocked = true
            binding.tabLayout.addTab(binding.tabLayout.newTab().setText("Диагностика"))
            setTabsEnabled(tabsUnlocked)
            Toast.makeText(this, "Диагностика открыта", Toast.LENGTH_SHORT).show()
        }
        showPage(DIAGNOSTICS_TAB)
        refreshDiagnostics()
    }

    private fun refreshDiagnostics() {
        val snapshot = DiagnosticsStore.read(this)
        val lastCapture = if (snapshot.time == 0L) {
            "нет данных"
        } else {
            DateFormat.getDateTimeInstance().format(Date(snapshot.time))
        }

        binding.diagnosticsText.setText(buildString {
            appendLine("ПРИЛОЖЕНИЕ")
            appendLine("Версия: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Android: ${Build.VERSION.RELEASE} / API ${Build.VERSION.SDK_INT}")
            appendLine()
            appendLine("СОСТОЯНИЕ")
            appendLine("Блокировка: ${yesNo(AppSettings.isProtectionEnabled(this@MainActivity))}")
            appendLine("Accessibility: ${yesNo(isAccessibilityEnabled())}")
            appendLine("Доступ к уведомлениям: ${yesNo(isNotificationAccessEnabled())}")
            appendLine("Звонилка по умолчанию: ${DefaultDialerResolver.packageName(this@MainActivity) ?: "не определена"}")
            appendLine("Пакет окна в настройках: ${AppSettings.callUiPackage(this@MainActivity).ifBlank { "не задан" }}")
            appendLine("Телефон: ${permissionState(Manifest.permission.READ_PHONE_STATE)}")
            appendLine("Журнал звонков: ${permissionState(Manifest.permission.READ_CALL_LOG)}")
            appendLine("Контакты: ${permissionState(Manifest.permission.READ_CONTACTS)}")
            appendLine("Управление звонком: ${permissionState(Manifest.permission.ANSWER_PHONE_CALLS)}")
            appendLine("Задержка рингтона: ${yesNo(RingtonePreparer.isDelayedRingtoneActive(this@MainActivity))}")
            appendLine("Задержка вибрации: ${yesNo(AppSettings.isVibrationDelayEnabled(this@MainActivity))}")
            appendLine("Энергосбережение отключено: ${yesNo(isBatteryOptimizationDisabled())}")
            appendLine("Текущий рингтон: ${RingtoneManager.getActualDefaultRingtoneUri(this@MainActivity, RingtoneManager.TYPE_RINGTONE) ?: "нет"}")
            appendLine("Состояние звонка: ${currentCallState()}")
            appendLine("Правил блокировки: ${db.allRules().size}")
            appendLine()
            appendLine("ПОСЛЕДНИЙ ЗВОНОК")
            appendLine("Время: $lastCapture")
            appendLine("Решение: ${snapshot.decision}")
            appendLine("Входящий номер: ${snapshot.incomingNumber.ifBlank { "не получен" }}")
            appendLine("Пакет события: ${snapshot.packageName.ifBlank { "нет данных" }}")
            appendLine("ID выбранного окна звонка: ${snapshot.callWindowId.takeIf { it >= 0 } ?: "не выбрано"}")
            appendLine("Класс: ${snapshot.className.ifBlank { "нет данных" }}")
            appendLine("Тип события: ${AccessibilityEvent.eventTypeToString(snapshot.eventType)} (${snapshot.eventType})")
            appendLine("Пакеты окон:")
            appendLine(snapshot.windowPackages.ifBlank { "нет данных" })
            appendLine()
            appendLine("УВЕДОМЛЕНИЕ ЗВОНКА")
            appendLine("Владелец: ${snapshot.notificationPackage.ifBlank { "нет данных" }}")
            appendLine("Опубликовал: ${snapshot.notificationOpPackage.ifBlank { "нет данных" }}")
            appendLine("Категория: ${snapshot.notificationCategory.ifBlank { "нет данных" }}")
            appendLine("Текст:")
            appendLine(snapshot.notificationText.ifBlank { "нет данных" })
            appendLine()
            appendLine("ТЕКУЩИЕ ПРАВИЛА")
            val rules = db.allRules()
            if (rules.isEmpty()) {
                appendLine("нет правил")
            } else {
                rules.forEach { rule ->
                    appendLine("#${rule.id} ${rule.type}: ${rule.value}")
                }
            }
            appendLine()
            appendLine("ТЕКСТ ACCESSIBILITY")
            appendLine(snapshot.text.ifBlank { "нет данных" })
            appendLine()
            appendLine("ERRORS (LAST 30)")
            append(snapshot.errors.ifBlank { "No errors recorded" })
        })
    }

    private fun permissionState(permission: String): String = yesNo(
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    )

    private fun yesNo(value: Boolean): String = if (value) "да" else "нет"

    @Suppress("DEPRECATION")
    private fun currentCallState(): String {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) !=
            PackageManager.PERMISSION_GRANTED
        ) return "нет разрешения"

        return try {
            when ((getSystemService(TELEPHONY_SERVICE) as TelephonyManager).callState) {
                TelephonyManager.CALL_STATE_IDLE -> "IDLE"
                TelephonyManager.CALL_STATE_RINGING -> "RINGING"
                TelephonyManager.CALL_STATE_OFFHOOK -> "OFFHOOK"
                else -> "неизвестно"
            }
        } catch (t: Throwable) {
            DiagnosticsStore.saveError("Diagnostics: read call state", t)
            "ошибка: ${t.javaClass.simpleName}"
        }
    }

    private fun isAccessibilityEnabled(): Boolean {
        val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
        val expectedClass = CallUiAccessibilityService::class.java.name

        return manager.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK
        ).any { info ->
            val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
            serviceInfo.packageName == packageName && serviceInfo.name == expectedClass
        }
    }

    private fun isNotificationAccessEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun updateStatus() {
        val enabled = AppSettings.isProtectionEnabled(this)
        if (binding.protectionSwitch.isChecked != enabled) {
            binding.protectionSwitch.isChecked = enabled
        }
        val vibrationDelayEnabled = AppSettings.isVibrationDelayEnabled(this)
        if (binding.vibrationDelaySwitch.isChecked != vibrationDelayEnabled) {
            binding.vibrationDelaySwitch.isChecked = vibrationDelayEnabled
        }
        updateRingtoneSwitch()
    }

    companion object {
        private const val KEY_SETUP_COMPLETE = "setup_complete"
        private const val KEY_BATTERY_REQUEST_SHOWN = "battery_request_shown"
        private const val DIAGNOSTICS_TAB = 3
        private const val DIAGNOSTICS_TAP_TIMEOUT_MS = 1_500L
    }
}
