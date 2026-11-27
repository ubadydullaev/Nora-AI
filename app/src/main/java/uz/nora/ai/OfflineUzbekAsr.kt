
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
    companion object {
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 1600
        private const val SPEECH_THRESHOLD = 450
        private const val SILENCE_LIMIT = 18
        private const val MAX_SPEECH_SECONDS = 15
    }

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

    fun ready(): Boolean {
        return modelFile.exists() &&
            tokensFile.exists() &&
            modelFile.length() > 1_000_000 &&
            tokensFile.length() > 100
    }

    @Synchronized
    fun downloadModel() {
        if (ready()) {
            onStatus("Ovoz modeli tayyor")
            return
        }

        check(downloading.compareAndSet(false, true)) {
            "Model allaqachon yuklanmoqda"
        }

        try {
            if (!modelDir.exists()) {
                check(modelDir.mkdirs()) {
                    "Model papkasini yaratib bo'lmadi"
                }
            }

            onStatus("GigaAM modeli yuklanmoqda")

            downloadFile(
                "https://huggingface.co/Alex13/sherpa-onnx-gigaam-uzbek-600m/resolve/b53434a0b06418831dbc1cec73d6b851054f593d/model.int8.onnx",
                modelFile,
                1_000_000
            )

            onStatus("Token fayli yuklanmoqda")

            downloadFile(
                "https://huggingface.co/Alex13/sherpa-onnx-gigaam-uzbek-600m/resolve/b53434a0b06418831dbc1cec73d/tokens.txt",
                tokensFile,
                100
            )

            check(ready()) {
                "Model to'liq yuklanmadi"
            }

            onStatus("Ovoz modeli yuklandi")

        } catch (e: Exception) {
            onStatus("Model yuklash xatosi: ${e.message}")
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
        val temp = File(
            destination.parentFile,
            destination.name + ".part"
        )

        temp.delete()

        val connection =
            URL(url).openConnection() as HttpURLConnection

        try {
            connection.connectTimeout = 30000
            connection.readTimeout = 120000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty(
                "User-Agent",
                "Nora-Android/1.0"
            )

            val response = connection.responseCode

            check(response in 200..299) {
                "HTTP yuklash xatosi: $response"
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

            check(temp.exists() && temp.length() >= minimumSize) {
                "Yuklangan fayl hajmi noto'g'ri"
            }

            if (destination.exists()) {
                check(destination.delete()) {
                    "Eski faylni almashtirib bo'lmadi"
                }
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

        check(ready()) {
            "Avval GigaAM modelini yuklang"
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

        return OfflineRecognizer(
            appContext.assets,
            OfflineRecognizerConfig(
                modelConfig = modelConfig
            )
        ).also {
            recognizer = it
        }
    }

    fun start() {
        if (!running.compareAndSet(false, true)) return

        if (!ready()) {
            running.set(false)
            onStatus("Avval ovoz modelini yuklang")
            return
        }

        if (
            ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            running.set(false)
            onStatus("Mikrofon ruxsati kerak")
            return
        }

        worker = thread(name = "Nora-ASR-Listen") {
            Process.setThreadPriority(
                Process.THREAD_PRIORITY_AUDIO
            )

            var recorder: AudioRecord? = null

            try {
                val minBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                check(minBuffer > 0) {
                    "Mikrofon formati qo'llab-quvvatlanmaydi"
                }

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.MIC,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuffer, FRAME_SIZE * 4)
                )

                check(
                    recorder.state == AudioRecord.STATE_INITIALIZED
                ) {
                    "Mikrofon ishga tushmadi"
                }

                audioRecord = recorder
                val engine = createRecognizer()

                recorder.startRecording()
                onStatus("Nora tinglamoqda")

                val pcm = ShortArray(FRAME_SIZE)
                val audio = ArrayList<Float>()
                var silenceFrames = 0
                var speechStarted = false

                while (running.get()) {
                    val count = recorder.read(
                        pcm,
                        0,
                        pcm.size
                    )

                    if (count <= 0) continue

                    var peak = 0

                    for (i in 0 until count) {
                        peak = maxOf(
                            peak,
                            abs(pcm[i].toInt())
                        )
                    }

                    val hasSpeech = peak > SPEECH_THRESHOLD

                    if (hasSpeech) {
                        speechStarted = true
                        silenceFrames = 0
                    } else if (speechStarted) {
                        silenceFrames++
                    }

                    // Jimlikdagi keraksiz audioni xotirada yig'maymiz.
                    if (speechStarted) {
                        for (i in 0 until count) {
                            audio.add(
                                pcm[i] / 32768.0f
                            )
                        }
                    }

                    val maxSamples =
                        SAMPLE_RATE * MAX_SPEECH_SECONDS

                    if (
                        speechStarted &&
                        (
                            silenceFrames >= SILENCE_LIMIT ||
                            audio.size >= maxSamples
                        )
                    ) {
                        recognize(
                            engine,
                            audio.toFloatArray()
                        )

                        audio.clear()
                        silenceFrames = 0
                        speechStarted = false
                    }
                }

                if (audio.isNotEmpty() && speechStarted) {
                    recognize(
                        engine,
                        audio.toFloatArray()
                    )
                }

            } catch (e: Exception) {
                if (running.get()) {
                    onStatus(
                        "Tinglash xatosi: ${e.message}"
                    )
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
        if (samples.size < FRAME_SIZE) return

        try {
            val stream = engine.createStream()

            try {
                stream.acceptWaveform(
                    samples,
                    SAMPLE_RATE
                )

                engine.decode(stream)

                val recognized =
                    engine.getResult(stream).text.trim()

                if (recognized.isNotBlank()) {
                    onRecognized(recognized)
                }

            } finally {
                stream.release()
            }

        } catch (_: Exception) {
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
