
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

    fun execute(context: Context, command: String): String {
        val text = command.lowercase(Locale.ROOT)
            .replace(",", " ")
            .replace(".", " ")
            .trim()

        val audio = context.getSystemService(Context.AUDIO_SERVICE)
            as AudioManager

        return try {
            when {
                text == "soat" || text.contains("soat necha") ->
                    currentTime()

                text.contains("bugun sana") ||
                text == "sana" ||
                text.contains("bugungi sana") ->
                    currentDate()

                text.contains("batareya") ||
                text.contains("zaryad qancha") ->
                    batteryStatus(context)

                text.contains("telegram") && isOpen(text) ->
                    openApp(context, "org.telegram.messenger", "Telegram")

                text.contains("youtube") && isOpen(text) ->
                    openApp(context, "com.google.android.youtube", "YouTube")

                text.contains("instagram") && isOpen(text) ->
                    openApp(context, "com.instagram.android", "Instagram")

                text.contains("chrome") && isOpen(text) ->
                    openApp(context, "com.android.chrome", "Chrome")

                text.contains("kamera") && isOpen(text) ->
                    openCamera(context)

                text.contains("telefon") && isOpen(text) ->
                    openDialer(context)

                text.contains("xabarlar") && isOpen(text) ->
                    openApp(
                        context,
                        "com.google.android.apps.messaging",
                        "Xabarlar"
                    )

                text.contains("sozlam") && isOpen(text) ->
                    openSettings(context)

                text.contains("wifi") || text.contains("wi-fi") ->
                    openWifiSettings(context)

                text.contains("bluetooth") ->
                    openBluetoothSettings(context)

                text.startsWith("raqamga qo'ng'iroq") ||
                text.startsWith("raqamga telefon") ->
                    dialNumber(context, text)

                text.startsWith("sms ") ||
                text.startsWith("xabar yoz ") ->
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

                text.contains("batareya sozlam") ->
                    openSystemPage(
                        context,
                        Settings.ACTION_BATTERY_SAVER_SETTINGS
                    )

                text.contains("ekran sozlam") ->
                    openSystemPage(
                        context,
                        Settings.ACTION_DISPLAY_SETTINGS
                    )

                text.contains("internet qidir") ||
                isInternetSearch(text) -> {
                    val query = text
                        .removePrefix("internetdan qidir")
                        .removePrefix("internet qidir")
                        .removePrefix("duckduckgo dan qidir")
                        .removePrefix("google dan qidir")
                        .removePrefix("qidir")
                        .trim()

                    if (query.isBlank()) {
                        "Nimani qidiray?"
                    } else {
                        openWebSearch(context, query)
                        "Internetdan qidiryapman: $query"
                    }
                }

                else -> UNKNOWN
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

    private fun isOpen(text: String): Boolean {
        return text.contains("och") ||
            text.contains("ishga tushir") ||
            text.contains("kir") ||
            text.contains("ishga sol")
    }

    private fun currentTime(): String {
        val time = SimpleDateFormat(
            "HH:mm",
            Locale.getDefault()
        ).format(Date())

        return "Hozir soat $time"
    }

    private fun currentDate(): String {
        val date = SimpleDateFormat(
            "dd MMMM yyyy",
            Locale("uz")
        ).format(Date())

        return "Bugungi sana: $date"
    }

    private fun batteryStatus(context: Context): String {
        val battery = context.getSystemService(
            Context.BATTERY_SERVICE
        ) as BatteryManager

        val percent = battery.getIntProperty(
            BatteryManager.BATTERY_PROPERTY_CAPACITY
        )

        val power = context.getSystemService(
            Context.POWER_SERVICE
        ) as PowerManager

        val mode = if (power.isPowerSaveMode) {
            "Quvvat tejash rejimi yoqilgan"
        } else {
            "Quvvat tejash rejimi o'chirilgan"
        }

        return "Batareya: $percent%. $mode"
    }

    private fun openApp(
        context: Context,
        packageName: String,
        label: String
    ): String {
        val intent = context.packageManager
            .getLaunchIntentForPackage(packageName)
            ?: return "$label ilovasi telefonda topilmadi"

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "$label ochilmoqda"
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

    private fun dialNumber(
        context: Context,
        text: String
    ): String {
        val number = text
            .removePrefix("raqamga qo'ng'iroq")
            .removePrefix("raqamga telefon")
            .trim()

        if (number.isBlank()) return "Telefon raqamini ayting"

        context.startActivity(
            Intent(
                Intent.ACTION_DIAL,
                Uri.parse("tel:${Uri.encode(number)}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

        return "$number raqami uchun qo'ng'iroq oynasi ochildi"
    }

    private fun composeSms(
        context: Context,
        text: String
    ): String {
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

    private fun mapSearch(
        context: Context,
        text: String
    ): String {
        val query = text
            .removePrefix("xaritadan qidir")
            .removePrefix("manzilni top")
            .trim()

        if (query.isBlank()) return "Qaysi manzilni qidiray?"

        val uri = Uri.parse(
            "geo:0,0?q=${Uri.encode(query)}"
        )

        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

        return "Xaritada qidirilmoqda: $query"
    }

    private fun setAlarm(
        context: Context,
        text: String
    ): String {
        val match = Regex(
            """(?:budilnik|alarm)\s+(\d{1,2})[:.](\d{2})"""
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

        return "Budilnik $hour:${
            minute.toString().padStart(2, '0')
        } vaqtiga sozlash oynasi ochildi"
    }

    private fun setTimer(
        context: Context,
        text: String
    ): String {
        val match = Regex(
            """taymer\s+(\d+)\s*(soniya|sekund|daqiqa|minut|soat)?"""
        ).find(text)

        if (match == null) {
            context.startActivity(
                Intent(AlarmClock.ACTION_SET_TIMER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return "Taymer oynasi ochildi. Masalan: taymer 5 daqiqa"
        }

        val amount = match.groupValues[1].toIntOrNull()
            ?: return "Taymer vaqti noto'g'ri"

        val unit = match.groupValues[2]
        val seconds = when (unit) {
            "soniya", "sekund" -> amount
            "soat" -> amount * 3600
            else -> amount * 60
        }

        if (seconds !in 1..86400) {
            return "Taymer 1 soniyadan 24 soatgacha bo'lishi kerak"
        }

        context.startActivity(
            Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds)
                putExtra(AlarmClock.EXTRA_MESSAGE, "Nora taymeri")
                putExtra(AlarmClock.EXTRA_SKIP_UI, false)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )

        return "Taymer $amount $unit uchun sozlash oynasi ochildi"
    }

    private fun openSettings(context: Context): String {
        openSystemPage(context, Settings.ACTION_SETTINGS)
        return "Telefon sozlamalari ochildi"
    }

    private fun openSystemPage(
        context: Context,
        action: String
    ) {
        context.startActivity(
            Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
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
        openSystemPage(context, Settings.ACTION_BLUETOOTH_SETTINGS)
        return "Bluetooth sozlamalari ochildi"
    }

    private fun openWebSearch(
        context: Context,
        query: String
    ) {
        val uri = Uri.parse(
            "https://duckduckgo.com/?q=${Uri.encode(query)}"
        )

        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
