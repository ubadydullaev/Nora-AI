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
    private val lock = Any()

    @Volatile
    private var ready = false

    @Volatile
    private var closed = false

    private var engine: TextToSpeech? = null
    private var pendingText: String? = null

    init {
        engine = TextToSpeech(appContext) { status ->
            val success = status == TextToSpeech.SUCCESS

            synchronized(lock) {
                if (closed) return@TextToSpeech

                if (success) {
                    configureVoice()
                    ready = true

                    pendingText?.let { text ->
                        pendingText = null
                        speakNow(text)
                    }
                }
            }

            try {
                onReady(success)
            } catch (_: Exception) {
            }
        }
    }

    private fun configureVoice() {
        val tts = engine ?: return

        try {
            val voices = tts.voices ?: emptySet()

            val offlineVoices = voices.filter {
                !it.isNetworkConnectionRequired
            }

            val preferred = chooseVoice(offlineVoices)
                ?: chooseVoice(voices)

            if (preferred != null) {
                tts.voice = preferred
            } else {
                setFallbackLanguage(tts)
            }

            tts.setPitch(0.88f)
            tts.setSpeechRate(0.93f)

        } catch (_: Exception) {
            setFallbackLanguage(tts)
        }
    }

    private fun chooseVoice(voices: Collection<Voice>): Voice? {
        val uzbek = voices
            .filter { it.locale.language.equals("uz", true) }
            .sortedWith(
                compareByDescending<Voice> {
                    it.locale.country.equals("UZ", true)
                }.thenByDescending {
                    it.quality
                }
            )
            .firstOrNull()

        if (uzbek != null) return uzbek

        val russian = voices
            .filter { it.locale.language == "ru" }
            .maxByOrNull { it.quality }

        if (russian != null) return russian

        val turkish = voices
            .filter { it.locale.language == "tr" }
            .maxByOrNull { it.quality }

        if (turkish != null) return turkish

        return voices
            .filter { it.locale.language == "en" }
            .maxByOrNull { it.quality }
    }

    private fun setFallbackLanguage(tts: TextToSpeech) {
        try {
            var result = tts.setLanguage(Locale("uz", "UZ"))

            if (
                result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                result = tts.setLanguage(Locale("ru", "RU"))
            }

            if (
                result == TextToSpeech.LANG_MISSING_DATA ||
                result == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                tts.setLanguage(Locale.US)
            }

            tts.setPitch(0.88f)
            tts.setSpeechRate(0.93f)

        } catch (_: Exception) {
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return

        synchronized(lock) {
            if (closed) return

            if (!ready) {
                pendingText = text.take(2500)
                return
            }

            speakNow(text)
        }
    }

    private fun speakNow(text: String) {
        val clean = text
            .replace(Regex("```[\\s\\S]*?```"), " Kod matni.")
            .replace(Regex("https?://\\S+"), "havola")
            .replace(Regex("[*_#`]"), "")
            .replace("\n", ". ")
            .replace(Regex("\\s+"), " ")
            .take(2500)

        if (clean.isBlank()) return

        try {
            engine?.speak(
                clean,
                TextToSpeech.QUEUE_FLUSH,
                null,
                "NORA_${System.currentTimeMillis()}"
            )
        } catch (_: Exception) {
        }
    }

    fun stop() {
        synchronized(lock) {
            pendingText = null

            try {
                engine?.stop()
            } catch (_: Exception) {
            }
        }
    }

    fun isReady(): Boolean = ready && !closed

    fun close() {
        synchronized(lock) {
            if (closed) return

            closed = true
            ready = false
            pendingText = null

            try {
                engine?.stop()
                engine?.shutdown()
            } catch (_: Exception) {
            }

            engine = null
        }
    }
}
