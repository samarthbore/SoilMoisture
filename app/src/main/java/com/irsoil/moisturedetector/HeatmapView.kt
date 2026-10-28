package com.irsoil.moisturedetector

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.roundToInt

/**
 * HeatmapView
 *
 * Custom View that renders moisture heatmaps in two modes:
 *   1. Grid mode  — 10×10 colored cells with % labels
 *   2. Pixel mode — full-resolution IR-style thermal image with touch-to-inspect
 *
 * In pixel mode, touching the heatmap shows a floating tooltip with
 * moisture %, temperature, and status at the touched point.
 */
class HeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Mode ──
    enum class Mode { GRID, PIXEL }
    private var currentMode: Mode = Mode.PIXEL

    // ── Grid mode data ──
    private var gridCells: List<SoilMoistureProcessor.GridCell> = emptyList()
    private var gridRows: Int = 0
    private var gridCols: Int = 0

    // ── Pixel mode data ──
    private var heatmapBitmap: Bitmap? = null
    private var moistureData: FloatArray? = null
    private var dataWidth: Int = 0
    private var dataHeight: Int = 0

    // ── Background image for overlay ──
    private var backgroundBitmap: Bitmap? = null
    private var overlayMode: Boolean = false

    // ── Touch tooltip state ──
    private var touchX: Float = -1f
    private var touchY: Float = -1f
    private var touchMoisture: Float = -1f
    private var touchTemp: Float = -1f
    private var showTooltip: Boolean = false

    // ── Paints ──
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val shadowTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 0, 0, 0)
        textAlign = Paint.Align.CENTER
    }
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        alpha = 160
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // ── Tooltip paints ──
    private val tooltipBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 15, 23, 42)
        style = Paint.Style.FILL
    }
    private val tooltipBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 100, 200, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val tooltipTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 38f
        isFakeBoldText = true
    }
    private val tooltipSubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 180, 200, 220)
        textAlign = Paint.Align.CENTER
        textSize = 28f
    }
    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val crosshairGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 100, 200, 255)
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val cellRect = RectF()
    private val srcRect = Rect()
    private val dstRect = Rect()
    private val tooltipRect = RectF()
    private val tooltipPath = Path()

    // ── Public API ──

    fun setMode(mode: Mode) {
        currentMode = mode
        showTooltip = false
        invalidate()
    }

    fun getMode(): Mode = currentMode

    fun setGridData(result: SoilMoistureProcessor.AnalysisResult) {
        gridCells = result.gridCells
        gridRows = result.gridRows
        gridCols = result.gridCols
        requestLayout()
        invalidate()
    }

    fun setPixelData(result: SoilMoistureProcessor.PixelAnalysisResult) {
        heatmapBitmap = result.heatmapBitmap
        moistureData = result.moistureData
        dataWidth = result.width
        dataHeight = result.height
        requestLayout()
        invalidate()
    }

    fun setBackgroundImage(bitmap: Bitmap?) {
        backgroundBitmap = bitmap
        invalidate()
    }

    fun setOverlayMode(enabled: Boolean) {
        overlayMode = enabled
        invalidate()
    }

    fun isOverlayMode(): Boolean = overlayMode

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, w)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (currentMode != Mode.PIXEL || moistureData == null) return super.onTouchEvent(event)

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touchX = event.x
                touchY = event.y
                updateTooltipData()
                showTooltip = true
                invalidate()
                // Request parent not to intercept (so scrollview doesn't steal the touch)
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // Keep tooltip visible for a moment after release
                showTooltip = true
                parent?.requestDisallowInterceptTouchEvent(false)
                invalidate()
                // Hide after 2 seconds
                postDelayed({
                    showTooltip = false
                    invalidate()
                }, 2000)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateTooltipData() {
        val data = moistureData ?: return
        if (dataWidth == 0 || dataHeight == 0) return

        // Map touch coordinates to data coordinates
        val px = ((touchX / width.toFloat()) * dataWidth).toInt().coerceIn(0, dataWidth - 1)
        val py = ((touchY / height.toFloat()) * dataHeight).toInt().coerceIn(0, dataHeight - 1)

        touchMoisture = data[py * dataWidth + px]
        touchTemp = (25f + ((100f - touchMoisture) / 100f) * 15f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        when (currentMode) {
            Mode.GRID -> drawGridMode(canvas)
            Mode.PIXEL -> drawPixelMode(canvas)
        }
    }

    // ─────────────────────────────────────────────
    // PIXEL MODE
    // ─────────────────────────────────────────────

    private fun drawPixelMode(canvas: Canvas) {
        // Draw faint background if overlay
        if (overlayMode && backgroundBitmap != null) {
            val bmp = backgroundBitmap!!
            srcRect.set(0, 0, bmp.width, bmp.height)
            dstRect.set(0, 0, width, height)
            canvas.drawBitmap(bmp, srcRect, dstRect, imagePaint)
        }

        val hbmp = heatmapBitmap ?: return
        srcRect.set(0, 0, hbmp.width, hbmp.height)
        dstRect.set(0, 0, width, height)

        if (overlayMode) {
            bitmapPaint.alpha = 160
        } else {
            bitmapPaint.alpha = 255
        }
        canvas.drawBitmap(hbmp, srcRect, dstRect, bitmapPaint)

        // Draw tooltip if active
        if (showTooltip && touchMoisture >= 0) {
            drawCrosshair(canvas)
            drawTooltip(canvas)
        }
    }

    private fun drawCrosshair(canvas: Canvas) {
        val cx = touchX
        val cy = touchY
        val r = 24f

        // Glow ring
        canvas.drawCircle(cx, cy, r + 4, crosshairGlowPaint)
        // Crosshair circle
        canvas.drawCircle(cx, cy, r, crosshairPaint)
        // Cross lines
        canvas.drawLine(cx - r - 8, cy, cx - r + 6, cy, crosshairPaint)
        canvas.drawLine(cx + r - 6, cy, cx + r + 8, cy, crosshairPaint)
        canvas.drawLine(cx, cy - r - 8, cx, cy - r + 6, crosshairPaint)
        canvas.drawLine(cx, cy + r - 6, cx, cy + r + 8, crosshairPaint)
    }

    private fun drawTooltip(canvas: Canvas) {
        val moistureInt = touchMoisture.roundToInt()
        val tempStr = String.format("%.1f°C", touchTemp)
        val statusStr = when {
            moistureInt < 30 -> "DRY"
            moistureInt < 70 -> "MODERATE"
            else -> "WET"
        }
        val statusColor = when {
            moistureInt < 30 -> Color.rgb(255, 76, 76)
            moistureInt < 70 -> Color.rgb(255, 184, 0)
            else -> Color.rgb(0, 223, 129)
        }

        val tooltipW = 240f
        val tooltipH = 120f

        // Position tooltip relative to touch point
        var tx = touchX - tooltipW / 2
        var ty = touchY - tooltipH - 50f

        // Keep tooltip within bounds
        if (tx < 12f) tx = 12f
        if (tx + tooltipW > width - 12f) tx = width - 12f - tooltipW
        if (ty < 12f) ty = touchY + 50f // flip below if too high

        tooltipRect.set(tx, ty, tx + tooltipW, ty + tooltipH)

        // Draw rounded background
        canvas.drawRoundRect(tooltipRect, 20f, 20f, tooltipBgPaint)
        canvas.drawRoundRect(tooltipRect, 20f, 20f, tooltipBorderPaint)

        // Main moisture text
        tooltipTextPaint.color = statusColor
        tooltipTextPaint.textSize = 40f
        canvas.drawText("${moistureInt}%", tx + tooltipW / 2, ty + 44f, tooltipTextPaint)

        // Status label
        tooltipSubPaint.textSize = 24f
        tooltipSubPaint.color = statusColor
        canvas.drawText(statusStr, tx + tooltipW / 2, ty + 72f, tooltipSubPaint)

        // Temperature
        tooltipSubPaint.color = Color.argb(180, 180, 200, 220)
        tooltipSubPaint.textSize = 22f
        canvas.drawText("Temp: $tempStr", tx + tooltipW / 2, ty + 102f, tooltipSubPaint)
    }

    // ─────────────────────────────────────────────
    // GRID MODE
    // ─────────────────────────────────────────────

    private fun drawGridMode(canvas: Canvas) {
        if (gridCells.isEmpty() || gridRows == 0 || gridCols == 0) return

        val viewWidth = width.toFloat()
        val viewHeight = height.toFloat()
        val cellWidth = viewWidth / gridCols
        val cellHeight = viewHeight / gridRows

        // Draw faint background if overlay
        if (overlayMode && backgroundBitmap != null) {
            val bmp = backgroundBitmap!!
            srcRect.set(0, 0, bmp.width, bmp.height)
            dstRect.set(0, 0, width, height)
            canvas.drawBitmap(bmp, srcRect, dstRect, imagePaint)
        }

        val fontSize = (minOf(cellWidth, cellHeight) * 0.28f).coerceIn(10f, 28f)
        textPaint.textSize = fontSize
        shadowTextPaint.textSize = fontSize

        val cellAlpha = if (overlayMode) 140 else 255

        for (cell in gridCells) {
            val left = cell.col * cellWidth
            val top = cell.row * cellHeight
            val right = left + cellWidth
            val bottom = top + cellHeight

            cellRect.set(left, top, right, bottom)
            cellRect.inset(2f, 2f)

            val baseColor = getGridMoistureColor(cell.moisturePercent)
            cellPaint.color = Color.argb(
                cellAlpha,
                Color.red(baseColor),
                Color.green(baseColor),
                Color.blue(baseColor)
            )
            canvas.drawRoundRect(cellRect, 12f, 12f, cellPaint)
            canvas.drawRoundRect(cellRect, 12f, 12f, borderPaint)

            // Percentage text
            val text = "${cell.moisturePercent.roundToInt()}%"
            val textX = left + cellWidth / 2
            val textY = top + cellHeight / 2 + fontSize / 3

            shadowTextPaint.color = Color.argb(200, 0, 0, 0)
            canvas.drawText(text, textX + 1f, textY + 1f, shadowTextPaint)
            canvas.drawText(text, textX, textY, textPaint)
        }
    }

    // ─────────────────────────────────────────────
    // Grid Mode Color (Red → Yellow → Green)
    // ─────────────────────────────────────────────

    private fun getGridMoistureColor(moisture: Double): Int {
        return when {
            moisture <= 30.0 -> {
                val ratio = (moisture / 30.0).toFloat()
                lerpColor(Color.rgb(211, 47, 47), Color.rgb(255, 152, 0), ratio)
            }
            moisture <= 70.0 -> {
                val ratio = ((moisture - 30.0) / 40.0).toFloat()
                lerpColor(Color.rgb(255, 152, 0), Color.rgb(102, 187, 106), ratio)
            }
            else -> {
                val ratio = ((moisture - 70.0) / 30.0).toFloat()
                lerpColor(Color.rgb(102, 187, 106), Color.rgb(46, 125, 50), ratio)
            }
        }
    }

    private fun lerpColor(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt().coerceIn(0, 255)
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt().coerceIn(0, 255)
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, bl)
    }
}
