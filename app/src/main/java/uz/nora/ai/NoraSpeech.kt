package uz.nora.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import java.util.Locale

class NoraSpeech(
    context: Context,
    private val onReady: (Boolean) -> Unit = {}
) {
    private val appContext = context.applicationContext

    @Volatile
    private var ready = false

    @Volatile
    private var closed = false

    private var engine: TextToSpeech? = null
    private var pendingText: String? = null

    private val lock = Any()

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

            onReady(success)
        }
    }

    private fun configureVoice() {
        val tts = engine ?: return

        try {
            val voices = tts.voices ?: emptySet()

            val preferred = voices.firstOrNull {
                it.locale.language == "uz" &&
                    !it.isNetworkConnectionRequired
            } ?: voices.firstOrNull {
                it.locale.language == "ru" &&
                    !it.isNetworkConnectionRequired
            } ?: voices.firstOrNull {
                it.locale.language == "tr" &&
                    !it.isNetworkConnectionRequired
            } ?: voices.firstOrNull {
                it.locale.language == "en" &&
                    !it.isNetworkConnectionRequired
            }

            if (preferred != null) {
                tts.voice = preferred
            } else {
                tts.language = Locale("uz", "UZ")
            }

            tts.setPitch(0.88f)
            tts.setSpeechRate(0.95f)
        } catch (_: Exception) {
            try {
                tts.language = Locale("ru", "RU")
                tts.setPitch(0.9f)
                tts.setSpeechRate(0.95f)
            } catch (_: Exception) {
            }
        }
    }

    fun speak(text: String) {
        if (text.isBlank()) return

        synchronized(lock) {
            if (closed) return

            if (!ready) {
                pendingText = text
                return
            }

            speakNow(text)
        }
    }

    private fun speakNow(text: String) {
        val clean = text
            .replace(Regex("[*_#`]"), "")
            .replace("\n", ". ")
            .take(2500)

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

    fun close() {
        synchronized(lock) {
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
