
package uz.nora.ai

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.*
import java.io.File
import kotlin.concurrent.thread

class OfflineUzbekAsr(
    private val activity: Activity,
    private val onStatus: (String) -> Unit,
    private val onText: (String) -> Unit
) {
    companion object {
        private const val RATE = 16000
        private const val MODEL_URL =
            "https://huggingface.co/Alex13/sherpa-onnx-gigaam-uzbek-600m/resolve/b53434a0b06418831dbc1cec73d6b851054f593d/model.int8.onnx"
        private const val TOKENS_URL =
            "https://huggingface.co/Alex13/sherpa-onnx-gigaam-uzbek-600m/resolve/b53434a0b06418831dbc1cec73d6b851054f593d/tokens.txt"
    }

    private val dir = File(activity.getExternalFilesDir(null), "gigaam")
    private val model = File(dir, "model.int8.onnx")
    private val tokens = File(dir, "tokens.txt")
    private var recognizer: OfflineRecognizer? = null
    private var recording = false

    fun downloadModel() {
        dir.mkdirs()
        if (ready()) {
            onStatus("Ovoz modeli tayyor")
            return
        }

        val dm = activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        fun enqueue(url: String, name: String) {
            dm.enqueue(
                DownloadManager.Request(Uri.parse(url))
                    .setTitle("Nora Uzbek ASR")
                    .setNotificationVisibility(
                        DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                    )
                    .setDestinationInExternalFilesDir(
                        activity, Environment.DIRECTORY_DOWNLOADS, name
                    )
            )
        }

        enqueue(MODEL_URL, "gigaam-model.onnx")
        enqueue(TOKENS_URL, "gigaam-tokens.txt")
        onStatus("Model yuklanmoqda. Yuklash tugagach, fayllarni Nora/gigaam papkasiga ko‘chirish kerak.")
    }

    fun ready() = model.exists() && tokens.exists() &&
        model.length() > 100_000_000L && tokens.length() > 100L

    private fun getRecognizer(): OfflineRecognizer {
        recognizer?.let { return it }

        val nemo = OfflineNemoEncDecCtcModelConfig.builder()
            .setModel(model.absolutePath)
            .build()

        val modelConfig = OfflineModelConfig.builder()
            .setNemo(nemo)
            .setTokens(tokens.absolutePath)
            .setNumThreads(2)
            .setProvider("cpu")
            .setDebug(false)
            .build()

        val config = OfflineRecognizerConfig.builder()
            .setOfflineModelConfig(modelConfig)
            .setDecodingMethod("greedy_search")
            .build()

        return OfflineRecognizer(config).also { recognizer = it }
    }

    fun listen() {
        if (recording) return

        if (!ready()) {
            onStatus("Avval GigaAM modelini yuklang")
            return
        }

        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            onStatus("Mikrofon ruxsati kerak")
            return
        }

        recording = true
        onStatus("Sizni tinglayapman...")

        thread {
            try {
                val size = RATE * 6
                val buffer = AudioRecord.getMinBufferSize(
                    RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                val recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(buffer, size * 2)
                )

                val pcm = ShortArray(size)
                recorder.startRecording()

                var count = 0
                while (count < size) {
                    val n = recorder.read(pcm, count, size - count)
                    if (n <= 0) error("Mikrofon xatosi")
                    count += n
                }

                recorder.stop()
                recorder.release()

                val samples = FloatArray(size) {
                    pcm[it] / 32768f
                }

                val engine = getRecognizer()
                val stream = engine.createStream()

                stream.acceptWaveform(samples, RATE)
                engine.decode(stream)

                val result = engine.getResult(stream).text
                stream.release()

                activity.runOnUiThread {
                    if (result.isNotBlank()) {
                        onText(result)
                        onStatus("Ovoz tanildi")
                    } else {
                        onStatus("Ovoz aniqlanmadi")
                    }
                }
            } catch (e: Exception) {
                activity.runOnUiThread {
                    onStatus("Xatolik: ${e.message}")
                }
            } finally {
                recording = false
            }
        }
    }

    fun close() {
        recognizer?.release()
        recognizer = null
    }
}
