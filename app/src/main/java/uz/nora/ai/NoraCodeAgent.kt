
package uz.nora.ai

import android.content.Context

class NoraCodeAgent(context: Context) {

    private val app = context.applicationContext
    private val github = NoraOnlineAgent(app)

    suspend fun analyzeFile(
        owner: String,
        repo: String,
        path: String
    ): String {
        require(owner.isNotBlank() && repo.isNotBlank()) {
            "Repository nomi noto'g'ri"
        }
        require(path.isNotBlank()) {
            "Tahlil qilinadigan fayl yo'li kerak"
        }

        val file = github.readGitHubFile(owner, repo, path)

        val prompt = """
Sen Nora loyihasining katta tajribali Android/Kotlin dasturchisisan.

REPOSITORY: $owner/$repo
FAYL: ${file.path}

Vazifa: faylni sintaksis, Android API, boshqa fayllar bilan moslik,
coroutine, lifecycle, xavfsizlik, xotira va runtime xatolari bo'yicha tahlil qil.

Javobda:
- Umumiy holat.
- Aniqlangan xatolar va xavf darajasi.
- Sabab va aniq tuzatish tavsiyasi.
- Taxminlarni alohida belgilash.

Faqat berilgan faylni ko'rding. Boshqa fayllarni tekshirdim deb aytma.
Hech qanday GitHub faylini o'zgartirma yoki commit qilma.
Javobni o'zbek tilida yoz.

FAYL:
${file.content.take(18000)}
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

Talablar:
- To'liq kod yoz, kodni "..." bilan qisqartirma.
- Mavjud API va klasslar bilan moslikni saqla.
- Keraksiz kutubxona qo'shma.
- Token va maxfiy kalitlarni kodga yozma.
- Android 16 cheklovlarini hisobga ol.
- Importlarni to'liq ko'rsat.
- Ishonching komil bo'lmagan API'ni mavjud deb ko'rsatma.
- Hech qanday GitHub faylini commit qilma.

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
        require(owner.isNotBlank() && repo.isNotBlank()) {
            "Repository noto'g'ri"
        }
        require(path.isNotBlank()) {
            "Fayl yo'li kerak"
        }
        require(task.isNotBlank()) {
            "O'zgartirish vazifasi kerak"
        }

        val file = github.readGitHubFile(owner, repo, path)

        require(file.content.length <= 300_000) {
            "Fayl juda katta. Hozircha 300 KB gacha bo'lgan fayllar qo'llanadi."
        }

        val prompt = """
Sen Nora loyihasining katta tajribali Kotlin Android muhandisisan.

REPOSITORY: $owner/$repo
FAYL: $path

FOYDALANUVCHI VAZIFASI:
$task

HOZIRGI FAYL KODI:
${file.content}

QAT'IY TALABLAR:
1. Faqat shu faylni yangilash uchun to'liq yangi kod yoz.
2. Mavjud funksiyalarni vazifaga aloqasi bo'lmasa o'chirma.
3. Boshqa fayllardagi API'lar bilan moslikni saqla.
4. Importlar to'liq bo'lsin.
5. Kodni qisqartirma, "..." ishlatma.
6. Maxfiy kalit, token yoki parol kiritma.
7. Yangi kutubxona talab qilinsa, koddan tashqarida ogohlantir.
8. Mavjud bo'lmagan metod yoki API'ni to'qib chiqarma.
9. GitHub'ga hech narsa yozma va commit qilma.
10. Javobda izoh, Markdown yoki code fence yozma.

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
