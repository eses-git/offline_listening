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
import kotlin.math.*

const val SAMPLE_RATE = 16000
const val N_MELS = 40
const val FRAME_RATE = 100
const val FRAME_SIZE = SAMPLE_RATE / FRAME_RATE  // Samples per frame
const val N_FRAMES = 100  // Number of time frames for TFLite model
const val BUFFER_SIZE = FRAME_SIZE * N_FRAMES
const val THRESHOLD = 0.5f  // Wake word detection confidence threshold

class OfflineListeningPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private lateinit var channel: MethodChannel
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var isListening = false
    private var interpreter: Interpreter? = null

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, "flutter_mfcc_plugin")
        channel.setMethodCallHandler(this)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        stopListening()
        interpreter?.close()
        interpreter = null
    }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "isModelLoaded" -> {
                val modelPath = call.argument<String>("modelPath")
                result.success(modelPath?.let { isModelLoaded(it) } ?: false)
            }

            "startListening" -> {
                val modelPath = call.argument<String>("modelPath")
                if (!modelPath.isNullOrEmpty()) {
                    CoroutineScope(Dispatchers.Main).launch {
                        try {
                            startListeningWithModel(modelPath)
                            result.success("Listening started")
                        } catch (e: Exception) {
                            stopListening()
                            result.error("MODEL_LOADING_ERROR", "Failed to start listening: ${e.message}", e)
                        }
                    }
                } else {
                    result.error("INVALID_ARGUMENT", "Model path is null or invalid", null)
                }
            }

            "stopListening" -> {
                stopListening()
                result.success("Listening stopped")
            }

            else -> result.notImplemented()
        }
    }

    private fun isModelLoaded(modelPath: String): Boolean {
        println("Received modelPath: $modelPath")
        val file = File(modelPath)
        println("File exists: ${file.exists()}")
        return file.exists()
    }

    private fun startListeningWithModel(modelPath: String) {
        interpreter = loadModelFromPath(modelPath)
        validateModel(interpreter!!)

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSize == AudioRecord.ERROR_BAD_VALUE || minBufferSize == AudioRecord.ERROR) {
            throw IllegalStateException("Invalid AudioRecord buffer size")
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            BUFFER_SIZE
        )

        isListening = true
        audioRecord?.startRecording()

        CoroutineScope(Dispatchers.IO).launch {
            processAudio()
        }
    }

    private suspend fun processAudio() {
        val audioBuffer = ShortArray(BUFFER_SIZE)
        val slidingWindow = ArrayDeque<Short>()  // To hold continuous audio data
        try {
            while (isListening) {
                val read = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                if (read > 0) {
                    // Add new audio samples to the sliding window
                    slidingWindow.addAll(audioBuffer.take(read))
                    if (slidingWindow.size >= BUFFER_SIZE) {
                        // Process the audio if we have enough data
                        val mfccFeatures = extractMFCC(slidingWindow.take(BUFFER_SIZE).toShortArray())
                        repeat(BUFFER_SIZE) {
                            if (slidingWindow.isNotEmpty()) {
                                slidingWindow.removeFirst()
                            }
                        }

                        // Run inference
                        if (detectWakeWord(interpreter!!, mfccFeatures)) {
                            println("Wake Word detected!")
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
        // Get input and output tensor details
        val inputTensor = interpreter.getInputTensor(0)
        val outputTensor = interpreter.getOutputTensor(0)

        val inputShape = inputTensor.shape()
        val outputShape = outputTensor.shape()

        // Log all details for debugging
        println("Model Validation Debugging:")
        println("Expected Input Shape: [1, $N_FRAMES, $N_MELS, 1]")
        println("Actual Input Shape: ${inputShape.contentToString()}")
        println("Input Tensor Details: Name=${inputTensor.name()}, Dtype=${inputTensor.dataType()}, Shape=${inputShape.contentToString()}")

        println("Expected Output Shape: [1, 1]")
        println("Actual Output Shape: ${outputShape.contentToString()}")
        println("Output Tensor Details: Name=${outputTensor.name()}, Dtype=${outputTensor.dataType()}, Shape=${outputShape.contentToString()}")

        // Validate input shape
        require(inputShape.contentEquals(intArrayOf(1, N_FRAMES, N_MELS, 1))) {
            "Model input shape must be [1, $N_FRAMES, $N_MELS, 1], but found ${inputShape.contentToString()}"
        }

        // Validate output shape
        require(outputShape.contentEquals(intArrayOf(1, 1))) {
            "Model output shape must be [1, 1], but found ${outputShape.contentToString()}"
        }
    }



    private fun detectWakeWord(
        interpreter: Interpreter,
        mfccFeatures: Array<Array<Array<FloatArray>>>
    ): Boolean {
        val output = Array(1) { FloatArray(1) }
        return try {
            println("Input Features Shape: [${mfccFeatures.size}, ${mfccFeatures[0].size}, ${mfccFeatures[0][0].size}, ${mfccFeatures[0][0][0].size}]")
            interpreter.run(mfccFeatures, output)
            println("Model Output: ${output[0][0]}")
            output[0][0] > THRESHOLD
        } catch (e: Exception) {
            println("Error during model inference: ${e.message}")
            false
        }
    }


    private fun extractMFCC(audioBuffer: ShortArray): Array<Array<Array<FloatArray>>> {
        // Normalize audio buffer to range [-1, 1]
        val audioFloat = audioBuffer.map { it.toFloat() / Short.MAX_VALUE }

        // Compute Mel spectrogram (2D array of shape [N_FRAMES, N_MELS])
        val melSpectrogram = computeMelSpectrogram(audioFloat)

        // Convert Mel spectrogram to 4D tensor [1, N_FRAMES, N_MELS, 1]
        return arrayOf(
            Array(N_FRAMES) { frameIndex ->
                Array(N_MELS) { melIndex ->
                    floatArrayOf(melSpectrogram[frameIndex][melIndex])
                }
            }
        )
    }


    private fun computeMelSpectrogram(audio: List<Float>): Array<FloatArray> {
        // Create an empty array to hold the Mel spectrogram
        val melSpectrogram = Array(N_FRAMES) { FloatArray(N_MELS) }

        // Fill the spectrogram with audio data, chunked into frames and Mel bins
        for (i in 0 until N_FRAMES) {
            for (j in 0 until N_MELS) {
                val index = i * N_MELS + j
                melSpectrogram[i][j] = if (index < audio.size) audio[index] else 0.0f
            }
        }

        return melSpectrogram
    }



}