package uz.nora.ai

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import androidx.core.content.ContextCompat
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

class OfflineUzbekAsr(
    context: Context,
    private val onRecognized: (String) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 1600
        private const val PRE_ROLL_SAMPLES = 8000
        private const val MIN_SPEECH_THRESHOLD = 280.0
        private const val NOISE_MULTIPLIER = 2.5
        private const val SILENCE_LIMIT = 20
        private const val MAX_SPEECH_SECONDS = 15
        private const val MIN_SPEECH_FRAMES = 3
        private const val MAX_MODEL_BYTES = 1_500_000_000L

        private const val MODEL_BASE =
            "https://huggingface.co/Alex13/sherpa-onnx-gigaam-uzbek-600m/resolve/b53434a0b06418831dbc1cec73d6b851054f593d/"
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
        return modelFile.isFile &&
            tokensFile.isFile &&
            modelFile.length() > 1_000_000L &&
            modelFile.length() <= MAX_MODEL_BYTES &&
            tokensFile.length() > 100L
    }

    @Synchronized
    fun downloadModel() {
        if (ready()) {
            onStatus("GigaAM ovoz modeli tayyor")
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
                MODEL_BASE + "model.int8.onnx",
                modelFile,
                1_000_000L
            )

            onStatus("Ovoz tokenlari yuklanmoqda")

            downloadFile(
                MODEL_BASE + "tokens.txt",
                tokensFile,
                100L
            )

            check(ready()) {
                "Model fayllari to'liq yuklanmadi"
            }

            onStatus("GigaAM modeli tayyor")
        } catch (e: Exception) {
            modelFile.delete()
            tokensFile.delete()
            onStatus("Model yuklash xatosi: ${e.message}")
            throw e
        } finally {
            downloading.set(false)
        }
    }

    private fun downloadFile(
        address: String,
        destination: File,
        minimumSize: Long
    ) {
        val temporary = File(
            destination.parentFile,
            destination.name + ".part"
        )

        temporary.delete()

        val connection = URL(address).openConnection() as HttpURLConnection

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

            check(connection.url.protocol.equals("https", true)) {
                "Model manzili xavfsiz HTTPS emas"
            }

            val declaredSize = connection.contentLengthLong
            if (declaredSize > 0) {
                check(declaredSize <= MAX_MODEL_BYTES) {
                    "Model fayli ruxsat etilgan hajmdan katta"
                }
            }

            var downloaded = 0L

            connection.inputStream.use { input ->
                temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(128 * 1024)

                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break

                        downloaded += count

                        check(downloaded <= MAX_MODEL_BYTES) {
                            "Yuklanayotgan fayl hajmi limitdan oshdi"
                        }

                        output.write(buffer, 0, count)
                    }
                }
            }

            check(temporary.isFile && temporary.length() >= minimumSize) {
                "Yuklangan fayl hajmi noto'g'ri"
            }

            if (destination.exists()) {
                check(destination.delete()) {
                    "Eski model faylini almashtirib bo'lmadi"
                }
            }

            if (!temporary.renameTo(destination)) {
                temporary.copyTo(destination, overwrite = true)
                temporary.delete()
            }
        } catch (e: Exception) {
            temporary.delete()
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
            onStatus("Avval GigaAM ovoz modelini yuklang")
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
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

            var recorder: AudioRecord? = null

            try {
                val minimumBuffer = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT
                )

                check(minimumBuffer > 0) {
                    "Mikrofon formati qo'llab-quvvatlanmaydi"
                }

                val bufferSize = maxOf(
                    minimumBuffer,
                    FRAME_SIZE * 4
                )

                recorder = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )

                check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                    "Mikrofon ishga tushmadi"
                }

                audioRecord = recorder
                val engine = createRecognizer()

                recorder.startRecording()
                onStatus("Nora tinglamoqda")

                val pcm = ShortArray(FRAME_SIZE)
                val preRoll = ShortArray(PRE_ROLL_SAMPLES)
                val audio = FloatAudioBuffer(SAMPLE_RATE * 5)

                var preWrite = 0
                var preCount = 0
                var silenceFrames = 0
                var speechFrames = 0
                var speechStarted = false
                var noiseFloor = 100.0

                val maximumSamples = SAMPLE_RATE * MAX_SPEECH_SECONDS

                while (running.get()) {
                    val count = recorder.read(
                        pcm,
                        0,
                        pcm.size,
                        AudioRecord.READ_BLOCKING
                    )

                    if (count <= 0) {
                        if (!running.get()) break
                        continue
                    }

                    var peak = 0
                    var sumSquares = 0.0

                    for (i in 0 until count) {
                        val sample = pcm[i].toInt()
                        peak = maxOf(peak, abs(sample))
                        sumSquares += sample.toDouble() * sample
                    }

                    val rms = sqrt(
                        sumSquares / count.coerceAtLeast(1)
                    )

                    val threshold = maxOf(
                        MIN_SPEECH_THRESHOLD,
                        noiseFloor * NOISE_MULTIPLIER
                    )

                    val hasSpeech =
                        peak > threshold &&
                            rms > threshold * 0.30

                    if (!speechStarted) {
                        if (hasSpeech) {
                            speechFrames++
                        } else {
                            speechFrames = 0
                            noiseFloor =
                                (noiseFloor * 0.94) + (rms * 0.06)
                        }

                        if (speechFrames >= MIN_SPEECH_FRAMES) {
                            speechStarted = true
                            silenceFrames = 0

                            val firstIndex =
                                (preWrite - preCount + preRoll.size) %
                                    preRoll.size

                            for (i in 0 until preCount) {
                                val index =
                                    (firstIndex + i) % preRoll.size

                                audio.add(
                                    preRoll[index] / 32768.0f
                                )
                            }

                            preCount = 0
                            preWrite = 0
                        }
                    } else {
                        if (hasSpeech) silenceFrames = 0
                        else silenceFrames++
                    }

                    if (speechStarted) {
                        for (i in 0 until count) {
                            audio.add(pcm[i] / 32768.0f)
                        }
                    } else {
                        for (i in 0 until count) {
                            preRoll[preWrite] = pcm[i]
                            preWrite = (preWrite + 1) % preRoll.size

                            if (preCount < preRoll.size) {
                                preCount++
                            }
                        }
                    }

                    if (
                        speechStarted &&
                        (
                            silenceFrames >= SILENCE_LIMIT ||
                                audio.size >= maximumSamples
                            )
                    ) {
                        recognize(engine, audio.toArray())
                        audio.clear()

                        preCount = 0
                        preWrite = 0
                        silenceFrames = 0
                        speechFrames = 0
                        speechStarted = false
                    }
                }

                if (audio.size >= FRAME_SIZE * 3 && speechStarted) {
                    recognize(engine, audio.toArray())
                }
            } catch (e: Exception) {
                if (running.get()) {
                    onStatus(
                        "Ovoz tinglash xatosi: ${e.message ?: "noma'lum"}"
                    )
                }
            } finally {
                try {
                    if (
                        recorder?.recordingState ==
                        AudioRecord.RECORDSTATE_RECORDING
                    ) {
                        recorder.stop()
                    }
                } catch (_: Exception) {
                }

                try {
                    recorder?.release()
                } catch (_: Exception) {
                }

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
        if (samples.size < FRAME_SIZE * 3) return

        try {
            val stream = engine.createStream()

            try {
                stream.acceptWaveform(
                    samples,
                    SAMPLE_RATE
                )

                engine.decode(stream)

                val recognized = engine.getResult(stream).text
                    .trim()
                    .replace(Regex("\\s+"), " ")

                if (recognized.isNotBlank()) {
                    onRecognized(recognized)
                }
            } finally {
                stream.release()
            }
        } catch (e: Exception) {
            onStatus(
                "Ovozni tanish xatosi: ${e.message ?: "qayta urinib ko'ring"}"
            )
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
            worker?.join(5000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }

        synchronized(this) {
            if (worker?.isAlive == true) {
                // Ishchi oqim hali dekodlashni tugatmagan bo'lsa,
                // recognizer'ni shu yerda bo'shatmaymiz.
                return
            }

            try {
                recognizer?.release()
            } catch (_: Exception) {
            }

            recognizer = null
            worker = null
        }
    }

    private class FloatAudioBuffer(initialCapacity: Int) {
        private var data = FloatArray(initialCapacity.coerceAtLeast(1024))
        var size: Int = 0
            private set

        fun add(value: Float) {
            if (size >= data.size) {
                data = data.copyOf(data.size * 2)
            }

            data[size++] = value
        }

        fun toArray(): FloatArray {
            return data.copyOf(size)
        }

        fun clear() {
            size = 0
        }
    }
}
