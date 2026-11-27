package uz.nora.ai

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.*
import kotlinx.coroutines.*

class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var chat: LinearLayout
    private lateinit var status: TextView
    private lateinit var message: EditText
    private lateinit var offlineAI: OfflineNoraAI

    private var downloadingOfflineAI = false
    private var downloadingAsr = false
    private var settingsVisible = false
    private var approvalDialog: AlertDialog? = null
    private var currentApprovalId: String? = null

    private val approvalQueue =
        ArrayDeque<PendingGitHubApproval>()

    private val queuedApprovalIds =
        mutableSetOf<String>()

    private val activityScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val memory by lazy { NoraConversationMemory(this) }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != NoraVoiceService.ACTION_UPDATE) return

            intent.getStringExtra(NoraVoiceService.EXTRA_STATUS)?.let {
                if (::status.isInitialized) status.text = it
            }

            intent.getStringExtra(NoraVoiceService.EXTRA_HEARD)?.let {
                addMessage("SEN", it)
            }

            intent.getStringExtra(NoraVoiceService.EXTRA_RESULT)?.let {
                addMessage("NORA", it)
            }

            val changeId = intent.getStringExtra(
                NoraVoiceService.EXTRA_CHANGE_ID
            )
            val preview = intent.getStringExtra(
                NoraVoiceService.EXTRA_CHANGE_PREVIEW
            )

            if (!changeId.isNullOrBlank() && !preview.isNullOrBlank()) {
                enqueueApproval(changeId, preview)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        offlineAI = OfflineNoraAI(this)
        window.statusBarColor = Color.rgb(3, 9, 24)
        window.navigationBarColor = Color.rgb(3, 9, 24)
        showChat()
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

        restorePendingApprovals()
    }

    override fun onStop() {
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
        super.onStop()
    }

    override fun onDestroy() {
        approvalDialog?.dismiss()
        approvalDialog = null
        activityScope.cancel()
        offlineAI.close()
        super.onDestroy()
    }

    @Deprecated("Deprecated Android back callback")
    override fun onBackPressed() {
        if (settingsVisible) showChat() else super.onBackPressed()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun background() = GradientDrawable(
        GradientDrawable.Orientation.TL_BR,
        intArrayOf(
            Color.rgb(3, 9, 24),
            Color.rgb(8, 25, 49),
            Color.rgb(18, 12, 43)
        )
    )

    private fun base() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(12), dp(16), dp(12))
        background = this@MainActivity.background()
    }

    private fun rounded(color: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(15).toFloat()
        setStroke(dp(1), Color.rgb(0, 145, 205))
    }

    private fun textButton(
        label: String,
        action: () -> Unit
    ) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        textSize = 14f
        setTextColor(Color.rgb(140, 230, 255))
        setPadding(dp(8), dp(8), dp(8), dp(8))
        setOnClickListener { action() }
    }

    private fun showChat() {
        settingsVisible = false
        root = base()

        val header = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
        }

        header.addView(
            textButton("☰") { showSettings() },
            LinearLayout.LayoutParams(dp(52), dp(48))
        )

        header.addView(
            TextView(this).apply {
                text = "N O R A"
                textSize = 23f
                letterSpacing = .18f
                gravity = Gravity.CENTER
                setTextColor(Color.rgb(115, 225, 255))
            },
            LinearLayout.LayoutParams(0, dp(48), 1f)
        )

        header.addView(
            textButton("Ⅱ") { stopNora() },
            LinearLayout.LayoutParams(dp(48), dp(48))
        )

        root.addView(header)
        root.addView(
            NoraVisual(this),
            LinearLayout.LayoutParams(-1, dp(205))
        )

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

        restoreChat()

        val row = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(7), dp(5), dp(7), dp(5))
            background = rounded(Color.rgb(13, 35, 60))
        }

        message = EditText(this).apply {
            hint = "Xabar yozing..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 4
            background = null
            setPadding(dp(10), dp(8), dp(5), dp(8))
        }

        row.addView(message, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(
            textButton("🎙") { requestMic() },
            LinearLayout.LayoutParams(dp(45), dp(45))
        )
        row.addView(
            textButton("➤") { sendTyped() },
            LinearLayout.LayoutParams(dp(45), dp(45))
        )

        root.addView(row)
        setContentView(root)
    }

    private fun restoreChat() {
        val history = memory.getHistory()

        if (history.length() == 0) {
            addMessage("NORA", "Salom. Men Nora. Sizga qanday yordam beray?")
            return
        }

        for (i in 0 until history.length()) {
            val item = history.optJSONObject(i) ?: continue
            val role = item.optString("role")
            val content = item.optString("content")
            if (content.isBlank()) continue
            addMessage(if (role == "user") "SEN" else "NORA", content)
        }
    }

    private fun sendTyped() {
        val value = message.text.toString().trim()
        if (value.isEmpty()) return

        message.setText("")
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(message.windowToken, 0)

        try {
            val intent = Intent(this, NoraVoiceService::class.java).apply {
                action = NoraVoiceService.ACTION_TEXT_COMMAND
                putExtra(NoraVoiceService.EXTRA_COMMAND, value)
            }
            startService(intent)
            status.text = "NORA BUYRUQNI BAJARMOQDA"
        } catch (e: Exception) {
            addMessage(
                "NORA",
                "Xatolik: ${e.message ?: "Xizmat ishga tushmadi"}"
            )
        }
    }

    private fun restorePendingApprovals() {
        try {
            val pending = NoraGitHubChangeManager(this).listPending()
            pending.forEach { item ->
                enqueueApproval(item.id, item.preview)
            }
        } catch (e: Exception) {
            if (::status.isInitialized) {
                status.text = "GITHUB TASDIQLARINI O'QISH XATOSI"
            }
            addMessage(
                "NORA",
                "Saqlangan GitHub tasdiqlarini tiklab bo'lmadi: ${e.message}"
            )
        }
    }

    private fun enqueueApproval(id: String, preview: String) {
        if (id.isBlank() || preview.isBlank()) return
        if (id == currentApprovalId || !queuedApprovalIds.add(id)) return

        approvalQueue.addLast(
            PendingGitHubApproval(
                id = id,
                owner = "",
                repo = "",
                path = "",
                preview = preview,
                createdAt = System.currentTimeMillis(),
                expiresAt = System.currentTimeMillis() + 15 * 60 * 1000L
            )
        )

        showNextApproval()
    }

    private fun showNextApproval() {
        if (isFinishing || isDestroyed) return
        if (approvalDialog?.isShowing == true) return

        while (approvalQueue.isNotEmpty()) {
            val item = approvalQueue.removeFirst()

            if (item.expiresAt <= System.currentTimeMillis()) {
                queuedApprovalIds.remove(item.id)
                continue
            }

            displayApproval(item.id, item.preview)
            return
        }
    }

    private fun displayApproval(id: String, preview: String) {
        if (isFinishing || isDestroyed) return

        currentApprovalId = id

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }

        container.addView(
            TextView(this).apply {
                text = "Quyidagi o'zgarishni diqqat bilan tekshiring. " +
                    "Tasdiqlash GitHub repository faylini o'zgartiradi."
                textSize = 14f
                setTextColor(Color.WHITE)
                setPadding(0, 0, 0, dp(10))
            }
        )

        val previewScroll = ScrollView(this).apply {
            addView(
                TextView(this@MainActivity).apply {
                    text = preview
                    textSize = 12f
                    setTextColor(Color.WHITE)
                    setTextIsSelectable(true)
                    setPadding(dp(8), dp(8), dp(8), dp(8))
                }
            )
        }

        container.addView(
            previewScroll,
            LinearLayout.LayoutParams(-1, dp(300))
        )

        val reviewed = CheckBox(this).apply {
            text = "O'zgarishni ko'rib chiqdim va commitga roziman"
            setTextColor(Color.WHITE)
            buttonTintList = android.content.res.ColorStateList.valueOf(
                Color.rgb(0, 220, 255)
            )
        }

        container.addView(reviewed)

        val dialog = AlertDialog.Builder(this)
            .setTitle("GitHub o'zgarishini tasdiqlash")
            .setView(container)
            .setPositiveButton("COMMIT QILISH", null)
            .setNegativeButton("BEKOR QILISH", null)
            .setCancelable(false)
            .create()

        approvalDialog = dialog

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false

            reviewed.setOnCheckedChangeListener { _, checked ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = checked
            }

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!reviewed.isChecked) return@setOnClickListener

                sendGitHubDecision(
                    NoraVoiceService.ACTION_CONFIRM_CHANGE,
                    id
                )
                finishApprovalDialog(dialog, id)
            }

            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                sendGitHubDecision(
                    NoraVoiceService.ACTION_CANCEL_CHANGE,
                    id
                )
                finishApprovalDialog(dialog, id)
            }
        }

        dialog.show()
    }

    private fun finishApprovalDialog(
        dialog: AlertDialog,
        id: String
    ) {
        queuedApprovalIds.remove(id)
        currentApprovalId = null
        approvalDialog = null
        dialog.dismiss()

        window.decorView.post {
            showNextApproval()
        }
    }

    private fun sendGitHubDecision(action: String, id: String) {
        try {
            val intent = Intent(this, NoraVoiceService::class.java).apply {
                this.action = action
                putExtra(NoraVoiceService.EXTRA_CHANGE_ID, id)
            }
            startService(intent)

            status.text = if (
                action == NoraVoiceService.ACTION_CONFIRM_CHANGE
            ) {
                "GITHUB COMMIT SO'ROVI YUBORILDI"
            } else {
                "GITHUB O'ZGARISHI BEKOR QILINDI"
            }
        } catch (e: Exception) {
            addMessage("NORA", "Xatolik: ${e.message}")
        }
    }

    private fun requestMic() {
        if (
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                100
            )
            return
        }
        requestNotificationPermission()
    }

    private fun requestNotificationPermission() {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                101
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
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent)
            else startService(intent)
            status.text = "NORA ISHGA TUSHMOQDA"
        } catch (e: Exception) {
            status.text = "XATO: ${e.message}"
        }
    }

    private fun stopNora() {
        try {
            startService(
                Intent(this, NoraVoiceService::class.java).apply {
                    action = NoraVoiceService.ACTION_STOP
                }
            )
            status.text = "NORA TO'XTATILDI"
        } catch (e: Exception) {
            status.text = "XATO: ${e.message}"
        }
    }

    @Deprecated("Deprecated Android permission callback")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        results: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, results)

        if (results.firstOrNull() != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(
                this,
                "Ruxsat berilmadi",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        when (requestCode) {
            100 -> requestNotificationPermission()
            101 -> startNora()
        }
    }

    private fun showSettings() {
        settingsVisible = true
        val page = base()

        page.addView(
            TextView(this).apply {
                text = "NORA SOZLAMALARI"
                textSize = 22f
                setTextColor(Color.rgb(110, 220, 255))
                setPadding(0, dp(12), 0, dp(18))
            }
        )

        val scroll = ScrollView(this)
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun section(title: String) {
            list.addView(
                TextView(this).apply {
                    text = title
                    textSize = 15f
                    setTextColor(Color.rgb(0, 220, 255))
                    setPadding(0, dp(16), 0, dp(8))
                }
            )
        }

        fun field(
            hintText: String,
            saved: String?,
            secret: Boolean = false
        ): EditText {
            val input = EditText(this).apply {
                hint = hintText
                setText(saved.orEmpty())
                setTextColor(Color.WHITE)
                setHintTextColor(Color.GRAY)
                textSize = 14f
                setSingleLine(true)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = rounded(Color.rgb(13, 35, 60))

                if (secret) {
                    inputType = InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }

            list.addView(
                input,
                LinearLayout.LayoutParams(-1, dp(52)).apply {
                    bottomMargin = dp(8)
                }
            )
            return input
        }

        section("ONLINE AI")
        val endpoint = field(
            "API manzili",
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
            "GitHub token",
            NoraSecureStore.read(this, "github_token"),
            true
        )

        list.addView(
            textButton("SOZLAMALARNI SAQLASH") {
                try {
                    save("ai_endpoint", endpoint.text.toString())
                    save("ai_model", model.text.toString())
                    save("ai_key", aiKey.text.toString())
                    save("github_token", githubToken.text.toString())
                    Toast.makeText(
                        this,
                        "Sozlamalar saqlandi",
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        this,
                        "Xato: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            },
            LinearLayout.LayoutParams(-1, dp(52))
        )

        section("ISH REJIMI")
        list.addView(
            textButton("OFFLINE REJIMNI YOQISH") {
                save("offline_only", "true")
                Toast.makeText(
                    this,
                    "Offline rejim yoqildi",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )
        list.addView(
            textButton("ONLINE REJIMNI YOQISH") {
                NoraSecureStore.delete(this, "offline_only")
                Toast.makeText(
                    this,
                    "Online rejim yoqildi",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )

        section("OFFLINE AI — QWEN3 1.7B")
        list.addView(
            TextView(this).apply {
                text = "Model hajmi taxminan 1.1 GB. Wi-Fi va kamida 2 GB bo'sh joy tavsiya etiladi."
                textSize = 12f
                setTextColor(Color.LTGRAY)
            }
        )
        list.addView(
            textButton("QWEN3 MODELINI YUKLASH") {
                downloadOfflineAI()
            }
        )
        list.addView(
            textButton("QWEN3 MODEL HOLATINI TEKSHIRISH") {
                Toast.makeText(
                    this,
                    if (offlineAI.isReady()) "Model tayyor"
                    else "Model yuklanmagan",
                    Toast.LENGTH_LONG
                ).show()
            }
        )

        section("OFFLINE OVOZNI TANISH")
        list.addView(
            textButton("GIGAAM OVOZ MODELINI YUKLASH") {
                downloadAsrModel()
            }
        )
        list.addView(
            textButton("GIGAAM MODEL HOLATINI TEKSHIRISH") {
                val asr = OfflineUzbekAsr(this, {}, {})
                val ready = asr.ready()
                asr.close()

                Toast.makeText(
                    this,
                    if (ready) "GigaAM modeli tayyor"
                    else "GigaAM modeli yuklanmagan",
                    Toast.LENGTH_LONG
                ).show()
            }
        )

        section("XOTIRA")
        list.addView(
            textButton("SUHBAT XOTIRASINI TOZALASH") {
                memory.clear()
                if (::chat.isInitialized) {
                    chat.removeAllViews()
                    addMessage("NORA", "Suhbat xotirasi tozalandi.")
                }
                Toast.makeText(
                    this,
                    "Xotira tozalandi",
                    Toast.LENGTH_SHORT
                ).show()
            }
        )

        scroll.addView(list)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        page.addView(textButton("SUHBATGA QAYTISH") { showChat() })
        setContentView(page)
    }

    private fun downloadAsrModel() {
        if (downloadingAsr) {
            Toast.makeText(
                this,
                "GigaAM modeli yuklanmoqda",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        downloadingAsr = true
        Toast.makeText(
            this,
            "GigaAM modeli yuklanmoqda",
            Toast.LENGTH_SHORT
        ).show()

        activityScope.launch {
            val asr = OfflineUzbekAsr(
                this@MainActivity,
                {},
                { msg ->
                    runOnUiThread {
                        if (::status.isInitialized) status.text = msg
                    }
                }
            )

            try {
                withContext(Dispatchers.IO) {
                    asr.downloadModel()
                }

                Toast.makeText(
                    this@MainActivity,
                    "GigaAM modeli yuklandi",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Xato: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                asr.close()
                downloadingAsr = false
            }
        }
    }

    private fun downloadOfflineAI() {
        if (downloadingOfflineAI) {
            Toast.makeText(
                this,
                "Model yuklanmoqda",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (offlineAI.isReady()) {
            Toast.makeText(
                this,
                "Qwen3 modeli tayyor",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        downloadingOfflineAI = true
        status.text = "QWEN3 YUKLASH BOSHLANDI"

        activityScope.launch {
            try {
                offlineAI.downloadModel { percent ->
                    runOnUiThread {
                        if (::status.isInitialized) {
                            status.text = "QWEN3 YUKLANMOQDA: $percent%"
                        }
                    }
                }

                status.text = "QWEN3 OFFLINE MODELI TAYYOR"
                Toast.makeText(
                    this@MainActivity,
                    "Qwen3 modeli tayyor",
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                status.text = "QWEN3 YUKLASH XATOSI"
                Toast.makeText(
                    this@MainActivity,
                    "Xato: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                downloadingOfflineAI = false
            }
        }
    }

    private fun save(name: String, value: String) {
        if (value.isBlank()) NoraSecureStore.delete(this, name)
        else NoraSecureStore.save(this, name, value.trim())
    }

    private fun addMessage(who: String, value: String) {
        if (!::chat.isInitialized) return

        val bubble = TextView(this).apply {
            text = "$who\n$value"
            textSize = 14f
            setTextColor(Color.WHITE)
            setPadding(dp(14), dp(11), dp(14), dp(11))
            background = rounded(
                if (who == "SEN") Color.rgb(17, 65, 91)
                else Color.rgb(20, 36, 67)
            )
        }

        chat.addView(
            bubble,
            LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = dp(9)
            }
        )
    }
}

private class NoraVisual(context: android.content.Context) :
    android.view.View(context) {

    private val paint = android.graphics.Paint(
        android.graphics.Paint.ANTI_ALIAS_FLAG
    )

    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)

        val x = width / 2f
        val y = height / 2f

        paint.shader = android.graphics.RadialGradient(
            x,
            y,
            width * .48f,
            intArrayOf(
                Color.argb(100, 0, 210, 255),
                Color.argb(30, 30, 80, 230),
                Color.TRANSPARENT
            ),
            null,
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawCircle(x, y, width * .48f, paint)
        paint.shader = null

        paint.color = Color.rgb(15, 48, 78)
        canvas.drawRoundRect(
            x - 75, y - 70, x + 75, y + 70,
            32f, 32f, paint
        )

        paint.style = android.graphics.Paint.Style.STROKE
        paint.strokeWidth = 4f
        paint.color = Color.rgb(0, 215, 255)
        canvas.drawRoundRect(
            x - 75, y - 70, x + 75, y + 70,
            32f, 32f, paint
        )

        paint.style = android.graphics.Paint.Style.FILL
        paint.color = Color.rgb(0, 220, 255)
        canvas.drawCircle(x - 32, y - 15, 11f, paint)
        canvas.drawCircle(x + 32, y - 15, 11f, paint)

        paint.color = Color.rgb(80, 210, 255)
        paint.style = android.graphics.Paint.Style.STROKE
        canvas.drawArc(
            x - 25, y + 3, x + 25, y + 40,
            10f, 150f, false, paint
        )

        paint.style = android.graphics.Paint.Style.FILL
        paint.color = Color.rgb(20, 75, 105)
        canvas.drawRoundRect(
            x - 50, y + 75, x + 50, y + 135,
            20f, 20f, paint
        )
    }
}
