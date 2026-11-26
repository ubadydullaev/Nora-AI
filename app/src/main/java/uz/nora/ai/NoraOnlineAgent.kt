package uz.nora.ai

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class NoraOnlineAgent(private val context: Context) {

    data class GitHubFile(
        val path: String,
        val content: String,
        val sha: String
    )

    private fun request(
        url: String,
        method: String = "GET",
        token: String? = null,
        body: String? = null,
        contentType: String = "application/json"
    ): String {
        val connection = URL(url).openConnection() as HttpURLConnection

        try {
            connection.requestMethod = method
            connection.connectTimeout = 20000
            connection.readTimeout = 60000
            connection.setRequestProperty("User-Agent", "Nora-Android")
            connection.setRequestProperty("Accept", "application/vnd.github+json")

            if (!token.isNullOrBlank()) {
                connection.setRequestProperty(
                    "Authorization",
                    "Bearer $token"
                )
            }

            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", contentType)
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

            val response = stream?.bufferedReader()?.use {
                it.readText()
            }.orEmpty()

            if (code !in 200..299) {
                throw IllegalStateException("Server xatosi $code: $response")
            }

            return response
        } finally {
            connection.disconnect()
        }
    }

    suspend fun askAI(
        question: String,
        model: String,
        endpoint: String
    ): String = withContext(Dispatchers.IO) {

        val apiKey = NoraSecureStore.read(context, "ai_key")
            ?: error("AI API kaliti sozlanmagan")

        require(question.isNotBlank()) {
            "Savol bo'sh bo'lmasligi kerak"
        }

        val url = endpoint.trimEnd('/') + "/chat/completions"

        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put(
                        "content",
                        "Sen Nora nomli shaxsiy yordamchisan. " +
                            "O'zbek tilida qisqa, aniq va tabiiy javob ber."
                    )
            )
            .put(
                JSONObject()
                    .put("role", "user")
                    .put("content", question)
            )

        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.5)
            .put("max_tokens", 700)

        val response = request(
            url = url,
            method = "POST",
            token = apiKey,
            body = body.toString()
        )

        JSONObject(response)
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .optString("content")
            .trim()
            .ifBlank { "Javob olinmadi." }
    }

    suspend fun readGitHubFile(
        owner: String,
        repo: String,
        path: String
    ): GitHubFile = withContext(Dispatchers.IO) {

        val token = NoraSecureStore.read(context, "github_token")
            ?: error("GitHub token sozlanmagan")

        val safePath = path.split("/")
            .joinToString("/") { android.net.Uri.encode(it) }

        val url = "https://api.github.com/repos/$owner/$repo/contents/$safePath"

        val json = JSONObject(
            request(url = url, token = token)
        )

        val encoded = json.optString("content")
            .replace("\n", "")

        val content = if (encoded.isNotBlank()) {
            String(
                Base64.decode(encoded, Base64.DEFAULT),
                Charsets.UTF_8
            )
        } else {
            val downloadUrl = json.optString("download_url")
            if (downloadUrl.isBlank()) {
                error("Fayl mazmunini olish imkoni bo'lmadi")
            }
            request(downloadUrl, token = token)
        }

        GitHubFile(
            path = json.getString("path"),
            content = content,
            sha = json.getString("sha")
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

        if (!confirmedByUser) {
            error("GitHub faylini o'zgartirish uchun foydalanuvchi tasdig'i kerak")
        }

        val token = NoraSecureStore.read(context, "github_token")
            ?: error("GitHub token sozlanmagan")

        require(newContent.isNotBlank()) {
            "Yangi kod bo'sh bo'lishi mumkin emas"
        }

        val current = readGitHubFile(owner, repo, path)

        val safePath = path.split("/")
            .joinToString("/") { android.net.Uri.encode(it) }

        val encoded = Base64.encodeToString(
            newContent.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )

        val body = JSONObject()
            .put("message", commitMessage)
            .put("content", encoded)
            .put("sha", current.sha)

        val response = request(
            url = "https://api.github.com/repos/$owner/$repo/contents/$safePath",
            method = "PUT",
            token = token,
            body = body.toString()
        )

        JSONObject(response)
            .getJSONObject("commit")
            .getString("sha")
    }
}
