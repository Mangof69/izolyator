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
import android.net.Uri
import android.os.Build
import java.io.File
import java.security.MessageDigest

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var list: LinearLayout
    private lateinit var report: TextView
    private val selected = linkedSetOf<String>()
    private val prefs by lazy { getSharedPreferences("isolation", MODE_PRIVATE) }
    private val apps = mutableListOf<ApplicationInfo>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected.addAll(prefs.getStringSet("selected_packages", emptySet()) ?: emptySet())
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(18, 22, 18, 14); setBackgroundColor(Color.rgb(247,248,250)) }
        root.addView(TextView(this).apply { text = "ИЗОЛЯТОР"; textSize = 27f; gravity = Gravity.CENTER; setTextColor(Color.rgb(25,45,70)) }, match(WRAP))
        root.addView(TextView(this).apply { text = "Контроль приложений и APK"; textSize = 15f; gravity = Gravity.CENTER; setPadding(0,6,0,10) }, match(WRAP))
        status = TextView(this).apply { textSize=16f; gravity=Gravity.CENTER; setPadding(6,10,6,10) }
        root.addView(status, match(WRAP))
        root.addView(Button(this).apply { text="Применить блокировку выбранных приложений"; setOnClickListener { startBlocking() } }, match(WRAP))
        root.addView(Button(this).apply { text="Остановить блокировку"; setOnClickListener { stopService(Intent(this@MainActivity, AppBlockVpnService::class.java).setAction(AppBlockVpnService.ACTION_STOP)); refreshStatus() } }, match(WRAP))
        root.addView(Button(this).apply { text="Выбрать APK для анализа"; setOnClickListener { val i=Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i, APK_REQUEST) } }, match(WRAP))
        report = TextView(this).apply { textSize=13f; setTextColor(Color.DKGRAY); setPadding(4,8,4,8); text="APK-анализатор проверяет локальные метаданные и разрешения; это не облачный антивирус." }
        val reportScroll=ScrollView(this); reportScroll.addView(report); root.addView(reportScroll, LinearLayout.LayoutParams(-1,dp(130)))
        root.addView(TextView(this).apply { text="Установленные приложения — отметь те, которым нужно блокировать сеть."; textSize=13f; setPadding(2,8,2,8) }, match(WRAP))
        val scroll=ScrollView(this); list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }; scroll.addView(list); root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        setContentView(root); loadApps(); refreshStatus()
    }

    override fun onResume() { super.onResume(); refreshStatus() }

    private fun loadApps() {
        val pm=packageManager
        apps.clear()
        apps.addAll(pm.getInstalledApplications(PackageManager.GET_META_DATA).filter { it.packageName!=packageName && (it.flags and ApplicationInfo.FLAG_SYSTEM==0) }.sortedBy { pm.getApplicationLabel(it).toString().lowercase() })
        list.removeAllViews()
        for(app in apps) list.addView(CheckBox(this).apply {
            text="${pm.getApplicationLabel(app)}\n${app.packageName}"; textSize=14f; isChecked=selected.contains(app.packageName); setPadding(4,7,4,7)
            setOnCheckedChangeListener { _,checked -> if(checked) selected.add(app.packageName) else selected.remove(app.packageName); prefs.edit().putStringSet("selected_packages",selected.toSet()).apply() }
        },match(WRAP))
        if(apps.isEmpty()) list.addView(TextView(this).apply{text="Приложения не найдены."})
    }

    private fun startBlocking() {
        if(selected.isEmpty()){ Toast.makeText(this,"Сначала отметь приложение",Toast.LENGTH_LONG).show(); return }
        prefs.edit().putStringSet("selected_packages",selected.toSet()).apply()
        val consent=VpnService.prepare(this)
        if(consent!=null){ @Suppress("DEPRECATION") startActivityForResult(consent,VPN_REQUEST) } else startVpnService()
    }

    @Deprecated("Compatibility callback for VPN and document picker")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK || data?.data==null){refreshStatus();return}
        when(requestCode){
            VPN_REQUEST -> startVpnService()
            APK_REQUEST -> analyzeApk(data.data!!)
        }
    }

    private fun startVpnService() {
        try { startForegroundService(Intent(this,AppBlockVpnService::class.java).setAction(AppBlockVpnService.ACTION_START)); status.text="Запрос запуска отправлен — проверяем состояние службы…" }
        catch(e:Exception){status.text="ЗАЩИТА НЕ ЗАПУЩЕНА: ${e.message}"}
        status.setTextColor(Color.rgb(160,80,0))
    }

    private fun analyzeApk(uri:Uri) {
        report.text="Анализ APK…"
        Thread {
            var temp:File?=null
            try {
                val input=contentResolver.openInputStream(uri) ?: error("Не удалось открыть файл")
                temp=File(cacheDir,"analysis-${System.currentTimeMillis()}.apk")
                input.use { src -> temp.outputStream().use { dst -> src.copyTo(dst) } }
                val bytes=temp.readBytes()
                val sha=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
                val flags=if(Build.VERSION.SDK_INT>=33) PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong() or PackageManager.GET_SIGNING_CERTIFICATES.toLong()) else null
                @Suppress("DEPRECATION")
                val info=if(Build.VERSION.SDK_INT>=33) packageManager.getPackageArchiveInfo(temp.absolutePath,flags!!) else packageManager.getPackageArchiveInfo(temp.absolutePath,PackageManager.GET_PERMISSIONS or PackageManager.GET_SIGNATURES)
                val text=if(info==null) "Файл не распознан как APK или Android не смог прочитать его манифест.\nРазмер: ${bytes.size} байт\nSHA-256: $sha" else {
                    val appInfo=info.applicationInfo
                    appInfo?.sourceDir=temp.absolutePath
                    appInfo?.publicSourceDir=temp.absolutePath
                    val label=try{packageManager.getApplicationLabel(appInfo!!).toString()}catch(_:Exception){info.packageName}
                    val perms=info.requestedPermissions?.toList().orEmpty()
                    val risky=perms.filter { p -> listOf("CAMERA","RECORD_AUDIO","READ_SMS","RECEIVE_SMS","READ_CONTACTS","ACCESS_FINE_LOCATION","ACCESS_BACKGROUND_LOCATION","READ_CALL_LOG","REQUEST_INSTALL_PACKAGES","SYSTEM_ALERT_WINDOW","BIND_ACCESSIBILITY_SERVICE","QUERY_ALL_PACKAGES").any { p.contains(it) } }
                    buildString {
                        append("ОТЧЁТ APK\nИмя: $label\nПакет: ${info.packageName}\nВерсия: ${info.versionName ?: "не указана"}\nРазмер: ${bytes.size} байт\nSHA-256: $sha\n")
                        append("\nЗапрошено разрешений: ${perms.size}\n")
                        if(perms.isEmpty()) append("Явные разрешения не найдены.\n") else perms.forEach { append("• $it\n") }
                        append("\nПОВЫШЕННОГО ВНИМАНИЯ: ${risky.size}\n")
                        risky.forEach { append("• $it\n") }
                        append("\nПодпись: ")
                        val sigs=if(Build.VERSION.SDK_INT>=28) info.signingInfo?.apkContentsSigners else @Suppress("DEPRECATION") info.signatures
                        append(if(sigs.isNullOrEmpty()) "не удалось получить" else sigs.joinToString { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString(""){"%02X".format(it)} })
                        append("\n\nЭто статическая проверка метаданных, не доказательство вредоносности или безопасности. Неизвестные динамические действия APK здесь не выполняются.")
                    }
                }
                runOnUiThread { report.text=text }
            } catch(e:Exception) { runOnUiThread { report.text="Не удалось проверить APK: ${e.javaClass.simpleName}: ${e.message}" } }
            finally { temp?.delete() }
        }.start()
    }

    private fun refreshStatus() {
        val running=AppBlockVpnService.isRunning
        status.text=if(running) "СЕТЕВАЯ БЛОКИРОВКА АКТИВНА" else "ЗАЩИТА НЕ АКТИВНА"
        status.setTextColor(if(running) Color.rgb(0,120,70) else Color.rgb(170,35,35))
    }
    private fun dp(v:Int)= (v*resources.displayMetrics.density).toInt()
    private fun match(height:Int)=LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,height)
    companion object { private const val WRAP=ViewGroup.LayoutParams.WRAP_CONTENT; private const val VPN_REQUEST=701; private const val APK_REQUEST=702 }
}