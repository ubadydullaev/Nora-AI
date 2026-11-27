
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
        const val ACTION_CONFIRM_CHANGE = "uz.nora.ai.CONFIRM_GITHUB_CHANGE"
        const val ACTION_CANCEL_CHANGE = "uz.nora.ai.CANCEL_GITHUB_CHANGE"

        const val EXTRA_STATUS = "status"
        const val EXTRA_HEARD = "heard"
        const val EXTRA_RESULT = "result"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_CHANGE_ID = "change_id"
        const val EXTRA_CHANGE_PREVIEW = "change_preview"

        private const val CHANNEL_ID = "nora_voice_channel"
        private const val NOTIFICATION_ID = 701
        private const val UNKNOWN = "Bu buyruq hali qo'shilmagan"

        private const val MAX_COMMAND_LENGTH = 12000
        private const val DUPLICATE_WINDOW_MS = 1800L
    }

    private val serviceScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val requestMutex = Mutex()

    private val conversationMemory by lazy {
        NoraConversationMemory(this)
    }

    private val changeManager by lazy {
        NoraGitHubChangeManager(this)
    }

    @Volatile
    private var asr: OfflineUzbekAsr? = null

    @Volatile
    private var stopping = false

    @Volatile
    private var awaitingApproval = false

    @Volatile
    private var lastCommand = ""

    @Volatile
    private var lastCommandTime = 0L

    private var speech: NoraSpeech? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var offlineAI: OfflineNoraAI? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        speech = NoraSpeech(this)
        awaitingApproval = changeManager.pendingCount() > 0
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

            ACTION_CONFIRM_CHANGE -> {
                val id = intent.getStringExtra(EXTRA_CHANGE_ID).orEmpty()
                confirmGitHubChange(id, startId)
            }

            ACTION_CANCEL_CHANGE -> {
                val id = intent.getStringExtra(EXTRA_CHANGE_ID).orEmpty()

                if (id.isBlank()) {
                    sendUpdate(
                        status = "GITHUB XATOSI",
                        result = "Bekor qilish ID topilmadi."
                    )
                } else {
                    val cancelled = changeManager.cancelChange(id)
                    awaitingApproval = changeManager.pendingCount() > 0

                    sendUpdate(
                        status = "GITHUB",
                        result = if (cancelled) {
                            "Tayyorlangan o'zgarish bekor qilindi."
                        } else {
                            "O'zgarish topilmadi yoki muddati tugagan."
                        }
                    )
                }

                stopIfIdle(startId)
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

    private fun confirmGitHubChange(
        id: String,
        startId: Int
    ) {
        if (id.isBlank()) {
            sendUpdate(
                status = "GITHUB XATOSI",
                result = "Tasdiqlash ID topilmadi."
            )
            return
        }

        serviceScope.launch {
            requestMutex.withLock {
                try {
                    val pending = changeManager.findPendingById(id)
                        ?: error(
                            "Tasdiq topilmadi yoki uning 15 daqiqalik muddati tugagan."
                        )

                    sendUpdate(
                        status = "GITHUB COMMIT BAJARILMOQDA"
                    )

                    val result = changeManager.commitChange(
                        changeId = pending.id,
                        confirmedByUser = true
                    )

                    awaitingApproval = changeManager.pendingCount() > 0

                    rememberAndRespond(
                        "GitHub ${pending.owner}/${pending.repo} repositoryidagi ${pending.path} o'zgarishini tasdiqlash",
                        "GITHUB COMMIT",
                        result
                    )

                } catch (e: Exception) {
                    awaitingApproval = changeManager.pendingCount() > 0

                    respond(
                        "GITHUB COMMIT XATOSI",
                        e.message ?: "Commit bajarilmadi."
                    )
                } finally {
                    stopIfIdle(startId)
                }
            }
        }
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
                "Offline ovoz modeli topilmadi. Sozlamalardan GigaAM modelini yuklang."

            sendUpdate(
                status = "MODEL TOPILMADI",
                result = answer
            )

            speakResult(answer)
            stopListening()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }

        listener.start()
    }

    private fun handleSpeech(recognized: String) {
        val cleaned = recognized
            .trim()
            .take(MAX_COMMAND_LENGTH)

        if (cleaned.isBlank()) return

        val now = System.currentTimeMillis()
        val normalized = normalize(cleaned)

        if (
            normalized == lastCommand &&
            now - lastCommandTime < DUPLICATE_WINDOW_MS
        ) {
            return
        }

        lastCommand = normalized
        lastCommandTime = now

        sendUpdate(heard = cleaned)

        val command = cleaned.replace(
            Regex("(?i)^nora[\\s,.:!?-]*"),
            ""
        ).trim()

        if (command.isNotBlank()) {
            processRequest(command)
        }
    }

    private fun normalize(value: String): String {
        return value.trim()
            .lowercase()
            .replace('’', '\'')
            .replace('ʻ', '\'')
            .replace('ʼ', '\'')
            .replace(Regex("\\s+"), " ")
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
                "Qwen3 offline modeli yuklanmagan. Sozlamalardan modelni yuklang."
            )
        }

        sendUpdate(status = "OFFLINE AI TAHLIL QILMOQDA")

        val context = conversationMemory.getContextText(5000)

        val prompt = if (context.isBlank()) {
            request
        } else {
            """
Oldingi suhbat konteksti:
$context

Hozirgi foydalanuvchi xabari:
$request

Javobda oldingi suhbatni kerak bo'lganda hisobga ol. Yangi xabarni asosiy vazifa deb bil. O'zbek tilida javob ber.
""".trimIndent()
        }

        return engine.ask(prompt)
    }

    private fun rememberAndRespond(
        request: String,
        state: String,
        answer: String
    ) {
        conversationMemory.addUserMessage(request)
        conversationMemory.addAssistantMessage(answer)
        respond(state, answer)
    }

    private fun processRequest(request: String) {
        if (request.isBlank()) return

        serviceScope.launch {
            requestMutex.withLock {
                if (stopping) return@withLock

                try {
                    sendUpdate(status = "NORA TAHLIL QILMOQDA")

                    val normalized = normalize(request)

                    if (
                        normalized in listOf(
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

                        rememberAndRespond(
                            request,
                            "OFFLINE REJIM",
                            "Offline rejim yoqildi. Telefon buyruqlari va yuklangan offline AI ishlaydi."
                        )
                        return@withLock
                    }

                    if (
                        normalized in listOf(
                            "online rejim",
                            "online rejimni yoq",
                            "internetni yoq"
                        )
                    ) {
                        NoraSecureStore.delete(
                            this@NoraVoiceService,
                            "offline_only"
                        )

                        rememberAndRespond(
                            request,
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
                        rememberAndRespond(
                            request,
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
                            rememberAndRespond(
                                request,
                                "BUYRUQ XATOSI",
                                localResult.removePrefix("LOCAL_ERROR:")
                            )
                        } else {
                            rememberAndRespond(
                                request,
                                "BAJARILDI",
                                localResult
                            )
                        }
                        return@withLock
                    }

                    if (offlineOnly) {
                        try {
                            val answer = answerOffline(request)
                            rememberAndRespond(
                                request,
                                "OFFLINE NORA",
                                answer
                            )
                        } catch (e: Exception) {
                            rememberAndRespond(
                                request,
                                "OFFLINE AI",
                                e.message ?: "Offline javob tayyorlab bo'lmadi."
                            )
                        }
                        return@withLock
                    }

                    val githubResult = handleGitHubCommand(request)

                    if (githubResult != null) {
                        if (!awaitingApproval) {
                            rememberAndRespond(
                                request,
                                "GITHUB",
                                githubResult
                            )
                        } else {
                            respond("GITHUB", githubResult)
                        }
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
                            val answer = answerOffline(request)
                            rememberAndRespond(
                                request,
                                "OFFLINE ZAXIRA",
                                answer
                            )
                        } else {
                            rememberAndRespond(
                                request,
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
                        val answer = NoraOnlineAgent(
                            this@NoraVoiceService
                        ).askAI(
                            question = request,
                            model = model,
                            endpoint = endpoint,
                            saveHistory = true
                        )

                        respond("NORA ONLINE", answer)

                    } catch (onlineError: Exception) {
                        sendUpdate(
                            status = "OFFLINE ZAXIRA TEKSHIRILMOQDA"
                        )

                        val engine = getOfflineAI()

                        if (engine.isReady()) {
                            val answer = answerOffline(request)

                            rememberAndRespond(
                                request,
                                "OFFLINE ZAXIRA",
                                "Online AI vaqtincha ishlamadi. Offline javob:\n\n$answer"
                            )
                        } else {
                            rememberAndRespond(
                                request,
                                "ONLINE AI XATOSI",
                                "${onlineError.message ?: "Ulanish xatosi"}\n\nQwen3 offline modeli ham yuklanmagan."
                            )
                        }
                    }

                } catch (e: CancellationException) {
                    throw e

                } catch (e: Exception) {
                    rememberAndRespond(
                        request,
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
        val auditHistory = NoraAuditHistory(this)

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

            "audit", "tekshir", "tekshiruv", "audit-taqqosla" -> {
                sendUpdate(
                    status = "REPOSITORY FAYLLARI O'ZARO TEKSHIRILMOQDA"
                )

                NoraRepositoryAudit(this).audit(owner, repo)
            }

            "audit-tarix", "audit-history" ->
                auditHistory.getHistoryInfo(owner, repo)

            "audit-old" ->
                auditHistory.getPrevious(owner, repo)
                    ?: "Oldingi audit hisoboti topilmadi."

            "audit-tozala" -> {
                auditHistory.clear(owner, repo)
                "Audit tarixi tozalandi: $owner/$repo"
            }

            "pending", "kutayotgan" -> {
                val pending = changeManager.findPending(owner, repo)

                if (pending == null) {
                    "Bu repository uchun kutilayotgan o'zgarish yo'q."
                } else {
                    awaitingApproval = true

                    sendUpdate(
                        status = "GITHUB TASDIQ KUTILMOQDA",
                        changeId = pending.first,
                        changePreview = pending.second
                    )

                    "Kutilayotgan o'zgarish qayta ochildi. Ekrandagi tafsilotlarni tekshiring."
                }
            }

            "bekor", "cancel" -> {
                val cancelled =
                    changeManager.cancelPendingForRepo(owner, repo)

                awaitingApproval = changeManager.pendingCount() > 0

                if (cancelled) {
                    "Repository uchun tayyorlangan o'zgarishlar bekor qilindi."
                } else {
                    "Bekor qilinadigan o'zgarish topilmadi."
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

            "tayyorla", "prepare" -> {
                val parts = extra.split("|", limit = 2)

                if (
                    parts.size != 2 ||
                    parts[0].isBlank() ||
                    parts[1].isBlank()
                ) {
                    return "Format: github tayyorla owner/repo fayl/yo'li | nima o'zgarsin"
                }

                val path = parts[0].trim()
                val task = parts[1].trim()

                sendUpdate(
                    status = "GITHUB FAYL AI ORQALI YANGILANMOQDA"
                )

                val newContent = codeAgent.reviseFile(
                    owner,
                    repo,
                    path,
                    task
                )

                val prepared = changeManager.prepareChange(
                    owner = owner,
                    repo = repo,
                    path = path,
                    newContent = newContent,
                    commitMessage = "Update $path with Nora AI"
                )

                awaitingApproval = true

                sendUpdate(
                    status = "GITHUB TASDIQ KUTILMOQDA",
                    changeId = prepared.first,
                    changePreview = prepared.second
                )

                "O'zgarish tayyorlandi. Ekrandagi ko'rinishni tekshiring. Tasdiqlamaguningizcha GitHub'ga yozilmaydi."
            }

            else ->
                """
                Nora GitHub buyruqlari:

                github fayllar owner/repo
                github actions owner/repo
                github oqi owner/repo fayl
                github tahlil owner/repo fayl
                github audit owner/repo
                github audit-tarix owner/repo
                github audit-old owner/repo
                github audit-tozala owner/repo
                github pending owner/repo
                github bekor owner/repo
                github kod owner/repo vazifa
                github tayyorla owner/repo fayl | vazifa
                """.trimIndent()
        }
    }

    private fun respond(
        state: String,
        answer: String
    ) {
        sendUpdate(
            status = state,
            result = answer
        )
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
        result: String? = null,
        changeId: String? = null,
        changePreview: String? = null
    ) {
        val intent = Intent(ACTION_UPDATE)
            .setPackage(packageName)

        status?.let {
            intent.putExtra(EXTRA_STATUS, it)
        }

        heard?.let {
            intent.putExtra(EXTRA_HEARD, it)
        }

        result?.let {
            intent.putExtra(EXTRA_RESULT, it)
        }

        changeId?.let {
            intent.putExtra(EXTRA_CHANGE_ID, it)
        }

        changePreview?.let {
            intent.putExtra(EXTRA_CHANGE_PREVIEW, it)
        }

        sendBroadcast(intent)
    }

    private fun stopIfIdle(startId: Int) {
        if (asr == null && !stopping) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }
    }

    private fun stopListening() {
        try {
            asr?.close()
        } catch (_: Exception) {
        }

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

        try {
            offlineAI?.close()
        } catch (_: Exception) {
        }
        offlineAI = null

        try {
            speech?.close()
        } catch (_: Exception) {
        }
        speech = null

        // Kutilayotgan GitHub o'zgarishlari o'chirilmaydi.
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
