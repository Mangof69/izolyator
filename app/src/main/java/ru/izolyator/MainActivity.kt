package ru.izolyator

import android.app.Activity
import android.os.Bundle
import android.content.pm.PackageManager
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private var protectionOn = false
    private lateinit var status: TextView
    private lateinit var toggle: Button
    private lateinit var details: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(Color.rgb(247, 248, 250))
        }
        root.addView(TextView(this).apply {
            text = "ИЗОЛЯТОР"
            textSize = 28f
            setTextColor(Color.rgb(25, 45, 70))
            gravity = Gravity.CENTER
        }, match(WRAP))
        root.addView(TextView(this).apply {
            text = "Контроль приложений"
            textSize = 16f
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 24)
        }, match(WRAP))
        status = TextView(this).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(12, 18, 12, 18)
        }
        root.addView(status, match(WRAP))
        toggle = Button(this).apply {
            textSize = 16f
            setOnClickListener {
                protectionOn = !protectionOn
                renderStatus()
            }
        }
        root.addView(toggle, match(WRAP))
        root.addView(Button(this).apply {
            text = "Выбрать приложение для проверки"
            setOnClickListener { showInstalledApps() }
        }, match(WRAP))
        details = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.DKGRAY)
            setPadding(4, 20, 4, 8)
            text = "Переключатель пока не блокирует приложения системно."
        }
        val scroll = ScrollView(this)
        scroll.addView(details)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        renderStatus()
    }

    private fun renderStatus() {
        status.text = if (protectionOn) "Режим включён (демо)" else "Режим выключен"
        status.setTextColor(if (protectionOn) Color.rgb(0, 120, 70) else Color.rgb(90, 90, 90))
        toggle.text = if (protectionOn) "Выключить режим" else "Включить режим"
        details.text = if (protectionOn)
            "Демо-режим активен. Реальная сетевая блокировка требует VPN-сервиса и системного разрешения пользователя. Android не позволяет обычному приложению полностью изолировать другие приложения."
        else
            "Пока режим выключен. Можно просмотреть установленные приложения. Это начальная версия, не полноценная песочница."
    }

    private fun showInstalledApps() {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != packageName }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
        val lines = apps.take(250).map {
            "${pm.getApplicationLabel(it)}\n${it.packageName}"
        }
        details.text = if (lines.isEmpty()) "Список приложений недоступен." else
            "Установленные приложения (до 250):\n\n" + lines.joinToString("\n\n")
    }

    private fun match(height: Int) = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height)
    companion object { private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT }
}