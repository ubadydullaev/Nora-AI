package uz.nora.ai

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.*

class NoraVoiceService : Service() {

    companion object {
        const val ACTION_START = "uz.nora.ai.START_LISTENING"
        const val ACTION_STOP = "uz.nora.ai.STOP_LISTENING"
        const val ACTION_UPDATE = "uz.nora.ai.VOICE_UPDATE"
        const val ACTION_TEXT_COMMAND = "uz.nora.ai.TEXT_COMMAND"

        const val EXTRA_STATUS = "status"
        const val EXTRA_HEARD = "heard"
        const val EXTRA_RESULT = "result"
        const val EXTRA_COMMAND = "command"

        private const val CHANNEL_ID = "nora_voice_channel"
        private const val NOTIFICATION_ID = 701
    }

    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_TEXT_COMMAND -> {
                startForegroundSafely()
                val command = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
                if (command.isNotBlank()) {
                    sendUpdate(heard = command)
                    processRequest(command)
                } else {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }

            ACTION_START, null -> {
                startForegroundSafely()
                startListening()
            }
        }
        return START_NOT_STICKY
    }

    private fun startForegroundSafely() {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Nora faol")
            .setContentText("Ovozli buyruqlarni tinglamoqda")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pending)
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
        val cleaned = recognized.trim()
        if (cleaned.isBlank()) return

        sendUpdate(heard = cleaned)

        val command = if (cleaned.lowercase().startsWith("nora")) {
            cleaned.drop(4).trim().trimStart(',', '.', '!', '?', ' ')
        } else {
            cleaned
        }

        if (command.isBlank()) {
            sendUpdate(status = "Buyruqni ayting.")
            return
        }

        processRequest(command)
    }

    private fun processRequest(request: String) {
        serviceScope.launch {
            try {
                sendUpdate(status = "NORA TAHLIL QILMOQDA")

                val localResult = try {
                    NoraCommandEngine.execute(this@NoraVoiceService, request)
                } catch (e: Exception) {
                    "LOCAL_ERROR:${e.message}"
                }

                if (localResult != "Bu buyruq hali qo'shilmagan") {
                    if (localResult.startsWith("LOCAL_ERROR:")) {
                        sendUpdate(
                            status = "Telefon buyrug'i bajarilmadi",
                            result = localResult.removePrefix("LOCAL_ERROR:")
                        )
                    } else {
                        sendUpdate(status = localResult, result = localResult)
                    }
                    return@launch
                }

                val key = NoraSecureStore.read(
                    this@NoraVoiceService,
                    "ai_key"
                )
                val endpoint = NoraSecureStore.read(
                    this@NoraVoiceService,
                    "ai_endpoint"
                )
                val model = NoraSecureStore.read(
                    this@NoraVoiceService,
                    "ai_model"
                )

                if (key.isNullOrBlank() ||
                    endpoint.isNullOrBlank() ||
                    model.isNullOrBlank()
                ) {
                    sendUpdate(
                        status = "Offline rejim",
                        result = "Bu buyruq offline rejimda hali mavjud emas. " +
                            "Online AI sozlamalarini kiriting."
                    )
                    return@launch
                }

                sendUpdate(status = "NORA JAVOB TAYYORLAMOQDA")

                val answer = NoraOnlineAgent(this@NoraVoiceService)
                    .askAI(request, model, endpoint)

                sendUpdate(status = "NORA TAYYOR", result = answer)

            } catch (e: Exception) {
                sendUpdate(
                    status = "NORA XATOSI",
                    result = e.message ?: "Noma'lum xatolik"
                )
            } finally {
                if (asr == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun sendUpdate(
        status: String? = null,
        heard: String? = null,
        result: String? = null
    ) {
        val update = Intent(ACTION_UPDATE).setPackage(packageName)
        status?.let { update.putExtra(EXTRA_STATUS, it) }
        heard?.let { update.putExtra(EXTRA_HEARD, it) }
        result?.let { update.putExtra(EXTRA_RESULT, it) }
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
            )
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        stopListening()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
