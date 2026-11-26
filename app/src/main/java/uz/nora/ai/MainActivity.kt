package uz.nora.ai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.Gravity
import android.view.View
import android.widget.*
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private val bg = Color.rgb(3, 9, 20)
    private val panel = Color.rgb(8, 21, 39)
    private val cyan = Color.rgb(0, 190, 255)
    private val pale = Color.rgb(205, 235, 255)

    private lateinit var status: TextView
    private lateinit var answer: TextView
    private lateinit var asr: OfflineUzbekAsr

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var availableVoices: List<Voice> = emptyList()
    private var selectedGender = "Ayol"
    private var selectedStyle = "Professional"
    private var femaleVoiceName = ""
    private var maleVoiceName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = Color.BLACK

        val prefs = getSharedPreferences("nora_settings", MODE_PRIVATE)
        selectedGender = prefs.getString("gender", "Ayol") ?: "Ayol"
        selectedStyle = prefs.getString("voice", "Professional") ?: "Professional"
        femaleVoiceName = prefs.getString("female_voice", "") ?: ""
        maleVoiceName = prefs.getString("male_voice", "") ?: ""

        asr = OfflineUzbekAsr(
            this,
            { message -> statusMessage(message) },
            { text -> showRecognizedText(text) }
        )

        tts = TextToSpeech(this, this)

        showHome()
    }

    private fun showHome() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(14), dp(20), dp(14))
            setBackgroundColor(bg)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(TextView(this).apply {
            text = "N O R A"
            textSize = 26f
            letterSpacing = 0.18f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(pale)
        }, LinearLayout.LayoutParams(0, dp(50), 1f))

        header.addView(TextView(this).apply {
            text = "⚙"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(cyan)
            setOnClickListener { showSettings() }
        }, LinearLayout.LayoutParams(dp(50), dp(50)))

        root.addView(header)

        root.addView(View(this).apply {
            setBackgroundColor(cyan)
        }, LinearLayout.LayoutParams(-1, dp(1)))

        status = TextView(this).apply {
            text = if (asr.ready()) "OFFLINE MODEL TAYYOR" else "OFFLINE MODEL YUKLANMAGAN"
            textSize = 11f
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
            setTextColor(cyan)
            setPadding(0, dp(18), 0, dp(8))
        }
        root.addView(status)

        val orb = TextView(this).apply {
            text = "◉"
            textSize = 112f
            gravity = Gravity.CENTER
            setTextColor(cyan)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }

        root.addView(orb, LinearLayout.LayoutParams(-1, 0, 1f))

        root.addView(TextView(this).apply {
            text = "Assalomu alaykum."
            textSize = 23f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.DEFAULT_BOLD
        })

        root.addView(TextView(this).apply {
            text = "Men Nora. Sizni tinglashga tayyorman."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(135, 166, 195))
            setPadding(0, dp(7), 0, dp(15))
        })

        answer = TextView(this).apply {
            text = "Suhbat va tanilgan ovoz matni shu yerda ko‘rinadi."
            textSize = 14f
            setTextColor(pale)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(panel, 18, Color.rgb(20, 65, 100))
        }

        root.addView(answer, LinearLayout.LayoutParams(-1, dp(85)).apply {
            bottomMargin = dp(12)
        })

        root.addView(actionButton("⬇  OFFLINE OVOZ MODELINI YUKLASH") {
            asr.downloadModel()
        })

        root.addView(actionButton("◉  GAPIRISHNI BOSHLASH") {
            startOfflineListening()
        })

        root.addView(actionButton("⚙  SOZLAMALAR") {
            showSettings()
        })

        root.addView(TextView(this).apply {
            text = "NORA • OFFLINE VOICE SYSTEM"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(Color.GRAY)
            setPadding(0, dp(12), 0, dp(4))
        })

        setContentView(root)
    }

    private fun startOfflineListening() {
        if (!asr.ready()) {
            statusMessage("AVVAL OFFLINE MODELINI YUKLAB OLING")
            return
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                100
            )
            return
        }

        asr.listen()
    }

    private fun showRecognizedText(text: String) {
        answer.text = "SIZ:\n$text"

        // Hozircha tanilgan matn ko‘rsatiladi.
        // Keyingi bosqichda AI javob tizimi ulanadi.
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

        if (requestCode == 100) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                asr.listen()
            } else {
                statusMessage("MIKROFON RUXSATI BERILMADI")
            }
        }
    }

    private fun showSettings() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(25))
            setBackgroundColor(bg)
        }

        page.addView(sectionTitle("NORA OVOZ SOZLAMALARI"))

        page.addView(infoText(
            "Ovozlar telefondagi mavjud TTS xizmatidan olinadi. " +
            "Tabiiy ovoz sifati telefoningizdagi ovoz paketiga bog‘liq."
        ))

        page.addView(sectionTitle("OVOZ TURINI TANLASH"))

        listOf("Ayol", "Erkak").forEach { gender ->
            page.addView(actionButton(
                (if (selectedGender == gender) "●  " else "○  ") + gender
            ) {
                selectedGender = gender
                getSharedPreferences("nora_settings", MODE_PRIVATE)
                    .edit().putString("gender", gender).apply()
                applySelectedVoice()
                showSettings()
            })
        }

        page.addView(sectionTitle("OVOZ USLUBI"))

        listOf(
            "Professional",
            "Soft",
            "Dynamic",
            "Cinematic"
        ).forEach { style ->
            page.addView(actionButton(
                (if (selectedStyle == style) "●  " else "○  ") + style
            ) {
                selectedStyle = style
                getSharedPreferences("nora_settings", MODE_PRIVATE)
                    .edit().putString("voice", style).apply()
                applyVoiceStyle()
                speak("Ovoz uslubi tanlandi")
                showSettings()
            })
        }

        page.addView(sectionTitle("TELEFON OVOZLARI"))

        val voiceNames = availableVoices.map {
            "${it.name} (${it.locale.displayName})"
        }

        if (voiceNames.isEmpty()) {
            page.addView(infoText(
                "Hozircha offline TTS ovozlari topilmadi. " +
                "Telefon sozlamalaridan TTS ovoz paketini tekshiring."
            ))
        } else {
            val spinner = Spinner(this)
            val adapter = ArrayAdapter(
                this,
                android.R.layout.simple_spinner_item,
                voiceNames
            )
            adapter.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item
            )
            spinner.adapter = adapter

            val selectedName = if (selectedGender == "Ayol") {
                femaleVoiceName
            } else {
                maleVoiceName
            }

            val index = availableVoices.indexOfFirst {
                it.name == selectedName
            }

            if (index >= 0) spinner.setSelection(index)

            spinner.onItemSelectedListener =
                object : android.widget.AdapterView.OnItemSelectedListener {
                    override fun onNothingSelected(
                        parent: android.widget.AdapterView<*>?
                    ) {}

                    override fun onItemSelected(
                        parent: android.widget.AdapterView<*>?,
                        view: View?,
                        position: Int,
                        id: Long
                    ) {
                        if (position !in availableVoices.indices) return

                        val chosen = availableVoices[position]

                        if (selectedGender == "Ayol") {
                            femaleVoiceName = chosen.name
                        } else {
                            maleVoiceName = chosen.name
                        }

                        getSharedPreferences("nora_settings", MODE_PRIVATE)
                            .edit()
                            .putString("female_voice", femaleVoiceName)
                            .putString("male_voice", maleVoiceName)
                            .apply()

                        applySelectedVoice()
                    }
                }

            page.addView(spinner)
        }

        page.addView(actionButton("OVOZNI SINASH") {
            speak("Assalomu alaykum. Men Nora. Sizga yordam berishga tayyorman.")
        })

        page.addView(actionButton("TELEFON TTS SOZLAMALARI") {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (_: Exception) {
                statusMessage("TTS SOZLAMALARI OCHILMADI")
            }
        })

        page.addView(actionButton("BOSH SAHIFAGA QAYTISH") {
            showHome()
        })

        setContentView(ScrollView(this).apply {
            addView(page)
        })
    }

    override fun onInit(result: Int) {
        if (result != TextToSpeech.SUCCESS) {
            statusMessage("TTS XIZMATI ISHGA TUSHMADI")
            return
        }

        ttsReady = true

        val engine = tts ?: return

        availableVoices = engine.voices
            ?.filter { !it.isNetworkConnectionRequired }
            ?.sortedBy { it.name.lowercase(Locale.ROOT) }
            ?: emptyList()

        val uzVoice = availableVoices.firstOrNull {
            it.locale.language == "uz"
        }

        if (femaleVoiceName.isBlank()) {
            femaleVoiceName = uzVoice?.name
                ?: availableVoices.firstOrNull()?.name.orEmpty()
        }

        if (maleVoiceName.isBlank()) {
            maleVoiceName = uzVoice?.name
                ?: availableVoices.firstOrNull()?.name.orEmpty()
        }

        applySelectedVoice()
    }

    private fun applySelectedVoice() {
        if (!ttsReady) return

        val wanted = if (selectedGender == "Ayol") {
            femaleVoiceName
        } else {
            maleVoiceName
        }

        availableVoices.firstOrNull {
            it.name == wanted
        }?.let {
            tts?.voice = it
        }

        applyVoiceStyle()
    }

    private fun applyVoiceStyle() {
        val engine = tts ?: return

        when (selectedStyle) {
            "Soft" -> {
                engine.setPitch(0.98f)
                engine.setSpeechRate(0.88f)
            }

            "Professional" -> {
                engine.setPitch(0.92f)
                engine.setSpeechRate(0.96f)
            }

            "Dynamic" -> {
                engine.setPitch(1.0f)
                engine.setSpeechRate(1.02f)
            }

            "Cinematic" -> {
                engine.setPitch(0.86f)
                engine.setSpeechRate(0.88f)
            }
        }
    }

    private fun speak(message: String) {
        if (!ttsReady) {
            statusMessage("OVOZ XIZMATI TAYYORLANMOQDA")
            return
        }

        applyVoiceStyle()
        tts?.speak(
            message,
            TextToSpeech.QUEUE_FLUSH,
            null,
            "NORA_TTS"
        )
    }

    private fun statusMessage(message: String) {
        runOnUiThread {
            if (::status.isInitialized) {
                status.text = message
            }
        }
    }

    private fun actionButton(
        label: String,
        action: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(15), dp(12), dp(15))
            background = rounded(panel, 14, Color.rgb(20, 90, 130))
            setOnClickListener { action() }

            layoutParams = LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(10)
            }
        }
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 12f
        letterSpacing = 0.15f
        setTextColor(cyan)
        setPadding(0, dp(14), 0, dp(8))
    }

    private fun infoText(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(Color.rgb(155, 180, 205))
        setPadding(0, dp(4), 0, dp(15))
    }

    private fun rounded(
        color: Int,
        radius: Int,
        stroke: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), stroke)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    override fun onDestroy() {
        asr.close()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }
}
