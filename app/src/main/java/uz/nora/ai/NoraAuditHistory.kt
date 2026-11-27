package uz.nora.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class NoraAuditHistory(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("nora_audit_history", Context.MODE_PRIVATE)

    private fun key(owner: String, repo: String): String {
        val safeOwner = owner.lowercase().replace(Regex("[^a-z0-9_.-]"), "_")
        val safeRepo = repo.lowercase().replace(Regex("[^a-z0-9_.-]"), "_")
        return "audit_${safeOwner}_${safeRepo}"
    }

    @Synchronized
    fun getLatest(owner: String, repo: String): String? {
        val raw = prefs.getString(key(owner, repo), null) ?: return null

        return try {
            val history = JSONArray(raw)
            if (history.length() == 0) null
            else history.optJSONObject(0)?.optString("report")
                ?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    @Synchronized
    fun save(owner: String, repo: String, report: String) {
        if (report.isBlank()) return

        val history = JSONArray()
        val previous = prefs.getString(key(owner, repo), null)

        if (!previous.isNullOrBlank()) {
            try {
                val oldHistory = JSONArray(previous)

                for (i in 0 until minOf(oldHistory.length(), 1)) {
                    val item = oldHistory.optJSONObject(i) ?: continue
                    history.put(item)
                }
            } catch (_: Exception) {
                // Eski tarix buzilgan bo'lsa, yangi tarixdan boshlanadi.
            }
        }

        val newEntry = JSONObject().apply {
            put("time", System.currentTimeMillis())
            put("report", report.take(MAX_REPORT_LENGTH))
        }

        val updated = JSONArray().apply {
            put(newEntry)

            for (i in 0 until history.length()) {
                put(history.getJSONObject(i))
            }
        }

        prefs.edit()
            .putString(key(owner, repo), updated.toString())
            .apply()
    }

    @Synchronized
    fun clear(owner: String, repo: String) {
        prefs.edit().remove(key(owner, repo)).apply()
    }

    companion object {
        private const val MAX_REPORT_LENGTH = 30000
    }
}
