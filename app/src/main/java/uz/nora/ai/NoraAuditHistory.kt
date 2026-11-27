package uz.nora.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NoraAuditHistory(context: Context) {

    private val app = context.applicationContext

    private val legacyPrefs = app.getSharedPreferences(
        LEGACY_PREFS,
        Context.MODE_PRIVATE
    )

    companion object {
        private const val LEGACY_PREFS = "nora_audit_history"
        private const val SECURE_KEY = "nora_audit_history_v2"

        private const val MAX_REPORT_LENGTH = 30000
        private const val MAX_PREVIEW_LENGTH = 1800
        private const val MAX_REPOSITORIES = 30
        private const val REPORTS_PER_REPOSITORY = 2
    }

    private fun repositoryKey(
        owner: String,
        repo: String
    ): String {
        require(owner.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) {
            "GitHub egasi noto'g'ri"
        }

        require(repo.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) {
            "Repository nomi noto'g'ri"
        }

        return "${owner.lowercase(Locale.ROOT)}_${repo.lowercase(Locale.ROOT)}"
            .replace(Regex("[^a-z0-9_.-]"), "_")
    }

    private fun readDatabase(): JSONObject {
        val encrypted = NoraSecureStore.read(app, SECURE_KEY)

        if (!encrypted.isNullOrBlank()) {
            try {
                return JSONObject(encrypted)
            } catch (_: Exception) {
                NoraSecureStore.delete(app, SECURE_KEY)
            }
        }

        return migrateLegacyHistory()
    }

    private fun migrateLegacyHistory(): JSONObject {
        val database = JSONObject()
        var migrated = false

        synchronized(legacyPrefs) {
            val oldEntries = legacyPrefs.all

            for ((oldKey, oldValue) in oldEntries) {
                if (!oldKey.startsWith("audit_")) continue

                val raw = oldValue as? String ?: continue

                try {
                    val oldArray = JSONArray(raw)
                    val cleaned = JSONArray()

                    for (i in 0 until minOf(
                        oldArray.length(),
                        REPORTS_PER_REPOSITORY
                    )) {
                        val item = oldArray.optJSONObject(i) ?: continue
                        val report = item.optString("report")
                            .take(MAX_REPORT_LENGTH)

                        if (report.isBlank()) continue

                        cleaned.put(
                            JSONObject()
                                .put("time", item.optLong("time", 0L))
                                .put("report", report)
                        )
                    }

                    if (cleaned.length() > 0) {
                        val safeKey = oldKey.removePrefix("audit_")
                            .take(220)

                        database.put(safeKey, cleaned)
                        migrated = true
                    }
                } catch (_: Exception) {
                    // Buzilgan eski yozuv migratsiyaga qo'shilmaydi.
                }
            }

            if (migrated) {
                try {
                    NoraSecureStore.save(
                        app,
                        SECURE_KEY,
                        database.toString()
                    )

                    // Shifrlangan nusxa saqlangandan keyingina
                    // eski ochiq SharedPreferences ma'lumoti o'chiriladi.
                    legacyPrefs.edit().clear().commit()
                } catch (_: Exception) {
                    // Migratsiya saqlanmasa, eski yozuvlar saqlanib qoladi.
                }
            }
        }

        return database
    }

    @Synchronized
    fun getLatest(
        owner: String,
        repo: String
    ): String? {
        return getReport(owner, repo, 0)
    }

    @Synchronized
    fun getPrevious(
        owner: String,
        repo: String
    ): String? {
        return getReport(owner, repo, 1)
    }

    private fun getReport(
        owner: String,
        repo: String,
        index: Int
    ): String? {
        val key = repositoryKey(owner, repo)
        val database = readDatabase()
        val reports = database.optJSONArray(key) ?: return null

        return reports.optJSONObject(index)
            ?.optString("report")
            ?.takeIf { it.isNotBlank() }
    }

    @Synchronized
    fun getHistoryInfo(
        owner: String,
        repo: String
    ): String {
        val key = repositoryKey(owner, repo)
        val reports = readDatabase().optJSONArray(key)
            ?: return "Bu repository uchun audit tarixi yo'q."

        if (reports.length() == 0) {
            return "Bu repository uchun audit tarixi yo'q."
        }

        val formatter = SimpleDateFormat(
            "dd.MM.yyyy HH:mm:ss",
            Locale.getDefault()
        )

        return buildString {
            appendLine("AUDIT TARIXI: $owner/$repo")
            appendLine("Saqlangan hisobotlar: ${reports.length()}")
            appendLine("Saqlash: Android Keystore AES-GCM shifrlashi")
            appendLine()

            for (i in 0 until reports.length()) {
                val item = reports.optJSONObject(i) ?: continue
                val time = item.optLong("time", 0L)
                val report = item.optString("report")

                val readableTime = if (time > 0L) {
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
    }

    @Synchronized
    fun save(
        owner: String,
        repo: String,
        report: String
    ) {
        if (report.isBlank()) return

        val key = repositoryKey(owner, repo)
        val database = readDatabase()

        val previous = database.optJSONArray(key)
        val updated = JSONArray()

        updated.put(
            JSONObject()
                .put("time", System.currentTimeMillis())
                .put("report", report.take(MAX_REPORT_LENGTH))
        )

        if (previous != null) {
            for (i in 0 until minOf(
                previous.length(),
                REPORTS_PER_REPOSITORY - 1
            )) {
                val item = previous.optJSONObject(i) ?: continue
                val oldReport = item.optString("report")

                if (oldReport.isBlank()) continue

                updated.put(
                    JSONObject()
                        .put("time", item.optLong("time", 0L))
                        .put(
                            "report",
                            oldReport.take(MAX_REPORT_LENGTH)
                        )
                )
            }
        }

        database.put(key, updated)

        // Eski repository yozuvlari ichidan eng eskilarini chiqaramiz.
        while (database.length() > MAX_REPOSITORIES) {
            val names = database.keys()
            var oldestKey: String? = null
            var oldestTime = Long.MAX_VALUE

            while (names.hasNext()) {
                val candidate = names.next()
                val items = database.optJSONArray(candidate)
                val time = items
                    ?.optJSONObject(0)
                    ?.optLong("time", 0L)
                    ?: 0L

                if (time < oldestTime) {
                    oldestTime = time
                    oldestKey = candidate
                }
            }

            if (oldestKey == null) break
            database.remove(oldestKey)
        }

        NoraSecureStore.save(
            app,
            SECURE_KEY,
            database.toString()
        )
    }

    @Synchronized
    fun clear(
        owner: String,
        repo: String
    ) {
        val key = repositoryKey(owner, repo)
        val database = readDatabase()

        database.remove(key)

        if (database.length() == 0) {
            NoraSecureStore.delete(app, SECURE_KEY)
        } else {
            NoraSecureStore.save(
                app,
                SECURE_KEY,
                database.toString()
            )
        }

        // Eski ochiq saqlashda shu repository yozuvi qolgan bo'lsa,
        // uni ham olib tashlaymiz.
        val legacyKey = "audit_$key"
        legacyPrefs.edit()
            .remove(legacyKey)
            .commit()
    }
}
