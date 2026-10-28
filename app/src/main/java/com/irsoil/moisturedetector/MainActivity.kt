package com.irsoil.moisturedetector

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.drawable.PaintDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.RectShape
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import com.google.android.gms.location.LocationServices
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.card.MaterialCardView
import kotlin.math.roundToInt

/**
 * MainActivity
 *
 * Handles:
 * - Illumination calibration (reference sheet → current sheet → LUT)
 * - Soil image upload with optional illumination correction
 * - Dual-mode moisture analysis (per-pixel IR + 10×10 grid)
 * - Interactive heatmap with touch-to-inspect
 */
class MainActivity : AppCompatActivity() {

    // ── UI References ──
    private lateinit var imagePreview: ImageView
    private lateinit var placeholderLayout: View
    private lateinit var btnUpload: MaterialButton
    private lateinit var btnOverlay: MaterialButton
    private lateinit var toggleMode: MaterialButtonToggleGroup
    private lateinit var btnPixelMode: MaterialButton
    private lateinit var btnGridMode: MaterialButton
    private lateinit var txtTouchHint: TextView
    private lateinit var moistureCard: LinearLayout
    private lateinit var heatmapCard: LinearLayout
    private lateinit var legendCard: LinearLayout
    private lateinit var txtMoisturePercent: TextView
    private lateinit var txtMoistureStatus: TextView
    private lateinit var txtTempRange: TextView
    private lateinit var heatmapView: HeatmapView
    private lateinit var gridLegend: LinearLayout
    private lateinit var pixelLegend: LinearLayout
    private lateinit var gradientBar: View

    // ── Calibration UI ──
    private lateinit var btnRefSheet: MaterialButton
    private lateinit var btnCurSheet: MaterialButton
    private lateinit var btnCalibrate: MaterialButton
    private lateinit var txtRefStatus: TextView
    private lateinit var txtCurStatus: TextView
    private lateinit var txtCalibStatus: TextView
    private lateinit var txtCalibDiag: TextView
    private lateinit var sheetPreviewSection: LinearLayout
    private lateinit var sheetSlider: ComparisonSliderView

    // ── Corrected Soil Preview UI ──
    private lateinit var correctedSoilCard: LinearLayout
    private lateinit var soilSlider: ComparisonSliderView

    // ── State ──
    private val gridSize = 10
    private var currentBitmap: Bitmap? = null
    private var currentGridResult: SoilMoistureProcessor.AnalysisResult? = null
    private var currentPixelResult: SoilMoistureProcessor.PixelAnalysisResult? = null

    // ── Calibration State ──
    private var referenceSheetBitmap: Bitmap? = null
    private var currentSheetBitmap: Bitmap? = null
    private var calibrationResult: IlluminationCorrector.CalibrationResult? = null

    // ── Image Pickers ──
    // Each picker is a separate launcher to distinguish which image was selected
    private lateinit var soilPickerLauncher: ActivityResultLauncher<String>
    private lateinit var refSheetPickerLauncher: ActivityResultLauncher<String>
    private lateinit var curSheetPickerLauncher: ActivityResultLauncher<String>
    private lateinit var locationPermissionLauncher: ActivityResultLauncher<Array<String>>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        registerPickers()

        // Request Location permission for real-time weather mapping
        locationPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))

        initViews()
        setupGradientBar()
        setupListeners()
    }

    /**
     * Register all three image picker launchers separately so we know
     * which picker returned each image.
     */
    private fun registerPickers() {
        soilPickerLauncher = registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let { handleSoilImageSelected(it) }
        }

        refSheetPickerLauncher = registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let { handleRefSheetSelected(it) }
        }

        curSheetPickerLauncher = registerForActivityResult(
            ActivityResultContracts.GetContent()
        ) { uri: Uri? ->
            uri?.let { handleCurSheetSelected(it) }
        }

        locationPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val granted = permissions.entries.all { it.value }
            if (granted) {
                // Permission granted
            } else {
                Toast.makeText(this, "Location permission denied. Map features will be disabled.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun initViews() {
        imagePreview = findViewById(R.id.imagePreview)
        placeholderLayout = findViewById(R.id.placeholderLayout)
        btnUpload = findViewById(R.id.btnUpload)
        btnOverlay = findViewById(R.id.btnOverlay)
        toggleMode = findViewById(R.id.toggleMode)
        btnPixelMode = findViewById(R.id.btnPixelMode)
        btnGridMode = findViewById(R.id.btnGridMode)
        txtTouchHint = findViewById(R.id.txtTouchHint)
        moistureCard = findViewById(R.id.moistureCard)
        heatmapCard = findViewById(R.id.heatmapCard)
        legendCard = findViewById(R.id.legendCard)
        txtMoisturePercent = findViewById(R.id.txtMoisturePercent)
        txtMoistureStatus = findViewById(R.id.txtMoistureStatus)
        txtTempRange = findViewById(R.id.txtTempRange)
        heatmapView = findViewById(R.id.heatmapView)
        gridLegend = findViewById(R.id.gridLegend)
        pixelLegend = findViewById(R.id.pixelLegend)
        gradientBar = findViewById(R.id.gradientBar)

        // Calibration views
        btnRefSheet = findViewById(R.id.btnRefSheet)
        btnCurSheet = findViewById(R.id.btnCurSheet)
        btnCalibrate = findViewById(R.id.btnCalibrate)
        txtRefStatus = findViewById(R.id.txtRefStatus)
        txtCurStatus = findViewById(R.id.txtCurStatus)
        sheetPreviewSection = findViewById(R.id.sheetPreviewSection)
        sheetSlider = findViewById(R.id.sheetSlider)

        // Corrected soil views
        correctedSoilCard = findViewById(R.id.correctedSoilCard)
        soilSlider = findViewById(R.id.soilSlider)
        txtCalibStatus = findViewById(R.id.txtCalibStatus)
        txtCalibDiag = findViewById(R.id.txtCalibDiag)
    }

    private fun setupGradientBar() {
        val colors = intArrayOf(
            Color.rgb(48, 18, 59),
            Color.rgb(222, 60, 75),
            Color.rgb(254, 144, 24),
            Color.rgb(234, 219, 43),
            Color.rgb(72, 205, 100),
            Color.rgb(13, 150, 155)
        )

        val drawable = PaintDrawable()
        drawable.shape = RectShape()
        drawable.shaderFactory = object : ShapeDrawable.ShaderFactory() {
            override fun resize(width: Int, height: Int): Shader {
                return LinearGradient(
                    0f, 0f, width.toFloat(), 0f,
                    colors, null, Shader.TileMode.CLAMP
                )
            }
        }
        drawable.setCornerRadius(12f)
        gradientBar.background = drawable
    }

    private fun setupListeners() {
        // ── Soil image upload ──
        btnUpload.setOnClickListener {
            soilPickerLauncher.launch("image/*")
        }

        // ── Overlay toggle ──
        btnOverlay.setOnClickListener {
            val isOverlay = !heatmapView.isOverlayMode()
            heatmapView.setOverlayMode(isOverlay)

            if (isOverlay) {
                btnOverlay.text = "Plain Heatmap"
                val color = getColor(R.color.primary_light)
                btnOverlay.setTextColor(color)
                btnOverlay.strokeColor = ColorStateList.valueOf(color)
                btnOverlay.iconTint = ColorStateList.valueOf(color)
            } else {
                btnOverlay.text = "Overlay on Image"
                val color = getColor(R.color.accent)
                btnOverlay.setTextColor(color)
                btnOverlay.strokeColor = ColorStateList.valueOf(color)
                btnOverlay.iconTint = ColorStateList.valueOf(color)
            }
        }

        // ── Mode toggle: Pixel vs Grid ──
        toggleMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.btnPixelMode -> switchToPixelMode()
                R.id.btnGridMode -> switchToGridMode()
            }
        }

        // ── Calibration buttons ──
        btnRefSheet.setOnClickListener {
            refSheetPickerLauncher.launch("image/*")
        }

        btnCurSheet.setOnClickListener {
            curSheetPickerLauncher.launch("image/*")
        }

        btnCalibrate.setOnClickListener {
            performCalibration()
        }
    }

    // ─────────────────────────────────────────────
    // CALIBRATION FLOW
    // ─────────────────────────────────────────────

    private fun handleRefSheetSelected(uri: Uri) {
        try {
            val bitmap = loadBitmapFromUri(uri)
            if (bitmap == null) {
                Toast.makeText(this, "Failed to load reference sheet", Toast.LENGTH_SHORT).show()
                return
            }
            referenceSheetBitmap = scaleBitmap(bitmap, 500)
            txtRefStatus.text = "  ✅"
            updateCalibrateButton()
            Toast.makeText(this, "Reference sheet loaded", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleCurSheetSelected(uri: Uri) {
        try {
            val bitmap = loadBitmapFromUri(uri)
            if (bitmap == null) {
                Toast.makeText(this, "Failed to load current sheet", Toast.LENGTH_SHORT).show()
                return
            }
            currentSheetBitmap = scaleBitmap(bitmap, 500)
            txtCurStatus.text = "  ✅"
            updateCalibrateButton()
            Toast.makeText(this, "Current sheet loaded", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateCalibrateButton() {
        val ready = referenceSheetBitmap != null && currentSheetBitmap != null
        btnCalibrate.isEnabled = ready
        if (ready) {
            btnCalibrate.backgroundTintList = ColorStateList.valueOf(getColor(R.color.accent))
        } else {
            btnCalibrate.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#6B7280"))
        }
    }

    private fun performCalibration() {
        val refBitmap = referenceSheetBitmap
        val curBitmap = currentSheetBitmap

        if (refBitmap == null || curBitmap == null) {
            Toast.makeText(this, "Please upload both sheet images first", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            // Run histogram matching calibration
            val result = IlluminationCorrector.calibrate(refBitmap, curBitmap)
            calibrationResult = result

            // Update UI to show calibrated state
            txtCalibStatus.text = "✅ Calibrated"
            txtCalibStatus.setTextColor(getColor(R.color.wet_green))

            // Show diagnostics
            val strengthPercent = (result.correctionStrength * 100).roundToInt()
            txtCalibDiag.text = String.format(
                "Ref Brightness: %.0f  |  Current: %.0f  |  Correction: %d%%",
                result.refMeanBrightness,
                result.currentMeanBrightness,
                strengthPercent
            )
            txtCalibDiag.visibility = View.VISIBLE

            // Show corrected sheet slider preview
            val correctedSheet = IlluminationCorrector.applyMapping(curBitmap, result.lookupTable)
            sheetSlider.setImages(curBitmap, correctedSheet)
            sheetPreviewSection.visibility = View.VISIBLE

            // Change calibrate button to show success
            btnCalibrate.text = "✓ Recalibrate"
            // Brutalist: No tint change (keep default brutal button)

            Toast.makeText(this, "Calibration complete! Upload a soil image to apply correction.", Toast.LENGTH_LONG).show()

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Calibration failed: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ─────────────────────────────────────────────
    // SOIL IMAGE PROCESSING
    // ─────────────────────────────────────────────

    private fun handleSoilImageSelected(uri: Uri) {
        try {
            val bitmap = loadBitmapFromUri(uri)

            if (bitmap == null) {
                Toast.makeText(this, "Failed to load image", Toast.LENGTH_SHORT).show()
                return
            }

            imagePreview.setImageBitmap(bitmap)
            placeholderLayout.visibility = View.GONE

            val scaledBitmap = scaleBitmap(bitmap, 500)

            // ── Apply illumination correction if calibrated ──
            val calib = calibrationResult
            val processingBitmap: Bitmap
            if (calib != null) {
                // Correct the soil image using the calibration LUT
                processingBitmap = IlluminationCorrector.correctSoilImage(scaledBitmap, calib)

                // Show soil comparison slider
                soilSlider.setImages(scaledBitmap, processingBitmap)
                correctedSoilCard.visibility = View.VISIBLE

                Toast.makeText(this, "Illumination correction applied ✓", Toast.LENGTH_SHORT).show()
            } else {
                processingBitmap = scaledBitmap
                correctedSoilCard.visibility = View.GONE
            }

            currentBitmap = processingBitmap
            heatmapView.setBackgroundImage(scaledBitmap) // Original for overlay

            // Process both modes with the (possibly corrected) bitmap
            currentGridResult = SoilMoistureProcessor.processImage(processingBitmap, gridSize)
            currentPixelResult = SoilMoistureProcessor.processImagePerPixel(processingBitmap)

            // Display based on current mode
            val isPixel = toggleMode.checkedButtonId == R.id.btnPixelMode
            if (isPixel) {
                heatmapView.setMode(HeatmapView.Mode.PIXEL)
                heatmapView.setPixelData(currentPixelResult!!)
                txtTouchHint.visibility = View.VISIBLE
                gridLegend.visibility = View.GONE
                pixelLegend.visibility = View.VISIBLE
            } else {
                heatmapView.setMode(HeatmapView.Mode.GRID)
                heatmapView.setGridData(currentGridResult!!)
                txtTouchHint.visibility = View.GONE
                gridLegend.visibility = View.VISIBLE
                pixelLegend.visibility = View.GONE
            }

            val result = if (isPixel) currentPixelResult!! else null
            val gridResult = if (!isPixel) currentGridResult!! else null

            val avgMoisture = result?.averageMoisture ?: gridResult!!.averageMoisture
            val minT = result?.minTemp ?: gridResult!!.minTemp
            val maxT = result?.maxTemp ?: gridResult!!.maxTemp
            val avgT = result?.averageTemp ?: gridResult!!.averageTemp

            displayResults(avgMoisture, minT, maxT, avgT)

            // Trigger Firebase Sync with Location data
            triggerCloudSync(
                rawBitmap = scaledBitmap,
                correctedBitmap = if (calib != null) processingBitmap else null,
                gridBitmap = currentGridResult?.heatmapBitmap,
                pixelBitmap = currentPixelResult?.heatmapBitmap,
                avgMoisture = avgMoisture,
                avgTemp = avgT,
                minTemp = minT,
                maxTemp = maxT,
                modeItem = if (isPixel) "pixel" else "grid"
            )

        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error processing image: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // ─────────────────────────────────────────────
    // MODE SWITCHING
    // ─────────────────────────────────────────────

    private fun switchToPixelMode() {
        heatmapView.setMode(HeatmapView.Mode.PIXEL)
        txtTouchHint.visibility = View.VISIBLE
        gridLegend.visibility = View.GONE
        pixelLegend.visibility = View.VISIBLE

        currentPixelResult?.let {
            heatmapView.setPixelData(it)
            updateStats(it.averageMoisture, it.minTemp, it.maxTemp, it.averageTemp)
        }
    }

    private fun switchToGridMode() {
        heatmapView.setMode(HeatmapView.Mode.GRID)
        txtTouchHint.visibility = View.GONE
        gridLegend.visibility = View.VISIBLE
        pixelLegend.visibility = View.GONE

        currentGridResult?.let {
            heatmapView.setGridData(it)
            updateStats(it.averageMoisture, it.minTemp, it.maxTemp, it.averageTemp)
        }
    }

    // ─────────────────────────────────────────────
    // IMAGE LOADING
    // ─────────────────────────────────────────────

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(contentResolver, uri)
                ImageDecoder.decodeBitmap(source) { decoder, _, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    decoder.isMutableRequired = true
                }
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(contentResolver, uri)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun scaleBitmap(bitmap: Bitmap, maxSize: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= maxSize && height <= maxSize) return bitmap

        val ratio = minOf(maxSize.toFloat() / width, maxSize.toFloat() / height)
        val newWidth = (width * ratio).toInt()
        val newHeight = (height * ratio).toInt()
        return Bitmap.createScaledBitmap(bitmap, newWidth, newHeight, true)
    }

    // ─────────────────────────────────────────────
    // DISPLAY RESULTS
    // ─────────────────────────────────────────────

    private fun displayResults(avgMoisture: Double, minT: Double, maxT: Double, avgT: Double) {
        val animDuration = 500L

        if (moistureCard.visibility == View.GONE) {
            moistureCard.alpha = 0f
            heatmapCard.alpha = 0f
            legendCard.alpha = 0f
            moistureCard.translationY = 50f
            heatmapCard.translationY = 50f
            legendCard.translationY = 50f

            moistureCard.visibility = View.VISIBLE
            heatmapCard.visibility = View.VISIBLE
            legendCard.visibility = View.VISIBLE

            moistureCard.animate().alpha(1f).translationY(0f).setDuration(animDuration).start()
            heatmapCard.animate().alpha(1f).translationY(0f).setDuration(animDuration).setStartDelay(150).start()
            legendCard.animate().alpha(1f).translationY(0f).setDuration(animDuration).setStartDelay(300).start()
        }

        updateStats(avgMoisture, minT, maxT, avgT)
    }

    private fun updateStats(avgMoisture: Double, minT: Double, maxT: Double, avgT: Double) {
        val avgPercent = avgMoisture.roundToInt()
        txtMoisturePercent.text = "${avgPercent}%"

        val (statusText, statusColor) = when {
            avgPercent < 30 -> "🔴 Dry Soil" to getColor(R.color.dry_red)
            avgPercent < 70 -> "🟡 Moderate Moisture" to getColor(R.color.moderate_yellow)
            else -> "🟢 Wet Soil" to getColor(R.color.wet_green)
        }
        txtMoistureStatus.text = statusText
        txtMoistureStatus.setTextColor(statusColor)

        txtTempRange.text = String.format(
            "Simulated Temp: %.1f°C – %.1f°C  (Avg: %.1f°C)",
            minT, maxT, avgT
        )
    }



    // ─────────────────────────────────────────────
    // CLOUD SYNC & LOCATION
    // ─────────────────────────────────────────────

    private fun triggerCloudSync(
        rawBitmap: Bitmap?,
        correctedBitmap: Bitmap?,
        gridBitmap: Bitmap?,
        pixelBitmap: Bitmap?,
        avgMoisture: Double,
        avgTemp: Double,
        minTemp: Double,
        maxTemp: Double,
        modeItem: String
    ) {
        val calib = calibrationResult
        val isCalibrated = calib != null
        val correctionStrength = calib?.correctionStrength ?: 0.0

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            val fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
            fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                doUpload(rawBitmap, correctedBitmap, gridBitmap, pixelBitmap, avgMoisture, avgTemp, minTemp, maxTemp, modeItem, isCalibrated, correctionStrength, location?.latitude, location?.longitude)
            }.addOnFailureListener {
                doUpload(rawBitmap, correctedBitmap, gridBitmap, pixelBitmap, avgMoisture, avgTemp, minTemp, maxTemp, modeItem, isCalibrated, correctionStrength, null, null)
            }
        } else {
            doUpload(rawBitmap, correctedBitmap, gridBitmap, pixelBitmap, avgMoisture, avgTemp, minTemp, maxTemp, modeItem, isCalibrated, correctionStrength, null, null)
        }
    }

    private fun doUpload(
        rawBmp: Bitmap?, correctedBmp: Bitmap?, gridBmp: Bitmap?, pixelBmp: Bitmap?,
        avgMoist: Double, avgT: Double, minT: Double, maxT: Double,
        mode: String, calibrated: Boolean, strength: Double,
        lat: Double?, lon: Double?
    ) {
        val data = SyncManager.ReadingData(
            avgMoist, avgT, minT, maxT, mode, calibrated,
            strength, lat, lon, rawBmp, correctedBmp, gridBmp, pixelBmp
        )
        Toast.makeText(this, "Syncing reading to cloud...", Toast.LENGTH_SHORT).show()
        SyncManager.uploadReading(data) { success ->
            if (success) {
                Toast.makeText(this, "Synced with dashboard ✓", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Cloud Sync failed", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

