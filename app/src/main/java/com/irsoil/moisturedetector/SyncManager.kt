package com.irsoil.moisturedetector

import android.graphics.Bitmap
import android.util.Base64
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * SyncManager — FREE TIER VERSION
 * 
 * Bypasses Firebase Storage (which requires billing/credit card) by:
 * 1. Compressing bitmaps to high-efficiency JPEGs.
 * 2. Converting JPEGs to Base64 strings.
 * 3. Storing these strings directly in Firestore documents.
 */
object SyncManager {
    private const val TAG = "SyncManager"
    private val db = FirebaseFirestore.getInstance()

    data class ReadingData(
        val moisture: Double,
        val avgTemp: Double,
        val minTemp: Double,
        val maxTemp: Double,
        val mode: String,
        val calibrated: Boolean,
        val correctionStrength: Double,
        val latitude: Double?,
        val longitude: Double?,
        val rawBitmap: Bitmap?,
        val correctedBitmap: Bitmap?,
        val gridBitmap: Bitmap?,
        val pixelBitmap: Bitmap?
    )

    fun uploadReading(data: ReadingData, onComplete: (Boolean) -> Unit) {
        val readingId = UUID.randomUUID().toString()
        val timestamp = System.currentTimeMillis()
        val isoTimestamp = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date(timestamp))

        // Convert bitmaps to Base64 strings
        val rawBase64 = data.rawBitmap?.let { bitmapToBase64(it) }
        val correctedBase64 = data.correctedBitmap?.let { bitmapToBase64(it) }
        val gridBase64 = data.gridBitmap?.let { bitmapToBase64(it) }
        val pixelBase64 = data.pixelBitmap?.let { bitmapToBase64(it) }

        val docData = hashMapOf<String, Any?>(
            "id" to readingId,
            "timestamp" to isoTimestamp,
            "timestamp_ms" to timestamp,
            "moisture" to data.moisture,
            "avgTemp" to data.avgTemp,
            "minTemp" to data.minTemp,
            "maxTemp" to data.maxTemp,
            "mode" to data.mode,
            "calibrated" to data.calibrated,
            "correctionStrength" to data.correctionStrength,
            "latitude" to data.latitude,
            "longitude" to data.longitude,
            "rawImage" to rawBase64,
            "correctedImage" to correctedBase64,
            "gridImage" to gridBase64,
            "pixelImage" to pixelBase64
        )

        Log.d(TAG, "Uploading reading $readingId to Firestore (Base64 mode)")

        db.collection("readings").document(readingId)
            .set(docData)
            .addOnSuccessListener {
                Log.d(TAG, "Reading successfully synced!")
                onComplete(true)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Sync failed: ${e.message}", e)
                onComplete(false)
            }
    }

    /**
     * Helper: Compress bitmap and encode as Base64 string.
     * Uses quality 40 to stay well under Firestore's 1MB document limit.
     */
    private fun bitmapToBase64(bitmap: Bitmap): String {
        val baos = ByteArrayOutputStream()
        // Use JPEG for high compression
        // 40% quality ensures the resulting document is small (~50-80KB per image)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 40, baos)
        val bytes = baos.toByteArray()
        // Format as Data URL so the web can read it directly
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
        return "data:image/jpeg;base64,$base64"
    }
}
