
package uz.nora.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NoraAuditHistory(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("nora_audit_history", Context.MODE_PRIVATE)

    private fun key(owner: String, repo: String): String {
        val safeOwner = owner.lowercase()
            .replace(Regex("[^a-z0-9_.-]"), "_")

        val safeRepo = repo.lowercase()
            .replace(Regex("[^a-z0-9_.-]"), "_")

        return "audit_${safeOwner}_${safeRepo}"
    }

    @Synchronized
    fun getLatest(owner: String, repo: String): String? {
        return getReport(owner, repo, 0)
    }

    @Synchronized
    fun getPrevious(owner: String, repo: String): String? {
        return getReport(owner, repo, 1)
    }

    private fun getReport(
        owner: String,
        repo: String,
        index: Int
    ): String? {
        val raw = prefs.getString(key(owner, repo), null)
            ?: return null

        return try {
            JSONArray(raw)
                .optJSONObject(index)
                ?.optString("report")
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun getHistoryInfo(owner: String, repo: String): String {
        val raw = prefs.getString(key(owner, repo), null)
            ?: return "Bu repository uchun audit tarixi yo'q."

        return try {
            val history = JSONArray(raw)

            if (history.length() == 0) {
                return "Bu repository uchun audit tarixi yo'q."
            }

            val formatter = SimpleDateFormat(
                "dd.MM.yyyy HH:mm:ss",
                Locale.getDefault()
            )

            buildString {
                appendLine("AUDIT TARIXI: $owner/$repo")
                appendLine("Saqlangan hisobotlar: ${history.length()}")
                appendLine()

                for (i in 0 until history.length()) {
                    val item = history.optJSONObject(i) ?: continue
                    val time = item.optLong("time")
                    val report = item.optString("report")

                    val readableTime = if (time > 0) {
                        formatter.format(Date(time))
                    } else {
                        "Vaqt noma'lum"
                    }

                    appendLine("TEKSHIRUV: ${i + 1}")
                    appendLine("Vaqt: $readableTime")
                    appendLine("------------------------------")
                    appendLine(report.take(MAX_PREVIEW_LENGTH))
                    appendLine()
                }
            }
        } catch (_: Exception) {
            "Audit tarixi ma'lumotlarini o'qib bo'lmadi."
        }
    }

    @Synchronized
    fun save(owner: String, repo: String, report: String) {
        if (report.isBlank()) return

        val storageKey = key(owner, repo)
        val previous = prefs.getString(storageKey, null)

        val oldLatest = try {
            previous?.let { JSONArray(it) }
                ?.optJSONObject(0)
        } catch (_: Exception) {
            null
        }

        val updated = JSONArray()

        updated.put(
            JSONObject().apply {
                put("time", System.currentTimeMillis())
                put("report", report.take(MAX_REPORT_LENGTH))
            }
        )

        // Oxirgi hisobot va undan oldingi bitta hisobot saqlanadi.
        if (oldLatest != null) {
            updated.put(oldLatest)
        }

        val saved = prefs.edit()
            .putString(storageKey, updated.toString())
            .commit()

        check(saved) {
            "Audit tarixini saqlab bo'lmadi"
        }
    }

    @Synchronized
    fun clear(owner: String, repo: String) {
        val removed = prefs.edit()
            .remove(key(owner, repo))
            .commit()

        check(removed) {
            "Audit tarixini tozalab bo'lmadi"
        }
    }

    companion object {
        private const val MAX_REPORT_LENGTH = 30000
        private const val MAX_PREVIEW_LENGTH = 1800
    }
}
