
package uz.nora.ai

import android.content.Context

class NoraCodeAgent(context: Context) {

    private val app = context.applicationContext
    private val github = NoraOnlineAgent(app)
    private val githubTools = NoraGitHubTools(app)

    suspend fun analyzeFile(
        owner: String,
        repo: String,
        path: String
    ): String {
        validateRepository(owner, repo)
        validatePath(path)

        val file = github.readGitHubFile(owner, repo, path)
        val repositoryMap = safeRepositoryMap(owner, repo)

        val prompt = """
Sen Nora loyihasining katta tajribali Android/Kotlin dasturchisisan.

REPOSITORY: $owner/$repo
FAYL: ${file.path}

REPOSITORY FAYLLARI RO'YXATI:
$repositoryMap

TEKSHIRILAYOTGAN FAYL:
${file.content.take(18000)}

Vazifa: berilgan faylni quyidagilar bo'yicha tahlil qil:
- Kotlin sintaksisi va ehtimoliy compile xatolari.
- Android API va Android 16 mosligi.
- Lifecycle, coroutine va thread xavfsizligi.
- Xotira, resurslar va runtime xatolari.
- Xavfsizlik va maxfiy ma'lumotlar.
- Repository ro'yxatidagi boshqa fayllar bilan ehtimoliy moslik.

MUHIM:
- Faqat yuqoridagi faylning mazmuni o'qildi.
- Repository ro'yxati faqat fayl nomlarini ko'rsatadi.
- Boshqa fayllarning kodini tekshirdim deb aytma.
- Taxminlarni aniq belgilab yoz.
- GitHub fayllarini o'zgartirma va commit qilma.

JAVOB:
1. Umumiy holat.
2. Xatolar va xavf darajasi.
3. Sabab va tuzatish yo'li.
4. Tekshirilmagan jihatlar.

Javobni o'zbek tilida yoz.
""".trimIndent()

        return ask(prompt)
    }

    suspend fun createCode(
        description: String,
        existingCode: String = ""
    ): String {
        require(description.isNotBlank()) {
            "Kod uchun vazifa tavsifi kerak"
        }

        val prompt = """
Sen Nora AI loyihasining Kotlin Android dasturchisisan.

Package: uz.nora.ai
Android: 16
Til: Kotlin

VAZIFA:
$description

MAVJUD KOD:
${existingCode.take(18000)}

TALABLAR:
- To'liq kod yoz, "..." bilan qisqartirma.
- Mavjud API va klasslar bilan moslikni saqla.
- Keraksiz kutubxona qo'shma.
- Token va maxfiy kalitlarni kodga yozma.
- Android 16 cheklovlarini hisobga ol.
- Importlarni to'liq ko'rsat.
- Ishonching komil bo'lmagan API'ni mavjud deb ko'rsatma.
- GitHub'ga hech narsa yozma va commit qilma.

Avval fayl yo'lini, keyin to'liq kodni ber.
Javob o'zbek tilida bo'lsin.
""".trimIndent()

        return ask(prompt)
    }

    suspend fun reviseFile(
        owner: String,
        repo: String,
        path: String,
        task: String
    ): String {
        validateRepository(owner, repo)
        validatePath(path)

        require(task.isNotBlank()) {
            "O'zgartirish vazifasi kerak"
        }

        val file = github.readGitHubFile(owner, repo, path)

        require(file.content.length <= 300_000) {
            "Fayl juda katta. Hozircha 300 KB gacha bo'lgan fayllar qo'llanadi."
        }

        val repositoryMap = safeRepositoryMap(owner, repo)

        val prompt = """
Sen Nora loyihasining katta tajribali Kotlin Android muhandisisan.

REPOSITORY: $owner/$repo
FAYL: $path

REPOSITORY FAYLLARI RO'YXATI:
$repositoryMap

FOYDALANUVCHI VAZIFASI:
$task

HOZIRGI FAYL KODI:
${file.content}

QAT'IY TALABLAR:
1. Faqat shu faylni yangilash uchun to'liq yangi kod yoz.
2. Mavjud funksiyalarni vazifaga aloqasi bo'lmasa o'chirma.
3. Repository ro'yxatidan mavjud klass va fayl nomlarini hisobga ol.
4. Boshqa fayllarning ichki kodini ko'rmaganingni unutma.
5. Importlar to'liq bo'lsin.
6. Kodni qisqartirma, "..." ishlatma.
7. Maxfiy kalit, token yoki parol kiritma.
8. Yangi kutubxona talab qilinsa, koddan tashqarida ogohlantir.
9. Mavjud bo'lmagan metod yoki API'ni to'qib chiqarma.
10. GitHub'ga hech narsa yozma va commit qilma.
11. Faqat to'liq Kotlin faylini qaytar.

JAVOB FORMATI:
Birinchi qatorda aynan NORA_FILE_START yoz.
Keyin to'liq Kotlin faylini yoz.
Oxirgi qatorda aynan NORA_FILE_END yoz.
""".trimIndent()

        val answer = ask(prompt)
        val start = answer.indexOf("NORA_FILE_START")
        val end = answer.lastIndexOf("NORA_FILE_END")

        require(start >= 0 && end > start) {
            "AI to'liq fayl formatida javob bermadi. O'zgarish saqlanmadi."
        }

        val code = answer
            .substring(start + "NORA_FILE_START".length, end)
            .trim()
            .removePrefix("```kotlin")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        require(code.isNotBlank() && code.length >= 30) {
            "AI qaytargan yangi kod yaroqsiz"
        }
        require(!code.contains("NORA_FILE_START")) {
            "AI javobi noto'g'ri formatlangan"
        }

        return code
    }

    private suspend fun safeRepositoryMap(
        owner: String,
        repo: String
    ): String {
        return try {
            githubTools.listFiles(owner, repo).take(9000)
        } catch (e: Exception) {
            "Fayllar ro'yxatini olish imkoni bo'lmadi: ${e.message}"
        }
    }

    private fun validateRepository(owner: String, repo: String) {
        val pattern = Regex("[A-Za-z0-9_.-]{1,100}")
        require(pattern.matches(owner)) {
            "GitHub egasi noto'g'ri"
        }
        require(pattern.matches(repo)) {
            "Repository nomi noto'g'ri"
        }
    }

    private fun validatePath(path: String) {
        require(path.isNotBlank()) {
            "Fayl yo'li kerak"
        }
        require(!path.startsWith("/") && !path.contains('\\')) {
            "Fayl yo'li noto'g'ri"
        }
        require(path.split("/").none {
            it.isBlank() || it == "." || it == ".."
        }) {
            "Fayl yo'lida ruxsat etilmagan qism bor"
        }
    }

    private suspend fun ask(prompt: String): String {
        val key = NoraSecureStore.read(app, "ai_key")
        val endpoint = NoraSecureStore.read(app, "ai_endpoint")
        val model = NoraSecureStore.read(app, "ai_model")

        if (
            key.isNullOrBlank() ||
            endpoint.isNullOrBlank() ||
            model.isNullOrBlank()
        ) {
            error("Online AI sozlamalari to'liq emas")
        }

        return github.askAI(
            question = prompt,
            model = model,
            endpoint = endpoint,
            saveHistory = false
        )
    }
}
