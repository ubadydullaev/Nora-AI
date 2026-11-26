
package uz.nora.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

class NoraConversationMemory(context: Context) {

    private val prefs = context.getSharedPreferences(
        "nora_conversation_memory",
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
        val history = getHistoryArray()

        history.put(
            JSONObject()
                .put("role", role)
                .put("content", content.take(6000))
        )

        while (history.length() > 30) {
            history.remove(0)
        }

        prefs.edit().putString("history", history.toString()).apply()
    }

    @Synchronized
    fun getHistory(): JSONArray {
        return getHistoryArray()
    }

    fun getContextText(): String {
        val history = getHistory()
        return buildString {
            for (i in 0 until history.length()) {
                val item = history.optJSONObject(i) ?: continue
                append(item.optString("role"))
                append(": ")
                append(item.optString("content"))
                append("\n")
            }
        }
    }

    @Synchronized
    fun clear() {
        prefs.edit().remove("history").apply()
    }

    fun count(): Int = getHistory().length()

    private fun getHistoryArray(): JSONArray {
        val saved = prefs.getString("history", null)
        return try {
            if (saved.isNullOrBlank()) JSONArray() else JSONArray(saved)
        } catch (_: Exception) {
            JSONArray()
        }
    }
}
