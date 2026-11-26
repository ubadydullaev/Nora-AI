
package uz.nora.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import java.util.Locale

class NoraSpeech(
    context: Context,
    private val onReady: (Boolean) -> Unit = {}
) {
    private val appContext = context.applicationContext

    @Volatile
    private var ready = false

    private var engine: TextToSpeech? = null

    init {
        engine = TextToSpeech(appContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                configureVoice()
                ready = true
                onReady(true)
            } else {
                onReady(false)
            }
        }
    }

    private fun configureVoice() {
        val tts = engine ?: return

        val voices = tts.voices ?: emptySet()

        val preferred = voices.firstOrNull {
            it.locale.language == "uz" && !it.isNetworkConnectionRequired
        } ?: voices.firstOrNull {
            it.locale.language == "ru" && !it.isNetworkConnectionRequired
        } ?: voices.firstOrNull {
            it.locale.language == "tr" && !it.isNetworkConnectionRequired
        } ?: voices.firstOrNull {
            it.locale.language == "en" && !it.isNetworkConnectionRequired
        }

        if (preferred != null) {
            tts.voice = preferred
        } else {
            tts.language = Locale("uz", "UZ")
        }

        tts.setPitch(0.88f)
        tts.setSpeechRate(0.95f)
    }

    fun speak(text: String) {
        if (!ready || text.isBlank()) return

        val clean = text
            .replace(Regex("[*_#`]"), "")
            .replace("\n", ". ")
            .take(2500)

        engine?.speak(
            clean,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "NORA_${System.currentTimeMillis()}"
        )
    }

    fun stop() {
        engine?.stop()
    }

    fun close() {
        ready = false
        engine?.stop()
        engine?.shutdown()
        engine = null
    }
}
