
package uz.nora.ai

import android.content.Context

class NoraCodeAgent(private val context: Context) {

    private val github = NoraOnlineAgent(context)

    suspend fun analyzeFile(
        owner: String,
        repo: String,
        path: String
    ): String {
        val file = github.readGitHubFile(owner, repo, path)

        val prompt = """
Sen Nora AI dasturlash agentisan.

Repository: $owner/$repo
Fayl: ${file.path}

Quyidagi kodni tahlil qil:
1. Xatolar va build muammolari.
2. Xavfsizlik muammolari.
3. Boshqa fayllar bilan moslik.
4. Ishlash tezligi va barqarorlik.
5. Aniq tuzatishlar.

Kod:
${file.content.take(18000)}

Javobni o'zbek tilida ber.
Aniq bo'lmagan narsani taxmin sifatida belgilagin.
""".trimIndent()

        return ask(prompt)
    }

    suspend fun createCode(
        description: String,
        existingCode: String = ""
    ): String {
        val prompt = """
Sen Nora loyihasining Kotlin Android dasturchisisan.

Vazifa:
$description

Mavjud kod:
${existingCode.take(18000)}

Talablar:
- To'liq ishlaydigan Kotlin kod yoz.
- Android 16 bilan moslikni hisobga ol.
- Mavjud Nora arxitekturasini buzma.
- Keraksiz kutubxona qo'shma.
- Xavfsizlikka rioya qil.
- Avval qisqa izoh, keyin to'liq kod ber.
- Yetishmayotgan ma'lumotni o'ylab topma.
""".trimIndent()

        return ask(prompt)
    }

    private suspend fun ask(prompt: String): String {
        val key = NoraSecureStore.read(context, "ai_key")
        val endpoint = NoraSecureStore.read(context, "ai_endpoint")
        val model = NoraSecureStore.read(context, "ai_model")

        if (
            key.isNullOrBlank() ||
            endpoint.isNullOrBlank() ||
            model.isNullOrBlank()
        ) {
            error("Online AI sozlamalari to'liq emas")
        }

        return NoraOnlineAgent(context).askAI(
            prompt,
            model,
            endpoint
        )
    }
}
