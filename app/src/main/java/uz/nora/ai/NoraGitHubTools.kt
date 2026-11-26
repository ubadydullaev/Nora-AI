package uz.nora.ai

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class NoraGitHubTools(private val context: Context) {

    private suspend fun get(url: String): String =
        withContext(Dispatchers.IO) {
            val token = NoraSecureStore.read(context, "github_token")
                ?: error("GitHub token sozlanmagan")

            val connection = URL(url).openConnection() as HttpURLConnection

            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 20000
                connection.readTimeout = 30000
                connection.setRequestProperty("User-Agent", "Nora-Android")
                connection.setRequestProperty(
                    "Accept",
                    "application/vnd.github+json"
                )
                connection.setRequestProperty(
                    "X-GitHub-Api-Version",
                    "2022-11-28"
                )
                connection.setRequestProperty(
                    "Authorization",
                    "Bearer $token"
                )

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
                    error("GitHub xatosi $code: ${response.take(500)}")
                }

                response
            } finally {
                connection.disconnect()
            }
        }

    suspend fun listFiles(owner: String, repo: String): String {
        val base = "https://api.github.com/repos/$owner/$repo"

        val repository = JSONObject(get(base))
        val branch = repository.optString("default_branch")
            .ifBlank { "main" }

        val encodedBranch = URLEncoder.encode(branch, "UTF-8")
        val tree = JSONObject(
            get("$base/git/trees/$encodedBranch?recursive=1")
        )

        if (tree.optBoolean("truncated")) {
            return "Repo juda katta. GitHub ro'yxatni qisqartirdi."
        }

        val items = tree.optJSONArray("tree")
            ?: return "Repo fayllari topilmadi."

        val result = StringBuilder()
        result.append("REPO: $owner/$repo\n")
            .append("BRANCH: $branch\n\n")

        var count = 0

        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)

            if (item.optString("type") == "blob") {
                result.append("• ")
                    .append(item.optString("path"))
                    .append('\n')

                count++

                if (count >= 150) {
                    result.append("\n... 150 ta faylda to'xtatildi.")
                    break
                }
            }
        }

        return result.toString()
    }

    suspend fun readFile(
        owner: String,
        repo: String,
        path: String
    ): String {
        val encodedPath = path.split("/")
            .joinToString("/") {
                URLEncoder.encode(it, "UTF-8")
                    .replace("+", "%20")
            }

        val json = JSONObject(
            get("https://api.github.com/repos/$owner/$repo/contents/$encodedPath")
        )

        if (json.optString("type") != "file") {
            return "Bu manzil oddiy fayl emas."
        }

        val encoded = json.optString("content")
            .replace("\n", "")
            .trim()

        if (encoded.isBlank()) {
            return "Fayl mazmuni bo'sh yoki GitHub uni qaytarmadi."
        }

        val bytes = Base64.decode(encoded, Base64.DEFAULT)
        val content = String(bytes, Charsets.UTF_8)

        return if (content.length > 18000) {
            content.take(18000) + "\n\n[Fayl uzunligi sababli qisqartirildi]"
        } else {
            content
        }
    }

    suspend fun latestActions(owner: String, repo: String): String {
        val json = JSONObject(
            get(
                "https://api.github.com/repos/$owner/$repo/actions/runs?per_page=5"
            )
        )

        val runs = json.optJSONArray("workflow_runs")
            ?: return "GitHub Actions natijasi topilmadi."

        if (runs.length() == 0) {
            return "Hali Actions ishga tushirilmagan."
        }

        val result = StringBuilder()
        result.append("GITHUB ACTIONS: $owner/$repo\n\n")

        for (i in 0 until runs.length()) {
            val run = runs.getJSONObject(i)

            val conclusion = run.optString("conclusion")
                .ifBlank { "hali tugamagan" }

            result.append("• ")
                .append(run.optString("name"))
                .append("\n  Holat: ")
                .append(run.optString("status"))
                .append("\n  Natija: ")
                .append(conclusion)
                .append("\n  Run ID: ")
                .append(run.optLong("id"))
                .append("\n\n")
        }

        return result.toString()
    }
}
