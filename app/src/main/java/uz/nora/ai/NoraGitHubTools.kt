package uz.nora.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
                    error("GitHub xatosi $code: $response")
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

        val tree = JSONObject(
            get("$base/git/trees/$branch?recursive=1")
        )

        if (tree.optBoolean("truncated")) {
            return "Repo juda katta. GitHub ro'yxatni qisqartirdi."
        }

        val items = tree.optJSONArray("tree")
            ?: return "Repo fayllari topilmadi."

        val result = StringBuilder()
        result.append("REPO: $owner/$repo\nBRANCH: $branch\n\n")

        var count = 0
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            if (item.optString("type") == "blob") {
                result.append("• ")
                    .append(item.optString("path"))
                    .append('\n')
                count++
                if (count >= 150) {
                    result.append("\n... ro'yxat 150 ta faylda to'xtatildi.")
                    break
                }
            }
        }

        return result.toString()
    }

    suspend fun latestActions(owner: String, repo: String): String {
        val json = JSONObject(
            get(
                "https://api.github.com/repos/$owner/$repo/actions/runs?per_page=5"
            )
        )

        val runs = json.optJSONArray("workflow_runs")
            ?: return "GitHub Actions natijasi topilmadi."

        if (runs.length() == 0) return "Hali Actions ishga tushirilmagan."

        val result = StringBuilder("GITHUB ACTIONS: $owner/$repo\n\n")

        for (i in 0 until runs.length()) {
            val run = runs.getJSONObject(i)
            result.append("• ")
                .append(run.optString("name"))
                .append("\n  Holat: ")
                .append(run.optString("status"))
                .append("\n  Natija: ")
                .append(run.optString("conclusion", "hali tugamagan"))
                .append("\n  Run ID: ")
                .append(run.optLong("id"))
                .append("\n\n")
        }

        return result.toString()
    }
}
