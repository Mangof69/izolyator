package ru.izolyator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean

class AppBlockVpnService : VpnService() {
    private var tunnel: ParcelFileDescriptor? = null
    private var worker: Thread? = null
    private val alive = AtomicBoolean(false)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopBlocking()
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification())
        startBlocking()
        return START_STICKY
    }

    private fun startBlocking() {
        stopTunnelOnly()
        val packages = getSharedPreferences("isolation", MODE_PRIVATE)
            .getStringSet("selected_packages", emptySet())?.toSet().orEmpty()
        if (packages.isEmpty()) {
            isRunning = false
            stopSelf()
            return
        }
        try {
            val builder = Builder()
                .setSession("Изолятор — блокировка сети")
                .setMtu(1500)
                .addAddress("10.111.0.1", 32)
                .addRoute("0.0.0.0", 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.addRoute("::", 0)
            packages.forEach { builder.addAllowedApplication(it) }
            tunnel = builder.establish()
            val fd = tunnel ?: throw IllegalStateException("Android не создал VPN-интерфейс")
            alive.set(true)
            isRunning = true
            worker = Thread {
                try {
                    // Intentionally drop every packet from selected apps: this is a network blackhole,
                    // not a forwarding VPN. Do not write packets back to the TUN interface.
                    val input = FileInputStream(fd.fileDescriptor)
                    val buffer = ByteArray(32767)
                    while (alive.get()) {
                        val count = input.read(buffer)
                        if (count < 0) break
                    }
                } catch (_: Exception) {
                } finally {
                    isRunning = false
                }
            }.apply { name = "Izolyator-TUN-drop"; isDaemon = true; start() }
        } catch (e: Exception) {
            isRunning = false
            stopTunnelOnly()
            stopSelf()
        }
    }

    private fun stopBlocking() {
        isRunning = false
        alive.set(false)
        stopTunnelOnly()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    private fun stopTunnelOnly() {
        alive.set(false)
        try { tunnel?.close() } catch (_: Exception) {}
        tunnel = null
        worker = null
    }

    private fun notification(): Notification {
        val channelId = "izolyator_vpn"
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(channelId, "Изолятор", NotificationManager.IMPORTANCE_LOW))
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, channelId) else Notification.Builder(this)
        return builder.setContentTitle("Изолятор")
            .setContentText("Сетевая блокировка выбранных приложений активна")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .build()
    }

    override fun onRevoke() {
        stopBlocking()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopBlocking()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "ru.izolyator.START"
        const val ACTION_STOP = "ru.izolyator.STOP"
        const val NOTIFICATION_ID = 51
        @Volatile var isRunning: Boolean = false
    }
}