package uz.nora.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class NoraConversationMemory(context: Context) {

    companion object {
        private const val SECURE_KEY = "nora_conversation_history"
        private const val LEGACY_PREFS = "nora_conversation_memory"
        private const val LEGACY_KEY = "history"

        private const val MAX_MESSAGES = 60
        private const val MAX_MESSAGE_LENGTH = 6000
        private const val MAX_CONTEXT_LENGTH = 18000
    }

    private val app = context.applicationContext
    private val lock = Any()

    init {
        migrateLegacyHistory()
    }

    private fun migrateLegacyHistory() {
        synchronized(lock) {
            val existing = NoraSecureStore.read(app, SECURE_KEY)
            if (!existing.isNullOrBlank()) return

            val legacy = app.getSharedPreferences(
                LEGACY_PREFS,
                Context.MODE_PRIVATE
            )

            val oldHistory = legacy.getString(LEGACY_KEY, null)
            if (oldHistory.isNullOrBlank()) return

            try {
                val cleaned = sanitize(JSONArray(oldHistory))
                NoraSecureStore.save(app, SECURE_KEY, cleaned.toString())

                legacy.edit()
                    .remove(LEGACY_KEY)
                    .apply()
            } catch (_: Exception) {
                // Eski tarix buzilgan bo'lsa, uni saqlashga urinmaymiz.
            }
        }
    }

    fun addUserMessage(message: String) {
        addMessage("user", message)
    }

    fun addAssistantMessage(message: String) {
        addMessage("assistant", message)
    }

    private fun addMessage(role: String, content: String) {
        if (role != "user" && role != "assistant") return

        val clean = content.trim()
            .replace("\u0000", "")
            .take(MAX_MESSAGE_LENGTH)

        if (clean.isBlank()) return

        synchronized(lock) {
            val history = getHistoryArray()

            history.put(
                JSONObject()
                    .put("role", role)
                    .put("content", clean)
                    .put("time", System.currentTimeMillis())
            )

            while (history.length() > MAX_MESSAGES) {
                history.remove(0)
            }

            NoraSecureStore.save(
                app,
                SECURE_KEY,
                history.toString()
            )
        }
    }

    fun getHistory(): JSONArray {
        synchronized(lock) {
            return getHistoryArray()
        }
    }

    fun getLastMessages(limit: Int): JSONArray {
        synchronized(lock) {
            val safeLimit = limit.coerceIn(0, MAX_MESSAGES)
            val history = getHistoryArray()
            val result = JSONArray()
            val start = maxOf(0, history.length() - safeLimit)

            for (i in start until history.length()) {
                val item = history.optJSONObject(i) ?: continue

                result.put(
                    JSONObject()
                        .put("role", item.optString("role"))
                        .put("content", item.optString("content"))
                        .put("time", item.optLong("time", 0L))
                )
            }

            return result
        }
    }

    fun getContextText(): String {
        synchronized(lock) {
            val history = getHistoryArray()
            val selected = mutableListOf<String>()
            var totalLength = 0

            for (i in history.length() - 1 downTo 0) {
                val item = history.optJSONObject(i) ?: continue
                val role = item.optString("role")
                val content = item.optString("content").trim()

                if (
                    (role != "user" && role != "assistant") ||
                    content.isBlank()
                ) continue

                val line = "$role: $content"
                val required = line.length + 1

                if (totalLength + required > MAX_CONTEXT_LENGTH) {
                    break
                }

                selected.add(line)
                totalLength += required
            }

            return selected.asReversed().joinToString("\n")
        }
    }

    fun getContextText(maxLength: Int): String {
        val safeLength = maxLength.coerceIn(0, MAX_CONTEXT_LENGTH)
        if (safeLength == 0) return ""

        val context = getContextText()
        if (context.length <= safeLength) return context

        return context.takeLast(safeLength)
    }

    fun clear() {
        synchronized(lock) {
            NoraSecureStore.delete(app, SECURE_KEY)
        }
    }

    fun count(): Int {
        synchronized(lock) {
            return getHistoryArray().length()
        }
    }

    fun getLastUserMessage(): String? {
        synchronized(lock) {
            val history = getHistoryArray()

            for (i in history.length() - 1 downTo 0) {
                val item = history.optJSONObject(i) ?: continue

                if (item.optString("role") == "user") {
                    return item.optString("content")
                        .takeIf { it.isNotBlank() }
                }
            }

            return null
        }
    }

    private fun getHistoryArray(): JSONArray {
        val saved = NoraSecureStore.read(app, SECURE_KEY)

        if (saved.isNullOrBlank()) return JSONArray()

        return try {
            sanitize(JSONArray(saved))
        } catch (_: Exception) {
            JSONArray()
        }
    }

    private fun sanitize(source: JSONArray): JSONArray {
        val clean = JSONArray()
        val start = maxOf(0, source.length() - MAX_MESSAGES)

        for (i in start until source.length()) {
            val item = source.optJSONObject(i) ?: continue
            val role = item.optString("role")
            val content = item.optString("content")
                .trim()
                .replace("\u0000", "")

            if (
                (role == "user" || role == "assistant") &&
                content.isNotBlank()
            ) {
                clean.put(
                    JSONObject()
                        .put("role", role)
                        .put("content", content.take(MAX_MESSAGE_LENGTH))
                        .put("time", item.optLong("time", 0L))
                )
            }
        }

        return clean
    }
}
