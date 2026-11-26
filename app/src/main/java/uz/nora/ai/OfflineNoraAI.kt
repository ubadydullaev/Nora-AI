package uz.nora.ai

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import android.content.Context
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class OfflineNoraAI(context: Context) {

    companion object {
        private const val MODEL_URL =
            "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"

        private const val MIN_MODEL_SIZE = 1_000_000_000L
        private const val MAX_HISTORY_CHARS = 2500

        private const val SYSTEM_PROMPT = """
            Sen Nora nomli shaxsiy yordamchisan.
            Faqat o'zbek tilida, lotin yozuvida javob ber.
            Javoblaring tabiiy, ravon, aniq va tushunarli bo'lsin.
            Oldingi suhbat mazmunini hisobga ol.
            Foydalanuvchiga hurmat bilan murojaat qil.
            Keraksiz uzun javoblardan qoch.
            Agar javobni bilmasang, bilmasligingni ochiq ayt.
            Telefon yoki internetda bajarmagan ishingni bajarildi deb aytma.
        """
    }

    private val appContext = context.applicationContext
    private val memory = NoraConversationMemory(appContext)

    private val modelDir = File(
        appContext.getExternalFilesDir(null) ?: appContext.filesDir,
        "models"
    )

    private val modelFile =
        File(modelDir, "Qwen3-1.7B-Q4_K_M.gguf")

    private val mutex = Mutex()

    @Volatile
    private var loadedModel: LlamaModel? = null

    fun isReady(): Boolean {
        return modelFile.exists() &&
            modelFile.length() >= MIN_MODEL_SIZE
    }

    suspend fun downloadModel(
        onProgress: (Int) -> Unit
    ) = withContext(Dispatchers.IO) {

        if (isReady()) {
            onProgress(100)
            return@withContext
        }

        if (!modelDir.exists() && !modelDir.mkdirs()) {
            error("Model papkasini yaratib bo'lmadi")
        }

        val tempFile = File(modelDir, modelFile.name + ".part")
        var existing = tempFile.length()

        val connection =
            (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30000
                readTimeout = 120000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Nora-Android/1.0")

                if (existing > 0) {
                    setRequestProperty("Range", "bytes=$existing-")
                }
            }

        try {
            connection.connect()

            val response = connection.responseCode

            if (response !in 200..299) {
                error("Yuklash serveri xato qaytardi: $response")
            }

            val append = existing > 0 && response == 206

            if (!append) {
                existing = 0
                tempFile.delete()
            }

            val totalLength = connection.contentLengthLong
            val total = if (totalLength > 0) {
                existing + totalLength
            } else {
                -1L
            }

            var downloaded = existing

            connection.inputStream.use { input ->
                FileOutputStream(tempFile, append).buffered().use { output ->
                    val buffer = ByteArray(256 * 1024)

                    while (true) {
                        currentCoroutineContext().ensureActive()

                        val count = input.read(buffer)
                        if (count < 0) break

                        output.write(buffer, 0, count)
                        downloaded += count

                        if (total > 0) {
                            onProgress(
                                (downloaded * 100 / total)
                                    .toInt()
                                    .coerceIn(0, 100)
                            )
                        }
                    }
                }
            }

            if (tempFile.length() < MIN_MODEL_SIZE) {
                error("Model to'liq yuklanmadi. Qayta urinib ko'ring.")
            }

            if (modelFile.exists()) modelFile.delete()

            if (!tempFile.renameTo(modelFile)) {
                tempFile.copyTo(modelFile, overwrite = true)
                tempFile.delete()
            }

            if (!isReady()) {
                error("Model faylini tekshirish muvaffaqiyatsiz")
            }

            onProgress(100)

        } finally {
            connection.disconnect()
        }
    }

    suspend fun ask(question: String): String = mutex.withLock {
        if (!isReady()) {
            error("Avval Nora AI modelini yuklab oling")
        }

        require(question.isNotBlank()) {
            "Savol bo'sh bo'lmasligi kerak"
        }

        val contextText = buildConversationContext()

        val model = loadedModel ?: withContext(Dispatchers.IO) {
            Llama.loadModel(
                modelPath = modelFile.absolutePath,
                config = LlamaConfig(
                    contextSize = 2048,
                    threads = 3
                )
            )
        }.also {
            loadedModel = it
        }

        val prompt = buildString {
            if (contextText.isNotBlank()) {
                append("Oldingi suhbat:\n")
                append(contextText)
                append("\n\n")
            }

            append("Yangi savol: ")
            append(question)
        }

        val result = withContext(Dispatchers.IO) {
            Llama.complete(
                model = model,
                prompt = prompt,
                systemPrompt = SYSTEM_PROMPT,
                maxTokens = 220
            )
        }

        val answer = result.text
            .replace(Regex("(?s)<think>.*?</think>"), "")
            .trim()
            .ifBlank {
                "Kechirasiz, javob tayyorlay olmadim."
            }

        memory.addUserMessage(question)
        memory.addAssistantMessage(answer)

        answer
    }

    private fun buildConversationContext(): String {
        val history: JSONArray = memory.getHistory()
        val start = maxOf(0, history.length() - 8)

        val context = buildString {
            for (i in start until history.length()) {
                val item = history.optJSONObject(i) ?: continue

                val role = item.optString("role")
                val content = item.optString("content")

                if (
                    (role == "user" || role == "assistant") &&
                    content.isNotBlank()
                ) {
                    append(
                        if (role == "user") "Foydalanuvchi: "
                        else "Nora: "
                    )
                    append(content)
                    append('\n')
                }
            }
        }

        return if (context.length > MAX_HISTORY_CHARS) {
            context.takeLast(MAX_HISTORY_CHARS)
        } else {
            context
        }
    }

    fun close() {
        loadedModel?.let {
            try {
                Llama.releaseModel(it)
            } catch (_: Exception) {
            }
        }

        loadedModel = null
    }
}
