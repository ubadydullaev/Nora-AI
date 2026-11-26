
package uz.nora.ai

import android.app.Activity
import android.os.Bundle
import android.content.Intent
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.widget.*
import android.view.Gravity
import android.graphics.Typeface
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private lateinit var status: TextView
    private lateinit var resultText: TextView
    private var selectedVoice = "Soft"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        tts = TextToSpeech(this, this)

        val prefs = getSharedPreferences("nora_settings", MODE_PRIVATE)
        selectedVoice = prefs.getString("voice", "Soft") ?: "Soft"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(28, 35, 28, 25)
        }

        val scroll = ScrollView(this)
        scroll.addView(root)

        val title = TextView(this).apply {
            text = "NORA"
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Shaxsiy AI yordamchingiz"
            textSize = 17f
            gravity = Gravity.CENTER
        }

        status = TextView(this).apply {
            text = "Nora ishga tayyor"
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(10, 25, 10, 20)
        }

        resultText = TextView(this).apply {
            text = "Ovozli buyruq yoki matn kiriting."
            textSize = 17f
            setPadding(12, 15, 12, 15)
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(status)

        root.addView(makeButton("🎙 Ovoz orqali gapirish") {
            startVoiceRecognition()
        })

        root.addView(resultText)

        root.addView(makeButton("🔊 Nora ovozini sinash") {
            speak("Assalomu alaykum. Men Nora, sizning yordamchingizman.")
        })

        val voiceTitle = TextView(this).apply {
            text = "\nOvoz uslubini tanlang"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(voiceTitle)

        val voices = listOf(
            "Soft",
            "Professional",
            "Young",
            "Cinematic"
        )

        voices.forEach { voice ->
            root.addView(makeButton(voice) {
                selectedVoice = voice
                prefs.edit().putString("voice", voice).apply()
                status.text = "Tanlangan ovoz: $voice"
                speak("Salom. Men Noraman.")
            })
        }

        root.addView(makeButton("ℹ Ilova haqida") {
            resultText.text =
                "Nora Android yordamchisining boshlang‘ich versiyasi.\n\n" +
                "Keyingi bosqichlarda AI, xotira, Telegram va GitHub " +
                "integratsiyalari qo‘shiladi."
        })

        setContentView(scroll)
    }

    private fun makeButton(
        label: String,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = label
            textSize = 16f
            isAllCaps = false
            setOnClickListener { action() }

            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.setMargins(0, 8, 0, 8)
            layoutParams = params
        }
    }

    private fun startVoiceRecognition() {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(android.Manifest.permission.RECORD_AUDIO),
                100
            )
            return
        }

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            intent.putExtra(
                RecognizerIntent.EXTRA_LANGUAGE,
                "uz-UZ"
            )
            intent.putExtra(
                RecognizerIntent.EXTRA_PROMPT,
                "Nora sizni tinglamoqda..."
            )

            startActivityForResult(intent, 101)
        } catch (e: Exception) {
            status.text = "Ovozli xizmat telefonda mavjud emas."
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == 100 &&
            grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            startVoiceRecognition()
        } else if (requestCode == 100) {
            status.text = "Mikrofon ruxsati berilmadi."
        }
    }

    @Deprecated("Legacy activity result API")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 101 && resultCode == RESULT_OK) {
            val matches = data?.getStringArrayListExtra(
                RecognizerIntent.EXTRA_RESULTS
            )

            val spokenText = matches?.firstOrNull()

            if (!spokenText.isNullOrBlank()) {
                resultText.text = "Siz: $spokenText"
                status.text = "Buyruq qabul qilindi"
            }
        }
    }

    override fun onInit(result: Int) {
        if (result == TextToSpeech.SUCCESS) {
            val language = tts?.setLanguage(Locale("uz", "UZ"))

            status.text = if (
                language == TextToSpeech.LANG_MISSING_DATA ||
                language == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                "O‘zbekcha TTS ovozi telefonda mavjud emas."
            } else {
                "Nora ishga tayyor"
            }
        }
    }

    private fun speak(message: String) {
        val engine = tts ?: return

        when (selectedVoice) {
            "Soft" -> {
                engine.setPitch(1.1f)
                engine.setSpeechRate(0.9f)
            }
            "Professional" -> {
                engine.setPitch(0.95f)
                engine.setSpeechRate(1.0f)
            }
            "Young" -> {
                engine.setPitch(1.2f)
                engine.setSpeechRate(1.05f)
            }
            "Cinematic" -> {
                engine.setPitch(0.85f)
                engine.setSpeechRate(0.85f)
            }
        }

        engine.speak(
            message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "NORA_SPEECH"
        )
    }

    override fun onDestroy() {
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
