
package uz.nora.ai

import android.content.Context
import dev.ffmpegkit.llama.Llama
import dev.ffmpegkit.llama.LlamaConfig
import dev.ffmpegkit.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class OfflineNoraAI(context: Context) {

    companion object {
        
        private const val MODEL_URL =
            "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"

        private const val MIN_MODEL_SIZE = 1_000_000_000L

        private const val SYSTEM_PROMPT = """
            Sen Nora nomli shaxsiy yordamchisan.
            Faqat o'zbek tilida javob ber.
            Javoblaring tabiiy, ravon, aniq va tushunarli bo'lsin.
            O'zingni sun'iy intellekt deb qayta-qayta ta'kidlayverma.
            Foydalanuvchiga hurmat bilan murojaat qil.
            Keraksiz uzun javoblardan qoch.
            Agar javobni bilmasang, bilmasligingni ochiq ayt.
        """
    }

    private val appContext = context.applicationContext

    private val modelDir =
        File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "models")

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

        if (isReady()) return@withContext

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
                        val count = input.read(buffer)
                        if (count < 0) break

                        output.write(buffer, 0, count)
                        downloaded += count

                        if (total > 0) {
                            val percent =
                                (downloaded * 100 / total).toInt()
                                    .coerceIn(0, 100)

                            onProgress(percent)
                        }
                    }
                }
            }

            if (tempFile.length() < MIN_MODEL_SIZE) {
                error("Model fayli to'liq yuklanmadi. Qayta urinib ko'ring.")
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

        val model = loadedModel ?: Llama.loadModel(
            modelPath = modelFile.absolutePath,
            config = LlamaConfig(
                contextSize = 2048,
                threads = 3
            )
        ).also {
            loadedModel = it
        }

        val result = Llama.complete(
            model = model,
            prompt = question,
            systemPrompt = SYSTEM_PROMPT,
            maxTokens = 220
        )

        result.text
            .replace(Regex("(?s)<think>.*?</think>"), "")
            .trim()
            .ifBlank { "Kechirasiz, javob tayyorlay olmadim." }
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
