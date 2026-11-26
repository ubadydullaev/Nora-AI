package uz.nora.ai

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

class NoraVoiceService : Service() {

    companion object {
        const val ACTION_START = "uz.nora.ai.START_LISTENING"
        const val ACTION_STOP = "uz.nora.ai.STOP_LISTENING"
        const val ACTION_UPDATE = "uz.nora.ai.VOICE_UPDATE"

        const val EXTRA_STATUS = "status"
        const val EXTRA_HEARD = "heard"
        const val EXTRA_RESULT = "result"

        private const val CHANNEL_ID = "nora_voice_channel"
        private const val NOTIFICATION_ID = 701
    }

    private var asr: OfflineUzbekAsr? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListening()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START, null -> {
                startForegroundSafely()
                startListening()
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundSafely() {
        val openApp = Intent(this, MainActivity::class.java)

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openApp,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Nora faol")
            .setContentText("Ovozli buyruqlaringizni tinglamoqda")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startListening() {
        if (asr != null) return

        val power = getSystemService(POWER_SERVICE) as PowerManager

        wakeLock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Nora::VoiceListening"
        ).apply {
            acquire(12 * 60 * 60 * 1000L)
        }

        asr = OfflineUzbekAsr(
            this,
            { recognized -> handleSpeech(recognized) },
            { message -> sendUpdate(status = message) }
        )

        asr?.start()
    }

    private fun handleSpeech(recognized: String) {
        sendUpdate(heard = recognized)

        val cleaned = recognized
            .trim()
            .replace(",", " ")
            .replace(".", " ")

        val lower = cleaned.lowercase()

        // Tasodifiy suhbatlarni buyruqqa aylantirmaslik uchun
        // buyruq "Nora" murojaati bilan boshlanishi kerak.
        if (!lower.startsWith("nora")) {
            sendUpdate(status = "Buyruq uchun Nora deb murojaat qiling")
            return
        }

        val command = cleaned
            .drop(4)
            .trim()
            .trimStart(',', '.', '!', '?', ' ')

        if (command.isBlank()) {
            sendUpdate(status = "Eshitdim. Buyruqni ayting.")
            return
        }

        try {
            val result = NoraCommandEngine.execute(this, command)
            sendUpdate(
                status = result,
                heard = recognized,
                result = result
            )
        } catch (e: Exception) {
            sendUpdate(status = "Buyruq bajarilmadi: ${e.message}")
        }
    }

    private fun sendUpdate(
        status: String? = null,
        heard: String? = null,
        result: String? = null
    ) {
        val update = Intent(ACTION_UPDATE).setPackage(packageName)

        if (status != null) update.putExtra(EXTRA_STATUS, status)
        if (heard != null) update.putExtra(EXTRA_HEARD, heard)
        if (result != null) update.putExtra(EXTRA_RESULT, result)

        sendBroadcast(update)
    }

    private fun stopListening() {
        asr?.close()
        asr = null

        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (_: Exception) {
        }

        wakeLock = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Nora ovozli boshqaruv",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Nora ovozli buyruqlarni tinglaydi"
            }

            val manager = getSystemService(
                NotificationManager::class.java
            )

            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopListening()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
