
package uz.nora.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class NoraConversationMemory(context: Context) {

    companion object {
        private const val PREFS = "nora_conversation_memory"
        private const val HISTORY_KEY = "history"
        private const val MAX_MESSAGES = 30
        private const val MAX_MESSAGE_LENGTH = 6000
        private const val MAX_CONTEXT_LENGTH = 12000
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
        if (clean.isBlank()) return

        val history = getHistoryArray()

        history.put(
            JSONObject()
                .put("role", role)
                .put("content", clean.take(MAX_MESSAGE_LENGTH))
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
    fun getContextText(): String {
        val history = getHistoryArray()

        val result = buildString {
            for (i in 0 until history.length()) {
                val item = history.optJSONObject(i) ?: continue
                val role = item.optString("role")
                val content = item.optString("content")

                if (
                    (role == "user" || role == "assistant") &&
                    content.isNotBlank()
                ) {
                    append(role)
                    append(": ")
                    append(content)
                    append('\n')
                }
            }
        }

        return if (result.length > MAX_CONTEXT_LENGTH) {
            result.takeLast(MAX_CONTEXT_LENGTH)
        } else {
            result
        }
    }

    @Synchronized
    fun clear() {
        prefs.edit()
            .remove(HISTORY_KEY)
            .commit()
    }

    @Synchronized
    fun count(): Int = getHistoryArray().length()

    private fun getHistoryArray(): JSONArray {
        val saved = prefs.getString(HISTORY_KEY, null)

        return try {
            if (saved.isNullOrBlank()) {
                JSONArray()
            } else {
                val source = JSONArray(saved)
                val clean = JSONArray()

                val start = maxOf(0, source.length() - MAX_MESSAGES)

                for (i in start until source.length()) {
                    val item = source.optJSONObject(i) ?: continue
                    val role = item.optString("role")
                    val content = item.optString("content").trim()

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

                clean
            }
        } catch (_: Exception) {
            JSONArray()
        }
    }
}
