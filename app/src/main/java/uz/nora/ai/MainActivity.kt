
package uz.nora.ai

import android.Manifest
import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*

class MainActivity : Activity() {
    private lateinit var root: LinearLayout
    private lateinit var chat: LinearLayout
    private lateinit var status: TextView
    private lateinit var message: EditText
    private var registered = false

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
        val f = IntentFilter(NoraVoiceService.ACTION_UPDATE)
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, f)
        }
        registered = true
    }

    override fun onStop() {
        if (registered) unregisterReceiver(receiver)
        registered = false
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

        val menu = button("☰") { showSettings() }
        header.addView(menu, LinearLayout.LayoutParams(dp(52), dp(48)))

        header.addView(TextView(this).apply {
            text = "N O R A"
            textSize = 23f
            letterSpacing = .18f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(115, 225, 255))
        }, LinearLayout.LayoutParams(0, dp(48), 1f))

        header.addView(TextView(this).apply {
            text = "●"
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(Color.rgb(0, 230, 180))
        }, LinearLayout.LayoutParams(dp(35), dp(48)))

        root.addView(header)

        val hero = NoraVisual(this)
        root.addView(hero, LinearLayout.LayoutParams(-1, dp(205)))

        status = TextView(this).apply {
            text = "NORA TAYYOR"
            gravity = Gravity.CENTER
            textSize = 12f
            letterSpacing = .12f
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

        val inputRow = LinearLayout(this).apply {
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
            setSingleLine(false)
            background = null
            setPadding(dp(10), dp(8), dp(5), dp(8))
        }
        inputRow.addView(message, LinearLayout.LayoutParams(0, -2, 1f))

        inputRow.addView(button("🎙") {
            requestMic()
        }, LinearLayout.LayoutParams(dp(45), dp(45)))

        inputRow.addView(button("➤") {
            sendTyped()
        }, LinearLayout.LayoutParams(dp(45), dp(45)))

        root.addView(inputRow)
        setContentView(root)
    }

    private fun sendTyped() {
        val text = message.text.toString().trim()
        if (text.isEmpty()) return
        addMessage("SEN", text)
        message.setText("")

        val i = Intent(this, NoraVoiceService::class.java).apply {
            action = NoraVoiceService.ACTION_TEXT_COMMAND
            putExtra(NoraVoiceService.EXTRA_COMMAND, text)
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i)
            else startService(i)
        } catch (e: Exception) {
            addMessage("NORA", "Xatolik: ${e.message}")
        }
    }

    private fun requestMic() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 100)
            return
        }
        startNora()
    }

    private fun startNora() {
        val i = Intent(this, NoraVoiceService::class.java).apply {
            action = NoraVoiceService.ACTION_START
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i)
            else startService(i)
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
            results.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startNora()
        } else status.text = "MIKROFON RUXSATI BERILMAGAN"
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
        val p = LinearLayout.LayoutParams(
            -1, -2
        ).apply {
            bottomMargin = dp(9)
        }
        chat.addView(bubble, p)
    }

    private fun showSettings() {
        val page = base()
        page.addView(TextView(this).apply {
            text = "NORA  /  SOZLAMALAR"
            textSize = 22f
            setTextColor(Color.rgb(110, 220, 255))
            setPadding(0, dp(15), 0, dp(20))
        })

        val items = listOf(
            "OVOZ VA TINGLASH" to "Mikrofon, ovozni tanish va tinglash rejimi",
            "OFFLINE AI" to "Mahalliy model va internet bo‘lmagan rejim",
            "ONLINE AI" to "Internet orqali suhbat va vazifalar",
            "GITHUB" to "Kod yozish, tahlil, Actions va repozitoriy",
            "OVOZ" to "Erkak ovozi va gapirish tezligi",
            "FON VA DIZAYN" to "Nora ko‘rinishi va fon rasmlari",
            "TELEFON BOSHQARUVI" to "Ilovalar va telefon buyruqlari",
            "FON REJIMI" to "Avtoishga tushish va batareya cheklovlari",
            "XAVFSIZLIK" to "Ruxsatlar va tasdiqlash talablari"
        )

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        items.forEach { item ->
            val card = TextView(this).apply {
                text = "${item.first}\n${item.second}"
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(dp(16), dp(17), dp(16), dp(17))
                background = rounded(Color.rgb(13, 36, 62))
            }
            list.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(11)
            })
        }

        scroll.addView(list)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        page.addView(button("SUHBATGA QAYTISH") { showChat() })
        setContentView(page)
    }

    private fun button(label: String, action: () -> Unit) =
        TextView(this).apply {
            text = label
            gravity = Gravity.CENTER
            textSize = 17f
            setTextColor(Color.rgb(140, 230, 255))
            setOnClickListener { action() }
        }

    private fun rounded(color: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(17).toFloat()
            setStroke(dp(1), Color.rgb(0, 145, 205))
        }

    private fun dp(v: Int) =
        (v * resources.displayMetrics.density).toInt()
}

private class NoraVisual(c: Context) : View(c) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val x = width / 2f
        val y = height / 2f

        p.shader = RadialGradient(
            x, y, width * .48f,
            intArrayOf(
                Color.argb(100, 0, 210, 255),
                Color.argb(30, 30, 80, 230),
                Color.TRANSPARENT
            ), null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x, y, width * .48f, p)
        p.shader = null

        p.color = Color.rgb(20, 65, 95)
        canvas.drawRoundRect(
            x - 62, y - 67, x + 62, y + 66, 28f, 28f, p
        )

        p.style = Paint.Style.STROKE
        p.strokeWidth = 4f
        p.color = Color.rgb(0, 215, 255)
        canvas.drawRoundRect(
            x - 62, y - 67, x + 62, y + 66, 28f, 28f, p
        )
        p.style = Paint.Style.FILL

        p.color = Color.rgb(0, 210, 255)
        canvas.drawCircle(x - 27, y - 16, 9f, p)
        canvas.drawCircle(x + 27, y - 16, 9f, p)

        p.color = Color.rgb(80, 210, 255)
        p.strokeWidth = 4f
        p.style = Paint.Style.STROKE
        canvas.drawArc(x - 22, y + 2, x + 22, y + 37, 15f, 150f, false, p)
        p.style = Paint.Style.FILL

        p.color = Color.rgb(20, 75, 105)
        canvas.drawRoundRect(x - 43, y + 70, x + 43, y + 125, 18f, 18f, p)

        p.color = Color.rgb(0, 185, 230)
        canvas.drawCircle(x - 78, y - 10, 8f, p)
        canvas.drawCircle(x + 78, y - 10, 8f, p)
    }
}
