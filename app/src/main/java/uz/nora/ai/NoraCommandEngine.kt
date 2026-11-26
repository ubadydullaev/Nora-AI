package uz.nora.ai

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.AlarmClock
import android.provider.Settings
import java.util.Locale

object NoraCommandEngine {

    fun execute(context: Context, command: String): String {
        val text = command
            .lowercase(Locale.ROOT)
            .replace(",", " ")
            .replace(".", " ")
            .trim()

        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        return when {
            text.contains("telegram") && isOpen(text) ->
                openApp(context, "org.telegram.messenger", "Telegram")

            text.contains("youtube") && isOpen(text) ->
                openApp(context, "com.google.android.youtube", "YouTube")

            text.contains("instagram") && isOpen(text) ->
                openApp(context, "com.instagram.android", "Instagram")

            text.contains("chrome") && isOpen(text) ->
                openApp(context, "com.android.chrome", "Chrome")

            text.contains("kamera") && isOpen(text) ->
                openApp(context, "com.android.camera", "Kamera")

            text.contains("sozlam") && isOpen(text) ->
                openSettings(context)

            text.contains("wifi") || text.contains("wi-fi") ->
                openWifiSettings(context)

            text.contains("bluetooth") ->
                openBluetoothSettings(context)

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

            text.contains("budilnik") ||
            text.contains("alarm") -> {
                val intent = Intent(AlarmClock.ACTION_SET_ALARM)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Budilnik oynasi ochildi"
            }

            text.contains("taymer") ||
            text.contains("timer") -> {
                val intent = Intent(AlarmClock.ACTION_SET_TIMER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                "Taymer oynasi ochildi"
            }

            else -> "Bu buyruq hali qo'shilmagan"
        }
    }

    private fun isOpen(text: String): Boolean {
        return text.contains("och") ||
            text.contains("ishga tushir") ||
            text.contains("kir")
    }

    private fun openApp(
        context: Context,
        packageName: String,
        label: String
    ): String {
        val intent = context.packageManager
            .getLaunchIntentForPackage(packageName)

        if (intent == null) {
            return "$label ilovasi telefonda topilmadi"
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "$label ochilmoqda"
    }

    private fun openSettings(context: Context): String {
        context.startActivity(
            Intent(Settings.ACTION_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return "Telefon sozlamalari ochildi"
    }

    private fun openWifiSettings(context: Context): String {
        val intent = if (android.os.Build.VERSION.SDK_INT >= 29) {
            Intent(Settings.Panel.ACTION_WIFI)
        } else {
            Intent(Settings.ACTION_WIFI_SETTINGS)
        }

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return "Wi-Fi sozlamalari ochildi"
    }

    private fun openBluetoothSettings(context: Context): String {
        context.startActivity(
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return "Bluetooth sozlamalari ochildi"
    }
}
