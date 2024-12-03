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
                if (!modelPaths.isNullOrEmpty()) {
                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            startListeningWithModels(modelPaths)
                            result.success("Listening started")
                        } catch (e: Exception) {
                            stopListening()
                            result.error("MODEL_LOADING_ERROR", "Failed to start listening: ${e.message}", e)
                        }
                    }
                } else {
                    result.error("INVALID_ARGUMENT", "Model paths are null or invalid", null)
                }
            }

            "stopListening" -> {
                stopListening()
                result.success("Listening stopped")
            }

            else -> result.notImplemented()
        }
    }
//-------------mfccextraction code ---------------------------------------

    fun extractMFCC(audioBuffer: ShortArray): Array<Array<Array<FloatArray>>> {
        // Step 1: Pre-process the audio (convert short audio buffer to float)
        val audioFloat = audioBuffer.map { it.toFloat() / Short.MAX_VALUE.toFloat() }

        // Step 2: Apply FFT to extract frequency-domain data
        val frequencyData = applyFFT(audioFloat)

        // Step 3: Apply Mel Filterbank to convert to Mel scale
        val melSpectrogram = melFilterBank(frequencyData)

        // Step 4: Compute MFCC coefficients
        val mfcc = computeMFCC(melSpectrogram)

        // Return in shape (None, 64, 15, 1) as requested (adjust the shape accordingly)
        return arrayOf(arrayOf(mfcc))
    }

    // Step 1: Apply FFT to extract frequency-domain data
    // Step 1: Apply FFT to extract frequency-domain data
    fun applyFFT(audio: List<Float>): Array<FloatArray> {
        // Convert List<Float> to FloatArray
        val signal = FloatArray(audio.size)

        // Convert to Long if needed
        val fft = FloatFFT_1D(signal.size.toLong())  // Make sure you cast to Long here
        fft.realForward(signal)  // Apply the FFT to the signal

        // Split into real and imaginary parts
        val realPart = FloatArray(signal.size / 2)
        val imaginaryPart = FloatArray(signal.size / 2)

        for (i in 0 until signal.size / 2) {
            realPart[i] = signal[i * 2]    // Real part
            imaginaryPart[i] = signal[i * 2 + 1] // Imaginary part
        }

        return Array(realPart.size) { i -> floatArrayOf(realPart[i], imaginaryPart[i]) }
    }


    // Step 2: Apply Mel Filterbank
    fun melFilterBank(frequencyData: Array<FloatArray>): Array<FloatArray> {
        val melData = Array(N_MELS) { FloatArray(frequencyData.size) }
        val melMin = 300.0 // Minimum Mel frequency
        val melMax = 8000.0 // Maximum Mel frequency

        val melScale = Array(N_MELS) { 0f }

        // Mapping from Hz to Mel scale
        for (i in 0 until N_MELS) {
            val melValue = melMin + (i.toDouble() * (melMax - melMin) / (N_MELS - 1))
            val logValue = 1 + melValue
            val logResult = log10(logValue.toFloat()) / 700
            melScale[i] = (2595 * logResult).toFloat()
        }

        // Filter frequencies with Mel filterbank
        for (i in 0 until N_MELS) {
            for (j in 0 until frequencyData.size) {
                melData[i][j] = frequencyData[j][0] * melScale[i]
            }
        }

        return melData
    }

    // Step 3: Compute MFCC coefficients (DCT - Discrete Cosine Transform)
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





    //-----end extraction coee ---------------------

    private fun isModelLoaded(modelPath: String): Boolean {
        return try {
            Interpreter(File(modelPath)).close()
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun startListeningWithModels(modelPaths: List<String>) {
        // Load interpreters
        interpreters.clear()
        modelPaths.forEach { interpreters.add(loadModelFromPath(it)) }

        // Validate models
        interpreters.forEach { validateModel(it) }

        // Initialize audio recording
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

        // Start listening
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
                    // Extract MFCC features from the audio buffer
                    val mfccFeatures = extractMFCC(audioBuffer)

                    // Iterate over each model and check for wake word
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

    private fun validateModel(interpreter: Interpreter) {
        val inputShape = interpreter.getInputTensor(0).shape()
        val outputShape = interpreter.getOutputTensor(0).shape()

        // Validate input shape for (None, 64, 15, 1)
        require(inputShape.size == 4 && inputShape[1] == N_MELS && inputShape[2] == N_FRAMES) {
            "Model input shape must be [1, $N_MELS, $N_FRAMES, 1]"
        }
        require(outputShape.size == 2) {
            "Model output shape must be [batch_size, classes]"
        }
    }

    private fun detectWakeWord(
        interpreter: Interpreter,
        mfccFeatures: Array<Array<Array<FloatArray>>>
    ): Boolean {
        val outputShape = interpreter.getOutputTensor(0).shape()
        val output = Array(outputShape[0]) { FloatArray(outputShape[1]) }

        return try {
            interpreter.run(mfccFeatures, output)
            output.any { it.maxOrNull() ?: 0f > 0.5f } // Use a threshold of 0.5 to detect wake word
        } catch (e: Exception) {
            println("Error during model inference: ${e.message}")
            false
        }
    }
}
