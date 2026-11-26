
package uz.nora.ai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.Gravity
import android.view.View
import android.widget.*
import android.content.res.ColorStateList
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private val bg = Color.rgb(3, 9, 20)
    private val panel = Color.rgb(8, 21, 39)
    private val cyan = Color.rgb(0, 190, 255)
    private val pale = Color.rgb(205, 235, 255)

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var selectedStyle = "Professional"
    private var selectedGender = "Ayol"
    private var femaleVoiceName = ""
    private var maleVoiceName = ""
    private var availableVoices: List<Voice> = emptyList()
    private lateinit var status: TextView
    private lateinit var answer: TextView
    private lateinit var orb: CoreView
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = Color.BLACK
        window.decorView.systemUiVisibility = 0

        val prefs = getSharedPreferences("nora_settings", MODE_PRIVATE)
        selectedStyle = prefs.getString("voice", "Professional") ?: "Professional"
        selectedGender = prefs.getString("gender", "Ayol") ?: "Ayol"
        femaleVoiceName = prefs.getString("female_voice", "") ?: ""
        maleVoiceName = prefs.getString("male_voice", "") ?: ""

        tts = TextToSpeech(this, this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(20), dp(14), dp(20), dp(12))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val brand = TextView(this).apply {
            text = "N O R A"
            textSize = 25f
            letterSpacing = 0.18f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setTextColor(pale)
        }

        val settings = TextView(this).apply {
            text = "⚙"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(cyan)
            setOnClickListener { showSettings() }
        }

        header.addView(brand, LinearLayout.LayoutParams(0, dp(48), 1f))
        header.addView(settings, LinearLayout.LayoutParams(dp(48), dp(48)))
        root.addView(header)

        val line = View(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.TRANSPARENT, cyan, Color.TRANSPARENT)
            )
        }
        root.addView(line, LinearLayout.LayoutParams(-1, dp(1)))

        status = TextView(this).apply {
            text = "TIZIM ISHGA TAYYOR"
            textSize = 11f
            letterSpacing = 0.2f
            gravity = Gravity.CENTER
            setTextColor(cyan)
            setPadding(0, dp(16), 0, dp(4))
        }
        root.addView(status)

        orb = CoreView()
        root.addView(
            orb,
            LinearLayout.LayoutParams(-1, 0, 1f).apply {
                topMargin = dp(4)
                bottomMargin = dp(4)
            }
        )

        val greeting = TextView(this).apply {
            text = "Assalomu alaykum."
            textSize = 23f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.WHITE)
        }
        root.addView(greeting)

        val sub = TextView(this).apply {
            text = "Men Nora. Sizni tinglashga tayyorman."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(135, 166, 195))
            setPadding(0, dp(5), 0, dp(14))
        }
        root.addView(sub)

        answer = TextView(this).apply {
            text = "Suhbat shu yerda ko‘rinadi."
            textSize = 14f
            setTextColor(pale)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(panel, 18, Color.rgb(20, 65, 100))
        }
        root.addView(answer, LinearLayout.LayoutParams(-1, dp(74)).apply {
            bottomMargin = dp(14)
        })

        val mic = TextView(this).apply {
            text = "◉"
            textSize = 38f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(0, 155, 255), Color.rgb(0, 65, 145))
            ).apply {
                shape = GradientDrawable.OVAL
                setStroke(dp(2), Color.rgb(75, 210, 255))
            }
            elevation = dp(12).toFloat()
            setOnClickListener { startVoiceRecognition() }
        }
        root.addView(mic, LinearLayout.LayoutParams(dp(82), dp(82)).apply {
            gravity = Gravity.CENTER
            bottomMargin = dp(8)
        })

        val micLabel = TextView(this).apply {
            text = "BOSING VA GAPIRING"
            textSize = 10f
            letterSpacing = 0.18f
            gravity = Gravity.CENTER
            setTextColor(cyan)
            setPadding(0, dp(3), 0, dp(12))
        }
        root.addView(micLabel)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = rounded(Color.rgb(6, 17, 32), 18, Color.rgb(20, 55, 85))
            setPadding(dp(5), dp(7), dp(5), dp(7))
        }

        bottom.addView(navItem("⌂", "Bosh sahifa", true) { showHome() },
            LinearLayout.LayoutParams(0, dp(52), 1f))
        bottom.addView(navItem("⚙", "Sozlamalar", false) { showSettings() },
            LinearLayout.LayoutParams(0, dp(52), 1f))
        bottom.addView(navItem("ⓘ", "Ma’lumot", false) { showAbout() },
            LinearLayout.LayoutParams(0, dp(52), 1f))

        root.addView(bottom)
        setContentView(root)
    }

    private fun navItem(
        icon: String,
        label: String,
        active: Boolean,
        action: () -> Unit
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setOnClickListener { action() }

            addView(TextView(this@MainActivity).apply {
                text = icon
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(if (active) cyan else Color.LTGRAY)
            })

            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(if (active) cyan else Color.LTGRAY)
            })
        }
    }

    private fun showHome() {
        recreate()
    }

    private fun showSettings() {
        val scroll = ScrollView(this)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(25))
            setBackgroundColor(bg)
        }
        scroll.addView(page)

        page.addView(TextView(this).apply {
            text = "N O R A"
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(pale)
            setPadding(0, dp(12), 0, dp(8))
        })

        page.addView(TextView(this).apply {
            text = "SOZLAMALAR"
            textSize = 12f
            gravity = Gravity.CENTER
            letterSpacing = 0.2f
            setTextColor(cyan)
            setPadding(0, 0, 0, dp(25))
        })

        page.addView(sectionTitle("OVOZ USLUBI"))
        page.addView(infoText("Uslubni tanlang. Ovoz sifati telefondagi TTS xizmatiga bog‘liq."))

        listOf(
            "Professional" to "Ravon va jiddiy",
            "Soft" to "Muloyim va sokin",
            "Dynamic" to "Jonli va faol",
            "Cinematic" to "Past va vazmin"
        ).forEach { item ->
            val selected = selectedStyle == item.first
            val button = TextView(this).apply {
                text = (if (selected) "●  " else "○  ") +
                    item.first + "\n     " + item.second
                textSize = 15f
                setTextColor(if (selected) Color.WHITE else pale)
                setPadding(dp(17), dp(15), dp(17), dp(15))
                background = rounded(
                    if (selected) Color.rgb(8, 45, 75) else panel,
                    15,
                    if (selected) cyan else Color.rgb(25, 55, 80)
                )
                setOnClickListener {
                    selectedStyle = item.first
                    getSharedPreferences("nora_settings", MODE_PRIVATE)
                        .edit().putString("voice", selectedStyle).apply()
                    speak("Ovoz uslubi tanlandi: ${item.first}")
                    showSettings()
                }
            }
            page.addView(button, LinearLayout.LayoutParams(-1, dp(70)).apply {
                bottomMargin = dp(10)
            })
        }

        page.addView(sectionTitle("OVOZNI SOZLASH"))
        page.addView(infoText(
            "Agar telefonda tabiiy o‘zbekcha ovoz bo‘lmasa, Android TTS sozlamalaridan boshqa ovoz yoki til paketini o‘rnating."
        ))

        page.addView(makeActionButton("Telefon TTS sozlamalarini ochish") {
            try {
                startActivity(Intent("com.android.settings.TTS_SETTINGS"))
            } catch (_: Exception) {
                statusMessage("TTS sozlamalari ochilmadi.")
            }
        })

        page.addView(makeActionButton("Nora ovozini sinash") {
            speak("Assalomu alaykum. Men Nora. Sizga yordam berishga tayyorman.")
        })

        page.addView(makeActionButton("Orqaga qaytish") { showHome() })
        setContentView(scroll)
    }

    private fun showAbout() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(25), dp(30), dp(25), dp(30))
            setBackgroundColor(bg)
        }
        page.addView(TextView(this).apply {
            text = "N O R A"
            textSize = 32f
            gravity = Gravity.CENTER
            setTextColor(cyan)
        })
        page.addView(infoText(
            "Nora — shaxsiy AI yordamchi loyihasi.\n\n" +
            "Hozirgi versiyada ovozli buyruq, matnni ko‘rsatish va TTS mavjud.\n\n" +
            "Keyingi bosqichlarda tabiiy AI suhbati, xotira, eslatmalar va Telegram integratsiyasi qo‘shiladi."
        ))
        page.addView(makeActionButton("Bosh sahifaga qaytish") { showHome() })
        setContentView(page)
    }

    private fun sectionTitle(value: String) = TextView(this).apply {
        text = value
        textSize = 12f
        letterSpacing = 0.15f
        setTextColor(cyan)
        setPadding(0, dp(12), 0, dp(10))
    }

    private fun infoText(value: String) = TextView(this).apply {
        text = value
        textSize = 14f
        setTextColor(Color.rgb(155, 180, 205))
        setPadding(0, dp(4), 0, dp(18))
    }

    private fun makeActionButton(label: String, action: () -> Unit) =
        TextView(this).apply {
            text = label
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(16), dp(12), dp(16))
            background = rounded(panel, 14, Color.rgb(20, 90, 130))
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(12)
            }
        }

    private fun startVoiceRecognition() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return
        }

        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "uz-UZ")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Nora sizni tinglamoqda")
            }
            startActivityForResult(intent, 101)
            statusMessage("SIZNI TINGLAYAPMAN...")
        } catch (_: Exception) {
            statusMessage("Ovozli tanish xizmati mavjud emas.")
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 100 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecognition()
        } else if (requestCode == 100) {
            statusMessage("Mikrofon ruxsati berilmadi.")
        }
    }

    @Deprecated("Legacy activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 101 && resultCode == RESULT_OK) {
            val spoken = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spoken.isNullOrBlank()) {
                answer.text = "SIZ:\n$spoken"
                statusMessage("BUYRUQ QABUL QILINDI")
                // Hozircha buyruq taniladi, keyingi bosqichda AI javobi ulanadi.
            } else {
                statusMessage("Ovoz aniqlanmadi.")
            }
        }
    }

    override fun onInit(result: Int) {
        if (result == TextToSpeech.SUCCESS) {
            ttsReady = true
            val engine = tts ?: return
            val uz = engine.setLanguage(Locale("uz", "UZ"))
            if (uz == TextToSpeech.LANG_MISSING_DATA ||
                uz == TextToSpeech.LANG_NOT_SUPPORTED
            ) {
                statusMessage("O‘zbekcha ovoz telefonda topilmadi. TTS sozlamasini tekshiring.")
            } else {
                statusMessage("TIZIM ISHGA TAYYOR")
                applyVoiceStyle()
            }
        } else {
            statusMessage("Ovoz xizmati ishga tushmadi.")
        }
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
        val engine = tts ?: return
        if (!ttsReady) {
            statusMessage("Ovoz xizmati tayyorlanmoqda.")
            return
        }
        applyVoiceStyle()
        engine.speak(message, TextToSpeech.QUEUE_FLUSH, null, "NORA_SPEECH")
    }

    private fun statusMessage(message: String) {
        if (::status.isInitialized) status.text = message
    }

    private fun rounded(color: Int, radius: Int, stroke: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            setStroke(dp(1), stroke)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        if (::orb.isInitialized) orb.stopAnimation()
        tts?.stop()
        tts?.shutdown()
        super.onDestroy()
    }

    inner class CoreView : View(this@MainActivity) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var angle = 0f
        private var running = true

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(width, height) * 0.27f

            paint.shader = RadialGradient(
                cx, cy, radius * 1.8f,
                intArrayOf(0x5530BFFF, 0x221080D0, 0x00000000),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius * 1.8f, paint)
            paint.shader = null

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2).toFloat()
            paint.color = 0xAA00BFFF.toInt()
            canvas.drawCircle(cx, cy, radius * 1.25f, paint)
            canvas.drawCircle(cx, cy, radius * 1.42f, paint)

            val rect = RectF(cx - radius * 1.55f, cy - radius * 1.55f,
                cx + radius * 1.55f, cy + radius * 1.55f)
            paint.strokeWidth = dp(3).toFloat()
            paint.color = Color.WHITE
            canvas.drawArc(rect, angle, 75f, false, paint)
            paint.color = cyan
            canvas.drawArc(rect, angle + 180f, 115f, false, paint)

            paint.style = Paint.Style.FILL
            paint.shader = RadialGradient(
                cx - radius * 0.25f, cy - radius * 0.3f, radius * 1.2f,
                intArrayOf(Color.rgb(90, 220, 255), Color.rgb(0, 85, 170),
                    Color.rgb(2, 15, 38)),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, radius, paint)
            paint.shader = null

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1).toFloat()
            paint.color = 0xAA8BE8FF.toInt()
            for (i in 0..5) {
                val a = Math.toRadians((angle + i * 60.0))
                val x1 = cx + cos(a).toFloat() * radius * 0.25f
                val y1 = cy + sin(a).toFloat() * radius * 0.25f
                val x2 = cx + cos(a).toFloat() * radius * 0.88f
                val y2 = cy + sin(a).toFloat() * radius * 0.88f
                canvas.drawLine(x1, y1, x2, y2, paint)
            }

            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            paint.textSize = dp(19).toFloat()
            canvas.drawText("N O R A", cx, cy + radius * 1.9f, paint)

            angle = (angle + 0.7f) % 360f
            if (running) postInvalidateDelayed(32)
        }

        fun stopAnimation() {
            running = false
        }
    }
}
