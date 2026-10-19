package com.example.kingsentry.data

import android.content.Context
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Helper class for running YOLO inference using TensorFlow Lite.
 * Migrated from ONNX Runtime for better compatibility and smaller app size.
 */
class YoloInferenceHelper(private val context: Context) : AutoCloseable {

    private var interpreter: Interpreter? = null

    companion object {
        private const val MODEL_ASSET_NAME = "best.tflite"
        private const val INPUT_SIZE = 640
        private const val CONFIDENCE_THRESHOLD = 0.60f // Stricter threshold to filter irrelevant objects
        private const val IOU_THRESHOLD = 0.45f // For Non-Maximum Suppression
        
        // Single-class model: Class 0 = Leaf Blight
        private val LABELS = listOf("Leaf Blight")
    }

    init {
        loadModel()
    }

    /**
     * Requirement 1: Initializes the Interpreter using the best.tflite asset.
     */
    private fun loadModel() {
        try {
            val assetFileDescriptor = context.assets.openFd(MODEL_ASSET_NAME)
            val inputStream = FileInputStream(assetFileDescriptor.fileDescriptor)
            val fileChannel = inputStream.channel
            val startOffset = assetFileDescriptor.startOffset
            val declaredLength = assetFileDescriptor.declaredLength
            val modelBuffer = fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
            
            val options = Interpreter.Options()
            // Using 4 threads for faster mobile inference
            options.setNumThreads(4)
            interpreter = Interpreter(modelBuffer, options)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Requirement 2: Function that takes an Android Bitmap as input.
     * Requirement 3: Preprocesses the Bitmap and runs inference asynchronously.
     */
    suspend fun detect(bitmap: Bitmap): InferenceResult? = withContext(Dispatchers.Default) {
        val tflite = interpreter ?: return@withContext null

        // 3. Preprocess: Resize and Normalize
        val resizedBitmap = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val inputBuffer = allocateByteBuffer(resizedBitmap)

        // 4. Run Inference
        // YOLO output for 1 class is typically [1, 5, 8400]
        val outputTensor = tflite.getOutputTensor(0)
        val outputShape = outputTensor.shape() 
        
        val probabilityBuffer = ByteBuffer.allocateDirect(outputShape[0] * outputShape[1] * outputShape[2] * 4)
        probabilityBuffer.order(ByteOrder.nativeOrder())

        tflite.run(inputBuffer, probabilityBuffer)
        
        // 5. Post-process: Parse raw YOLO output
        return@withContext parseOutput(probabilityBuffer, outputShape)
    }

    /**
     * Manual preprocessing: Resize and Normalize to Float32 ByteBuffer.
     */
    private fun allocateByteBuffer(bitmap: Bitmap): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(1 * 3 * INPUT_SIZE * INPUT_SIZE * 4)
        buffer.order(ByteOrder.nativeOrder())
        
        val pixels = IntArray(INPUT_SIZE * INPUT_SIZE)
        bitmap.getPixels(pixels, 0, INPUT_SIZE, 0, 0, INPUT_SIZE, INPUT_SIZE)

        // NCHW format and normalization to [0, 1]
        for (c in 0 until 3) {
            for (i in pixels.indices) {
                val pixel = pixels[i]
                val value = when (c) {
                    0 -> (pixel shr 16) and 0xFF // Red
                    1 -> (pixel shr 8) and 0xFF  // Green
                    else -> pixel and 0xFF       // Blue
                }
                buffer.putFloat(value / 255.0f)
            }
        }
        buffer.rewind()
        return buffer
    }

    /**
     * Requirement 5: Parse the raw YOLO output array and filter via NMS.
     * Fixed coordinate scaling and confidence normalization.
     */
    private fun parseOutput(buffer: ByteBuffer, shape: IntArray): InferenceResult? {
        buffer.rewind()
        
        // Output format detection [1, Features, Anchors]
        val numFeatures = shape[1] // typically 5 for 1 class
        val numAnchors = shape[2]  // typically 8400
        
        val data = FloatArray(numFeatures * numAnchors)
        buffer.asFloatBuffer()[data]

        val rawDetections = mutableListOf<DetectionResult>()

        for (i in 0 until numAnchors) {
            // Indexing for [1, 5, 8400] layout
            val cx = data[0 * numAnchors + i]
            val cy = data[1 * numAnchors + i]
            val w = data[2 * numAnchors + i]
            val h = data[3 * numAnchors + i]
            val confidence = data[4 * numAnchors + i].coerceIn(0f, 1f)

            if (confidence > CONFIDENCE_THRESHOLD) {
                // Convert center to corners and scale to 640x640 space
                val left = (cx - w / 2f).coerceIn(0f, INPUT_SIZE.toFloat())
                val top = (cy - h / 2f).coerceIn(0f, INPUT_SIZE.toFloat())
                val right = (cx + w / 2f).coerceIn(0f, INPUT_SIZE.toFloat())
                val bottom = (cy + h / 2f).coerceIn(0f, INPUT_SIZE.toFloat())

                rawDetections.add(
                    DetectionResult(
                        label = "Leaf Blight",
                        score = confidence,
                        boundingBox = RectF(left, top, right, bottom)
                    )
                )
            }
        }

        // Apply NMS to remove overlapping "grid" boxes
        val filteredDetections = applyNMS(rawDetections)

        // Determine final result based on top detection
        val topResult = filteredDetections.maxByOrNull { it.score }
        
        return if (topResult != null) {
            InferenceResult(
                detectedDisease = "Leaf Blight",
                confidenceScore = topResult.score,
                boundingBoxes = filteredDetections
            )
        } else {
            // Requirement: Handle irrelevant objects or low confidence
            InferenceResult(
                detectedDisease = "Unknown / Not a Durian Leaf",
                confidenceScore = 0.0f,
                boundingBoxes = emptyList()
            )
        }
    }

    /**
     * Non-Maximum Suppression logic to consolidate redundant boxes.
     */
    private fun applyNMS(detections: List<DetectionResult>): List<DetectionResult> {
        if (detections.isEmpty()) return emptyList()
        
        val sorted = detections.sortedByDescending { it.score }.toMutableList()
        val results = mutableListOf<DetectionResult>()
        
        while (sorted.isNotEmpty()) {
            val first = sorted.removeAt(0)
            results.add(first)
            
            val iterator = sorted.iterator()
            while (iterator.hasNext()) {
                val next = iterator.next()
                if (calculateIOU(first.boundingBox, next.boundingBox) > IOU_THRESHOLD) {
                    iterator.remove()
                }
            }
        }
        // Limit to top 5 results for a clean UI
        return results.take(5)
    }

    private fun calculateIOU(box1: RectF, box2: RectF): Float {
        val x1 = maxOf(box1.left, box2.left)
        val y1 = maxOf(box1.top, box2.top)
        val x2 = minOf(box1.right, box2.right)
        val y2 = minOf(box1.bottom, box2.bottom)
        
        val intersectionArea = maxOf(0f, x2 - x1) * maxOf(0f, y2 - y1)
        val area1 = (box1.right - box1.left) * (box1.bottom - box1.top)
        val area2 = (box2.right - box2.left) * (box2.bottom - box2.top)
        
        return intersectionArea / (area1 + area2 - intersectionArea)
    }

    override fun close() {
        interpreter?.close()
        interpreter = null
    }
}

data class InferenceResult(
    val detectedDisease: String,
    val confidenceScore: Float,
    val boundingBoxes: List<DetectionResult>
)

data class DetectionResult(
    val label: String,
    val score: Float,
    val boundingBox: RectF
)

data class RectF(val left: Float, val top: Float, val right: Float, val bottom: Float)
