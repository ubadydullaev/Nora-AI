package uz.nora.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

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
            val history = JSONArray(raw)
            history.optJSONObject(index)
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

            buildString {
                appendLine("AUDIT TARIXI: $owner/$repo")
                appendLine()

                for (i in 0 until history.length()) {
                    val item = history.optJSONObject(i) ?: continue
                    val time = item.optLong("time")
                    val report = item.optString("report")

                    appendLine("Tekshiruv: ${i + 1}")
                    appendLine("Vaqt: $time")
                    appendLine(
                        report.take(1800)
                    )
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

        if (oldLatest != null) {
            updated.put(oldLatest)
        }

        prefs.edit()
            .putString(storageKey, updated.toString())
            .commit()
    }

    @Synchronized
    fun clear(owner: String, repo: String) {
        prefs.edit()
            .remove(key(owner, repo))
            .commit()
    }

    companion object {
        private const val MAX_REPORT_LENGTH = 30000
    }
}
