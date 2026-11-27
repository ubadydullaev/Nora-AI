
package uz.nora.ai

import android.content.Context
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
        private const val MAX_PENDING = 10
        private const val EXPIRY_MS = 15 * 60 * 1000L
    }

    suspend fun prepareChange(
        owner: String,
        repo: String,
        path: String,
        newContent: String,
        commitMessage: String
    ): Pair<String, String> {

        require(owner.isNotBlank()) { "GitHub egasi kerak" }
        require(repo.isNotBlank()) { "Repository nomi kerak" }
        require(path.isNotBlank()) { "Fayl yo'li kerak" }
        require(newContent.isNotBlank()) { "Yangi kod bo'sh" }
        require(newContent.length <= 500_000) {
            "Yangi fayl juda katta"
        }
        require(commitMessage.isNotBlank()) {
            "Commit xabari kerak"
        }

        val current = agent.readGitHubFile(owner, repo, path)

        val change = NoraCodeChange(
            owner = owner,
            repo = repo,
            path = path,
            oldContent = current.content,
            newContent = newContent,
            commitMessage = commitMessage
        )

        require(change.validate()) {
            "O'zgarish ma'lumotlari noto'g'ri"
        }

        require(current.content != newContent) {
            "Yangi kod eski fayl bilan bir xil. O'zgarish yo'q."
        }

        val id = UUID.randomUUID().toString()

        synchronized(pendingChanges) {
            removeExpiredLocked()

            pendingChanges[id] = PendingChange(
                change = change,
                createdAt = System.currentTimeMillis()
            )

            while (pendingChanges.size > MAX_PENDING) {
                val first = pendingChanges.keys.first()
                pendingChanges.remove(first)
            }
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

        // GitHub fayli tayyorlanganidan beri o'zgarmaganini tekshirish.
        val latest = agent.readGitHubFile(
            change.owner,
            change.repo,
            change.path
        )

        require(latest.content == change.oldContent) {
            "GitHub fayli tayyorlanganidan keyin o'zgargan. " +
                "Xavfsizlik uchun commit bekor qilindi. Qayta tayyorlang."
        }

        // Faqat muvaffaqiyatli commitdan keyin pending yozuv o'chiriladi.
        val result = agent.updateGitHubFile(
            owner = change.owner,
            repo = change.repo,
            path = change.path,
            newContent = change.newContent,
            commitMessage = change.commitMessage,
            confirmedByUser = true
        )

        synchronized(pendingChanges) {
            pendingChanges.remove(changeId)
        }

        return result
    }

    fun cancelChange(changeId: String): Boolean {
        return synchronized(pendingChanges) {
            pendingChanges.remove(changeId) != null
        }
    }

    fun clearPendingChanges() {
        synchronized(pendingChanges) {
            pendingChanges.clear()
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

        expired.forEach { pendingChanges.remove(it) }
    }
}
