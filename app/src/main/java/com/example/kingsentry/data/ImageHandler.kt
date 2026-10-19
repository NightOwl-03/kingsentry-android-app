package com.example.kingsentry.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import com.example.kingsentry.data.DetectionResult

/**
 * Requirement 5: Handling missing physical image files gracefully.
 */
object ImageHandler {
    private const val TAG = "ImageHandler"

    /**
     * Saves a bitmap to the app's internal storage, optionally drawing bounding boxes.
     */
    fun saveBitmap(context: Context, bitmap: Bitmap, detections: List<DetectionResult> = emptyList()): String? {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)
        val paint = Paint().apply {
            color = android.graphics.Color.RED
            style = Paint.Style.STROKE
            strokeWidth = 8f
        }

        // Draw detection boxes on the saved physical file
        detections.forEach { detection ->
            val scaleX = mutableBitmap.width / 640f
            val scaleY = mutableBitmap.height / 640f
            canvas.drawRect(
                detection.boundingBox.left * scaleX,
                detection.boundingBox.top * scaleY,
                detection.boundingBox.right * scaleX,
                detection.boundingBox.bottom * scaleY,
                paint
            )
        }

        val filename = "scan_${System.currentTimeMillis()}.jpg"
        val file = File(context.filesDir, filename)
        return try {
            val out = FileOutputStream(file)
            mutableBitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.flush()
            out.close()
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error saving bitmap: ${e.message}")
            null
        }
    }

    /**
     * Loads a bitmap from the given absolute path.
     */
    fun loadBitmap(path: String?): Bitmap? {
        if (path.isNullOrBlank()) return null
        return try {
            val file = File(path)
            if (file.exists()) {
                BitmapFactory.decodeFile(path)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error loading bitmap: ${e.message}")
            null
        }
    }

    /**
     * Checks if the image at the given path exists.
     * @param path The absolute path to the image file.
     * @return The File object if it exists, otherwise null.
     */
    fun verifyAndGetImageFile(path: String?): File? {
        if (path.isNullOrBlank()) return null
        
        val file = File(path)
        return if (file.exists()) {
            file
        } else {
            // Log the missing file for debugging and maintenance
            Log.w(TAG, "Image file not found at path: $path. It may have been deleted from the gallery.")
            null
        }
    }

    /**
     * Deletes an image file from storage.
     */
    fun deleteImageFile(path: String?) {
        if (path.isNullOrBlank()) return
        try {
            val file = File(path)
            if (file.exists()) {
                file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting image file: ${e.message}")
        }
    }

    /**
     * Sample usage for a ViewModel or UI component.
     * Demonstrates how to handle missing files gracefully.
     */
    fun processScanForUi(scan: ScanHistoryDetail): ScanUiState {
        val imageFile = verifyAndGetImageFile(scan.imagePath)
        
        return if (imageFile != null) {
            ScanUiState.Success(
                diseaseName = scan.diseaseName,
                imageFile = imageFile
            )
        } else {
            // Graceful fallback: Use a placeholder or gray box state
            ScanUiState.MissingImage(
                diseaseName = scan.diseaseName,
                message = "Original image was deleted or moved"
            )
        }
    }
}

/**
 * Simple UI state representation for fallback handling.
 */
sealed class ScanUiState {
    data class Success(val diseaseName: String, val imageFile: File) : ScanUiState()
    data class MissingImage(val diseaseName: String, val message: String) : ScanUiState()
}
