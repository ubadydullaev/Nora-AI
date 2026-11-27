
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

class NoraGitHubTools(context: Context) {

    private val app = context.applicationContext

    companion object {
        private const val MAX_FILE_SIZE = 500000
        private const val MAX_LIST_ITEMS = 500
        private const val MAX_LIST_LENGTH = 30000
    }

    suspend fun listFiles(
        owner: String,
        repo: String
    ): String = withContext(Dispatchers.IO) {

        validateRepository(owner, repo)

        val url =
            "https://api.github.com/repos/$owner/$repo/git/trees/HEAD?recursive=1"

        val json = JSONObject(
            request(url)
        )

        if (json.optBoolean("truncated")) {
            error("Repository juda katta. Fayllar ro'yxati to'liq olinmadi.")
        }

        val tree = json.optJSONArray("tree")
            ?: error("GitHub fayllar ro'yxatini qaytarmadi.")

        val output = StringBuilder()
        var count = 0

        for (i in 0 until tree.length()) {
            if (count >= MAX_LIST_ITEMS) break

            val item = tree.optJSONObject(i) ?: continue
            if (item.optString("type") != "blob") continue

            val path = item.optString("path")
            if (path.isBlank()) continue

            val size = item.optLong("size", 0L)

            output.append("• ")
                .append(path)

            if (size > 0) {
                output.append(" (")
                    .append(size)
                    .append(" bytes)")
            }

            output.append('\n')
            count++

            if (output.length >= MAX_LIST_LENGTH) break
        }

        if (output.isEmpty()) {
            "Repository ichida fayllar topilmadi."
        } else {
            output.toString().take(MAX_LIST_LENGTH)
        }
    }

    suspend fun readFile(
        owner: String,
        repo: String,
        path: String
    ): String = withContext(Dispatchers.IO) {

        validateRepository(owner, repo)
        validatePath(path)

        val encodedPath = encodePath(path)

        val url =
            "https://api.github.com/repos/$owner/$repo/contents/$encodedPath"

        val json = JSONObject(request(url))

        require(json.optString("type") == "file") {
            "Bu manzil oddiy fayl emas."
        }

        val size = json.optLong("size", 0L)

        require(size <= MAX_FILE_SIZE) {
            "Fayl 500 KB dan katta. O'qish cheklangan."
        }

        val encoded = json.optString("content")

        if (encoded.isBlank()) {
            error("Fayl mazmuni bo'sh yoki GitHub uni qaytarmadi.")
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
            error("Faylni UTF-8 formatida o'qib bo'lmadi.")
        }

        require(content.length <= MAX_FILE_SIZE) {
            "Fayl hajmi ruxsat etilgan chegaradan katta."
        }

        content
    }

    suspend fun latestActions(
        owner: String,
        repo: String
    ): String = withContext(Dispatchers.IO) {

        validateRepository(owner, repo)

        val url =
            "https://api.github.com/repos/$owner/$repo/actions/runs?per_page=10"

        val json = JSONObject(request(url))
        val runs = json.optJSONArray("workflow_runs")
            ?: JSONArray()

        if (runs.length() == 0) {
            return@withContext "GitHub Actions runlari topilmadi."
        }

        val output = StringBuilder()

        for (i in 0 until minOf(runs.length(), 10)) {
            val run = runs.optJSONObject(i) ?: continue

            val name = run.optString("name", "Noma'lum workflow")
            val status = run.optString("status", "noma'lum")
            val conclusion = run.optString("conclusion", "hali tugamagan")
            val branch = run.optString("head_branch", "noma'lum")
            val created = run.optString("created_at", "")
            val runNumber = run.optLong("run_number", 0L)
            val htmlUrl = run.optString("html_url", "")

            output.append("Workflow: ")
                .append(name)
                .append('\n')

            output.append("Run: #")
                .append(runNumber)
                .append('\n')

            output.append("Holat: ")
                .append(status)
                .append('\n')

            output.append("Natija: ")
                .append(conclusion)
                .append('\n')

            output.append("Branch: ")
                .append(branch)
                .append('\n')

            output.append("Vaqt: ")
                .append(created)
                .append('\n')

            if (htmlUrl.startsWith("https://github.com/")) {
                output.append("Havola: ")
                    .append(htmlUrl)
                    .append('\n')
            }

            output.append("--------------------\n")
        }

        output.toString()
    }

    private fun validateRepository(
        owner: String,
        repo: String
    ) {
        val pattern = Regex("^[A-Za-z0-9_.-]{1,100}$")

        require(pattern.matches(owner)) {
            "GitHub egasi noto'g'ri."
        }

        require(pattern.matches(repo)) {
            "Repository nomi noto'g'ri."
        }
    }

    private fun validatePath(path: String) {
        require(path.isNotBlank() && path.length <= 500) {
            "Fayl yo'li noto'g'ri."
        }

        require(!path.startsWith("/") && !path.contains('\\')) {
            "Fayl yo'li xavfsiz emas."
        }

        require(
            path.split("/").none {
                it.isBlank() || it == "." || it == ".."
            }
        ) {
            "Fayl yo'lida ruxsat etilmagan qism bor."
        }
    }

    private fun encodePath(path: String): String {
        return path.split("/").joinToString("/") {
            URLEncoder.encode(it, "UTF-8")
                .replace("+", "%20")
        }
    }

    private fun request(url: String): String {
        val parsed = URL(url)

        require(
            parsed.protocol.equals("https", true) &&
                parsed.host.equals("api.github.com", true) &&
                parsed.userInfo == null
        ) {
            "Faqat GitHub HTTPS API manziliga ulanish mumkin."
        }

        val token = NoraSecureStore.read(app, "github_token")
            ?.takeIf { it.isNotBlank() }
            ?: error("GitHub token sozlanmagan.")

        val connection =
            parsed.openConnection() as HttpURLConnection

        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 20000
            connection.readTimeout = 45000
            connection.instanceFollowRedirects = false
            connection.useCaches = false

            connection.setRequestProperty(
                "Authorization",
                "Bearer $token"
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
                "User-Agent",
                "Nora-Android/1.0"
            )

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

            if (code !in 200..299) {
                val detail = try {
                    JSONObject(response)
                        .optString("message")
                } catch (_: Exception) {
                    ""
                }

                error(
                    "GitHub xatosi ($code): " +
                        detail.ifBlank { response.take(500) }
                )
            }

            require(response.length <= 2000000) {
                "GitHub javobi juda katta."
            }

            return response

        } finally {
            connection.disconnect()
        }
    }
}
