package uz.nora.ai

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.sqrt

class OfflineUzbekAsr(
    context: Context,
    private val onText: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val RATE = 16000
        private const val FRAME_SIZE = 3200
        private const val SILENCE_FRAMES = 5
        private const val MAX_FRAMES = 60

        private const val MODEL_URL =
            "https://huggingface.co/Alex13/sherpa-onnx-gigaam-uzbek-600m/resolve/b53434a0b06418831dbc1cec73d6b851054f593d/model.int8.onnx"

        private const val TOKENS_URL =
            "https://huggingface.co/Alex13/sherpa-onnx-gigaam-uzbek-600m/resolve/b53434a0b06418831dbc1cec73d6b851054f593d/tokens.txt"
    }

    private val app = context.applicationContext
    private val dir = File(app.getExternalFilesDir(null) ?: app.filesDir, "gigaam")
    private val modelFile = File(dir, "model.int8.onnx")
    private val tokensFile = File(dir, "tokens.txt")

    private val running = AtomicBoolean(false)
    private val downloading = AtomicBoolean(false)

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    fun ready(): Boolean =
        modelFile.exists() &&
        tokensFile.exists() &&
        modelFile.length() > 10_000_000L &&
        tokensFile.length() > 100L

    fun downloadModel() {
        if (ready()) {
            onStatus("Ovoz modeli tayyor")
            return
        }

        if (!downloading.compareAndSet(false, true)) return

        thread(name = "NoraASRDownload") {
            try {
                if (!dir.exists()) dir.mkdirs()

                downloadFile(MODEL_URL, modelFile)
                downloadFile(TOKENS_URL, tokensFile)

                if (!ready()) error("Model tekshiruvdan o'tmadi")

                onStatus("Ovoz modeli tayyor")
            } catch (e: Exception) {
                onStatus("Yuklash xatosi: ${e.message}")
            } finally {
                downloading.set(false)
            }
        }
    }

    private fun downloadFile(url: String, destination: File) {
        val temp = File(dir, destination.name + ".part")
        temp.delete()

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 30000
        connection.readTimeout = 120000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Nora-Android/1.0")

        try {
            connection.connect()

            if (connection.responseCode !in 200..299) {
                error("Server: ${connection.responseCode}")
            }

            connection.inputStream.use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var count: Int

                    while (input.read(buffer).also { count = it } >= 0) {
                        if (count > 0) output.write(buffer, 0, count)
                    }
                }
            }

            if (temp.length() < 100) error("Yuklangan fayl bo'sh")

            temp.copyTo(destination, overwrite = true)
        } finally {
            connection.disconnect()
            temp.delete()
        }
    }

    private fun getRecognizer(): OfflineRecognizer {
        recognizer?.let { return it }

        val modelConfig = OfflineModelConfig(
            nemo = OfflineNemoEncDecCtcModelConfig(
                model = modelFile.absolutePath
            ),
            tokens = tokensFile.absolutePath,
            numThreads = 3,
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

    fun start() {
        if (!ready()) {
            onStatus("Avval offline ovoz modelini yuklang")
            return
        }

        if (!running.compareAndSet(false, true)) return

        thread(name = "NoraAlwaysListening") {
            listenLoop()
        }
    }

    private fun listenLoop() {
        var recorder: AudioRecord? = null

        try {
            val minBuffer = AudioRecord.getMinBufferSize(
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (minBuffer <= 0) error("Mikrofon ishga tushmadi")

            recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME_SIZE * 4)
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                error("Mikrofon tayyor emas")
            }

            recorder.startRecording()
            onStatus("NORA TINGLAMOQDA")

            val frame = ShortArray(FRAME_SIZE)
            val speech = ArrayList<Float>(FRAME_SIZE * 20)

            var speaking = false
            var silenceCount = 0
            var frameCount = 0

            while (running.get()) {
                val read = recorder.read(frame, 0, frame.size)

                if (read <= 0) continue

                var energy = 0.0
                for (i in 0 until read) {
                    val value = frame[i].toDouble() / 32768.0
                    energy += value * value
                }

                val rms = sqrt(energy / read)
                val voiceDetected = rms > 0.012

                if (voiceDetected) {
                    speaking = true
                    silenceCount = 0
                    frameCount++
                } else if (speaking) {
                    silenceCount++
                    frameCount++
                }

                if (speaking) {
                    for (i in 0 until read) {
                        speech.add(frame[i] / 32768.0f)
                    }
                }

                if (speaking &&
                    (silenceCount >= SILENCE_FRAMES || frameCount >= MAX_FRAMES)
                ) {
                    val phrase = speech.toFloatArray()

                    speech.clear()
                    speaking = false
                    silenceCount = 0
                    frameCount = 0

                    if (phrase.size > RATE / 3) {
                        recognize(phrase)
                    }

                    if (running.get()) onStatus("NORA TINGLAMOQDA")
                }
            }
        } catch (e: Exception) {
            onStatus("Ovoz xatosi: ${e.message}")
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
        }
    }

    private fun recognize(samples: FloatArray) {
        try {
            onStatus("GAP TAHLIL QILINMOQDA")

            val engine = getRecognizer()
            val stream = engine.createStream()

            val text = try {
                stream.acceptWaveform(samples, RATE)
                engine.decode(stream)
                engine.getResult(stream).text.trim()
            } finally {
                stream.release()
            }

            if (text.isNotBlank()) {
                onText(text)
            }
        } catch (e: Exception) {
            onStatus("Tanish xatosi: ${e.message}")
        }
    }

    fun stop() {
        running.set(false)
    }

    fun close() {
        stop()

        try {
            recognizer?.release()
        } catch (_: Exception) {
        }

        recognizer = null
    }
}
