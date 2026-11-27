package uz.nora.ai

import android.content.Context

class NoraCodeAgent(context: Context) {

    private val app = context.applicationContext
    private val online = NoraOnlineAgent(app)
    private val github = NoraGitHubTools(app)

    companion object {
        private const val MAX_PROMPT_LENGTH = 19000
        private const val MAX_TARGET_LENGTH = 7000
        private const val MAX_RELATED_LENGTH = 5500
        private const val MAX_FILE_MAP_LENGTH = 1400
        private const val MAX_RELATED_FILES = 5
    }

    suspend fun analyzeFile(
        owner: String,
        repo: String,
        path: String
    ): String {
        validateRepository(owner, repo)
        validatePath(path)

        val target = online.readGitHubFile(owner, repo, path)
        val fileMap = safeRepositoryMap(owner, repo)
        val related = loadRelatedFiles(owner, repo, path, fileMap)

        val prompt = """
Sen Nora AI loyihasining katta tajribali Kotlin Android muhandisisan.

REPOSITORY: $owner/$repo
ASOSIY FAYL: ${target.path}

REPOSITORY FAYLLARI RO'YXATI:
${fileMap.take(MAX_FILE_MAP_LENGTH)}

ASOSIY FAYL KODI:
${target.content.take(MAX_TARGET_LENGTH)}

BOG'LIQ FAYLLAR KODI:
${related.ifBlank { "Bog'liq fayllar o'qilmadi." }}

VAZIFA:
Asosiy faylni bog'liq fayllar bilan birga chuqur tahlil qil.

TEKSHIRUV:
1. Kotlin sintaksisi, importlar va compile xatolari.
2. Chaqirilgan klass va metodlar haqiqatan mavjudligi.
3. Argumentlar va qaytish turlari mosligi.
4. Android API va Android 16 mosligi.
5. Activity, Service va boshqa komponentlar lifecycle'i.
6. Coroutine, thread xavfsizligi va parallel ishlar.
7. Xotira, fayllar, audio va tarmoq resurslari.
8. Tokenlar, HTTPS, GitHub ruxsatlari va xavfsizlik.
9. Boshqa fayllar bilan integratsiya muammolari.
10. Runtime xatolariga sabab bo'lishi mumkin bo'lgan holatlar.

JAVOB TARTIBI:
- UMUMIY HOLAT.
- KRITIK XATOLAR.
- MUHIM XATOLAR.
- OGOHLANTIRISHLAR.
- FAYLLARARO MOSLIK.
- TUZATISH KERAK BO'LGAN JOYLAR.
- TAVSIYA ETILGAN ISH TARTIBI.

QAT'IY QOIDALAR:
- Faqat mazmuni berilgan fayllarni o'qilgan deb hisobla.
- Faqat fayl nomi ko'rsatilgan bo'lsa, uning ichki kodini bilaman deb aytma.
- Taxminni aniq xatodan ajrat.
- Build ishga tushirilmagan bo'lsa, build o'tdi deb yozma.
- GitHub fayllarini o'zgartirma.
- Hech qanday commit qilma.
- O'zbek tilida javob ber.
""".trimIndent()

        return ask(bounded(prompt))
    }

    suspend fun createCode(
        description: String,
        existingCode: String = ""
    ): String {
        require(description.isNotBlank()) {
            "Kod uchun vazifa tavsifi kerak"
        }

        val prompt = """
Sen Nora AI loyihasining Kotlin Android muhandisisan.

PACKAGE: uz.nora.ai
ANDROID: 16
TIL: Kotlin

FOYDALANUVCHI VAZIFASI:
$description

MAVJUD KOD:
${existingCode.take(9000)}

TALABLAR:
1. To'liq va ishlatishga tayyor kod yoz.
2. Kodni "..." bilan qisqartirma.
3. Importlarni to'liq ko'rsat.
4. Mavjud API va klasslar bilan moslikni saqla.
5. Keraksiz kutubxona qo'shma.
6. Token, parol va maxfiy kalitlarni kodga yozma.
7. Android 16 cheklovlarini hisobga ol.
8. Mavjud bo'lmagan metod yoki API'ni to'qib chiqarma.
9. Ishonching komil bo'lmagan joyni alohida qayd et.
10. GitHub'ga hech narsa yozma va commit qilma.

Avval fayl yo'lini, keyin to'liq kodni ber.
Javob o'zbek tilida bo'lsin.
""".trimIndent()

        return ask(bounded(prompt))
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

        val target = online.readGitHubFile(owner, repo, path)

        require(target.content.length <= 300000) {
            "Fayl juda katta. Hozircha 300 KB gacha bo'lgan fayllar qo'llanadi."
        }

        val fileMap = safeRepositoryMap(owner, repo)
        val related = loadRelatedFiles(owner, repo, path, fileMap)

        val prompt = """
Sen Nora loyihasining katta tajribali Kotlin Android muhandisisan.

REPOSITORY: $owner/$repo
O'ZGARTIRILADIGAN FAYL: $path

FOYDALANUVCHI VAZIFASI:
$task

REPOSITORY FAYLLARI RO'YXATI:
${fileMap.take(MAX_FILE_MAP_LENGTH)}

BOG'LIQ FAYLLAR KODI:
${related.ifBlank { "Bog'liq fayllar o'qilmadi." }}

HOZIRGI ASOSIY FAYL KODI:
${target.content.take(MAX_TARGET_LENGTH)}

QAT'IY TALABLAR:
1. Faqat ko'rsatilgan asosiy faylni yangilash uchun to'liq yangi kod yoz.
2. Mavjud funksiyalarni vazifaga aloqasi bo'lmasa o'chirma.
3. Bog'liq fayllardagi haqiqiy klass va metod nomlarini hisobga ol.
4. Boshqa fayllarni o'zgartirish kerak bo'lsa, buni koddan keyin alohida ogohlantir.
5. Importlar to'liq bo'lsin.
6. Kodni qisqartirma va "..." ishlatma.
7. Maxfiy kalit, token yoki parol kiritma.
8. Yangi kutubxona zarur bo'lsa, uni alohida qayd et.
9. Mavjud bo'lmagan metod yoki API'ni to'qib chiqarma.
10. Hozirgi faylda ishlayotgan funksiyalarni asossiz yo'qotma.
11. GitHub'ga hech narsa yozma va commit qilma.
12. Faqat to'liq Kotlin faylini qaytar.

JAVOB FORMATI:
Birinchi qatorda aynan NORA_FILE_START yoz.
Keyin to'liq Kotlin faylini yoz.
Oxirgi qatorda aynan NORA_FILE_END yoz.
""".trimIndent()

        val answer = ask(bounded(prompt))
        val startMarker = "NORA_FILE_START"
        val endMarker = "NORA_FILE_END"

        val start = answer.indexOf(startMarker)
        val end = answer.lastIndexOf(endMarker)

        require(start >= 0 && end > start) {
            "AI to'liq fayl formatida javob bermadi. O'zgarish saqlanmadi."
        }

        var code = answer
            .substring(start + startMarker.length, end)
            .trim()

        code = code
            .removePrefix("```kotlin")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()

        require(code.isNotBlank() && code.length >= 30) {
            "AI qaytargan yangi kod yaroqsiz."
        }

        require(
            code.startsWith("package ") ||
                code.startsWith("import ") ||
                code.startsWith("@file:")
        ) {
            "AI to'liq Kotlin faylini qaytarmadi."
        }

        require(!code.contains(startMarker) && !code.contains(endMarker)) {
            "AI javobi noto'g'ri formatlangan."
        }

        return code
    }

    private suspend fun loadRelatedFiles(
        owner: String,
        repo: String,
        targetPath: String,
        fileMap: String
    ): String {
        val paths = fileMap.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("• ") }
            .map { it.removePrefix("• ").trim() }
            .filter { it.isNotBlank() && it != targetPath }
            .distinct()
            .toList()

        val selected = selectRelatedPaths(targetPath, paths)
            .take(MAX_RELATED_FILES)

        if (selected.isEmpty()) return ""

        val output = StringBuilder()
        var remaining = MAX_RELATED_LENGTH

        for (path in selected) {
            if (remaining <= 0) break

            try {
                val content = github.readFile(owner, repo, path)

                if (
                    content.isBlank() ||
                    content.startsWith("Bu manzil oddiy fayl emas.") ||
                    content.startsWith("Fayl mazmuni bo'sh")
                ) {
                    continue
                }

                val safeContent = content.take(
                    minOf(remaining, 2200)
                )

                output.append("\n\n===== RELATED FILE: ")
                    .append(path)
                    .append(" =====\n")
                    .append(safeContent)

                if (safeContent.length < content.length) {
                    output.append("\n[MAZMUN QISQARTIRILDI]")
                }

                remaining -= safeContent.length
            } catch (e: Exception) {
                output.append("\n\n===== ")
                    .append(path)
                    .append(" =====\n")
                    .append("[O'QILMADI: ")
                    .append(e.message ?: "noma'lum xato")
                    .append("]")
            }
        }

        return output.toString().take(MAX_RELATED_LENGTH)
    }

    private fun selectRelatedPaths(
        targetPath: String,
        allPaths: List<String>
    ): List<String> {
        val targetName = targetPath.substringAfterLast("/")
            .substringBeforeLast(".")

        val targetDirectory = targetPath.substringBeforeLast(
            "/",
            missingDelimiterValue = ""
        )

        val priorityNames = when {
            targetName.contains("MainActivity", true) -> listOf(
                "NoraVoiceService.kt",
                "NoraGitHubChangeManager.kt",
                "NoraConversationMemory.kt",
                "OfflineNoraAI.kt",
                "AndroidManifest.xml"
            )

            targetName.contains("NoraVoiceService", true) -> listOf(
                "NoraCommandEngine.kt",
                "NoraGitHubChangeManager.kt",
                "NoraConversationMemory.kt",
                "OfflineUzbekAsr.kt",
                "OfflineNoraAI.kt"
            )

            targetName.contains("NoraCodeAgent", true) -> listOf(
                "NoraOnlineAgent.kt",
                "NoraGitHubTools.kt",
                "NoraGitHubChangeManager.kt",
                "NoraCodeChange.kt",
                "NoraSecureStore.kt"
            )

            targetName.contains("NoraOnlineAgent", true) -> listOf(
                "NoraSecureStore.kt",
                "NoraConversationMemory.kt",
                "NoraGitHubChangeManager.kt",
                "NoraCodeChange.kt"
            )

            targetName.contains("OfflineUzbekAsr", true) -> listOf(
                "NoraVoiceService.kt",
                "NoraSpeech.kt",
                "AndroidManifest.xml",
                "app/build.gradle.kts"
            )

            targetName.contains("NoraCommandEngine", true) -> listOf(
                "NoraVoiceService.kt",
                "MainActivity.kt",
                "AndroidManifest.xml"
            )

            else -> listOf(
                "NoraVoiceService.kt",
                "MainActivity.kt",
                "NoraConversationMemory.kt",
                "NoraSecureStore.kt",
                "app/build.gradle.kts"
            )
        }

        val prioritized = priorityNames.mapNotNull { wanted ->
            allPaths.firstOrNull {
                it.substringAfterLast("/") == wanted
            }
        }

        val siblings = allPaths.filter {
            it.substringBeforeLast("/", "") == targetDirectory &&
                (
                    it.endsWith(".kt", true) ||
                    it.endsWith(".xml", true) ||
                    it.endsWith(".kts", true)
                )
        }

        return (prioritized + siblings + allPaths.filter {
            it == "app/build.gradle.kts" ||
                it == "app/src/main/AndroidManifest.xml"
        }).distinct()
    }

    private suspend fun safeRepositoryMap(
        owner: String,
        repo: String
    ): String {
        return try {
            github.listFiles(owner, repo).take(9000)
        } catch (e: Exception) {
            "Repository fayllari ro'yxatini olish imkoni bo'lmadi: ${e.message}"
        }
    }

    private fun validateRepository(
        owner: String,
        repo: String
    ) {
        val pattern = Regex("[A-Za-z0-9_.-]{1,100}")

        require(pattern.matches(owner)) {
            "GitHub egasi noto'g'ri."
        }

        require(pattern.matches(repo)) {
            "Repository nomi noto'g'ri."
        }
    }

    private fun validatePath(path: String) {
        require(path.isNotBlank()) {
            "Fayl yo'li kerak."
        }

        require(path.length <= 500) {
            "Fayl yo'li juda uzun."
        }

        require(!path.startsWith("/") && !path.contains('\\')) {
            "Fayl yo'li noto'g'ri."
        }

        require(
            path.split("/").none {
                it.isBlank() || it == "." || it == ".."
            }
        ) {
            "Fayl yo'lida ruxsat etilmagan qism bor."
        }
    }

    private fun bounded(prompt: String): String {
        require(prompt.length <= MAX_PROMPT_LENGTH) {
            "AI tahlil so'rovi juda uzun. Fayl hajmini kamaytiring."
        }
        return prompt
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
            error("Online AI sozlamalari to'liq emas.")
        }

        return online.askAI(
            question = prompt,
            model = model,
            endpoint = endpoint,
            saveHistory = false
        )
    }
}
