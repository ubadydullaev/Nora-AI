
package uz.nora.ai

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class GitHubFile(
    val path: String,
    val content: String,
    val sha: String
)

class NoraOnlineAgent(context: Context) {

    private val app = context.applicationContext
    private val memory = NoraConversationMemory(app)

    companion object {
        private const val MAX_QUESTION_LENGTH = 20000
        private const val MAX_FILE_LENGTH = 500000
        private const val MAX_CONTEXT_LENGTH = 24000
        private const val MAX_HISTORY_MESSAGES = 24
        private const val MAX_RESPONSE_LENGTH = 1000000
    }

    suspend fun askAI(
        question: String,
        model: String,
        endpoint: String,
        saveHistory: Boolean = true
    ): String = withContext(Dispatchers.IO) {

        require(question.isNotBlank()) {
            "Savol bo'sh bo'lishi kerak"
        }
        require(question.length <= MAX_QUESTION_LENGTH) {
            "Savol juda uzun"
        }
        require(model.isNotBlank()) {
            "AI modeli ko'rsatilmagan"
        }
        require(endpoint.isNotBlank()) {
            "API manzili ko'rsatilmagan"
        }

        val apiKey = NoraSecureStore.read(app, "ai_key")
            ?.takeIf { it.isNotBlank() }
            ?: error("AI API kaliti topilmadi")

        val base = endpoint.trim().trimEnd('/')
        val apiUrl = if (base.endsWith("/chat/completions")) {
            base
        } else {
            "$base/chat/completions"
        }

        val parsed = URL(apiUrl)

        require(
            parsed.protocol.equals("https", true) &&
                parsed.host.isNotBlank() &&
                parsed.userInfo == null
        ) {
            "AI manzili HTTPS bo'lishi kerak"
        }

        val messages = JSONArray()

        val systemPrompt = """
            Sen Nora nomli shaxsiy AI yordamchisisan.

            TIL:
            - O'zbek tilida, lotin yozuvida gaplash.
            - Tabiiy, tushunarli va aniq javob ber.
            - Oddiy savollarga qisqa javob ber.
            - Murakkab ishlarni tartibli bajar.
            - Oldingi suhbat kontekstini hisobga ol.

            ISHONCH:
            - Bajarmagan ishingni bajarildi deb aytma.
            - Tekshirmagan ma'lumotni tekshirdim deb ko'rsatma.
            - Kod so'ralganda to'liq va izchil fayl ber.
            - Ko'rilmagan fayl haqida taxminni fakt deb ko'rsatma.
            - Maxfiy kalit va tokenlarni hech qachon oshkor qilma.

            GITHUB:
            - Faylni o'zgartirishdan oldin uning mavjud kodini o'rgan.
            - Loyiha arxitekturasi va mavjud API'larni saqla.
            - O'zgarish sababini tushuntir.
            - Foydalanuvchi tasdig'isiz commit qilma.
            - Foydalanuvchi tasdig'isiz fayl o'chirma.
            - Eski SHA o'zgargan bo'lsa, commitni to'xtat.

            ANDROID:
            - Android versiyasi va dependency mosligini hisobga ol.
            - Qurilma resurslarini tejaydigan yechimlarni afzal ko'r.
            - Offline ishlashi mumkin bo'lgan vazifalarni offline bajar.
        """.trimIndent()

        messages.put(
            JSONObject()
                .put("role", "system")
                .put("content", systemPrompt)
        )

        val history = memory.getLastMessages(MAX_HISTORY_MESSAGES)
        var contextLength = 0

        for (i in 0 until history.length()) {
            val item = history.optJSONObject(i) ?: continue
            val role = item.optString("role")
            val content = item.optString("content").trim()

            if (
                role != "user" &&
                role != "assistant"
            ) continue

            if (content.isBlank()) continue

            val remaining = MAX_CONTEXT_LENGTH - contextLength
            if (remaining <= 0) break

            val safeContent = content.take(
                minOf(6000, remaining)
            )

            messages.put(
                JSONObject()
                    .put("role", role)
                    .put("content", safeContent)
            )

            contextLength += safeContent.length
        }

        messages.put(
            JSONObject()
                .put("role", "user")
                .put("content", question)
        )

        val body = JSONObject().apply {
            put("model", model.trim())
            put("messages", messages)
            put("temperature", 0.65)
        }

        val response = request(
            url = apiUrl,
            method = "POST",
            token = apiKey,
            authHost = parsed.host,
            body = body.toString(),
            jsonContent = true
        )

        val json = JSONObject(response)

        val answer = json.optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            .orEmpty()

        if (answer.isBlank()) {
            error(
                json.optJSONObject("error")
                    ?.optString("message")
                    ?.takeIf { it.isNotBlank() }
                    ?: "AI javobi bo'sh qaytdi"
            )
        }

        if (saveHistory) {
            memory.addUserMessage(question)
            memory.addAssistantMessage(answer)
        }

        answer
    }

    suspend fun readGitHubFile(
        owner: String,
        repo: String,
        path: String
    ): GitHubFile = withContext(Dispatchers.IO) {

        validateGitHubPath(owner, repo, path)

        val safePath = encodePath(path)
        val url =
            "https://api.github.com/repos/$owner/$repo/contents/$safePath"

        val response = JSONObject(
            request(
                url = url,
                token = githubToken(),
                authHost = "api.github.com"
            )
        )

        require(response.optString("type") == "file") {
            "GitHub manzili oddiy fayl emas"
        }

        val encoded = response.optString("content")

        require(encoded.isNotBlank()) {
            "GitHub fayl mazmunini qaytarmadi"
        }

        val content = try {
            String(
                Base64.decode(
                    encoded.replace("\\s".toRegex(), ""),
                    Base64.DEFAULT
                ),
                Charsets.UTF_8
            )
        } catch (_: Exception) {
            error("GitHub faylini dekodlashda xatolik")
        }

        require(content.length <= MAX_FILE_LENGTH) {
            "GitHub fayli 500 KB dan katta"
        }

        val sha = response.optString("sha")

        require(sha.isNotBlank()) {
            "GitHub SHA topilmadi"
        }

        GitHubFile(
            path = path,
            content = content,
            sha = sha
        )
    }

    suspend fun updateGitHubFile(
        owner: String,
        repo: String,
        path: String,
        newContent: String,
        commitMessage: String,
        confirmedByUser: Boolean,
        expectedSha: String? = null
    ): String = withContext(Dispatchers.IO) {

        require(confirmedByUser) {
            "Commit uchun foydalanuvchi tasdig'i kerak"
        }

        validateGitHubPath(owner, repo, path)

        require(commitMessage.isNotBlank()) {
            "Commit xabari bo'sh"
        }
        require(commitMessage.length <= 200) {
            "Commit xabari juda uzun"
        }
        require(newContent.isNotBlank()) {
            "Yangi fayl mazmuni bo'sh"
        }
        require(newContent.length <= MAX_FILE_LENGTH) {
            "Fayl hajmi juda katta"
        }
        require(!expectedSha.isNullOrBlank()) {
            "Tasdiqlangan SHA qiymati kerak"
        }

        val current = readGitHubFile(owner, repo, path)

        require(current.sha == expectedSha) {
            "GitHub fayli tasdiqlashdan oldin o'zgargan. " +
                "Commit bekor qilindi. Faylni qayta o'qing."
        }

        val safePath = encodePath(path)
        val url =
            "https://api.github.com/repos/$owner/$repo/contents/$safePath"

        val encoded = Base64.encodeToString(
            newContent.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )

        val body = JSONObject().apply {
            put("message", commitMessage)
            put("content", encoded)
            put("sha", current.sha)
        }

        val response = JSONObject(
            request(
                url = url,
                method = "PUT",
                token = githubToken(),
                authHost = "api.github.com",
                body = body.toString(),
                jsonContent = true
            )
        )

        val commitSha = response.optJSONObject("commit")
            ?.optString("sha")
            ?.takeIf { it.isNotBlank() }

        require(!commitSha.isNullOrBlank()) {
            "GitHub commit SHA qaytarmadi"
        }

        "GitHub fayli yangilandi: $path\nCommit: $commitSha"
    }

    private fun validateGitHubPath(
        owner: String,
        repo: String,
        path: String
    ) {
        val validPart = Regex("^[A-Za-z0-9_.-]{1,100}$")

        require(validPart.matches(owner)) {
            "GitHub egasi noto'g'ri"
        }
        require(validPart.matches(repo)) {
            "Repo nomi noto'g'ri"
        }
        require(path.isNotBlank() && path.length <= 500) {
            "Fayl yo'li noto'g'ri"
        }
        require(!path.startsWith("/") && !path.contains('\\')) {
            "Fayl yo'li noto'g'ri"
        }
        require(
            path.split("/").none {
                it.isBlank() || it == "." || it == ".."
            }
        ) {
            "Fayl yo'lida ruxsat etilmagan qism bor"
        }
    }

    private fun encodePath(path: String): String {
        return path.split("/").joinToString("/") {
            URLEncoder.encode(it, "UTF-8")
                .replace("+", "%20")
        }
    }

    private fun githubToken(): String {
        return NoraSecureStore.read(app, "github_token")
            ?.takeIf { it.isNotBlank() }
            ?: error("GitHub token sozlanmagan")
    }

    private fun request(
        url: String,
        method: String = "GET",
        token: String? = null,
        authHost: String? = null,
        body: String? = null,
        jsonContent: Boolean = false
    ): String {

        val parsedUrl = URL(url)

        require(
            parsedUrl.protocol.equals("https", true) &&
                parsedUrl.host.isNotBlank() &&
                parsedUrl.userInfo == null
        ) {
            "Faqat HTTPS manzillarga ulanish mumkin"
        }

        val connection =
            parsedUrl.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = method
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            connection.instanceFollowRedirects = false
            connection.useCaches = false

            connection.setRequestProperty(
                "User-Agent",
                "Nora-Android/1.0"
            )

            connection.setRequestProperty(
                "Accept",
                if (
                    parsedUrl.host.equals(
                        "api.github.com",
                        ignoreCase = true
                    )
                ) {
                    "application/vnd.github+json"
                } else {
                    "application/json"
                }
            )

            if (
                !token.isNullOrBlank() &&
                !authHost.isNullOrBlank() &&
                parsedUrl.host.equals(authHost, true)
            ) {
                connection.setRequestProperty(
                    "Authorization",
                    "Bearer $token"
                )
            }

            if (jsonContent) {
                connection.setRequestProperty(
                    "Content-Type",
                    "application/json; charset=utf-8"
                )
            }

            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use { output ->
                    output.write(body.toByteArray(Charsets.UTF_8))
                }
            }

            val code = connection.responseCode

            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()

            require(response.length <= MAX_RESPONSE_LENGTH) {
                "Server javobi juda katta"
            }

            if (code !in 200..299) {
                val detail = try {
                    JSONObject(response)
                        .optString("message")
                        .takeIf { it.isNotBlank() }
                } catch (_: Exception) {
                    null
                }

                error(
                    "Server xatosi ($code): " +
                        (detail ?: response.take(1000))
                )
            }

            return response

        } finally {
            connection.disconnect()
        }
    }
}
