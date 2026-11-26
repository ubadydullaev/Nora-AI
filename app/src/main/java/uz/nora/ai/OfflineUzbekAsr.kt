package uz.nora.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.*
import java.io.File
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs

class OfflineUzbekAsr(
    context: Context,
    private val onRecognized: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    private val appContext = context.applicationContext
    private val modelDir = File(appContext.filesDir, "gigaam_uzbek")
    private val modelFile = File(modelDir, "model.int8.onnx")
    private val tokensFile = File(modelDir, "tokens.txt")

    private val downloading = AtomicBoolean(false)
    private val running = AtomicBoolean(false)

    @Volatile private var recognizer: OfflineRecognizer? = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var worker: Thread? = null

    private val sampleRate = 16000
    private val frameSize = 1600

    fun ready(): Boolean {
        return modelFile.exists() &&
            tokensFile.exists() &&
            modelFile.length() > 1_000_000 &&
            tokensFile.length() > 100
    }

    fun downloadModel() {
        if (ready()) {
            onStatus("Ovoz modeli tayyor")
            return
        }

        if (!downloading.compareAndSet(false, true)) return

        thread(name = "Nora-ASR-Download") {
            try {
                modelDir.mkdirs()

                downloadFile(
                    "https://huggingface.co/Alex13/sherpa-onnx-gigaam-uzbek-600m/resolve/b53434a0b06418831dbc1cec73d6b851054f593d/model.int8.onnx",
                    modelFile
                )

                downloadFile(
                    "https://huggingface.co/Alex13/sherpa-onnx-gigaam-uzbek-600m/resolve/b53434a0b06418831dbc1cec73d6b851054f593d/tokens.txt",
                    tokensFile
                )

                onStatus(if (ready()) "Ovoz modeli yuklandi" else "Model fayllari to‘liq emas")
            } catch (e: Exception) {
                onStatus("Model yuklash xatosi: ${e.message ?: "noma’lum xato"}")
            } finally {
                downloading.set(false)
            }
        }
    }

    private fun downloadFile(url: String, destination: File) {
        val temp = File(destination.parentFile, destination.name + ".part")

        URL(url).openConnection().apply {
            connectTimeout = 20000
            readTimeout = 60000
            getInputStream().use { input ->
                temp.outputStream().use { output ->
                    input.copyTo(output, 64 * 1024)
                }
            }
        }

        if (!temp.exists() || temp.length() < 100) {
            temp.delete()
            throw IllegalStateException("Yuklangan fayl noto‘g‘ri")
        }

        if (destination.exists()) destination.delete()

        if (!temp.renameTo(destination)) {
            temp.copyTo(destination, overwrite = true)
            temp.delete()
        }
    }

    @Synchronized
    private fun createRecognizer(): OfflineRecognizer {
        recognizer?.let { return it }

        if (!ready()) throw IllegalStateException("Avval ovoz modelini yuklang")

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
            modelConfig = modelConfig
        )

        return OfflineRecognizer(
            appContext.assets,
            config
        ).also { recognizer = it }
    }

    fun start() {
        if (running.get()) return

        if (!ready()) {
            onStatus("Avval ovoz modelini yuklang")
            return
        }

        if (ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            onStatus("Mikrofon ruxsati kerak")
            return
        }

        running.set(true)

        worker = thread(name = "Nora-ASR-Listen") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

            var recorder: AudioRecord? = null

            try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                if (minBuffer <= 0) {
                    throw IllegalStateException("Mikrofonni ishga tushirib bo‘lmadi")
                }

                val bufferSize = maxOf(minBuffer, frameSize * 4)

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    sampleRate,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                    throw IllegalStateException("Mikrofon tayyor emas")
                }

                audioRecord = recorder
                val engine = createRecognizer()

                recorder.startRecording()
                onStatus("Nora tinglamoqda")

                val pcm = ShortArray(frameSize)
                val audio = ArrayList<Float>()
                var silenceFrames = 0
                var speechStarted = false

                while (running.get()) {
                    val count = recorder.read(pcm, 0, pcm.size)

                    if (count <= 0) continue

                    var peak = 0
                    for (i in 0 until count) {
                        peak = maxOf(peak, abs(pcm[i].toInt()))
                    }

                    val voiceDetected = peak > 450

                    if (voiceDetected) {
                        speechStarted = true
                        silenceFrames = 0
                    } else if (speechStarted) {
                        silenceFrames++
                    }

                    for (i in 0 until count) {
                        audio.add(pcm[i] / 32768.0f)
                    }

                    val finished = speechStarted &&
                        (silenceFrames >= 18 || audio.size >= sampleRate * 15)

                    if (finished) {
                        val samples = audio.toFloatArray()
                        audio.clear()
                        silenceFrames = 0
                        speechStarted = false

                        recognize(engine, samples)
                    }
                }

                if (audio.isNotEmpty() && speechStarted) {
                    recognize(engine, audio.toFloatArray())
                }

            } catch (e: Exception) {
                onStatus("Tinglash xatosi: ${e.message ?: "noma’lum xato"}")
            } finally {
                try {
                    recorder?.stop()
                } catch (_: Exception) {
                }

                recorder?.release()
                audioRecord = null
                running.set(false)
                onStatus("Tinglash to‘xtadi")
            }
        }
    }

    private fun recognize(engine: OfflineRecognizer, samples: FloatArray) {
        if (samples.size < 1600) return

        try {
            val stream = engine.createStream()
            stream.acceptWaveform(samples, sampleRate)
            engine.decode(stream)

            val result = engine.getResult(stream)
            val text = result.text.trim()

            stream.release()

            if (text.isNotBlank()) {
                onRecognized(text)
            }
        } catch (e: Exception) {
            onStatus("Ovozni tanish xatosi")
        }
    }

    fun stop() {
        running.set(false)

        try {
            audioRecord?.stop()
        } catch (_: Exception) {
        }
    }

    fun close() {
        stop()
        worker?.join(1500)

        synchronized(this) {
            recognizer?.release()
            recognizer = null
        }
    }
}
