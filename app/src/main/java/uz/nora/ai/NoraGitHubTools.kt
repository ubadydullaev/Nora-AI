package uz.nora.ai

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class NoraGitHubTools(context: Context) {

    private val app = context.applicationContext

    private fun token(): String =
        NoraSecureStore.read(app, "github_token")
            ?.takeIf { it.isNotBlank() }
            ?: error("GitHub token sozlanmagan. Nora sozlamalariga kiriting.")

    private fun validateRepo(owner: String, repo: String) {
        val pattern = Regex("[A-Za-z0-9_.-]{1,100}")

        require(pattern.matches(owner)) { "GitHub egasi noto'g'ri" }
        require(pattern.matches(repo)) { "Repo nomi noto'g'ri" }
    }

    private fun validatePath(path: String) {
        require(path.isNotBlank()) { "Fayl yo'li bo'sh" }
        require(!path.startsWith("/") && !path.contains('\\')) {
            "Fayl yo'li noto'g'ri"
        }
        require(path.split("/").none {
            it.isBlank() || it == "." || it == ".."
        }) {
            "Fayl yo'lida ruxsat etilmagan qism bor"
        }
    }

    private fun encodePath(path: String): String =
        path.split("/").joinToString("/") {
            URLEncoder.encode(it, "UTF-8").replace("+", "%20")
        }

    private suspend fun get(url: String): String =
        withContext(Dispatchers.IO) {
            val parsed = URL(url)

            require(
                parsed.protocol == "https" &&
                    parsed.host.equals("api.github.com", true)
            ) {
                "Faqat GitHub HTTPS API manziliga ulanish mumkin"
            }

            val connection =
                parsed.openConnection() as HttpURLConnection

            try {
                connection.requestMethod = "GET"
                connection.connectTimeout = 20000
                connection.readTimeout = 30000
                connection.instanceFollowRedirects = false

                connection.setRequestProperty(
                    "User-Agent",
                    "Nora-Android/1.0"
                )
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
                    "Bearer ${token()}"
                )

                val code = connection.responseCode

                val stream = if (code in 200..299) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }

                val response = stream
                    ?.bufferedReader()
                    ?.use { it.readText() }
                    .orEmpty()

                if (code !in 200..299) {
                    val message = when (code) {
                        401 -> "GitHub token noto'g'ri yoki muddati tugagan."
                        403 -> "GitHub ruxsati yoki API limiti bilan muammo."
                        404 -> "Repo yoki fayl topilmadi."
                        429 -> "GitHub so'rovlar limiti tugadi."
                        else -> "GitHub xatosi: HTTP $code"
                    }

                    error("$message\n${response.take(700)}")
                }

                response
            } finally {
                connection.disconnect()
            }
        }

    suspend fun listFiles(
        owner: String,
        repo: String
    ): String {
        validateRepo(owner, repo)

        val base = "https://api.github.com/repos/$owner/$repo"
        val repository = JSONObject(get(base))

        val branch = repository.optString("default_branch")
            .ifBlank { "main" }

        val encodedBranch = URLEncoder.encode(branch, "UTF-8")

        val tree = JSONObject(
            get("$base/git/trees/$encodedBranch?recursive=1")
        )

        val items = tree.optJSONArray("tree")
            ?: return "Repo fayllari topilmadi."

        val result = StringBuilder()
        result.append("REPO: $owner/$repo\n")
        result.append("BRANCH: $branch\n\n")

        var count = 0

        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            if (item.optString("type") != "blob") continue

            result.append("• ")
                .append(item.optString("path"))
                .append('\n')

            count++

            if (count >= 150) {
                result.append("\n... 150 ta faylda to'xtatildi.")
                break
            }
        }

        if (tree.optBoolean("truncated")) {
            result.append("\nGitHub katta repo sabab ro'yxatni qisqartirdi.")
        }

        if (count == 0) return "Repo ichida fayllar topilmadi."

        return result.toString()
    }

    suspend fun readFile(
        owner: String,
        repo: String,
        path: String
    ): String {
        validateRepo(owner, repo)
        validatePath(path)

        val encodedPath = encodePath(path)

        val json = JSONObject(
            get(
                "https://api.github.com/repos/$owner/$repo/contents/$encodedPath"
            )
        )

        if (json.optString("type") != "file") {
            return "Bu manzil oddiy fayl emas."
        }

        val encoded = json.optString("content")
            .replace("\\s".toRegex(), "")

        if (encoded.isBlank()) {
            return "Fayl mazmuni bo'sh yoki GitHub uni qaytarmadi."
        }

        val content = String(
            Base64.decode(encoded, Base64.DEFAULT),
            Charsets.UTF_8
        )

        return if (content.length > 18000) {
            content.take(18000) +
                "\n\n[Fayl uzunligi sababli qisqartirildi]"
        } else {
            content
        }
    }

    suspend fun latestActions(
        owner: String,
        repo: String
    ): String {
        validateRepo(owner, repo)

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
            val run = runs.optJSONObject(i) ?: continue

            result.append("• ")
                .append(run.optString("name"))
                .append("\n  Holat: ")
                .append(run.optString("status"))
                .append("\n  Natija: ")
                .append(
                    run.optString("conclusion")
                        .ifBlank { "hali tugamagan" }
                )
                .append("\n  Run ID: ")
                .append(run.optLong("id"))
                .append("\n  Branch: ")
                .append(run.optString("head_branch"))
                .append("\n\n")
        }

        return result.toString()
    }
}
