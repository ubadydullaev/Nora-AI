
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
        AppTarget(listOf("telegram", "телеграм"), listOf("org.telegram.messenger"), "Telegram"),
        AppTarget(listOf("youtube", "ютуб"), listOf("com.google.android.youtube"), "YouTube"),
        AppTarget(listOf("instagram", "инстаграм"), listOf("com.instagram.android"), "Instagram"),
        AppTarget(listOf("whatsapp", "vatsap", "ватсап"), listOf("com.whatsapp"), "WhatsApp"),
        AppTarget(listOf("tiktok", "tik tok", "тикток"), listOf("com.zhiliaoapp.musically"), "TikTok"),
        AppTarget(listOf("chrome", "brauzer", "internet brauzer"), listOf("com.android.chrome"), "Chrome"),
        AppTarget(listOf("xarita", "maps", "google maps", "karta", "xaritalar"), listOf("com.google.android.apps.maps"), "Google Maps"),
        AppTarget(listOf("gmail", "pochta", "elektron pochta"), listOf("com.google.android.gm"), "Gmail"),
        AppTarget(listOf("play market", "play store", "pley market"), listOf("com.android.vending"), "Play Market"),
        AppTarget(listOf("kalkulyator", "calculator", "hisoblagich"), listOf("com.google.android.calculator", "com.miui.calculator"), "Kalkulyator"),
        AppTarget(listOf("kalendar", "calendar"), listOf("com.google.android.calendar", "com.android.calendar"), "Kalendar"),
        AppTarget(listOf("soat ilovasi", "soat"), listOf("com.google.android.deskclock", "com.android.deskclock"), "Soat"),
        AppTarget(listOf("fayllar", "file manager", "fayl menejeri"), listOf("com.google.android.documentsui", "com.mi.android.globalFileexplorer"), "Fayllar"),
        AppTarget(listOf("xabarlar", "sms ilovasi", "messages"), listOf("com.google.android.apps.messaging", "com.android.mms"), "Xabarlar"),
        AppTarget(listOf("kamera", "camera"), listOf("com.android.camera", "com.google.android.GoogleCamera"), "Kamera"),
        AppTarget(listOf("sozlamalar", "settings", "nastroyka", "nastroykalar"), listOf("com.android.settings"), "Sozlamalar")
    )

    fun execute(context: Context, command: String): String {
        val original = command.trim()
        if (original.isBlank()) return "Buyruqni eshitmadim. Qayta ayting."

        val text = normalize(original)
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        return try {
            when {
                isHelp(text) -> help()
                isTimeRequest(text) -> currentTime()
                isDateRequest(text) -> currentDate()

                isBatterySettings(text) ->
                    openSystemPage(context, Settings.ACTION_BATTERY_SAVER_SETTINGS)

                isDisplaySettings(text) ->
                    openSystemPage(context, Settings.ACTION_DISPLAY_SETTINGS)

                isGeneralSettings(text) ->
                    openSystemPage(context, Settings.ACTION_SETTINGS)

                isBatteryRequest(text) -> batteryStatus(context)
                isWifiRequest(text) -> openWifiSettings(context)
                isBluetoothRequest(text) -> openBluetoothSettings(context)

                isMobileSettings(text) ->
                    openSystemPage(context, Settings.ACTION_NETWORK_OPERATOR_SETTINGS)

                isInternetSearch(text) -> {
                    val query = extractSearchQuery(text)
                    if (query.isBlank()) "Nimani qidiray?"
                    else {
                        openWebSearch(context, query)
                        "Internetdan qidiryapman: $query"
                    }
                }

                isMapSearch(text) -> mapSearch(context, original)
                isDialCommand(text) -> dialNumber(context, original)
                isSmsCommand(text) -> composeSms(context, original)
                isAlarmCommand(text) -> setAlarm(context, text)
                isTimerCommand(text) -> setTimer(context, text)

                isVolumeUp(text) -> {
                    audio.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_RAISE,
                        AudioManager.FLAG_SHOW_UI
                    )
                    "Ovoz balandlatildi"
                }

                isVolumeDown(text) -> {
                    audio.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI
                    )
                    "Ovoz pasaytirildi"
                }

                isMute(text) -> {
                    audio.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_MUTE,
                        AudioManager.FLAG_SHOW_UI
                    )
                    "Media ovozi o'chirildi"
                }

                isUnmute(text) -> {
                    audio.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        AudioManager.ADJUST_UNMUTE,
                        AudioManager.FLAG_SHOW_UI
                    )
                    "Media ovozi yoqildi"
                }

                else -> executeAppCommand(context, text)
            }
        } catch (e: Exception) {
            "Buyruqni bajarib bo'lmadi: ${e.message ?: "noma'lum xato"}"
        }
    }

    private fun executeAppCommand(context: Context, text: String): String {
        val target = apps
            .sortedByDescending { item -> item.names.maxOf { it.length } }
            .firstOrNull { app ->
                app.names.any { name ->
                    containsPhrase(text, name) &&
                        (isOpen(text) || text == name || text.startsWith("nora "))
                }
            }

        if (target != null) {
            return openFirstApp(context, target.packages, target.label)
        }

        if (
            text.contains("kamera") || text.contains("camera")
        ) {
            return openCamera(context)
        }

        if (text.contains("telefon") && isOpen(text)) {
            return openDialer(context)
        }

        return UNKNOWN
    }

    private fun normalize(value: String): String {
        return value.lowercase(Locale.ROOT)
            .replace('’', '\'')
            .replace('ʻ', '\'')
            .replace('‘', '\'')
            .replace('ʼ', '\'')
            .replace('`', '\'')
            .replace(Regex("[,!?;]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun containsPhrase(text: String, phrase: String): Boolean {
        return Regex(
            "(^|\\\\s)${Regex.escape(phrase)}($|\\\\s)"
        ).containsMatchIn(text)
    }

    private fun isHelp(text: String) =
        text in listOf(
            "yordam",
            "nora yordam",
            "buyruqlar",
            "nima qila olasan",
            "nimalar qila olasan"
        )

    private fun isTimeRequest(text: String) =
        text == "soat" || text == "vaqt" ||
            text.contains("soat necha") ||
            text.contains("hozirgi vaqt") ||
            text.contains("vaqtni ayt") ||
            text.contains("soatni ayt")

    private fun isDateRequest(text: String) =
        text == "sana" ||
            text.contains("bugun sana") ||
            text.contains("bugungi sana") ||
            text.contains("sanani ayt")

    private fun isBatterySettings(text: String) =
        text.contains("batareya sozlam") ||
            text.contains("quvvat tejash sozlam")

    private fun isDisplaySettings(text: String) =
        text.contains("ekran sozlam") ||
            text.contains("displey sozlam")

    private fun isGeneralSettings(text: String) =
        text == "sozlamalar" ||
            text == "sozlama och" ||
            text == "telefon sozlamalari" ||
            text == "nastroyka och" ||
            text == "nastroykalarni och"

    private fun isBatteryRequest(text: String) =
        text.contains("batareya") ||
            text.contains("zaryad qancha") ||
            text.contains("telefon zaryadi") ||
            text.contains("quvvat qancha")

    private fun isWifiRequest(text: String) =
        text.contains("wifi") ||
            text.contains("wi-fi") ||
            text.contains("вай фай")

    private fun isBluetoothRequest(text: String) =
        text.contains("bluetooth") ||
            text.contains("blutus")

    private fun isMobileSettings(text: String) =
        text.contains("mobil internet sozlam") ||
            text.contains("sim karta sozlam") ||
            text.contains("tarmoq sozlam")

    private fun isVolumeUp(text: String) =
        text.contains("ovozni ko'tar") ||
            text.contains("ovozni balandlat") ||
            text.contains("ovozni oshir") ||
            text.contains("ovozni balandroq qil") ||
            text.contains("volume up")

    private fun isVolumeDown(text: String) =
        text.contains("ovozni pasaytir") ||
            text.contains("ovozni tushir") ||
            text.contains("ovozni kamaytir") ||
            text.contains("volume down")

    private fun isMute(text: String) =
        text.contains("ovozni o'chir") ||
            text.contains("ovozsiz qil") ||
            text.contains("ovozni mute qil")

    private fun isUnmute(text: String) =
        text.contains("ovozni yoq") ||
            text.contains("ovozni qaytar") ||
            text.contains("ovozsiz rejimdan chiqar")

    private fun isDialCommand(text: String) =
        text.startsWith("raqamga qo'ng'iroq") ||
            text.startsWith("raqamga telefon") ||
            text.startsWith("qo'ng'iroq qil") ||
            text.startsWith("telefon qil")

    private fun isSmsCommand(text: String) =
        text.startsWith("sms ") ||
            text == "sms" ||
            text.startsWith("xabar yoz ")

    private fun isMapSearch(text: String) =
        text.startsWith("xaritadan qidir") ||
            text.startsWith("manzilni top") ||
            text.startsWith("kartadan qidir")

    private fun isAlarmCommand(text: String) =
        text.contains("budilnik") || text.contains("alarm")

    private fun isTimerCommand(text: String) =
        text.contains("taymer") || text.contains("timer")

    fun isInternetSearch(command: String): Boolean {
        val text = normalize(command)
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
        val verbs = listOf(
            "och", "ochib", "oching", "ochvor", "ochib ber",
            "ishga tushir", "ishga sol", "kir", "kiring",
            "kirib ber", "ishga tushur", "ochib yubor"
        )
        return verbs.any { text.contains(it) }
    }

    private fun help() = """
        Nora buyruqlari:

        • Soat necha, bugun sana
        • Batareya holati va batareya sozlamalari
        • Telegramni och, YouTube'ga kir
        • Instagramni ishga tushir, WhatsAppni och
        • Chrome, xarita, Gmail, kamera och
        • Wi-Fi, Bluetooth va mobil tarmoq sozlamalari
        • Budilnik 7:30
        • Taymer 5 daqiqa
        • Ovozni ko'tar, ovozni pasaytir, ovozni o'chir
        • Raqamga qo'ng'iroq qil +998...
        • SMS xabar yoz ...
        • Xaritadan qidir ...
        • Internetdan qidir ...
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

    private fun dialNumber(context: Context, original: String): String {
        val lower = normalize(original)
        val prefixes = listOf(
            "raqamga qo'ng'iroq",
            "raqamga telefon",
            "qo'ng'iroq qil",
            "telefon qil"
        )

        val prefix = prefixes.firstOrNull { lower.startsWith(it) }
            ?: return "Telefon raqamini ayting"

        val number = original.drop(prefix.length)
            .trim()
            .replace(Regex("[^+0-9]"), "")

        if (number.isBlank()) return "Telefon raqamini ayting"

        context.startActivity(
            Intent(
                Intent.ACTION_DIAL,
                Uri.fromParts("tel", number, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

        return "$number raqami uchun qo'ng'iroq oynasi ochildi"
    }

    private fun composeSms(context: Context, original: String): String {
        val lower = normalize(original)
        val prefix = if (lower.startsWith("xabar yoz")) "xabar yoz" else "sms"
        val content = original.drop(prefix.length).trim()

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

    private fun mapSearch(context: Context, original: String): String {
        val lower = normalize(original)
        val prefixes = listOf(
            "xaritadan qidir",
            "manzilni top",
            "kartadan qidir"
        )

        val prefix = prefixes.firstOrNull { lower.startsWith(it) }
            ?: return "Qaysi manzilni qidiray?"

        val query = original.drop(prefix.length).trim()
        if (query.isBlank()) return "Qaysi manzilni qidiray?"

        context.startActivity(
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse("geo:0,0?q=${Uri.encode(query)}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
            return "Vaqt noto'g'ri"
        }

        context.startActivity(
            Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minute)
                putExtra(AlarmClock.EXTRA_MESSAGE, "Nora budilnigi")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        return "Budilnik $hour:${minute.toString().padStart(2, '0')} uchun sozlash oynasi ochildi"
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
        val multiplier = when (unit) {
            "soniya", "sekund" -> 1L
            "soat" -> 3600L
            else -> 60L
        }

        if (amount < 1L || amount > 86400L / multiplier) {
            return "Taymer 1 soniyadan 24 soatgacha bo'lishi kerak"
        }

        val seconds = amount * multiplier

        context.startActivity(
            Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds.toInt())
                putExtra(AlarmClock.EXTRA_MESSAGE, "Nora taymeri")
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        return "Taymer $amount ${unit.ifBlank { "daqiqa" }} uchun sozlash oynasi ochildi"
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

        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
        val uri = Uri.parse(
            "https://duckduckgo.com/?q=${Uri.encode(query)}"
        )

        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
