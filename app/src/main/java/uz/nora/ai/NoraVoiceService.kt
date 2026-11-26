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
        private const val UNKNOWN = "Bu buyruq hali qo'shilmagan"
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
                val command = intent.getStringExtra(EXTRA_COMMAND).orEmpty()
                if (command.isNotBlank()) {
                    sendUpdate(heard = command)
                    processRequest(command)
                } else if (asr == null) {
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
            this, 0,
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
            { handleSpeech(it) },
            { sendUpdate(status = it) }
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

        if (command.isNotBlank()) processRequest(command)
    }

    private fun processRequest(request: String) {
        serviceScope.launch {
            try {
                sendUpdate(status = "NORA TAHLIL QILMOQDA")

                val normalized = request.trim().lowercase()

                if (normalized in listOf(
                        "offline rejim",
                        "offline rejimni yoq",
                        "internetni o'chir",
                        "internetsiz ishlash"
                    )
                ) {
                    NoraSecureStore.save(this@NoraVoiceService, "offline_only", "true")
                    sendUpdate(
                        status = "OFFLINE REJIM",
                        result = "Internet AI o'chirildi. Faqat telefon buyruqlari ishlaydi."
                    )
                    return@launch
                }

                if (normalized in listOf(
                        "online rejim",
                        "online rejimni yoq",
                        "internetni yoq"
                    )
                ) {
                    NoraSecureStore.delete(this@NoraVoiceService, "offline_only")
                    sendUpdate(
                        status = "ONLINE REJIM",
                        result = "Internet AI rejimi yoqildi."
                    )
                    return@launch
                }

                val githubResult = handleGitHubCommand(request)
                if (githubResult != null) {
                    sendUpdate(status = "GITHUB", result = githubResult)
                    return@launch
                }

                val localResult = try {
                    NoraCommandEngine.execute(this@NoraVoiceService, request)
                } catch (e: Exception) {
                    "LOCAL_ERROR:${e.message}"
                }

                if (localResult != UNKNOWN) {
                    if (localResult.startsWith("LOCAL_ERROR:")) {
                        sendUpdate(
                            status = "BUYRUQ XATOSI",
                            result = localResult.removePrefix("LOCAL_ERROR:")
                        )
                    } else {
                        sendUpdate(status = "BAJARILDI", result = localResult)
                    }
                    return@launch
                }

                if (NoraSecureStore.read(
                        this@NoraVoiceService,
                        "offline_only"
                    ) == "true"
                ) {
                    sendUpdate(
                        status = "OFFLINE REJIM",
                        result = "Bu buyruq offline imkoniyatlarimga hali qo'shilmagan."
                    )
                    return@launch
                }

                val key = NoraSecureStore.read(this@NoraVoiceService, "ai_key")
                val endpoint = NoraSecureStore.read(this@NoraVoiceService, "ai_endpoint")
                val model = NoraSecureStore.read(this@NoraVoiceService, "ai_model")

                if (key.isNullOrBlank() ||
                    endpoint.isNullOrBlank() ||
                    model.isNullOrBlank()
                ) {
                    sendUpdate(
                        status = "AI SOZLANMAGAN",
                        result = "Online AI uchun API manzili, model va kalitni sozlang."
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

    private suspend fun handleGitHubCommand(command: String): String? {
        val parts = command.trim().split(Regex("\\s+"), limit = 4)
        if (parts.size < 3 || !parts[0].equals("github", true)) return null

        val action = parts[1].lowercase()
        val repository = parts[2].split("/")
        if (repository.size != 2 ||
            repository.any { it.isBlank() }
        ) {
            return "Repo format: github fayllar owner/repo"
        }

        val owner = repository[0]
        val repo = repository[1]
        val github = NoraGitHubTools(this)

        return when (action) {
            "fayllar", "files" ->
                github.listFiles(owner, repo)

            "actions", "holat" ->
                github.latestActions(owner, repo)

            "oqi", "read" -> {
                if (parts.size < 4) {
                    "Fayl yo'li kerak: github oqi owner/repo app/src/main/..."
                } else {
                    val file = NoraOnlineAgent(this)
                        .readGitHubFile(owner, repo, parts[3])
                    "FAYL: ${file.path}\n\n${file.content.take(12000)}"
                }
            }

            else -> "GitHub buyruqlari: github fayllar owner/repo; github actions owner/repo; github oqi owner/repo fayl/yo'li"
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
