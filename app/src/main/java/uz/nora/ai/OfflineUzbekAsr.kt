package uz.nora.ai

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class OfflineUzbekAsr(
    private val activity: Activity,
    private val onStatus: (String) -> Unit,
    private val onText: (String) -> Unit
) {
    companion object {
        private const val RATE = 16000
        private const val RECORD_SECONDS = 6

        private const val MODEL_URL =
            "https://huggingface.co/Alex13/sherpa-onnx-gigaam-uzbek-600m/resolve/b53434a0b06418831dbc1cec73d6b851054f593d/model.int8.onnx"

        private const val TOKENS_URL =
            "https://huggingface.co/Alex13/sherpa-onnx-gigaam-uzbek-600m/resolve/b53434a0b06418831dbc1cec73d6b851054f593d/tokens.txt"
    }

    private val dir = File(activity.getExternalFilesDir(null), "gigaam")
    private val model = File(dir, "model.int8.onnx")
    private val tokens = File(dir, "tokens.txt")

    private val recording = AtomicBoolean(false)
    private val downloading = AtomicBoolean(false)

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    private fun status(message: String) {
        activity.runOnUiThread {
            onStatus(message)
        }
    }

    fun ready(): Boolean {
        return model.exists() &&
            tokens.exists() &&
            model.length() > 10_000_000L &&
            tokens.length() > 100L
    }

    fun downloadModel() {
        if (ready()) {
            status("OFFLINE OVOZ MODELI TAYYOR")
            return
        }

        if (!downloading.compareAndSet(false, true)) {
            status("Model hozir yuklanmoqda...")
            return
        }

        thread(name = "NoraModelDownload") {
            try {
                if (!dir.exists() && !dir.mkdirs()) {
                    error("Model papkasini yaratib bo‘lmadi")
                }

                if (!model.exists() || model.length() <= 10_000_000L) {
                    downloadFile(MODEL_URL, model, "Asosiy ovoz modeli")
                }

                if (!tokens.exists() || tokens.length() <= 100L) {
                    downloadFile(TOKENS_URL, tokens, "Tokenlar")
                }

                if (!ready()) {
                    error("Yuklangan fayllar tekshiruvdan o‘tmadi")
                }

                status("MODEL TAYYOR. INTERNETNI O‘CHIRIB SINASH MUMKIN.")
            } catch (e: Exception) {
                status("YUKLASH XATOSI: ${e.message ?: "Noma’lum xato"}")
            } finally {
                downloading.set(false)
            }
        }
    }

    private fun downloadFile(
        address: String,
        destination: File,
        title: String
    ) {
        val temp = File(destination.parentFile, destination.name + ".part")
        temp.delete()

        val connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30000
            readTimeout = 60000
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Nora-Android/1.0")
        }

        try {
            connection.connect()

            val response = connection.responseCode
            if (response !in 200..299) {
                error("$title: server javobi $response")
            }

            val total = connection.contentLengthLong

            connection.inputStream.use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(1024 * 256)
                    var downloaded = 0L
                    var lastUpdate = 0L

                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break

                        output.write(buffer, 0, count)
                        downloaded += count

                        val now = System.currentTimeMillis()
                        if (now - lastUpdate > 1000) {
                            lastUpdate = now

                            val message = if (total > 0) {
                                val percent = downloaded * 100 / total
                                "$title yuklanmoqda: $percent%"
                            } else {
                                "$title yuklanmoqda: ${downloaded / 1024 / 1024} MB"
                            }

                            status(message)
                        }
                    }
                }
            }

            if (!temp.exists() || temp.length() < 100L) {
                error("$title fayli bo‘sh yoki noto‘liq")
            }

            if (destination.exists()) destination.delete()

            if (!temp.renameTo(destination)) {
                temp.copyTo(destination, overwrite = true)
                temp.delete()
            }

            if (!destination.exists() || destination.length() < 100L) {
                error("$title saqlanmadi")
            }
        } finally {
            connection.disconnect()
            if (temp.exists()) temp.delete()
        }
    }

    private fun getRecognizer(): OfflineRecognizer {
        recognizer?.let { return it }

        check(ready()) { "Ovoz modeli hali yuklanmagan" }

        val modelConfig = OfflineModelConfig(
            nemo = OfflineNemoEncDecCtcModelConfig(
                model = model.absolutePath
            ),
            tokens = tokens.absolutePath,
            numThreads = 2,
            provider = "cpu",
            debug = false
        )

        val config = OfflineRecognizerConfig(
            modelConfig = modelConfig,
            decodingMethod = "greedy_search"
        )

        return OfflineRecognizer(config = config).also {
            recognizer = it
        }
    }

    fun listen() {
        if (!ready()) {
            status("AVVAL OFFLINE MODELINI YUKLAB OLING")
            return
        }

        if (!recording.compareAndSet(false, true)) {
            status("Hozir tinglayapman...")
            return
        }

        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            recording.set(false)
            status("Mikrofon ruxsati kerak")
            return
        }

        thread(name = "NoraOfflineRecognition") {
            var recorder: AudioRecord? = null
            var stream: OnlineStream? = null

            try {
                status("SIZNI TINGLAYAPMAN... 6 SONIYA GAPIRING")

                val sampleCount = RATE * RECORD_SECONDS

                val minBuffer = AudioRecord.getMinBufferSize(
                    RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                if (minBuffer <= 0) {
                    error("Telefon mikrofonini ishga tushirib bo‘lmadi")
                }

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, sampleCount * 2)
                )

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    error("Mikrofon tayyor emas")
                }

                val pcm = ShortArray(sampleCount)

                recorder.startRecording()

                var count = 0
                while (count < sampleCount) {
                    val read = recorder.read(
                        pcm,
                        count,
                        sampleCount - count
                    )

                    if (read <= 0) {
                        error("Mikrofondan ovoz olinmadi")
                    }

                    count += read
                }

                recorder.stop()
                recorder.release()
                recorder = null

                status("OVOZ TAHLIL QILINMOQDA...")

                val samples = FloatArray(sampleCount) {
                    pcm[it] / 32768.0f
                }

                val engine = getRecognizer()
                val stream = engine.createStream()

                val recognized = try {
                    stream.acceptWaveform(samples, RATE)
                    engine.decode(stream)
                    engine.getResult(stream).text.trim()
                    } finally {
                    stream.release()
             }
                if (recognized.isNotBlank()) {
                    activity.runOnUiThread {
                        onText(recognized)
                        onStatus("OVOZ MATNGA AYLANTIRILDI")
                    }
                } else {
                    status("OVOZ ANIQLANMADI. QAYTA URINIB KO‘RING.")
                }
            } catch (e: Exception) {
                status("TANISH XATOSI: ${e.message ?: "Noma’lum xato"}")
            } finally {
                try {
                    recorder?.let {
                        if (it.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                            it.stop()
                        }
                        it.release()
                    }
                } catch (_: Exception) {
                }

                try {
                    stream?.release()
                } catch (_: Exception) {
                }

                recording.set(false)
            }
        }
    }

    fun close() {
        try {
            recognizer?.release()
        } catch (_: Exception) {
        }
        recognizer = null
    }
}
