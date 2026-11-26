
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

    suspend fun askAI(
        question: String,
        model: String,
        endpoint: String
    ): String = withContext(Dispatchers.IO) {
        val apiKey = NoraSecureStore.read(app, "ai_key")
            ?: error("AI API kaliti topilmadi")

        require(question.isNotBlank()) { "Savol bo'sh bo'lmasligi kerak" }
        require(model.isNotBlank()) { "AI modeli ko'rsatilmagan" }
        require(endpoint.isNotBlank()) { "API manzili ko'rsatilmagan" }

        val base = endpoint.trim().trimEnd('/')
        val apiUrl = if (base.endsWith("/chat/completions")) {
            base
        } else {
            "$base/chat/completions"
        }

        val apiHost = URL(apiUrl).host
        require(URL(apiUrl).protocol == "https") {
            "AI API manzili HTTPS bo'lishi kerak"
        }

        memory.addUserMessage(question)

        val messages = JSONArray()
        messages.put(JSONObject().apply {
            put("role", "system")
            put("content", """
                Sen Nora nomli shaxsiy AI yordamchisisan.
                Foydalanuvchi bilan o'zbek tilida, lotin yozuvida gaplash.
                Javoblaring aniq, tabiiy va tushunarli bo'lsin.
                Oldingi suhbat tarixini hisobga ol.
                Telefon yoki GitHub'da bajarmagan ishingni bajarildi deb aytma.
                Kod so'ralganda to'liq va izchil kod ber.
                Faylni o'zgartirish yoki commit qilishdan oldin foydalanuvchi tasdig'ini ol.
                Maxfiy kalitlar va tokenlarni hech qachon javobda qaytarma.
            """.trimIndent())
        })

        val history = memory.getHistory()
        val start = maxOf(0, history.length() - 20)

        for (i in start until history.length()) {
            val item = history.optJSONObject(i) ?: continue
            val role = item.optString("role")
            val content = item.optString("content")
            if ((role == "user" || role == "assistant") && content.isNotBlank()) {
                messages.put(JSONObject().apply {
                    put("role", role)
                    put("content", content)
                })
            }
        }

        val body = JSONObject().apply {
            put("model", model)
            put("messages", messages)
            put("temperature", 0.7)
        }

        val response = request(
            url = apiUrl,
            method = "POST",
            token = apiKey,
            authHost = apiHost,
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
                json.optJSONObject("error")?.optString("message")
                    ?: "AI javobi bo'sh qaytdi"
            )
        }

        memory.addAssistantMessage(answer)
        answer
    }

    suspend fun readGitHubFile(
        owner: String,
        repo: String,
        path: String
    ): GitHubFile = withContext(Dispatchers.IO) {
        require(owner.isNotBlank() && repo.isNotBlank() && path.isNotBlank()) {
            "GitHub repo yoki fayl yo'li noto'g'ri"
        }

        val safePath = path.split("/").joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }

        val url = "https://api.github.com/repos/$owner/$repo/contents/$safePath"
        val response = JSONObject(
            request(
                url = url,
                token = githubToken(),
                authHost = "api.github.com"
            )
        )

        val encoded = response.optString("content")
        val downloadUrl = response.optString("download_url")

        val content = if (encoded.isNotBlank()) {
            String(
                Base64.decode(
                    encoded.replace("\\s".toRegex(), ""),
                    Base64.DEFAULT
                ),
                Charsets.UTF_8
            )
        } else if (downloadUrl.isNotBlank()) {
            // GitHub tokeni raw/download serveriga yuborilmaydi.
            request(downloadUrl)
        } else {
            error("GitHub fayl mazmunini qaytarmadi")
        }

        GitHubFile(path, content, response.optString("sha"))
    }

    suspend fun updateGitHubFile(
        owner: String,
        repo: String,
        path: String,
        newContent: String,
        commitMessage: String,
        confirmedByUser: Boolean
    ): String = withContext(Dispatchers.IO) {
        require(confirmedByUser) {
            "Commit qilish uchun foydalanuvchi tasdig'i kerak"
        }
        require(path.isNotBlank()) { "Fayl yo'li ko'rsatilmagan" }
        require(commitMessage.isNotBlank()) { "Commit xabari bo'sh" }

        val current = readGitHubFile(owner, repo, path)

        val safePath = path.split("/").joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }

        val url = "https://api.github.com/repos/$owner/$repo/contents/$safePath"

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

        val commitSha = response.optJSONObject("commit")?.optString("sha")
        "GitHub fayli yangilandi: $path\nCommit: ${commitSha ?: "yaratildi"}"
    }

    private fun githubToken(): String =
        NoraSecureStore.read(app, "github_token")
            ?.takeIf { it.isNotBlank() }
            ?: error("GitHub token sozlanmagan")

    private fun request(
        url: String,
        method: String = "GET",
        token: String? = null,
        authHost: String? = null,
        body: String? = null,
        jsonContent: Boolean = false
    ): String {
        val parsedUrl = URL(url)
        require(parsedUrl.protocol == "https") {
            "Faqat HTTPS manzillarga ulanish mumkin"
        }

        val connection = parsedUrl.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = method
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Nora-Android/1.0")

            if (parsedUrl.host.equals("api.github.com", ignoreCase = true)) {
                connection.setRequestProperty(
                    "Accept",
                    "application/vnd.github+json"
                )
            } else {
                connection.setRequestProperty("Accept", "application/json")
            }

            if (
                !token.isNullOrBlank() &&
                !authHost.isNullOrBlank() &&
                parsedUrl.host.equals(authHost, ignoreCase = true)
            ) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }

            if (jsonContent) {
                connection.setRequestProperty("Content-Type", "application/json")
            }

            if (body != null) {
                connection.doOutput = true
                connection.outputStream.use {
                    it.write(body.toByteArray(Charsets.UTF_8))
                }
            }

            val code = connection.responseCode
            val stream = if (code in 200..299) {
                connection.inputStream
            } else {
                connection.errorStream
            }

            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()

            if (code !in 200..299) {
                error("Server xatosi ($code): ${response.take(1500)}")
            }

            return response
        } finally {
            connection.disconnect()
        }
    }
}
