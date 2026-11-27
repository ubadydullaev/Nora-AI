
package uz.nora.ai

import android.content.Context

class NoraRepositoryAudit(context: Context) {

    private val app = context.applicationContext
    private val github = NoraGitHubTools(app)
    private val online = NoraOnlineAgent(app)

    suspend fun audit(owner: String, repo: String): String {
        require(owner.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) {
            "GitHub egasi noto'g'ri"
        }
        require(repo.matches(Regex("[A-Za-z0-9_.-]{1,100}"))) {
            "Repo nomi noto'g'ri"
        }

        val listing = github.listFiles(owner, repo)

        if (
            listing.contains("fayllar topilmadi", ignoreCase = true) ||
            listing.contains("fayllar topilmadi.", ignoreCase = true)
        ) {
            return listing
        }

        val paths = listing.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("• ") }
            .map { it.removePrefix("• ").trim() }
            .filter { path ->
                path.endsWith(".kt", true) ||
                    path.endsWith(".kts", true) ||
                    path.endsWith(".xml", true) ||
                    path.endsWith(".properties", true) ||
                    path.endsWith(".yml", true) ||
                    path.endsWith(".yaml", true) ||
                    path.endsWith(".md", true)
            }
            .distinct()
            .toList()

        if (paths.isEmpty()) {
            return "Repository ichida tahlil qilinadigan loyiha fayllari topilmadi."
        }

        val priority = listOf(
            "app/src/main/AndroidManifest.xml",
            "settings.gradle.kts",
            "build.gradle.kts",
            "app/build.gradle.kts",
            "gradle.properties",
            ".github/workflows/android.yml",
            "README.md"
        )

        val selected = (
            priority.filter { it in paths } +
                paths.filter {
                    it.endsWith(".kt", true) ||
                        it.endsWith(".kts", true) ||
                        it.endsWith(".xml", true)
                }
            ).distinct().take(12)

        val files = StringBuilder()
        var loaded = 0
        var totalChars = 0
        val maxTotalChars = 42000

        for (path in selected) {
            if (totalChars >= maxTotalChars) break

            try {
                val content = github.readFile(owner, repo, path)
                if (
                    content.isBlank() ||
                    content.startsWith("Bu manzil oddiy fayl emas.")
                ) {
                    continue
                }

                val remaining = maxTotalChars - totalChars
                val safeContent = content.take(minOf(9000, remaining))

                files.append("\n\n===== FILE: ")
                    .append(path)
                    .append(" =====\n")
                    .append(safeContent)

                if (safeContent.length < content.length) {
                    files.append("\n[FAYL MAZMUNI QISQARTIRILDI]")
                }

                totalChars += safeContent.length
                loaded++
            } catch (e: Exception) {
                files.append("\n\n===== FILE: ")
                    .append(path)
                    .append(" =====\n")
                    .append("[O'QILMADI: ")
                    .append(e.message ?: "noma'lum xato")
                    .append("]")
            }
        }

        if (loaded == 0) {
            return "Repository fayllarini o'qib bo'lmadi. GitHub token ruxsatlarini tekshiring."
        }

        val prompt = """
Sen Nora AI Android loyihasining katta tajribali Kotlin muhandisisan.

REPOSITORY: $owner/$repo

Quyida repositorydan tanlab olingan fayllar berilgan.
Ularni bir-biriga solishtirib, o'zaro moslikni tekshir.

TEKSHIRUV:
1. Kotlin importlari, class va method nomlari o'zaro mosmi?
2. Bir faylda chaqirilgan method boshqa faylda mavjudmi?
3. Method argumentlari va qaytish turlari mosmi?
4. AndroidManifest permission, service va activity e'lonlari to'g'rimi?
5. Gradle plugin, dependency va Kotlin versiyalari mosmi?
6. Coroutine, lifecycle, service va Android 16 cheklovlari.
7. GitHub API, token xavfsizligi va foydalanuvchi tasdig'i.
8. Build yoki runtime xatolariga sabab bo'lishi mumkin bo'lgan joylar.
9. Takroriy, ishlatilmaydigan yoki bir-biriga zid kodlar.

JAVOBNI SHU TARTIBDA BER:
- TEKSHIRUV QAMROVI: nechta fayl tekshirildi.
- KRITIK XATOLAR: build yoki xavfsizlikni buzadigan xatolar.
- MUHIM XATOLAR: runtime va funksional muammolar.
- OGOHLANTIRISHLAR: ehtimoliy muammolar.
- FAYLLARARO MOSLIK: qaysi fayllar bir-biriga mos yoki mos emas.
- TUZATISH REJASI: aniq fayl yo'li va bajariladigan ish.
- UMUMIY BAHO: tayyor, qisman tayyor yoki tayyor emas.

MUHIM:
- Faqat berilgan fayllarni ko'rding. Ko'rilmagan fayllarni tekshirdim deb yozma.
- Taxminni aniq xatodan ajrat.
- Build ishga tushirilmagan bo'lsa, build muvaffaqiyatli deb aytma.
- Hech qanday faylni o'zgartirma va commit qilma.
- Javobni o'zbek tilida yoz.

TEKSHIRILGAN FAYLLAR:
$files
""".trimIndent()

        val key = NoraSecureStore.read(app, "ai_key")
        val endpoint = NoraSecureStore.read(app, "ai_endpoint")
        val model = NoraSecureStore.read(app, "ai_model")

        if (
            key.isNullOrBlank() ||
            endpoint.isNullOrBlank() ||
            model.isNullOrBlank()
        ) {
            error("Repository audit uchun Online AI sozlamalari kerak.")
        }

        return online.askAI(
            question = prompt,
            model = model,
            endpoint = endpoint,
            saveHistory = false
        )
    }
}
