package uz.nora.ai

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.*
import kotlinx.coroutines.*

class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var chat: LinearLayout
    private lateinit var status: TextView
    private lateinit var message: EditText

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var asrDownloader: OfflineUzbekAsr? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action != NoraVoiceService.ACTION_UPDATE) return
            i.getStringExtra(NoraVoiceService.EXTRA_STATUS)?.let {
                status.text = it
            }
            i.getStringExtra(NoraVoiceService.EXTRA_HEARD)?.let {
                addMessage("SEN", it)
            }
            i.getStringExtra(NoraVoiceService.EXTRA_RESULT)?.let {
                addMessage("NORA", it)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(3, 9, 24)
        window.navigationBarColor = Color.rgb(3, 9, 24)
        showChat()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(NoraVoiceService.ACTION_UPDATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }
    }

    override fun onStop() {
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {}
        super.onStop()
    }

    private fun base(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.rgb(3, 9, 24),
                    Color.rgb(8, 25, 49),
                    Color.rgb(18, 12, 43)
                )
            )
        }

    private fun showChat() {
        root = base()

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(button("☰") { showSettings() },
            LinearLayout.LayoutParams(dp(52), dp(48)))

        header.addView(TextView(this).apply {
            text = "N O R A"
            textSize = 23f
            letterSpacing = .18f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(115, 225, 255))
        }, LinearLayout.LayoutParams(0, dp(48), 1f))

        root.addView(header)

        root.addView(NoraVisual(this),
            LinearLayout.LayoutParams(-1, dp(205)))

        status = TextView(this).apply {
            text = "NORA TAYYOR"
            gravity = Gravity.CENTER
            textSize = 12f
            setTextColor(Color.rgb(0, 220, 255))
            setPadding(0, dp(8), 0, dp(10))
        }
        root.addView(status)

        val scroll = ScrollView(this)
        chat = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(5), dp(8), dp(5), dp(8))
        }
        scroll.addView(chat)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        addMessage("NORA", "Salom. Men Nora. Sizga qanday yordam beray?")

        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(5), dp(7), dp(5))
            background = rounded(Color.rgb(13, 35, 60))
        }

        message = EditText(this).apply {
            hint = "Xabar yozing..."
            hintTextColor = Color.GRAY
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 4
            background = null
            setPadding(dp(10), dp(8), dp(5), dp(8))
        }

        row.addView(message, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(button("🎙") { requestMic() },
            LinearLayout.LayoutParams(dp(45), dp(45)))
        row.addView(button("➤") { sendTyped() },
            LinearLayout.LayoutParams(dp(45), dp(45)))

        root.addView(row)
        setContentView(root)
    }

    private fun sendTyped() {
        val text = message.text.toString().trim()
        if (text.isEmpty()) return

        message.setText("")
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(message.windowToken, 0)

        addMessage("SEN", text)

        val key = NoraSecureStore.read(this, "ai_key")
        val endpoint = NoraSecureStore.read(this, "ai_endpoint")
        val model = NoraSecureStore.read(this, "ai_model")

        if (!key.isNullOrBlank() &&
            !endpoint.isNullOrBlank() &&
            !model.isNullOrBlank()
        ) {
            status.text = "NORA JAVOB TAYYORLAMOQDA"

            scope.launch {
                try {
                    val answer = withContext(Dispatchers.IO) {
                        NoraOnlineAgent(this@MainActivity)
                            .askAI(text, model, endpoint)
                    }
                    addMessage("NORA", answer)
                    status.text = "NORA TAYYOR"
                } catch (e: Exception) {
                    addMessage("NORA", "Online xato: ${e.message}")
                    status.text = "ONLINE AI XATOSI"
                }
            }
        } else {
            val intent = Intent(this, NoraVoiceService::class.java).apply {
                action = NoraVoiceService.ACTION_TEXT_COMMAND
                putExtra(NoraVoiceService.EXTRA_COMMAND, text)
            }
            try {
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent)
                else startService(intent)
            } catch (e: Exception) {
                addMessage("NORA", "Xatolik: ${e.message}")
            }
        }
    }

    private fun requestMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return
        }
        startNora()
    }

    private fun startNora() {
        val intent = Intent(this, NoraVoiceService::class.java).apply {
            action = NoraVoiceService.ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent)
            else startService(intent)
            status.text = "NORA ISHGA TUSHMOQDA"
        } catch (e: Exception) {
            status.text = "XATO: ${e.message}"
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == 100 &&
            results.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) startNora()
    }

    private fun showSettings() {
        val page = base()

        page.addView(TextView(this).apply {
            text = "NORA SOZLAMALARI"
            textSize = 22f
            setTextColor(Color.rgb(110, 220, 255))
            setPadding(0, dp(12), 0, dp(18))
        })

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun section(title: String) {
            list.addView(TextView(this).apply {
                text = title
                textSize = 15f
                setTextColor(Color.rgb(0, 220, 255))
                setPadding(0, dp(16), 0, dp(8))
            })
        }

        fun field(hint: String, saved: String? = null, secret: Boolean = false): EditText {
            val input = EditText(this).apply {
                this.hint = hint
                setText(saved.orEmpty())
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                textSize = 14f
                setSingleLine(true)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(Color.rgb(13, 35, 60))
                if (secret) {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }
            list.addView(input, LinearLayout.LayoutParams(-1, dp(52)).apply {
                bottomMargin = dp(8)
            })
            return input
        }

        section("ONLINE AI")
        val endpoint = field(
            "API manzili, masalan https://.../v1",
            NoraSecureStore.read(this, "ai_endpoint")
        )
        val model = field(
            "Model nomi",
            NoraSecureStore.read(this, "ai_model")
        )
        val aiKey = field(
            "AI API kaliti",
            NoraSecureStore.read(this, "ai_key"),
            true
        )

        section("GITHUB")
        val githubToken = field(
            "GitHub Personal Access Token",
            NoraSecureStore.read(this, "github_token"),
            true
        )

        list.addView(button("SOZLAMALARNI SAQLASH") {
            try {
                saveField("ai_endpoint", endpoint.text.toString())
                saveField("ai_model", model.text.toString())
                saveField("ai_key", aiKey.text.toString())
                saveField("github_token", githubToken.text.toString())
                Toast.makeText(this, "Saqlangan", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Xato: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, LinearLayout.LayoutParams(-1, dp(52)))

        section("OFFLINE OVOZ MODELI")
        list.addView(button("GIGAAM MODELINI YUKLASH") {
            status.text = "MODEL YUKLANMOQDA"
            asrDownloader = OfflineUzbekAsr(
                this,
                { },
                { message ->
                    runOnUiThread {
                        status.text = message
                        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
                    }
                }
            )
            asrDownloader?.downloadModel()
        }, LinearLayout.LayoutParams(-1, dp(52)))

        list.addView(button("MODEL HOLATINI TEKSHIRISH") {
            val checker = OfflineUzbekAsr(this, {}, {})
            val ready = checker.ready()
            Toast.makeText(
                this,
                if (ready) "Offline model tayyor" else "Model hali yuklanmagan",
                Toast.LENGTH_LONG
            ).show()
        }, LinearLayout.LayoutParams(-1, dp(52)))

        section("OVOZLI BOSHQARUV")
        list.addView(button("TINGLASHNI TO‘XTATISH") {
            val intent = Intent(this, NoraVoiceService::class.java).apply {
                action = NoraVoiceService.ACTION_STOP
            }
            startService(intent)
            status.text = "NORA TO‘XTATILDI"
        }, LinearLayout.LayoutParams(-1, dp(52)))

        scroll.addView(list)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        page.addView(button("SUHBATGA QAYTISH") { showChat() })
        setContentView(page)
    }

    private fun saveField(name: String, value: String) {
        if (value.isBlank()) NoraSecureStore.delete(this, name)
        else NoraSecureStore.save(this, name, value.trim())
    }

    private fun addMessage(who: String, text: String) {
        val bubble = TextView(this).apply {
            this.text = "$who\n$text"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = rounded(
                if (who == "SEN") Color.rgb(17, 65, 91)
                else Color.rgb(20, 36, 67)
            )
        }
        chat.addView(bubble, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(9)
        })
    }

    private fun button(label: String, action: () -> Unit) =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 14f
            setTextColor(Color.rgb(140, 230, 255))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { action() }
        }

    private fun rounded(color: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(15).toFloat()
            setStroke(dp(1), Color.rgb(0, 145, 205))
        }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        scope.cancel()
        asrDownloader = null
        super.onDestroy()
    }
}

private class NoraVisual(c: Context) : android.view.View(c) {
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        val x = width / 2f
        val y = height / 2f

        paint.shader = android.graphics.RadialGradient(
            x, y, width * .48f,
            intArrayOf(
                Color.argb(100, 0, 210, 255),
                Color.argb(30, 30, 80, 230),
                Color.TRANSPARENT
            ), null, android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x, y, width * .48f, paint)
        paint.shader = null

        paint.color = Color.rgb(15, 48, 78)
        canvas.drawRoundRect(x - 75, y - 70, x + 75, y + 70, 32f, 32f, paint)

        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = Color.rgb(0, 215, 255)
        canvas.drawRoundRect(x - 75, y - 70, x + 75, y + 70, 32f, 32f, paint)
        paint.style = android.graphics.Paint.Style.FILL

        paint.color = Color.rgb(0, 220, 255)
        canvas.drawCircle(x - 32, y - 15, 11f, paint)
        canvas.drawCircle(x + 32, y - 15, 11f, paint)

        paint.color = Color.rgb(80, 210, 255)
        paint.style = android.graphics.Paint.Style.STROKE
        canvas.drawArc(x - 25, y + 3, x + 25, y + 40, 10f, 150f, false, paint)
        paint.style = android.graphics.Paint.Style.FILL

        paint.color = Color.rgb(20, 75, 105)
        canvas.drawRoundRect(x - 50, y + 75, x + 50, y + 135, 20f, 20f, paint)
    }
}
