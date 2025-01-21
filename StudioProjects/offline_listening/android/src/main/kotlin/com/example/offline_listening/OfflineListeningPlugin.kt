package com.example.offline_listening

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import kotlinx.coroutines.*
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

const val SAMPLE_RATE = 16000
const val FRAME_SIZE = SAMPLE_RATE / 200  // Frame size based on frame rate
const val HOP_SIZE = FRAME_SIZE / 2      // 50% overlap for sliding window
const val N_FRAMES = 256                 // Number of frames for 1-second clip
const val REQUIRED_SAMPLES = FRAME_SIZE * (N_FRAMES - 1) + FRAME_SIZE // Total samples
const val MEL_BANDS = 300                // Mel bands, matching training code
const val THRESHOLD = 0.95f               // Threshold for classification

class OfflineListeningPlugin : FlutterPlugin, MethodChannel.MethodCallHandler {
    private lateinit var channel: MethodChannel
    private var audioRecord: AudioRecord? = null
    @Volatile
    private var isListening = false
    private var interpreter: Interpreter? = null

    private val audioBuffer = ArrayList<Short>()

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
        return File(modelPath).exists()
    }

    private fun loadModel(modelPath: String): Interpreter {
        val modelFile = File(modelPath)
        require(modelFile.exists()) { "Model file not found at $modelPath" }
        return Interpreter(modelFile)
    }

    private fun startListeningWithModel(modelPath: String) {
        interpreter = loadModel(modelPath)

        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        require(minBufferSize != AudioRecord.ERROR_BAD_VALUE && minBufferSize != AudioRecord.ERROR) {
            "Invalid AudioRecord buffer size"
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            REQUIRED_SAMPLES
        )
        require(audioRecord?.state == AudioRecord.STATE_INITIALIZED) {
            "Failed to initialize AudioRecord"
        }

        isListening = true
        audioRecord?.startRecording()

        CoroutineScope(Dispatchers.IO).launch {
            processAudio()
        }
    }

    private suspend fun processAudio() {
        val tempBuffer = ShortArray(FRAME_SIZE)
        try {
            while (isListening) {
                val read = audioRecord?.read(tempBuffer, 0, FRAME_SIZE) ?: 0
                if (read > 0) {
                    synchronized(audioBuffer) {
                        if (audioBuffer.size > REQUIRED_SAMPLES) {
                            audioBuffer.subList(0, audioBuffer.size - REQUIRED_SAMPLES).clear()
                        }
                        audioBuffer.addAll(tempBuffer.take(read))
                        if (audioBuffer.size >= REQUIRED_SAMPLES) {
                            val frame = synchronized(audioBuffer) {
                                audioBuffer.take(REQUIRED_SAMPLES).toShortArray()
                            }
                            synchronized(audioBuffer) {
                                audioBuffer.subList(0, HOP_SIZE).clear()
                            }

                            // Debugging: Log raw audio frame
                            android.util.Log.d("OfflineListening", "Raw audio frame: ${frame.joinToString(", ")}")

                            val windowedFrame = applyWindowing(frame)

                            // Debugging: Log windowed frame
                            android.util.Log.d("OfflineListening", "Windowed audio frame: ${windowedFrame.joinToString(", ")}")

                            val inputTensor = preprocessAudioToShort(windowedFrame)

                            // Debugging: Log input tensor after preprocessing
                            android.util.Log.d("OfflineListening", "Input tensor after preprocessing: ${inputTensor.joinToString(", ")}")

                            val output = classifyWakeWord(inputTensor)

                            // Debugging: Log model output
                            android.util.Log.d("OfflineListening", "Model output: $output")

                            if (output > THRESHOLD) {
                                // Wake word detected, log the event
                                android.util.Log.d("OfflineListening", "Wake word detected with output: $output")
                                CoroutineScope(Dispatchers.Main).launch {
                                    channel.invokeMethod("onWakeWordDetected", null)
                                }
                            } else {
                                // Debugging: Log when no wake word is recognized
                                android.util.Log.d("OfflineListening", "Wake word not recognized. Output below threshold.")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun preprocessAudio(audioFrame: ShortArray): FloatArray {
        val floatAudioFrame = audioFrame.map { it.toFloat() / Short.MAX_VALUE }.toFloatArray()

        // Debugging: Log the raw audio frame after conversion to float
        android.util.Log.d("OfflineListening", "Float audio frame: ${floatAudioFrame.joinToString(", ")}")

        val requiredSize = 19200
        return if (floatAudioFrame.size > requiredSize) {
            floatAudioFrame.sliceArray(0 until requiredSize)
        } else {
            FloatArray(requiredSize).apply {
                floatAudioFrame.copyInto(this)
            }
        }
    }

    private fun preprocessAudioToShort(audioFrame: ShortArray): FloatArray {
        val floatAudioFrame = audioFrame.map { it.toFloat() / Short.MAX_VALUE }.toFloatArray()

        // Debugging: Log the float audio frame
        android.util.Log.d("OfflineListening", "Processed float audio frame: ${floatAudioFrame.joinToString(", ")}")

        val requiredSize = 19200
        return if (floatAudioFrame.size > requiredSize) {
            floatAudioFrame.sliceArray(0 until requiredSize)
        } else {
            FloatArray(requiredSize).apply {
                floatAudioFrame.copyInto(this)
            }
        }
    }

    private fun applyWindowing(frame: ShortArray): ShortArray {
        val windowedFrame = ShortArray(frame.size)
        for (i in frame.indices) {
            // Applying Hamming window
            windowedFrame[i] = (frame[i] * (0.54 - 0.46 * cos(2 * PI * i / (frame.size - 1)))).toInt().toShort()  // Fixing toInt() and then toShort()
        }

        // Debugging: Log windowed frame
        android.util.Log.d("OfflineListening", "Windowed frame: ${windowedFrame.joinToString(", ")}")

        return windowedFrame
    }


    private fun classifyWakeWord(inputTensor: FloatArray): Float {
        val expectedSize = 19200
        if (inputTensor.size != expectedSize) {
            throw IllegalArgumentException("Input tensor size mismatch. Expected $expectedSize, but got ${inputTensor.size}")
        }

        val inputBuffer = ByteBuffer.allocateDirect(expectedSize * 4)
            .order(ByteOrder.nativeOrder())
        inputBuffer.asFloatBuffer().put(inputTensor)

        val outputSize = interpreter?.getOutputTensor(0)?.numElements() ?: 1
        val outputBuffer = ByteBuffer.allocateDirect(outputSize * 4).order(ByteOrder.nativeOrder())

        interpreter?.run(inputBuffer, outputBuffer)
        outputBuffer.rewind()

        return if (outputBuffer.remaining() >= 4) {
            outputBuffer.float
        } else {
            throw IllegalStateException("Output buffer is smaller than expected!")
        }
    }

    private fun stopListening() {
        isListening = false
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
    }
}
