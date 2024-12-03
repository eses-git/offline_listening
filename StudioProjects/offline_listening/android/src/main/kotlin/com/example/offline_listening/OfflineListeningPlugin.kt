package com.example.offline_listening

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.tensorflow.lite.Interpreter
import java.io.File
import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.*

const val SAMPLE_RATE = 16000
const val N_MELS = 64
const val FFT_SIZE = 512
const val HOP_SIZE = 256
const val N_FRAMES = 15
val NUM_COEFFICIENTS = 13

class OfflineListeningPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private lateinit var channel: MethodChannel
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var isListening = false
    private val interpreters = mutableListOf<Interpreter>()

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, "flutter_mfcc_plugin")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        stopListening()
        interpreters.forEach { it.close() }
        interpreters.clear()
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "isModelLoaded" -> {
                val modelPath = call.argument<String>("modelPath")
                result.success(modelPath?.let { isModelLoaded(it) } ?: false)
            }

            "startListening" -> {
                val modelPaths = call.argument<List<String>>("modelPaths")
                val shapes = call.argument<List<List<Int>>>("shapes") // Shape passed here
                if (!modelPaths.isNullOrEmpty() && shapes != null && shapes.size == modelPaths.size) {
                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            startListeningWithModels(modelPaths, shapes)
                            result.success("Listening started")
                        } catch (e: Exception) {
                            stopListening()
                            result.error("MODEL_LOADING_ERROR", "Failed to start listening: ${e.message}", e)
                        }
                    }
                } else {
                    result.error("INVALID_ARGUMENT", "Model paths or shapes are null or invalid", null)
                }
            }

            "stopListening" -> {
                stopListening()
                result.success("Listening stopped")
            }

            else -> result.notImplemented()
        }
    }

    fun extractMFCC(audioBuffer: ShortArray): Array<Array<Array<FloatArray>>> {
        val audioFloat = audioBuffer.map { it.toFloat() / Short.MAX_VALUE.toFloat() }
        val frequencyData = applyFFT(audioFloat)
        val melSpectrogram = melFilterBank(frequencyData)
        val mfcc = computeMFCC(melSpectrogram)
        return arrayOf(arrayOf(mfcc))
    }

    fun applyFFT(audio: List<Float>): Array<FloatArray> {
        val signal = FloatArray(audio.size)
        val fft = FloatFFT_1D(signal.size.toLong())
        fft.realForward(signal)
        val realPart = FloatArray(signal.size / 2)
        val imaginaryPart = FloatArray(signal.size / 2)

        for (i in 0 until signal.size / 2) {
            realPart[i] = signal[i * 2]
            imaginaryPart[i] = signal[i * 2 + 1]
        }

        return Array(realPart.size) { i -> floatArrayOf(realPart[i], imaginaryPart[i]) }
    }

    fun melFilterBank(frequencyData: Array<FloatArray>): Array<FloatArray> {
        val melData = Array(N_MELS) { FloatArray(frequencyData.size) }
        val melMin = 300.0
        val melMax = 8000.0
        val melScale = Array(N_MELS) { 0f }

        for (i in 0 until N_MELS) {
            val melValue = melMin + (i.toDouble() * (melMax - melMin) / (N_MELS - 1))
            val logValue = 1 + melValue
            val logResult = log10(logValue.toFloat()) / 700
            melScale[i] = (2595 * logResult).toFloat()
        }

        for (i in 0 until N_MELS) {
            for (j in 0 until frequencyData.size) {
                melData[i][j] = frequencyData[j][0] * melScale[i]
            }
        }

        return melData
    }

    fun computeMFCC(melSpectrogram: Array<FloatArray>): Array<FloatArray> {
        val mfcc = Array(melSpectrogram.size) { FloatArray(NUM_COEFFICIENTS) }
        for (i in melSpectrogram.indices) {
            for (j in 0 until NUM_COEFFICIENTS) {
                var sum = 0f
                for (k in melSpectrogram[i].indices) {
                    sum += melSpectrogram[i][k] * cos(Math.PI * (j.toFloat() + 0.5) * k / melSpectrogram[i].size).toFloat()
                }
                mfcc[i][j] = sum
            }
        }
        return mfcc
    }

    private fun isModelLoaded(modelPath: String): Boolean {
        return try {
            Interpreter(File(modelPath)).close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun startListeningWithModels(modelPaths: List<String>, shapes: List<List<Int>>) {
        interpreters.clear()
        modelPaths.forEachIndexed { index, modelPath ->
            val interpreter = loadModelFromPath(modelPath)
            validateModel(interpreter, shapes[index])  // Pass shape information here
            interpreters.add(interpreter)
        }

        val bufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (bufferSize == AudioRecord.ERROR_BAD_VALUE || bufferSize == AudioRecord.ERROR) {
            throw IllegalStateException("Invalid AudioRecord buffer size")
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        isListening = true
        audioRecord?.startRecording()

        CoroutineScope(Dispatchers.IO).launch {
            processAudio(bufferSize)
        }
    }

    private suspend fun processAudio(bufferSize: Int) {
        val audioBuffer = ShortArray(bufferSize)
        try {
            while (isListening) {
                val read = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                if (read > 0) {
                    val mfccFeatures = extractMFCC(audioBuffer)
                    interpreters.forEachIndexed { index, interpreter ->
                        if (detectWakeWord(interpreter, mfccFeatures)) {
                            println("Wake Word detected by model $index!")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("Error during audio processing: ${e.message}")
        }
    }

    private fun stopListening() {
        isListening = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }

    private fun loadModelFromPath(modelPath: String): Interpreter {
        val file = File(modelPath)
        require(file.exists()) { "Model file not found at $modelPath" }
        return Interpreter(file)
    }

    private fun validateModel(interpreter: Interpreter, expectedShape: List<Int>) {
        val inputShape = interpreter.getInputTensor(0).shape().toList()
        val outputShape = interpreter.getOutputTensor(0).shape().toList()

        require(inputShape == expectedShape) {
            "Model input shape $inputShape does not match expected shape $expectedShape"
        }
        require(outputShape.size == 2) {
            "Model output shape must be [batch_size, classes], but was $outputShape"
        }
    }

    private fun detectWakeWord(interpreter: Interpreter, mfccFeatures: Array<Array<Array<FloatArray>>>): Boolean {
        val outputShape = interpreter.getOutputTensor(0).shape()
        val output = Array(outputShape[0]) { FloatArray(outputShape[1]) }

        return try {
            interpreter.run(mfccFeatures, output)
            output.any { it.maxOrNull() ?: 0f > 0.5f }
        } catch (e: Exception) {
            println("Error during model inference: ${e.message}")
            false
        }
    }
}
