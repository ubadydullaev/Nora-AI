package uz.nora.ai

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import android.view.View

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var conversation: TextView
    private lateinit var asrDownloader: OfflineUzbekAsr

    private var listening = false
    private var receiverRegistered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != NoraVoiceService.ACTION_UPDATE) return

            intent.getStringExtra(NoraVoiceService.EXTRA_STATUS)?.let {
                status.text = it
            }

            intent.getStringExtra(NoraVoiceService.EXTRA_HEARD)?.let {
                conversation.text = "SEN:\n$it"
            }

            intent.getStringExtra(NoraVoiceService.EXTRA_RESULT)?.let {
                conversation.text =
                    conversation.text.toString() + "\n\nNORA:\n$it"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(5, 10, 25)
        window.navigationBarColor = Color.rgb(5, 10, 25)

        asrDownloader = OfflineUzbekAsr(
            this,
            {},
            { message -> runOnUiThread { status.text = message } }
        )

        showHome()
    }

    override fun onStart() {
        super.onStart()

        val filter = IntentFilter(NoraVoiceService.ACTION_UPDATE)

        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(
                receiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(receiver, filter)
        }

        receiverRegistered = true
    }

    override fun onStop() {
        if (receiverRegistered) {
            unregisterReceiver(receiver)
            receiverRegistered = false
        }
        super.onStop()
    }

    private fun showHome() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(12), dp(20), dp(16))

            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.rgb(4, 10, 28),
                    Color.rgb(8, 25, 48),
                    Color.rgb(12, 9, 38)
                )
            )
        }

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
        }

        val logo = TextView(this).apply {
            text = "N O R A"
            textSize = 25f
            letterSpacing = 0.15f
            setTextColor(Color.rgb(130, 225, 255))
            typeface = Typeface.create(
                "sans-serif-light",
                Typeface.NORMAL
            )
        }

        header.addView(
            logo,
            LinearLayout.LayoutParams(0, dp(55), 1f)
        )

        val settings = TextView(this).apply {
            text = "⚙"
            textSize = 29f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setOnClickListener { showSettings() }
        }

        header.addView(settings, LinearLayout.LayoutParams(dp(50), dp(50)))
        root.addView(header)

        val orb = NoraRobotView(this)
        root.addView(
            orb,
            LinearLayout.LayoutParams(-1, dp(230))
        )

        status = TextView(this).apply {
            text = "NORA TAYYOR"
            textSize = 12f
            gravity = Gravity.CENTER
            letterSpacing = 0.12f
            setTextColor(Color.rgb(0, 220, 255))
            setPadding(0, dp(8), 0, dp(12))
        }

        root.addView(status)

        conversation = TextView(this).apply {
            text = "Nora bilan suhbat shu yerda ko‘rinadi.\n\n" +
                "Buyruq berish uchun: «Nora, Telegramni och»."
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = rounded(
                Color.rgb(13, 32, 58),
                Color.rgb(0, 150, 220)
            )
        }

        root.addView(
            conversation,
            LinearLayout.LayoutParams(-1, 0, 1f).apply {
                bottomMargin = dp(12)
            }
        )

        val download = makeButton("OFFLINE OVOZ MODELINI YUKLASH") {
            status.text = "OVOZ MODELI YUKLANMOQDA"
            asrDownloader.downloadModel()
        }

        root.addView(download)

        val listen = makeButton("NORA'NI ISHGA TUSHIR") {
            requestAndStart()
        }

        root.addView(listen)

        val stop = makeButton("TINGLASHNI TO‘XTATISH") {
            stopNora()
        }

        root.addView(stop)

        root.addView(
            TextView(this).apply {
                text = "NORA • PERSONAL VOICE ASSISTANT"
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(105, 145, 180))
                setPadding(0, dp(12), 0, 0)
            }
        )

        setContentView(root)
    }

    private fun requestAndStart() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                100
            )
            return
        }

        startNora()
    }

    private fun startNora() {
        val intent = Intent(this, NoraVoiceService::class.java).apply {
            action = NoraVoiceService.ACTION_START
        }

        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }

            listening = true
            status.text = "NORA ISHGA TUSHMOQDA"
        } catch (e: Exception) {
            status.text = "XIZMATNI ISHGA TUSHIRIB BO‘LMADI: ${e.message}"
        }
    }

    private fun stopNora() {
        val intent = Intent(this, NoraVoiceService::class.java).apply {
            action = NoraVoiceService.ACTION_STOP
        }

        startService(intent)
        listening = false
        status.text = "NORA TO‘XTATILDI"
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
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        ) {
            startNora()
        } else if (requestCode == 100) {
            status.text = "MIKROFON RUXSATI KERAK"
        }
    }

    private fun showSettings() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.rgb(5, 12, 30),
                    Color.rgb(15, 25, 55)
                )
            )
        }

        page.addView(settingTitle("NORA SOZLAMALARI"))

        page.addView(settingCard(
            "OVOZ VA TINGLASH",
            "Nora ovozni avtomatik aniqlaydi. Hozircha buyruq «Nora» murojaati bilan boshlanadi."
        ))

        page.addView(settingCard(
            "OFFLINE REJIM",
            "Internet talab qilmaydigan telefon buyruqlari qurilmaning o‘zida bajariladi."
        ))

        page.addView(settingCard(
            "ONLINE REJIM",
            "Online suhbat va erkak ovozli javob keyingi bosqichda ulanadi."
        ))

        page.addView(settingCard(
            "FON REJIMI",
            "Ekran o‘chganida tinglash uchun Nora xizmatini ishga tushiring va Android batareya sozlamalarida cheklovni olib tashlang."
        ))

        page.addView(makeButton("ORTGA") {
            showHome()
        })

        setContentView(ScrollView(this).apply {
            addView(page)
        })
    }

    private fun settingTitle(value: String) =
        TextView(this).apply {
            text = value
            textSize = 22f
            setTextColor(Color.rgb(120, 220, 255))
            setPadding(0, 0, 0, dp(20))
        }

    private fun settingCard(title: String, detail: String): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(15))
            background = rounded(
                Color.rgb(12, 30, 55),
                Color.rgb(25, 100, 150)
            )
        }

        box.addView(TextView(this).apply {
            text = title
            textSize = 14f
            setTextColor(Color.rgb(0, 220, 255))
        })

        box.addView(TextView(this).apply {
            text = detail
            textSize = 13f
            setTextColor(Color.LTGRAY)
            setPadding(0, dp(7), 0, 0)
        })

        box.layoutParams = LinearLayout.LayoutParams(
            -1,
            -2
        ).apply {
            bottomMargin = dp(12)
        }

        return box
    }

    private fun makeButton(
        label: String,
        action: () -> Unit
    ): TextView {
        return TextView(this).apply {
            text = label
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(12), dp(16), dp(12), dp(16))

            background = rounded(
                Color.rgb(12, 38, 67),
                Color.rgb(0, 145, 210)
            )

            setOnClickListener { action() }

            layoutParams = LinearLayout.LayoutParams(
                -1,
                -2
            ).apply {
                bottomMargin = dp(9)
            }
        }
    }

    private fun rounded(color: Int, stroke: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(15).toFloat()
            setStroke(dp(1), stroke)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        asrDownloader.close()
        super.onDestroy()
    }
}

private class NoraRobotView(context: Context) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val cx = width / 2f
        val cy = height / 2f

        paint.shader = RadialGradient(
            cx,
            cy,
            width * 0.42f,
            intArrayOf(
                Color.argb(100, 0, 220, 255),
                Color.argb(30, 0, 110, 255),
                Color.TRANSPARENT
            ),
            null,
            Shader.TileMode.CLAMP
        )

        canvas.drawCircle(cx, cy, width * 0.42f, paint)
        paint.shader = null

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.rgb(0, 190, 255)
        canvas.drawCircle(cx, cy, 82f, paint)

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(13, 35, 65)
        canvas.drawRoundRect(
            cx - 58,
            cy - 48,
            cx + 58,
            cy + 52,
            30f,
            30f,
            paint
        )

        paint.color = Color.rgb(0, 210, 255)
        canvas.drawRoundRect(
            cx - 37,
            cy - 12,
            cx - 12,
            cy + 8,
            10f,
            10f,
            paint
        )

        canvas.drawRoundRect(
            cx + 12,
            cy - 12,
            cx + 37,
            cy + 8,
            10f,
            10f,
            paint
        )

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.rgb(100, 220, 255)
        canvas.drawArc(
            cx - 22,
            cy + 5,
            cx + 22,
            cy + 32,
            15f,
            150f,
            false,
            paint
        )

        paint.style = Paint.Style.FILL
        paint.color = Color.rgb(0, 180, 230)
        canvas.drawCircle(cx - 66, cy, 7f, paint)
        canvas.drawCircle(cx + 66, cy, 7f, paint)
    }
}
