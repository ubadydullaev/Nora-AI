
package uz.nora.ai

import android.content.Context
import java.util.UUID

class NoraGitHubChangeManager(context: Context) {

    private val app = context.applicationContext
    private val agent = NoraOnlineAgent(app)

    private val pendingChanges =
        LinkedHashMap<String, NoraCodeChange>()

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

        val current = agent.readGitHubFile(
            owner,
            repo,
            path
        )

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

        val id = UUID.randomUUID().toString()

        synchronized(pendingChanges) {
            pendingChanges[id] = change

            while (pendingChanges.size > 10) {
                val first = pendingChanges.keys.first()
                pendingChanges.remove(first)
            }
        }

        return Pair(id, change.preview())
    }

    suspend fun commitChange(
        changeId: String,
        confirmedByUser: Boolean
    ): String {

        require(confirmedByUser) {
            "Commit uchun foydalanuvchi tasdig'i kerak"
        }

        val change = synchronized(pendingChanges) {
            pendingChanges.remove(changeId)
        } ?: error(
            "O'zgarish topilmadi yoki muddati tugagan. Qayta tayyorlang."
        )

        require(change.validate()) {
            "O'zgarish ma'lumotlari yaroqsiz"
        }

        val latest = agent.readGitHubFile(
            change.owner,
            change.repo,
            change.path
        )

        require(latest.content == change.oldContent) {
            "GitHub fayli tayyorlanganidan keyin o'zgargan. " +
                "Xavfsizlik uchun commit bekor qilindi."
        }

        return agent.updateGitHubFile(
            owner = change.owner,
            repo = change.repo,
            path = change.path,
            newContent = change.newContent,
            commitMessage = change.commitMessage,
            confirmedByUser = true
        )
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
}
