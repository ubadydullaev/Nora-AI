
package uz.nora.ai

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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

        require(question.isNotBlank()) {
            "Savol bo'sh bo'lmasligi kerak"
        }

        require(model.isNotBlank()) {
            "AI modeli ko'rsatilmagan"
        }

        val base = endpoint.trim().trimEnd('/')

        val apiUrl = when {
            base.endsWith("/chat/completions") -> base
            else -> "$base/chat/completions"
        }

        memory.addUserMessage(question)

        val messages = JSONArray()

        messages.put(
            JSONObject().apply {
                put("role", "system")
                put(
                    "content",
                    """
                    Sen Nora nomli shaxsiy AI yordamchisisan.
                    Foydalanuvchi bilan o'zbek tilida, lotin yozuvida gaplash.
                    Javoblaring aniq, tabiiy va tushunarli bo'lsin.
                    Foydalanuvchining oldingi suhbatlarini hisobga ol.
                    Telefon yoki GitHub'da bajarmagan ishingni bajarildi deb aytma.
                    Kod yozganda to'liq va izchil kod ber.
                    Xavfli yoki qaytarib bo'lmaydigan amallardan oldin tasdiq so'ra.
                    """.trimIndent()
                )
            }
        )

        val history = memory.getHistory()

        val start = maxOf(0, history.length() - 20)

        for (i in start until history.length()) {
            val item = history.optJSONObject(i) ?: continue

            val role = item.optString("role")
            val content = item.optString("content")

            if (
                (role == "user" || role == "assistant") &&
                content.isNotBlank()
            ) {
                messages.put(
                    JSONObject().apply {
                        put("role", role)
                        put("content", content)
                    }
                )
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
            body = body.toString(),
            jsonContent = true
        )

        val json = JSONObject(response)

        val answer = json
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            ?.trim()
            .orEmpty()

        if (answer.isBlank()) {
            error(
                json.optJSONObject("error")
                    ?.optString("message")
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

        val safePath = path.split("/")
            .joinToString("/") {
                java.net.URLEncoder.encode(it, "UTF-8")
                    .replace("+", "%20")
            }

        val url =
            "https://api.github.com/repos/$owner/$repo/contents/$safePath"

        val response = JSONObject(
            request(url, token = githubToken())
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
            request(downloadUrl, token = githubToken())
        } else {
            ""
        }

        GitHubFile(
            path = path,
            content = content,
            sha = response.optString("sha")
        )
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

        require(path.isNotBlank()) {
            "Fayl yo'li ko'rsatilmagan"
        }

        require(commitMessage.isNotBlank()) {
            "Commit xabari bo'sh"
        }

        val current = readGitHubFile(owner, repo, path)

        val safePath = path.split("/")
            .joinToString("/") {
                java.net.URLEncoder.encode(it, "UTF-8")
                    .replace("+", "%20")
            }

        val url =
            "https://api.github.com/repos/$owner/$repo/contents/$safePath"

        val encodedContent = Base64.encodeToString(
            newContent.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )

        val body = JSONObject().apply {
            put("message", commitMessage)
            put("content", encodedContent)
            put("sha", current.sha)
        }

        val response = request(
            url = url,
            method = "PUT",
            token = githubToken(),
            body = body.toString(),
            jsonContent = true
        )

        val result = JSONObject(response)
        val commit = result.optJSONObject("commit")

        "GitHub fayli yangilandi: $path\nCommit: ${commit?.optString("sha") ?: "yaratildi"}"
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
        body: String? = null,
        jsonContent: Boolean = false
    ): String {
        val connection =
            URL(url).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = method
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            connection.setRequestProperty(
                "User-Agent",
                "Nora-Android"
            )
            connection.setRequestProperty(
                "Accept",
                "application/vnd.github+json"
            )

            if (!token.isNullOrBlank()) {
                connection.setRequestProperty(
                    "Authorization",
                    "Bearer $token"
                )
            }

            if (jsonContent) {
                connection.setRequestProperty(
                    "Content-Type",
                    "application/json"
                )
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

            val response = stream?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()

            if (code !in 200..299) {
                error("Server xatosi ($code): ${response.take(1500)}")
            }

            return response
        } finally {
            connection.disconnect()
        }
    }
}
