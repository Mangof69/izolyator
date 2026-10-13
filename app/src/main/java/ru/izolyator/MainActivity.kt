package ru.izolyator

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private val selected = linkedSetOf<String>()
    private val prefs by lazy { getSharedPreferences("isolation", MODE_PRIVATE) }
    private val apps = mutableListOf<ApplicationInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected.addAll(prefs.getStringSet("selected_packages", emptySet()) ?: emptySet())
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 24, 20, 16)
            setBackgroundColor(Color.rgb(247, 248, 250))
        }
        root.addView(TextView(this).apply {
            text = "ИЗОЛЯТОР"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(25, 45, 70))
        }, match(WRAP))
        root.addView(TextView(this).apply {
            text = "Блокировка сети выбранных приложений"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 12)
        }, match(WRAP))
        status = TextView(this).apply {
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(8, 12, 8, 12)
        }
        root.addView(status, match(WRAP))
        root.addView(Button(this).apply {
            text = "Сохранить выбор и включить блокировку"
            setOnClickListener { startBlocking() }
        }, match(WRAP))
        root.addView(Button(this).apply {
            text = "Остановить блокировку"
            setOnClickListener { stopService(Intent(this@MainActivity, AppBlockVpnService::class.java)); refreshStatus() }
        }, match(WRAP))
        root.addView(TextView(this).apply {
            text = "Отметь приложения ниже. Их сетевые пакеты будут отбрасываться, пока работает VPN. Это блокирует сеть, но не отключает само приложение."
            textSize = 13f
            setPadding(2, 10, 2, 10)
        }, match(WRAP))
        val scroll = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(list)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        loadApps()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun loadApps() {
        val pm = packageManager
        apps.clear()
        apps.addAll(pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != packageName && (it.flags and ApplicationInfo.FLAG_SYSTEM == 0) }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() })
        list.removeAllViews()
        for (app in apps) {
            val cb = CheckBox(this).apply {
                text = "${pm.getApplicationLabel(app)}\n${app.packageName}"
                textSize = 14f
                isChecked = selected.contains(app.packageName)
                setPadding(4, 8, 4, 8)
                setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(app.packageName) else selected.remove(app.packageName)
                    prefs.edit().putStringSet("selected_packages", selected.toSet()).apply()
                }
            }
            list.addView(cb, match(WRAP))
        }
        if (apps.isEmpty()) list.addView(TextView(this).apply { text = "Приложения не найдены." })
    }

    private fun startBlocking() {
        if (selected.isEmpty()) {
            Toast.makeText(this, "Сначала выбери хотя бы одно приложение", Toast.LENGTH_LONG).show()
            return
        }
        prefs.edit().putStringSet("selected_packages", selected.toSet()).apply()
        val consent = VpnService.prepare(this)
        if (consent != null) {
            @Suppress("DEPRECATION")
            startActivityForResult(consent, VPN_REQUEST)
        } else startVpnService()
    }

    @Deprecated("Deprecated by Android API; retained for VPN consent compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == VPN_REQUEST && resultCode == RESULT_OK) startVpnService()
        else refreshStatus()
    }

    private fun startVpnService() {
        val intent = Intent(this, AppBlockVpnService::class.java).setAction(AppBlockVpnService.ACTION_START)
        try {
            startForegroundService(intent)
            status.text = "Запрос запуска отправлен; проверяем состояние службы…"
        } catch (e: Exception) {
            status.text = "Защита не запущена: ${e.javaClass.simpleName}: ${e.message}"
        }
        status.setTextColor(Color.rgb(160, 80, 0))
    }

    private fun refreshStatus() {
        val running = AppBlockVpnService.isRunning
        status.text = if (running) "БЛОКИРОВКА СЕТИ РАБОТАЕТ" else "ЗАЩИТА НЕ АКТИВНА"
        status.setTextColor(if (running) Color.rgb(0, 120, 70) else Color.rgb(170, 35, 35))
    }

    private fun match(height: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    companion object { private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT; private const val VPN_REQUEST = 701 }
}