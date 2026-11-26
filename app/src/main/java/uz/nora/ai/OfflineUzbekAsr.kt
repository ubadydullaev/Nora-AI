
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
import java.net.HttpURLConnection
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

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var worker: Thread? = null

    private val sampleRate = 16000
    private val frameSize = 1600

    fun ready(): Boolean {
        return modelFile.exists() &&
            tokensFile.exists() &&
            modelFile.length() > 1_000_000 &&
            tokensFile.length() > 100
    }

    /**
     * Bu metodni IO oqimida chaqiring.
     * Yuklash tugamaguncha qaytmaydi.
     */
    @Synchronized
    fun downloadModel() {
        if (ready()) {
            onStatus("Ovoz modeli tayyor")
            return
        }

        if (!downloading.compareAndSet(false, true)) {
            throw IllegalStateException("Model allaqachon yuklanmoqda")
        }

        try {
            if (!modelDir.exists() && !modelDir.mkdirs()) {
                error("Model papkasini yaratib bo'lmadi")
            }

            onStatus("GigaAM modeli yuklanmoqda")

            downloadFile(
                "https://huggingface.co/Alex13/sherpa-onnx-gigaam-uzbek-600m/resolve/b53434a0b06418831dbc1cec73d6b851054f593d/model.int8.onnx",
                modelFile,
                1_000_000
            )

            onStatus("Ovoz modeli fayllari tekshirilmoqda")

            downloadFile(
                "https://huggingface.co/Alex13/sherpa-onnx-gigaam-uzbek-600m/resolve/b53434a0b06418831dbc1cec73d/tokens.txt",
                tokensFile,
                100
            )

            if (!ready()) {
                error("GigaAM modeli to'liq yuklanmadi")
            }

            onStatus("Ovoz modeli yuklandi")

        } catch (e: Exception) {
            onStatus("Model yuklash xatosi: ${e.message ?: "noma'lum xato"}")
            throw e
        } finally {
            downloading.set(false)
        }
    }

    private fun downloadFile(
        url: String,
        destination: File,
        minimumSize: Long
    ) {
        val temp = File(destination.parentFile, destination.name + ".part")
        temp.delete()

        val connection = URL(url).openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = 30000
            connection.readTimeout = 120000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "Nora-Android/1.0")

            val response = connection.responseCode

            if (response !in 200..299) {
                error("Yuklash serveri xatosi: HTTP $response")
            }

            connection.inputStream.use { input ->
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)

                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }

            if (!temp.exists() || temp.length() < minimumSize) {
                error("Yuklangan fayl hajmi noto'g'ri")
            }

            if (destination.exists() && !destination.delete()) {
                error("Eski model faylini almashtirib bo'lmadi")
            }

            if (!temp.renameTo(destination)) {
                temp.copyTo(destination, overwrite = true)
                temp.delete()
            }

        } catch (e: Exception) {
            temp.delete()
            throw e
        } finally {
            connection.disconnect()
        }
    }

    @Synchronized
    private fun createRecognizer(): OfflineRecognizer {
        recognizer?.let { return it }

        if (!ready()) {
            throw IllegalStateException("Avval ovoz modelini yuklang")
        }

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
        ).also {
            recognizer = it
        }
    }

    fun start() {
        if (running.get()) return

        if (!ready()) {
            onStatus("Avval ovoz modelini yuklang")
            return
        }

        if (
            ContextCompat.checkSelfPermission(
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
                    error("Mikrofon bu formatni qo'llab-quvvatlamaydi")
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
                    error("Mikrofon tayyor emas")
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
                if (running.get()) {
                    onStatus("Tinglash xatosi: ${e.message ?: "noma'lum xato"}")
                }
            } finally {
                try {
                    recorder?.stop()
                } catch (_: Exception) {
                }

                recorder?.release()
                audioRecord = null
                running.set(false)
                onStatus("Tinglash to'xtadi")
            }
        }
    }

    private fun recognize(
        engine: OfflineRecognizer,
        samples: FloatArray
    ) {
        if (samples.size < 1600) return

        var stream: OnlineStream? = null

        try {
            stream = engine.createStream()
            stream.acceptWaveform(samples, sampleRate)
            engine.decode(stream)

            val result = engine.getResult(stream)
            val text = result.text.trim()

            if (text.isNotBlank()) {
                onRecognized(text)
            }

        } catch (_: Exception) {
            onStatus("Ovozni tanish xatosi")
        } finally {
            try {
                stream?.release()
            } catch (_: Exception) {
            }
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

        try {
            worker?.join(3000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        synchronized(this) {
            try {
                recognizer?.release()
            } catch (_: Exception) {
            }

            recognizer = null
        }
    }
}
