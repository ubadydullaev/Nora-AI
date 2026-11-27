
package uz.nora.ai

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    private val requestMutex = Mutex()

    @Volatile
    private var asr: OfflineUzbekAsr? = null

    @Volatile
    private var stopping = false

    private var speech: NoraSpeech? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var offlineAI: OfflineNoraAI? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        speech = NoraSpeech(this)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopping = true
                stopListening()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }

            ACTION_TEXT_COMMAND -> {
                stopping = false

                val command = intent
                    .getStringExtra(EXTRA_COMMAND)
                    .orEmpty()
                    .trim()

                if (command.isNotBlank()) {
                    sendUpdate(heard = command)
                    processRequest(command)
                }
            }

            ACTION_START, null -> {
                stopping = false

                try {
                    startForegroundSafely()
                    startListening()
                } catch (e: Exception) {
                    sendUpdate(
                        status = "MIKROFON XATOSI",
                        result = e.message ?: "Ovozli xizmat ishga tushmadi"
                    )
                    stopListening()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundSafely() {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
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
        if (asr != null) {
            sendUpdate(status = "NORA TINGLAMOQDA")
            return
        }

        val power = getSystemService(POWER_SERVICE) as PowerManager

        wakeLock = power.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "Nora::VoiceListening"
        ).apply {
            acquire(12 * 60 * 60 * 1000L)
        }

        val listener = OfflineUzbekAsr(
            this,
            { recognized ->
                if (!stopping) handleSpeech(recognized)
            },
            { message ->
                if (!stopping) sendUpdate(status = message)
            }
        )

        asr = listener

        if (!listener.ready()) {
            val answer =
                "Offline ovoz modeli topilmadi. Avval GigaAM modelini yuklang."

            sendUpdate(status = "MODEL TOPILMADI", result = answer)
            speakResult(answer)
            stopListening()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        listener.start()
    }

    private fun handleSpeech(recognized: String) {
        val cleaned = recognized.trim()
        if (cleaned.isBlank()) return

        sendUpdate(heard = cleaned)

        val command = cleaned.replace(
            Regex("(?i)^nora[\\s,.:!?-]*"),
            ""
        ).trim()

        if (command.isNotBlank()) {
            processRequest(command)
        }
    }

    private fun getOfflineAI(): OfflineNoraAI {
        return offlineAI ?: OfflineNoraAI(this).also {
            offlineAI = it
        }
    }

    private suspend fun answerOffline(request: String): String {
        val engine = getOfflineAI()

        if (!engine.isReady()) {
            error(
                "Qwen3 offline modeli yuklanmagan. " +
                    "Sozlamalardan modelni yuklang."
            )
        }

        sendUpdate(status = "OFFLINE AI TAHLIL QILMOQDA")
        return engine.ask(request)
    }

    private fun processRequest(request: String) {
        serviceScope.launch {
            requestMutex.withLock {
                if (stopping) return@withLock

                try {
                    sendUpdate(status = "NORA TAHLIL QILMOQDA")

                    val normalized = request.trim()
                        .lowercase()
                        .replace('’', '\'')
                        .replace('ʻ', '\'')

                    if (normalized in listOf(
                            "offline rejim",
                            "offline rejimni yoq",
                            "internetni o'chir",
                            "internetsiz ishlash"
                        )
                    ) {
                        NoraSecureStore.save(
                            this@NoraVoiceService,
                            "offline_only",
                            "true"
                        )

                        respond(
                            "OFFLINE REJIM",
                            "Offline rejim yoqildi. Telefon buyruqlari va yuklangan offline AI ishlaydi."
                        )
                        return@withLock
                    }

                    if (normalized in listOf(
                            "online rejim",
                            "online rejimni yoq",
                            "internetni yoq"
                        )
                    ) {
                        NoraSecureStore.delete(
                            this@NoraVoiceService,
                            "offline_only"
                        )

                        respond(
                            "ONLINE REJIM",
                            "Online rejim yoqildi."
                        )
                        return@withLock
                    }

                    val offlineOnly =
                        NoraSecureStore.read(
                            this@NoraVoiceService,
                            "offline_only"
                        ) == "true"

                    if (
                        offlineOnly &&
                        NoraCommandEngine.isInternetSearch(request)
                    ) {
                        respond(
                            "OFFLINE REJIM",
                            "Internet qidiruvi uchun online rejimni yoqing."
                        )
                        return@withLock
                    }

                    val localResult = try {
                        NoraCommandEngine.execute(
                            this@NoraVoiceService,
                            request
                        )
                    } catch (e: Exception) {
                        "LOCAL_ERROR:${e.message}"
                    }

                    if (localResult != UNKNOWN) {
                        if (localResult.startsWith("LOCAL_ERROR:")) {
                            respond(
                                "BUYRUQ XATOSI",
                                localResult.removePrefix("LOCAL_ERROR:")
                            )
                        } else {
                            respond("BAJARILDI", localResult)
                        }
                        return@withLock
                    }

                    if (offlineOnly) {
                        try {
                            respond(
                                "OFFLINE NORA",
                                answerOffline(request)
                            )
                        } catch (e: Exception) {
                            respond(
                                "OFFLINE AI",
                                e.message ?: "Offline javob tayyorlab bo'lmadi."
                            )
                        }
                        return@withLock
                    }

                    val githubResult = handleGitHubCommand(request)

                    if (githubResult != null) {
                        respond("GITHUB", githubResult)
                        return@withLock
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

                    if (
                        key.isNullOrBlank() ||
                        endpoint.isNullOrBlank() ||
                        model.isNullOrBlank()
                    ) {
                        val engine = getOfflineAI()

                        if (engine.isReady()) {
                            respond(
                                "OFFLINE ZAXIRA",
                                answerOffline(request)
                            )
                        } else {
                            respond(
                                "AI SOZLANMAGAN",
                                "Online AI sozlanmagan va Qwen3 offline modeli yuklanmagan."
                            )
                        }
                        return@withLock
                    }

                    sendUpdate(
                        status = "NORA ONLINE JAVOB TAYYORLAMOQDA"
                    )

                    try {
                        val answer = NoraOnlineAgent(this@NoraVoiceService)
                            .askAI(request, model, endpoint)

                        respond("NORA ONLINE", answer)

                    } catch (onlineError: Exception) {
                        sendUpdate(
                            status = "OFFLINE ZAXIRA TEKSHIRILMOQDA"
                        )

                        val engine = getOfflineAI()

                        if (engine.isReady()) {
                            val answer = answerOffline(request)

                            respond(
                                "OFFLINE ZAXIRA",
                                "Online AI vaqtincha ishlamadi. Offline javob:\n\n$answer"
                            )
                        } else {
                            respond(
                                "ONLINE AI XATOSI",
                                "${onlineError.message ?: "Ulanish xatosi"}\n\nQwen3 offline modeli ham yuklanmagan."
                            )
                        }
                    }

                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    respond(
                        "NORA XATOSI",
                        e.message ?: "Noma'lum xatolik"
                    )
                } finally {
                    if (asr == null && !stopping) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
    }

    private suspend fun handleGitHubCommand(
        command: String
    ): String? {
        val match = Regex(
            """(?i)^github\s+(\S+)\s+(\S+)(?:\s+([\s\S]+))?$"""
        ).find(command.trim()) ?: return null

        val action = match.groupValues[1].lowercase()
        val repository = match.groupValues[2].split("/")
        val extra = match.groupValues[3].trim()

        if (
            repository.size != 2 ||
            repository.any { it.isBlank() }
        ) {
            return "Repo formati noto'g'ri. Masalan: github fayllar ubadydullaev/Nora-AI"
        }

        val owner = repository[0]
        val repo = repository[1]

        val github = NoraGitHubTools(this)
        val codeAgent = NoraCodeAgent(this)

        return when (action) {
            "fayllar", "files" ->
                github.listFiles(owner, repo)

            "actions", "holat" ->
                github.latestActions(owner, repo)

            "oqi", "read" -> {
                if (extra.isBlank()) {
                    "Fayl yo'li kerak."
                } else {
                    val file = NoraOnlineAgent(this)
                        .readGitHubFile(owner, repo, extra)

                    "FAYL: ${file.path}\n\n${file.content.take(12000)}"
                }
            }

            "tahlil", "analyze" -> {
                if (extra.isBlank()) {
                    "Tahlil uchun fayl yo'lini kiriting."
                } else {
                    codeAgent.analyzeFile(owner, repo, extra)
                }
            }

            "kod", "code" -> {
                if (extra.isBlank()) {
                    "Qanday kod kerakligini kiriting."
                } else {
                    codeAgent.createCode(
                        "Repository: $owner/$repo\nVazifa: $extra"
                    )
                }
            }

            else ->
                "Buyruqlar: github fayllar owner/repo; github actions owner/repo; github oqi owner/repo fayl; github tahlil owner/repo fayl; github kod owner/repo vazifa"
        }
    }

    private fun respond(state: String, answer: String) {
        sendUpdate(status = state, result = answer)
        speakResult(answer)
    }

    private fun speakResult(text: String) {
        if (text.isNotBlank()) {
            speech?.speak(text)
        }
    }

    private fun sendUpdate(
        status: String? = null,
        heard: String? = null,
        result: String? = null
    ) {
        val intent = Intent(ACTION_UPDATE)
            .setPackage(packageName)

        status?.let { intent.putExtra(EXTRA_STATUS, it) }
        heard?.let { intent.putExtra(EXTRA_HEARD, it) }
        result?.let { intent.putExtra(EXTRA_RESULT, it) }

        sendBroadcast(intent)
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
        stopping = true
        stopListening()

        serviceScope.cancel()

        offlineAI?.close()
        offlineAI = null

        speech?.close()
        speech = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
