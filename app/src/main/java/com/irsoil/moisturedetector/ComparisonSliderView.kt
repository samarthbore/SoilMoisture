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

/**
 * ComparisonSliderView
 *
 * A custom view that displays two images (before/after) with a draggable
 * vertical divider. Dragging the handle reveals more of either image,
 * creating a curtain-wipe comparison effect.
 *
 * Left side  = "Before" image
 * Right side = "After" (corrected) image
 */
class ComparisonSliderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var beforeBitmap: Bitmap? = null
    private var afterBitmap: Bitmap? = null

    // Slider position as fraction [0.0, 1.0] — starts at center
    private var sliderPosition: Float = 0.5f

    // Drawing helpers
    private val srcRect = Rect()
    private val dstRect = Rect()
    private val clipRect = Rect()
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    // Divider line
    private val dividerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }
    private val dividerGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
        strokeWidth = 10f
        style = Paint.Style.STROKE
    }

    // Handle (the draggable circle on the divider)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val handleBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 0, 0, 0)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val handleShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 0, 0, 0)
        style = Paint.Style.FILL
    }

    // Arrow paint inside handle
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 30, 30, 30)
        style = Paint.Style.FILL
    }

    // Label paints
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 15, 23, 42)
        style = Paint.Style.FILL
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 28f
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    private val handleRadius = 28f
    private val arrowPath = Path()
    private val labelRect = RectF()

    fun setImages(before: Bitmap?, after: Bitmap?) {
        beforeBitmap = before
        afterBitmap = after
        sliderPosition = 0.5f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        // Keep it square
        setMeasuredDimension(w, w)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                sliderPosition = (event.x / width.toFloat()).coerceIn(0.02f, 0.98f)
                invalidate()
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width
        val h = height
        if (w == 0 || h == 0) return

        val dividerX = (w * sliderPosition).toInt()

        // ── Draw AFTER image (full, as background) ──
        afterBitmap?.let { bmp ->
            srcRect.set(0, 0, bmp.width, bmp.height)
            dstRect.set(0, 0, w, h)
            canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
        }

        // ── Draw BEFORE image (clipped to left of divider) ──
        beforeBitmap?.let { bmp ->
            canvas.save()
            clipRect.set(0, 0, dividerX, h)
            canvas.clipRect(clipRect)
            srcRect.set(0, 0, bmp.width, bmp.height)
            dstRect.set(0, 0, w, h)
            canvas.drawBitmap(bmp, srcRect, dstRect, bitmapPaint)
            canvas.restore()
        }

        // ── Draw divider line ──
        val dx = dividerX.toFloat()
        canvas.drawLine(dx, 0f, dx, h.toFloat(), dividerGlowPaint)
        canvas.drawLine(dx, 0f, dx, h.toFloat(), dividerPaint)

        // ── Draw handle ──
        val cy = h / 2f

        // Shadow
        canvas.drawCircle(dx + 2f, cy + 2f, handleRadius + 2f, handleShadowPaint)
        // White circle
        canvas.drawCircle(dx, cy, handleRadius, handlePaint)
        canvas.drawCircle(dx, cy, handleRadius, handleBorderPaint)

        // Left arrow (◄)
        arrowPath.reset()
        arrowPath.moveTo(dx - 6f, cy)
        arrowPath.lineTo(dx - 16f, cy)
        arrowPath.moveTo(dx - 14f, cy - 6f)
        arrowPath.lineTo(dx - 18f, cy)
        arrowPath.lineTo(dx - 14f, cy + 6f)
        arrowPaint.style = Paint.Style.STROKE
        arrowPaint.strokeWidth = 2.5f
        arrowPaint.strokeCap = Paint.Cap.ROUND
        canvas.drawPath(arrowPath, arrowPaint)

        // Right arrow (►)
        arrowPath.reset()
        arrowPath.moveTo(dx + 6f, cy)
        arrowPath.lineTo(dx + 16f, cy)
        arrowPath.moveTo(dx + 14f, cy - 6f)
        arrowPath.lineTo(dx + 18f, cy)
        arrowPath.lineTo(dx + 14f, cy + 6f)
        canvas.drawPath(arrowPath, arrowPaint)

        // ── Draw labels ──
        drawLabel(canvas, "BEFORE", 0f + 16f, 16f, Paint.Align.LEFT)
        drawLabel(canvas, "AFTER", w.toFloat() - 16f, 16f, Paint.Align.RIGHT)
    }

    private fun drawLabel(canvas: Canvas, text: String, x: Float, y: Float, align: Paint.Align) {
        labelTextPaint.textAlign = align
        val textWidth = labelTextPaint.measureText(text)

        val padH = 16f
        val padV = 8f
        val left: Float
        val right: Float

        when (align) {
            Paint.Align.LEFT -> {
                left = x - padH
                right = x + textWidth + padH
            }
            Paint.Align.RIGHT -> {
                left = x - textWidth - padH
                right = x + padH
            }
            else -> {
                left = x - textWidth / 2 - padH
                right = x + textWidth / 2 + padH
            }
        }

        labelRect.set(left, y, right, y + labelTextPaint.textSize + padV * 2)
        canvas.drawRoundRect(labelRect, 12f, 12f, labelBgPaint)
        canvas.drawText(text, x, y + padV + labelTextPaint.textSize * 0.8f, labelTextPaint)
    }
}
