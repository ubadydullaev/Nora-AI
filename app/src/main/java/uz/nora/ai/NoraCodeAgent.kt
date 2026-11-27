
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

VAZIFA:
Ushbu faylni chuqur texnik tahlil qil.

TEKSHIRUV:
1. Kotlin sintaksisi va kompilyatsiya xatolari.
2. Android API va versiyalar bilan moslik.
3. Boshqa Nora fayllari bilan API mosligi.
4. Coroutine, thread va lifecycle muammolari.
5. Xotira va protsessor sarfi.
6. Internet, token va ma'lumotlar xavfsizligi.
7. Runtime xatolari va noto'g'ri holatlar.
8. Kodning barqarorligi va ishlash tezligi.

JAVOB FORMATI:
- Umumiy holat.
- Aniqlangan xatolar.
- Xavf darajasi: yuqori, o'rta yoki past.
- Muammoning aniq sababi.
- Tuzatish bo'yicha aniq tavsiya.
- Kerak bo'lsa, to'liq tuzatilgan fayl.

MUHIM:
- Faqat berilgan faylni ko'rding. Boshqa fayllarni tekshirdim deb aytma.
- Aniqlanmagan muammoni mavjud deb ko'rsatma.
- Taxminni alohida belgilagin.
- Javobni o'zbek tilida yoz.
- Hech qanday faylni o'zing o'zgartirma yoki commit qilma.
- Mavjud kodni keraksiz qayta yozishni tavsiya qilma.

FAYL KODI:
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
Sen Nora AI loyihasining asosiy Kotlin Android dasturchisisan.

LOYIHA:
- Ilova nomi: Nora.
- Package: uz.nora.ai.
- Android 16 bilan mos bo'lishi kerak.
- Kotlin tilidan foydalan.
- Offline va online rejimlarni hisobga ol.
- Mavjud arxitekturani saqla.

VAZIFA:
$description

MAVJUD KOD:
${existingCode.take(18000)}

TALABLAR:
1. To'liq fayl kodini yoz.
2. Mavjud klass va metodlar bilan moslikni saqla.
3. Keraksiz kutubxona qo'shma.
4. Xavfsizlikni buzma.
5. API kalitlari va tokenlarni kodga yozma.
6. Android 16 cheklovlarini hisobga ol.
7. Xatolarni ushlash va resurslarni yopishni unutma.
8. Kodni qisqartirib yoki "..." bilan almashtirma.
9. Fayl yo'lini ko'rsat.
10. Boshqa fayllarga bog'liq bo'lsa, kerakli API'larni aniq ko'rsat.
11. Ishonching komil bo'lmagan API yoki kutubxona metodini mavjud deb ko'rsatma.
12. Kompilyatsiya uchun kerakli importlarni yoz.

JAVOB:
Avval qisqa izoh, keyin to'liq kod.
Javobni o'zbek tilida ber.
Hech qanday GitHub faylini o'zing commit qilma.
""".trimIndent()

        return ask(prompt)
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
