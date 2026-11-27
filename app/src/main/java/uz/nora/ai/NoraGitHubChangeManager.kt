package uz.nora.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class NoraGitHubChangeManager(context: Context) {

    private data class PendingChange(
        val change: NoraCodeChange,
        val createdAt: Long
    )

    private val app = context.applicationContext
    private val agent = NoraOnlineAgent(app)

    private val pendingChanges =
        LinkedHashMap<String, PendingChange>()

    companion object {
        private const val STORAGE_KEY = "pending_github_changes"
        private const val MAX_PENDING = 3
        private const val MAX_CONTENT_LENGTH = 200_000
        private const val EXPIRY_MS = 15 * 60 * 1000L
    }

    init {
        restorePending()
    }

    private fun restorePending() {
        val raw = NoraSecureStore.read(app, STORAGE_KEY)
            ?: return

        try {
            val array = JSONArray(raw)
            val now = System.currentTimeMillis()

            synchronized(pendingChanges) {
                pendingChanges.clear()

                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue

                    val id = item.optString("id")
                    val createdAt = item.optLong("createdAt")

                    val change = NoraCodeChange(
                        owner = item.optString("owner"),
                        repo = item.optString("repo"),
                        path = item.optString("path"),
                        oldContent = item.optString("oldContent"),
                        newContent = item.optString("newContent"),
                        commitMessage = item.optString("commitMessage"),
                        expectedSha = item.optString("expectedSha")
                    )

                    if (
                        id.isBlank() ||
                        createdAt <= 0 ||
                        now - createdAt > EXPIRY_MS ||
                        !change.validate() ||
                        change.newContent.length > MAX_CONTENT_LENGTH
                    ) {
                        continue
                    }

                    pendingChanges[id] = PendingChange(
                        change = change,
                        createdAt = createdAt
                    )
                }

                while (pendingChanges.size > MAX_PENDING) {
                    pendingChanges.remove(
                        pendingChanges.keys.first()
                    )
                }

                persistLocked()
            }
        } catch (_: Exception) {
            synchronized(pendingChanges) {
                pendingChanges.clear()
                NoraSecureStore.delete(app, STORAGE_KEY)
            }
        }
    }

    private fun persistLocked() {
        if (pendingChanges.isEmpty()) {
            NoraSecureStore.delete(app, STORAGE_KEY)
            return
        }

        val array = JSONArray()

        pendingChanges.forEach { (id, pending) ->
            val change = pending.change

            array.put(
                JSONObject().apply {
                    put("id", id)
                    put("createdAt", pending.createdAt)
                    put("owner", change.owner)
                    put("repo", change.repo)
                    put("path", change.path)
                    put("oldContent", change.oldContent)
                    put("newContent", change.newContent)
                    put("commitMessage", change.commitMessage)
                    put("expectedSha", change.expectedSha)
                }
            )
        }

        NoraSecureStore.save(
            app,
            STORAGE_KEY,
            array.toString()
        )
    }

    suspend fun prepareChange(
        owner: String,
        repo: String,
        path: String,
        newContent: String,
        commitMessage: String
    ): Pair<String, String> {

        require(newContent.isNotBlank()) {
            "Yangi kod bo'sh"
        }

        require(newContent.length <= MAX_CONTENT_LENGTH) {
            "Yangi fayl 200 KB dan katta. Uni kichikroq qismlarga ajrating."
        }

        require(commitMessage.isNotBlank()) {
            "Commit xabari kerak"
        }

        val current = agent.readGitHubFile(
            owner,
            repo,
            path
        )

        require(current.content != newContent) {
            "Yangi kod eski fayl bilan bir xil. O'zgarish yo'q."
        }

        val change = NoraCodeChange(
            owner = owner,
            repo = repo,
            path = path,
            oldContent = current.content,
            newContent = newContent,
            commitMessage = commitMessage,
            expectedSha = current.sha
        )

        require(change.validate()) {
            "O'zgarish ma'lumotlari noto'g'ri"
        }

        val id = UUID.randomUUID().toString()

        synchronized(pendingChanges) {
            removeExpiredLocked()

            pendingChanges[id] = PendingChange(
                change = change,
                createdAt = System.currentTimeMillis()
            )

            while (pendingChanges.size > MAX_PENDING) {
                pendingChanges.remove(
                    pendingChanges.keys.first()
                )
            }

            persistLocked()
        }

        return id to change.preview()
    }

    suspend fun commitChange(
        changeId: String,
        confirmedByUser: Boolean
    ): String {

        require(confirmedByUser) {
            "Commit uchun foydalanuvchi tasdig'i kerak"
        }

        require(changeId.isNotBlank()) {
            "O'zgarish ID bo'sh"
        }

        val pending = synchronized(pendingChanges) {
            removeExpiredLocked()
            pendingChanges[changeId]
        } ?: error(
            "O'zgarish topilmadi yoki 15 daqiqalik muddati tugagan. Qayta tayyorlang."
        )

        val change = pending.change

        require(change.validate()) {
            "O'zgarish ma'lumotlari yaroqsiz"
        }

        val result = agent.updateGitHubFile(
            owner = change.owner,
            repo = change.repo,
            path = change.path,
            newContent = change.newContent,
            commitMessage = change.commitMessage,
            confirmedByUser = true,
            expectedSha = change.expectedSha
        )

        synchronized(pendingChanges) {
            pendingChanges.remove(changeId)
            persistLocked()
        }

        return result
    }

    fun findPending(
        owner: String,
        repo: String
    ): Pair<String, String>? {
        return synchronized(pendingChanges) {
            removeExpiredLocked()

            val found = pendingChanges.entries
                .lastOrNull {
                    it.value.change.owner.equals(owner, true) &&
                        it.value.change.repo.equals(repo, true)
                } ?: return@synchronized null

            found.key to found.value.change.preview()
        }
    }

    fun cancelPendingForRepo(
        owner: String,
        repo: String
    ): Boolean {
        return synchronized(pendingChanges) {
            removeExpiredLocked()

            val ids = pendingChanges.filterValues {
                it.change.owner.equals(owner, true) &&
                    it.change.repo.equals(repo, true)
            }.keys.toList()

            if (ids.isEmpty()) {
                return@synchronized false
            }

            ids.forEach { pendingChanges.remove(it) }
            persistLocked()
            true
        }
    }

    fun cancelChange(changeId: String): Boolean {
        return synchronized(pendingChanges) {
            val removed = pendingChanges.remove(changeId) != null
            if (removed) persistLocked()
            removed
        }
    }

    fun clearPendingChanges() {
        synchronized(pendingChanges) {
            pendingChanges.clear()
            persistLocked()
        }
    }

    fun pendingCount(): Int {
        return synchronized(pendingChanges) {
            removeExpiredLocked()
            pendingChanges.size
        }
    }

    private fun removeExpiredLocked() {
        val now = System.currentTimeMillis()

        val expired = pendingChanges.filterValues {
            now - it.createdAt > EXPIRY_MS
        }.keys.toList()

        if (expired.isNotEmpty()) {
            expired.forEach {
                pendingChanges.remove(it)
            }
            persistLocked()
        }
    }
}
