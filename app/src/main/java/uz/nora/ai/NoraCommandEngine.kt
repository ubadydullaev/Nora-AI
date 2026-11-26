
package uz.nora.ai

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object NoraCommandEngine {

    private const val UNKNOWN = "Bu buyruq hali qo'shilmagan"

    private data class AppTarget(
        val names: List<String>,
        val packages: List<String>,
        val label: String
    )

    private val apps = listOf(
        AppTarget(listOf("telegram"), listOf("org.telegram.messenger"), "Telegram"),
        AppTarget(listOf("youtube"), listOf("com.google.android.youtube"), "YouTube"),
        AppTarget(listOf("instagram"), listOf("com.instagram.android"), "Instagram"),
        AppTarget(listOf("whatsapp", "vatsap"), listOf("com.whatsapp"), "WhatsApp"),
        AppTarget(listOf("tiktok", "tik tok"), listOf("com.zhiliaoapp.musically"), "TikTok"),
        AppTarget(listOf("chrome", "brauzer"), listOf("com.android.chrome"), "Chrome"),
        AppTarget(listOf("xarita", "maps", "google maps"), listOf("com.google.android.apps.maps"), "Google Maps"),
        AppTarget(listOf("gmail", "pochta"), listOf("com.google.android.gm"), "Gmail"),
        AppTarget(listOf("play market", "play store"), listOf("com.android.vending"), "Play Market"),
        AppTarget(listOf("kalkulyator", "calculator"), listOf("com.google.android.calculator", "com.miui.calculator"), "Kalkulyator"),
        AppTarget(listOf("kalendar", "calendar"), listOf("com.google.android.calendar", "com.android.calendar"), "Kalendar"),
        AppTarget(listOf("soat ilovasi"), listOf("com.google.android.deskclock", "com.android.deskclock"), "Soat"),
        AppTarget(listOf("fayllar", "file manager"), listOf("com.google.android.documentsui", "com.mi.android.globalFileexplorer"), "Fayllar"),
        AppTarget(listOf("xabarlar", "sms ilovasi"), listOf("com.google.android.apps.messaging", "com.android.mms"), "Xabarlar")
    )

    fun execute(context: Context, command: String): String {
        val text = command.lowercase(Locale.ROOT)
            .replace('’', '\'')
            .replace('ʻ', '\'')
            .replace('‘', '\'')
            .replace(",", " ")
            .replace(".", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        return try {
            when {
                text in listOf("yordam", "nora yordam", "buyruqlar", "nima qila olasan") ->
                    help()

                text == "soat" || text.contains("soat necha") ||
                    text == "vaqt" || text.contains("hozirgi vaqt") ->
                    currentTime()

                text == "sana" || text.contains("bugun sana") ||
                    text.contains("bugungi sana") ->
                    currentDate()

                text.contains("batareya") || text.contains("zaryad qancha") ||
                    text.contains("telefon zaryadi") ->
                    batteryStatus(context)

                text.contains("batareya sozlam") ->
                    openSystemPage(context, Settings.ACTION_BATTERY_SAVER_SETTINGS)

                text.contains("ekran sozlam") ->
                    openSystemPage(context, Settings.ACTION_DISPLAY_SETTINGS)

                text.contains("wifi") || text.contains("wi-fi") ->
                    openWifiSettings(context)

                text.contains("bluetooth") ->
                    openBluetoothSettings(context)

                text.startsWith("raqamga qo'ng'iroq") ||
                    text.startsWith("raqamga telefon") ||
                    text.startsWith("qo'ng'iroq qil") ->
                    dialNumber(context, text)

                text.startsWith("sms ") || text.startsWith("xabar yoz ") ->
                    composeSms(context, text)

                text.startsWith("xaritadan qidir ") ||
                    text.startsWith("manzilni top ") ->
                    mapSearch(context, text)

                text.contains("budilnik") || text.contains("alarm") ->
                    setAlarm(context, text)

                text.contains("taymer") || text.contains("timer") ->
                    setTimer(context, text)

                text.contains("ovozni ko'tar") ||
                    text.contains("ovozni balandlat") ||
                    text.contains("ovozni oshir") ||
                    text.contains("volume up") -> {
                    audio.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE,
                        AudioManager.FLAG_SHOW_UI
                    )
                    "Ovoz balandlatildi"
                }

                text.contains("ovozni pasaytir") ||
                    text.contains("ovozni tushir") ||
                    text.contains("volume down") -> {
                    audio.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI
                    )
                    "Ovoz pasaytirildi"
                }

                text.contains("ovozni o'chir") ||
                    text.contains("ovozsiz qil") -> {
                    audio.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_MUTE,
                        AudioManager.FLAG_SHOW_UI
                    )
                    "Media ovozi o'chirildi"
                }

                text.contains("ovozni yoq") ||
                    text.contains("ovozni qaytar") -> {
                    audio.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_UNMUTE,
                        AudioManager.FLAG_SHOW_UI
                    )
                    "Media ovozi yoqildi"
                }

                text.contains("sozlamalar") ||
                    text == "sozlama och" ||
                    text == "telefon sozlamalari" ->
                    openSystemPage(context, Settings.ACTION_SETTINGS)

                text.contains("kamera") && isOpen(text) ->
                    openCamera(context)

                text.contains("telefon") && isOpen(text) ->
                    openDialer(context)

                else -> {
                    val target = apps.firstOrNull { app ->
                        app.names.any { text.contains(it) }
                    }

                    if (target != null && isOpen(text)) {
                        openFirstApp(context, target.packages, target.label)
                    } else if (isInternetSearch(text)) {
                        val query = extractSearchQuery(text)
                        if (query.isBlank()) {
                            "Nimani qidiray?"
                        } else {
                            openWebSearch(context, query)
                            "Internetdan qidiryapman: $query"
                        }
                    } else {
                        UNKNOWN
                    }
                }
            }
        } catch (e: Exception) {
            "Buyruqni bajarib bo'lmadi: ${e.message ?: "noma'lum xato"}"
        }
    }

    fun isInternetSearch(command: String): Boolean {
        val text = command.lowercase(Locale.ROOT).trim()
        return text.startsWith("internetdan qidir") ||
            text.startsWith("internet qidir") ||
            text.startsWith("duckduckgo dan qidir") ||
            text.startsWith("google dan qidir") ||
            text.startsWith("qidir ")
    }

    private fun extractSearchQuery(text: String): String {
        val prefixes = listOf(
            "internetdan qidir",
            "internet qidir",
            "duckduckgo dan qidir",
            "google dan qidir",
            "qidir"
        )

        return prefixes.firstOrNull { text.startsWith(it) }
            ?.let { text.removePrefix(it).trim() }
            ?: text
    }

    private fun isOpen(text: String): Boolean {
        return Regex("""\b(och|ochib|oching|kir|kiring|kirib|ishga tushir|ishga sol)\b""")
            .containsMatchIn(text)
    }

    private fun help(): String = """
        Nora quyidagi buyruqlarni bajara oladi:

        • Soat va sanani aytish
        • Batareya holatini tekshirish
        • Telegram, YouTube, Instagram, WhatsApp, TikTok ochish
        • Chrome, xarita, Gmail va boshqa ilovalarni ochish
        • Wi-Fi va Bluetooth sozlamalarini ochish
        • Budilnik va taymer sozlash
        • Ovoz balandligini boshqarish
        • Telefon raqamiga qo'ng'iroq oynasini ochish
        • SMS yozish oynasini ochish
        • Manzilni xaritadan qidirish
        • Internetdan ma'lumot qidirish

        Masalan: Telegramni och, taymer 5 daqiqa, soat necha.
    """.trimIndent()

    private fun currentTime(): String {
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        return "Hozir soat $time"
    }

    private fun currentDate(): String {
        val date = SimpleDateFormat("dd MMMM yyyy", Locale("uz")).format(Date())
        return "Bugungi sana: $date"
    }

    private fun batteryStatus(context: Context): String {
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val mode = if (power.isPowerSaveMode) {
            "Quvvat tejash rejimi yoqilgan"
        } else {
            "Quvvat tejash rejimi o'chirilgan"
        }
        return "Batareya: $percent%. $mode"
    }

    private fun openFirstApp(
        context: Context,
        packages: List<String>,
        label: String
    ): String {
        for (packageName in packages) {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                return "$label ochilmoqda"
            }
        }
        return "$label ilovasi telefonda topilmadi"
    }

    private fun openCamera(context: Context): String {
        val intent = Intent("android.media.action.IMAGE_CAPTURE")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (intent.resolveActivity(context.packageManager) == null) {
            return "Kamera ilovasi topilmadi"
        }

        context.startActivity(intent)
        return "Kamera ochilmoqda"
    }

    private fun openDialer(context: Context): String {
        context.startActivity(
            Intent(Intent.ACTION_DIAL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return "Telefon terish oynasi ochildi"
    }

    private fun dialNumber(context: Context, text: String): String {
        val number = text
            .removePrefix("raqamga qo'ng'iroq")
            .removePrefix("raqamga telefon")
            .removePrefix("qo'ng'iroq qil")
            .trim()

        if (number.isBlank()) return "Telefon raqamini ayting"

        context.startActivity(
            Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return "$number raqami uchun qo'ng'iroq oynasi ochildi"
    }

    private fun composeSms(context: Context, text: String): String {
        val content = text
            .removePrefix("sms")
            .removePrefix("xabar yoz")
            .trim()

        if (content.isBlank()) return "Xabar matnini ayting"

        context.startActivity(
            Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:")
                putExtra("sms_body", content)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
        return "SMS yozish oynasi ochildi. Yuborishni o'zingiz tasdiqlang."
    }

    private fun mapSearch(context: Context, text: String): String {
        val query = text
            .removePrefix("xaritadan qidir")
            .removePrefix("manzilni top")
            .trim()

        if (query.isBlank()) return "Qaysi manzilni qidiray?"

        val uri = Uri.parse("geo:0,0?q=${Uri.encode(query)}")
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return "Xaritada qidirilmoqda: $query"
    }

    private fun setAlarm(context: Context, text: String): String {
        val match = Regex(
            """(?:budilnik|alarm)(?:\s+soat)?\s+(\d{1,2})[:.](\d{2})"""
        ).find(text)

        if (match == null) {
            context.startActivity(
                Intent(AlarmClock.ACTION_SET_ALARM)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return "Budilnik oynasi ochildi. Masalan: budilnik 7:30"
        }

        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].toInt()

        if (hour !in 0..23 || minute !in 0..59) {
            return "Vaqt noto'g'ri ko'rsatilgan"
        }

        context.startActivity(
            Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, "Nora budilnigi")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        return "Budilnik $hour:${minute.toString().padStart(2, '0')} vaqtiga sozlash oynasi ochildi"
    }

    private fun setTimer(context: Context, text: String): String {
        val match = Regex(
            """(?:taymer|timer)\s+(\d+)\s*(soniya|sekund|daqiqa|minut|soat)?"""
        ).find(text)

        if (match == null) {
            context.startActivity(
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return "Taymer oynasi ochildi. Masalan: taymer 5 daqiqa"
        }

        val amount = match.groupValues[1].toLongOrNull()
            ?: return "Taymer vaqti noto'g'ri"

        val unit = match.groupValues[2]
        val seconds = when (unit) {
            "soniya", "sekund" -> amount
            "soat" -> amount * 3600L
            else -> amount * 60L
        }

        if (seconds !in 1L..86400L) {
            return "Taymer 1 soniyadan 24 soatgacha bo'lishi kerak"
        }

        context.startActivity(
            Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds.toInt())
                putExtra(AlarmClock.EXTRA_MESSAGE, "Nora taymeri")
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        val unitName = unit.ifBlank { "daqiqa" }
        return "Taymer $amount $unitName uchun sozlash oynasi ochildi"
    }

    private fun openSystemPage(context: Context, action: String): String {
        context.startActivity(
            Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return "Sozlamalar ochildi"
    }

    private fun openWifiSettings(context: Context): String {
        val intent = if (Build.VERSION.SDK_INT >= 29) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Wi-Fi boshqaruv oynasi ochildi"
    }

    private fun openBluetoothSettings(context: Context): String {
        context.startActivity(
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return "Bluetooth sozlamalari ochildi"
    }

    private fun openWebSearch(context: Context, query: String) {
        val uri = Uri.parse("https://duckduckgo.com/?q=${Uri.encode(query)}")
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
