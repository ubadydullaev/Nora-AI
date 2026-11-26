
package uz.nora.ai

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import java.util.Locale

object NoraCommandEngine {

    fun execute(context: Context, command: String): String {
        val text = command.lowercase(Locale.ROOT)
            .replace(",", " ")
            .replace(".", " ")
            .trim()

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        return try {
            when {
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
                    openApp(context, "com.google.android.apps.messaging", "Xabarlar")

                text.contains("sozlam") && isOpen(text) ->
                    openSettings(context)

                text.contains("wifi") || text.contains("wi-fi") ->
                    openWifiSettings(context)

                text.contains("bluetooth") ->
                    openBluetoothSettings(context)

                isInternetSearch(text) -> {
                    val query = text
                        .removePrefix("internetdan qidir")
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

                text.contains("budilnik") || text.contains("alarm") -> {
                    context.startActivity(
                        Intent(AlarmClock.ACTION_SET_ALARM)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    "Budilnik oynasi ochildi"
                }

                text.contains("taymer") || text.contains("timer") -> {
                    context.startActivity(
                        Intent(AlarmClock.ACTION_SET_TIMER)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    "Taymer oynasi ochildi"
                }

                text.contains("batareya sozlam") -> {
                    openSystemPage(context, Settings.ACTION_BATTERY_SAVER_SETTINGS)
                    "Batareya sozlamalari ochildi"
                }

                text.contains("ekran sozlam") -> {
                    openSystemPage(context, Settings.ACTION_DISPLAY_SETTINGS)
                    "Ekran sozlamalari ochildi"
                }

                else -> "Bu buyruq hali qo'shilmagan"
            }
        } catch (e: Exception) {
            "Buyruqni bajarib bo'lmadi: ${e.message ?: "noma'lum xato"}"
        }
    }

    fun isInternetSearch(command: String): Boolean {
        val text = command.lowercase(Locale.ROOT).trim()
        return text.startsWith("internetdan qidir") ||
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

    private fun openApp(
        context: Context,
        packageName: String,
        label: String
    ): String {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
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

    private fun openSettings(context: Context): String {
        openSystemPage(context, Settings.ACTION_SETTINGS)
        return "Telefon sozlamalari ochildi"
    }

    private fun openSystemPage(context: Context, action: String) {
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

    private fun openWebSearch(context: Context, query: String) {
        val uri = Uri.parse(
            "https://duckduckgo.com/?q=" + Uri.encode(query)
        )

        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
