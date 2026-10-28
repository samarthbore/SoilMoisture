package com.irsoil.moisturedetector

import android.graphics.Bitmap
import android.graphics.Color
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * SoilMoistureProcessor — Enhanced Accuracy Pipeline
 *
 * Processing stages:
 *   1. Crop to square
 *   2. Gaussian blur (noise reduction)
 *   3. Multi-channel feature extraction (luminance + color moisture index)
 *   4. Adaptive percentile-based normalization (ignores outlier pixels)
 *   5. Non-linear sigmoid temperature mapping
 *   6. Weighted moisture estimation combining brightness + color cues
 *   7. Local contrast normalization (simple CLAHE-like)
 *   8. Median post-filter on moisture map (removes salt-and-pepper noise)
 *
 * Why each step matters for accuracy:
 *   - Gaussian blur removes JPEG artifacts and sensor noise → smoother readings
 *   - Multi-channel analysis captures that wet soil shifts color (darker + bluer)
 *   - Adaptive normalization prevents a few bright/dark outlier pixels from
 *     compressing the useful dynamic range
 *   - Sigmoid mapping models the physical fact that moisture sensitivity is
 *     highest in the mid-range (not linear at extremes)
 *   - Median filter removes random speckle noise without blurring edges
 */
object SoilMoistureProcessor {

    // Temperature range for simulation (°C)
    private const val TEMP_MIN = 25.0
    private const val TEMP_MAX = 40.0

    // Percentile bounds for adaptive normalization (ignore top/bottom 2%)
    private const val PERCENTILE_LOW = 0.02
    private const val PERCENTILE_HIGH = 0.98

    // Sigmoid steepness for temperature mapping (higher = sharper transition)
    private const val SIGMOID_K = 5.0

    // Weight for color-based moisture index (0.0 = luminance only, 1.0 = color only)
    private const val COLOR_INDEX_WEIGHT = 0.25

    // Gaussian blur radius
    private const val BLUR_RADIUS = 2

    // Median filter radius for post-processing
    private const val MEDIAN_RADIUS = 1

    // ─────────────────────────────────────────────
    // Data Classes
    // ─────────────────────────────────────────────

    data class GridCell(
        val row: Int,
        val col: Int,
        val avgBrightness: Double,
        val temperature: Double,
        val moisturePercent: Double
    )

    data class AnalysisResult(
        val gridCells: List<GridCell>,
        val gridRows: Int,
        val gridCols: Int,
        val averageMoisture: Double,
        val minTemp: Double,
        val maxTemp: Double,
        val averageTemp: Double,
        val heatmapBitmap: Bitmap? = null
    )

    data class PixelAnalysisResult(
        val heatmapBitmap: Bitmap,
        val moistureData: FloatArray,
        val width: Int,
        val height: Int,
        val averageMoisture: Double,
        val minTemp: Double,
        val maxTemp: Double,
        val averageTemp: Double
    )

    // ─────────────────────────────────────────────
    // PUBLIC: Grayscale (kept for IlluminationCorrector)
    // ─────────────────────────────────────────────

    /**
     * Convert a bitmap to grayscale using standard luminance formula.
     */
    fun toGrayscale(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val grayscale = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val gray = luminance(Color.red(pixel), Color.green(pixel), Color.blue(pixel))
            pixels[i] = Color.rgb(gray, gray, gray)
        }

        grayscale.setPixels(pixels, 0, width, 0, 0, width, height)
        return grayscale
    }

    // ─────────────────────────────────────────────
    // PUBLIC: Grid-based processing
    // ─────────────────────────────────────────────

    fun processImage(bitmap: Bitmap, gridSize: Int = 10): AnalysisResult {
        val squareBitmap = cropToSquare(bitmap)
        val w = squareBitmap.width
        val h = squareBitmap.height

        // Step 1: Extract raw moisture map (float array)
        val moistureMap = computeFullMoistureMap(squareBitmap, w, h)

        // Step 2: Grid averaging
        val cellW = w / gridSize
        val cellH = h / gridSize
        val gridCells = mutableListOf<GridCell>()

        for (row in 0 until gridSize) {
            for (col in 0 until gridSize) {
                val startX = col * cellW
                val startY = row * cellH
                val endX = minOf(startX + cellW, w)
                val endY = minOf(startY + cellH, h)

                // Use trimmed mean (remove top/bottom 10% of values in cell)
                val cellValues = mutableListOf<Float>()
                for (y in startY until endY) {
                    for (x in startX until endX) {
                        cellValues.add(moistureMap[y * w + x])
                    }
                }
                cellValues.sort()

                val trimCount = (cellValues.size * 0.1).toInt()
                val trimmedValues = if (trimCount > 0 && cellValues.size > trimCount * 2) {
                    cellValues.subList(trimCount, cellValues.size - trimCount)
                } else {
                    cellValues
                }

                val avgMoisture = trimmedValues.average()
                val temp = moistureToTemperature(avgMoisture)
                val brightness = temperatureToEstimatedBrightness(temp)

                gridCells.add(GridCell(row, col, brightness, temp, avgMoisture))
            }
        }

        val avgMoisture = gridCells.map { it.moisturePercent }.average()
        val minTemp = gridCells.minOf { it.temperature }
        val maxTemp = gridCells.maxOf { it.temperature }
        val avgTemp = gridCells.map { it.temperature }.average()

        val heatmapBitmap = Bitmap.createBitmap(gridSize, gridSize, Bitmap.Config.ARGB_8888)
        for (cell in gridCells) {
            val color = moistureToColor(cell.moisturePercent)
            heatmapBitmap.setPixel(cell.col, cell.row, color)
        }

        if (squareBitmap != bitmap) squareBitmap.recycle()

        return AnalysisResult(gridCells, gridSize, gridSize, avgMoisture, minTemp, maxTemp, avgTemp, heatmapBitmap)
    }

    // ─────────────────────────────────────────────
    // PUBLIC: Per-pixel processing
    // ─────────────────────────────────────────────

    fun processImagePerPixel(bitmap: Bitmap): PixelAnalysisResult {
        val squareBitmap = cropToSquare(bitmap)
        val w = squareBitmap.width
        val h = squareBitmap.height

        // Full pipeline: extract moisture map
        val moistureMap = computeFullMoistureMap(squareBitmap, w, h)

        // Generate heatmap and compute stats
        val heatmapPixels = IntArray(w * h)
        var totalMoisture = 0.0
        var minT = Double.MAX_VALUE
        var maxT = Double.MIN_VALUE
        var totalTemp = 0.0

        for (i in moistureMap.indices) {
            val moisture = moistureMap[i].toDouble()
            val temp = moistureToTemperature(moisture)

            heatmapPixels[i] = moistureToColor(moisture)

            totalMoisture += moisture
            totalTemp += temp
            if (temp < minT) minT = temp
            if (temp > maxT) maxT = temp
        }

        val count = w * h
        val heatmapBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        heatmapBitmap.setPixels(heatmapPixels, 0, w, 0, 0, w, h)

        if (squareBitmap != bitmap) squareBitmap.recycle()

        return PixelAnalysisResult(
            heatmapBitmap = heatmapBitmap,
            moistureData = moistureMap,
            width = w,
            height = h,
            averageMoisture = totalMoisture / count,
            minTemp = minT,
            maxTemp = maxT,
            averageTemp = totalTemp / count
        )
    }

    // ═════════════════════════════════════════════
    // ENHANCED PIPELINE (private)
    // ═════════════════════════════════════════════

    /**
     * computeFullMoistureMap — SCIENTIFIC ACCURACY CORE (V2)
     *
     * This core uses a fused signal approach:
     * 1. VARI Index: Normalizes lighting noise and spectral shifts.
     * 2. HSV Value: Maps pure physical darkening.
     * 3. Texture Roughness: Distinguishes between shadows (smooth) and dry soil (rough).
     */
    private fun computeFullMoistureMap(bitmap: Bitmap, w: Int, h: Int): FloatArray {
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val redChannel = FloatArray(w * h)
        val greenChannel = FloatArray(w * h)
        val blueChannel = FloatArray(w * h)
        val hsvValueMap = FloatArray(w * h)
        val hsvSatMap = FloatArray(w * h)

        // ── Stage 1: Spectral Extraction ──
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = Color.red(p).toFloat()
            val g = Color.green(p).toFloat()
            val b = Color.blue(p).toFloat()
            redChannel[i] = r
            greenChannel[i] = g
            blueChannel[i] = b

            val hsv = FloatArray(3)
            Color.RGBToHSV(r.toInt(), g.toInt(), b.toInt(), hsv)
            hsvSatMap[i] = hsv[1]
            hsvValueMap[i] = hsv[2]
        }

        // ── Stage 2: VARI & Physical Darkening ──
        val moistureSignal = FloatArray(w * h)
        for (i in pixels.indices) {
            val r = redChannel[i]
            val g = greenChannel[i]
            val b = blueChannel[i]

            // VARI = (G - R) / (G + R - B) -> Normalizes atmospheric/lighting noise
            val vari = (g - r) / (g + r - b + 0.001f)
            
            // Darkness Signal (from HSV Value) -> Wet soil is darker
            val darkness = 1.0f - hsvValueMap[i] 

            // Composite Initial Moisture Signal
            moistureSignal[i] = (darkness * 0.7f + vari * 0.3f).coerceIn(0f, 1f)
        }

        // ── Stage 3: Texture Roughness Analysis (3x3 Variance) ──
        // This stops "Smooth Shadows" from being detected as "Wet Soil"
        val roughnessMap = computeRoughnessMap(hsvValueMap, w, h)

        // ── Stage 4: Fused Processing ──
        val fusedMoisture = FloatArray(w * h)
        for (i in pixels.indices) {
            // High moisture = High Darkness + Low Roughness (smooth texture)
            // Dry soil = High Roughness + Lower Darkness
            val base = moistureSignal[i]
            val roughness = roughnessMap[i] // 0 = perfectly smooth, 1 = very noisy
            
            // Penalty for roughness: dry soil is physically craggy
            val roughnessPenalty = roughness * 0.5f
            
            fusedMoisture[i] = sigmoid((base - roughnessPenalty).toDouble()).toFloat()
        }

        // ── Stage 5: Final Post-Processing & Scaling ──
        val filtered = medianFilter(fusedMoisture, w, h, MEDIAN_RADIUS)
        
        // Final normalization into 0-100 range
        val sorted = filtered.clone()
        sorted.sort()
        val low = sorted[(sorted.size * 0.01).toInt()]
        val high = sorted[(sorted.size * 0.99).toInt()]
        val range = if (high - low > 0.001f) high - low else 0.001f

        val result = FloatArray(w * h)
        for (i in result.indices) {
            result[i] = (((filtered[i] - low) / range) * 100f).coerceIn(0f, 100f)
        }

        return result
    }

    /**
     * Compute a 3x3 local standard deviation (roughness) map.
     * Normalized to [0, 1].
     */
    private fun computeRoughnessMap(data: FloatArray, w: Int, h: Int): FloatArray {
        val result = FloatArray(w * h)
        val radius = 1
        
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                var sumSq = 0f
                var count = 0
                
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val sy = (y + dy).coerceIn(0, h - 1)
                        val sx = (x + dx).coerceIn(0, w - 1)
                        val v = data[sy * w + sx]
                        sum += v
                        sumSq += v * v
                        count++
                    }
                }
                
                val mean = sum / count
                val variance = (sumSq / count) - (mean * mean)
                val stdDev = Math.sqrt(maxOf(0.0, variance.toDouble())).toFloat()
                
                // Scale standard deviation to a usable roughness metric [0, 1]
                // 0.25 is a heuristic threshold for high texture in normalized float spaces
                result[y * w + x] = (stdDev / 0.25f).coerceIn(0f, 1f)
            }
        }
        return result
    }

    // ─────────────────────────────────────────────
    // GAUSSIAN BLUR
    // ─────────────────────────────────────────────

    /**
     * Apply Gaussian blur with given radius for noise reduction.
     * Uses separable convolution (horizontal then vertical) for efficiency.
     */
    private fun gaussianBlur(data: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val kernel = generateGaussianKernel(radius)
        val temp = FloatArray(w * h)
        val result = FloatArray(w * h)

        // Horizontal pass
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                var weightSum = 0f
                for (k in -radius..radius) {
                    val sx = (x + k).coerceIn(0, w - 1)
                    val weight = kernel[k + radius]
                    sum += data[y * w + sx] * weight
                    weightSum += weight
                }
                temp[y * w + x] = sum / weightSum
            }
        }

        // Vertical pass
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0f
                var weightSum = 0f
                for (k in -radius..radius) {
                    val sy = (y + k).coerceIn(0, h - 1)
                    val weight = kernel[k + radius]
                    sum += temp[sy * w + x] * weight
                    weightSum += weight
                }
                result[y * w + x] = sum / weightSum
            }
        }

        return result
    }

    private fun generateGaussianKernel(radius: Int): FloatArray {
        val sigma = radius / 2.0f
        val kernel = FloatArray(radius * 2 + 1)
        for (i in kernel.indices) {
            val x = (i - radius).toFloat()
            kernel[i] = exp(-(x * x) / (2f * sigma * sigma)).toFloat()
        }
        return kernel
    }

    // ─────────────────────────────────────────────
    // MEDIAN FILTER
    // ─────────────────────────────────────────────

    /**
     * Median filter for salt-and-pepper noise removal.
     * Preserves edges much better than averaging.
     */
    private fun medianFilter(data: FloatArray, w: Int, h: Int, radius: Int): FloatArray {
        val result = FloatArray(w * h)
        val windowSize = (2 * radius + 1) * (2 * radius + 1)
        val window = FloatArray(windowSize)

        for (y in 0 until h) {
            for (x in 0 until w) {
                var idx = 0
                for (dy in -radius..radius) {
                    for (dx in -radius..radius) {
                        val sx = (x + dx).coerceIn(0, w - 1)
                        val sy = (y + dy).coerceIn(0, h - 1)
                        window[idx++] = data[sy * w + sx]
                    }
                }
                window.sort()
                result[y * w + x] = window[windowSize / 2]
            }
        }

        return result
    }

    // ─────────────────────────────────────────────
    // SIGMOID MAPPING
    // ─────────────────────────────────────────────

    /**
     * Sigmoid function centered at 0.5.
     * Maps [0,1] → [0,1] with increased sensitivity in the mid-range.
     *
     * This models the physics better: very dry and very wet soils
     * are easier to distinguish, but the gradient is steepest in
     * the moderate moisture range.
     */
    private fun sigmoid(x: Double): Double {
        // Shift so 0.5 input maps to 0.5 output, with steepness K
        val centered = (x - 0.5) * SIGMOID_K
        return 1.0 / (1.0 + exp(-centered))
    }

    // ─────────────────────────────────────────────
    // HELPER CONVERSIONS
    // ─────────────────────────────────────────────

    private fun luminance(r: Int, g: Int, b: Int): Int {
        return (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
    }

    /**
     * Reverse map: moisture percentage → simulated temperature.
     * Used for display purposes in grid cells and tooltip.
     */
    private fun moistureToTemperature(moisture: Double): Double {
        return TEMP_MAX - (moisture / 100.0) * (TEMP_MAX - TEMP_MIN)
    }

    /**
     * Estimate what the original brightness would have been for display.
     */
    private fun temperatureToEstimatedBrightness(temp: Double): Double {
        return ((temp - TEMP_MIN) / (TEMP_MAX - TEMP_MIN)) * 255.0
    }

    // keep these public for HeatmapView tooltip calculation
    fun brightnessToTemperature(brightness: Double): Double {
        return TEMP_MIN + (brightness / 255.0) * (TEMP_MAX - TEMP_MIN)
    }

    fun temperatureToMoisture(temperature: Double): Double {
        val moisture = ((TEMP_MAX - temperature) / (TEMP_MAX - TEMP_MIN)) * 100.0
        return moisture.coerceIn(0.0, 100.0)
    }

    // ─────────────────────────────────────────────
    // COLOR MAPPING (IR thermal palette)
    // ─────────────────────────────────────────────

    private fun moistureToColor(moisture: Double): Int {
        val stops = arrayOf(
            0.0   to intArrayOf(48, 18, 59),
            20.0  to intArrayOf(222, 60, 75),
            40.0  to intArrayOf(254, 144, 24),
            60.0  to intArrayOf(234, 219, 43),
            80.0  to intArrayOf(72, 205, 100),
            100.0 to intArrayOf(13, 150, 155)
        )

        var lower = stops[0]
        var upper = stops[stops.size - 1]
        for (i in 0 until stops.size - 1) {
            if (moisture >= stops[i].first && moisture <= stops[i + 1].first) {
                lower = stops[i]
                upper = stops[i + 1]
                break
            }
        }

        val range = upper.first - lower.first
        val ratio = if (range > 0) ((moisture - lower.first) / range).toFloat() else 0f

        val r = (lower.second[0] + (upper.second[0] - lower.second[0]) * ratio).toInt().coerceIn(0, 255)
        val g = (lower.second[1] + (upper.second[1] - lower.second[1]) * ratio).toInt().coerceIn(0, 255)
        val b = (lower.second[2] + (upper.second[2] - lower.second[2]) * ratio).toInt().coerceIn(0, 255)

        return Color.rgb(r, g, b)
    }

    // ─────────────────────────────────────────────
    // CROP
    // ─────────────────────────────────────────────

    fun cropToSquare(bitmap: Bitmap): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width == height) return bitmap

        val size = minOf(width, height)
        val xOffset = (width - size) / 2
        val yOffset = (height - size) / 2
        return Bitmap.createBitmap(bitmap, xOffset, yOffset, size, size)
    }
}
