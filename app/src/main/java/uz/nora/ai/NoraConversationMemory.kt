package uz.nora.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class NoraConversationMemory(context: Context) {

    companion object {
        private const val PREFS = "nora_conversation_memory"
        private const val HISTORY_KEY = "history"

        private const val MAX_MESSAGES = 60
        private const val MAX_MESSAGE_LENGTH = 6000
        private const val MAX_CONTEXT_LENGTH = 18000
    }

    private val prefs = context.applicationContext.getSharedPreferences(
        PREFS,
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun addUserMessage(message: String) {
        addMessage("user", message)
    }

    @Synchronized
    fun addAssistantMessage(message: String) {
        addMessage("assistant", message)
    }

    private fun addMessage(role: String, content: String) {
        if (role != "user" && role != "assistant") return

        val clean = content.trim()
            .replace("\u0000", "")
            .take(MAX_MESSAGE_LENGTH)

        if (clean.isBlank()) return

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

        prefs.edit()
            .putString(HISTORY_KEY, history.toString())
            .apply()
    }

    @Synchronized
    fun getHistory(): JSONArray {
        return getHistoryArray()
    }

    @Synchronized
    fun getLastMessages(limit: Int): JSONArray {
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

    @Synchronized
    fun getContextText(): String {
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
            ) {
                continue
            }

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

    @Synchronized
    fun getContextText(maxLength: Int): String {
        val safeLength = maxLength.coerceIn(0, MAX_CONTEXT_LENGTH)
        if (safeLength == 0) return ""

        val context = getContextText()
        if (context.length <= safeLength) return context

        return context.takeLast(safeLength)
    }

    @Synchronized
    fun clear() {
        prefs.edit()
            .remove(HISTORY_KEY)
            .commit()
    }

    @Synchronized
    fun count(): Int {
        return getHistoryArray().length()
    }

    @Synchronized
    fun getLastUserMessage(): String? {
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

    private fun getHistoryArray(): JSONArray {
        val saved = prefs.getString(HISTORY_KEY, null)

        return try {
            if (saved.isNullOrBlank()) {
                return JSONArray()
            }

            val source = JSONArray(saved)
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
                            .put(
                                "content",
                                content.take(MAX_MESSAGE_LENGTH)
                            )
                            .put(
                                "time",
                                item.optLong("time", 0L)
                            )
                    )
                }
            }

            clean
        } catch (_: Exception) {
            JSONArray()
        }
    }
}
