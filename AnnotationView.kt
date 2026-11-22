package com.personal.hebpdf

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

enum class Tool { VIEW, PEN, HIGHLIGHTER, ERASER, TEXT }

/**
 * מציג עמוד PDF עם שכבת הערות.
 * זום: צביטה בשתי אצבעות (בכל כלי). הזזה: שתי אצבעות, או אצבע אחת במצב "צפייה". הקשה כפולה במצב צפייה = זום מהיר.
 */
class AnnotationView(ctx: Context) : View(ctx) {

    var bitmap: Bitmap? = null
        set(v) { field = v; needsFit = true; invalidate() }
    var annots: MutableList<Annot> = mutableListOf()
        set(v) { field = v; invalidate() }
    var tool = Tool.PEN
    var color = Color.BLUE
    var penWidthNorm = 0.004f
    var onTextTap: ((Float, Float) -> Unit)? = null
    var onChanged: (() -> Unit)? = null
    var onZoomChanged: ((Float) -> Unit)? = null

    private val dest = RectF()
    private var fitWidth = 1f
    private var needsFit = true
    private var current: Stroke? = null
    private var multi = false
    private var lastX = 0f; private var lastY = 0f
    private var downX = 0f; private var downY = 0f
    private var lastFx = 0f; private var lastFy = 0f
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val maxZoom = 6f

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(40, 0, 0, 0) }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }

    val zoom get() = dest.width() / fitWidth

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(d: ScaleGestureDetector): Boolean {
            lastFx = d.focusX; lastFy = d.focusY; cancelStroke(); return true
        }
        override fun onScale(d: ScaleGestureDetector): Boolean {
            dest.offset(d.focusX - lastFx, d.focusY - lastFy)
            zoomBy(d.scaleFactor, d.focusX, d.focusY)
            lastFx = d.focusX; lastFy = d.focusY
            return true
        }
    })

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (tool != Tool.VIEW) return false
            if (zoom > 1.3f) resetZoom() else zoomBy(2.5f, e.x, e.y)
            return true
        }
    })

    fun resetZoom() { needsFit = true; invalidate(); onZoomChanged?.invoke(1f) }

    private fun fit() {
        val b = bitmap ?: return
        val pad = context.dp(12).toFloat()
        val s = minOf((width - 2 * pad) / b.width, (height - 2 * pad) / b.height)
        val w = b.width * s; val h = b.height * s
        dest.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
        fitWidth = w
    }

    private fun zoomBy(f: Float, fx: Float, fy: Float) {
        val cur = zoom
        val nf = (cur * f).coerceIn(1f, maxZoom) / cur
        dest.set(fx - (fx - dest.left) * nf, fy - (fy - dest.top) * nf, fx + (dest.right - fx) * nf, fy + (dest.bottom - fy) * nf)
        clamp(); onZoomChanged?.invoke(zoom)
    }

    private fun clamp() {
        val w = width.toFloat(); val h = height.toFloat()
        if (dest.width() <= w) dest.offsetTo((w - dest.width()) / 2, dest.top)
        else { if (dest.left > 0) dest.offset(-dest.left, 0f); if (dest.right < w) dest.offset(w - dest.right, 0f) }
        if (dest.height() <= h) dest.offsetTo(dest.left, (h - dest.height()) / 2)
        else { if (dest.top > 0) dest.offset(0f, -dest.top); if (dest.bottom < h) dest.offset(0f, h - dest.bottom) }
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { needsFit = true }

    override fun onDraw(c: Canvas) {
        val b = bitmap ?: return
        if (needsFit) { fit(); needsFit = false }
        val r = context.dp(3).toFloat()
        c.drawRoundRect(dest.left - 1, dest.top + r - 1, dest.right + 1, dest.bottom + r, r, r, shadowPaint)
        c.drawBitmap(b, null, dest, bmpPaint)
        annots.forEach { draw(c, it) }
        current?.let { draw(c, it) }
    }

    private fun sx(x: Float) = dest.left + x * dest.width()
    private fun sy(y: Float) = dest.top + y * dest.height()

    private fun draw(c: Canvas, a: Annot) {
        when (a) {
            is Stroke -> {
                if (a.points.isEmpty()) return
                strokePaint.color = a.color
                strokePaint.alpha = if (a.highlighter) 90 else 255
                strokePaint.strokeWidth = a.widthNorm * dest.width()
                val p = Path().apply {
                    moveTo(sx(a.points[0].x), sy(a.points[0].y))
                    for (i in 1 until a.points.size) lineTo(sx(a.points[i].x), sy(a.points[i].y))
                    if (a.points.size == 1) lineTo(sx(a.points[0].x) + 0.1f, sy(a.points[0].y))
                }
                c.drawPath(p, strokePaint)
            }
            is TextAnnot -> {
                textPaint.color = a.color
                textPaint.typeface = Fonts.typeface(context, a.fontFile)
                textPaint.textSize = a.sizeNorm * dest.width()
                var y = sy(a.y)
                a.text.split('\n').forEach { line -> c.drawText(line, sx(a.x), y, textPaint); y += textPaint.textSize * 1.25f }
            }
        }
    }

    private fun cancelStroke() { if (current != null) { current = null; invalidate() } }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null) return false
        scaleDetector.onTouchEvent(e)
        gestures.onTouchEvent(e)

        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN) { multi = true; cancelStroke(); return true }
        if (multi) {
            if (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL) multi = false
            return true
        }

        val nx = (e.x - dest.left) / dest.width()
        val ny = (e.y - dest.top) / dest.height()
        when (tool) {
            Tool.VIEW -> when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lastX = e.x; lastY = e.y }
                MotionEvent.ACTION_MOVE -> { dest.offset(e.x - lastX, e.y - lastY); clamp(); lastX = e.x; lastY = e.y }
            }
            Tool.PEN, Tool.HIGHLIGHTER -> when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val hl = tool == Tool.HIGHLIGHTER
                    val c = if (hl && color == Color.BLUE) Color.YELLOW else color
                    current = Stroke(mutableListOf(PointF(nx, ny)), c, if (hl) penWidthNorm * 5 else penWidthNorm, hl)
                }
                MotionEvent.ACTION_MOVE -> {
                    for (h in 0 until e.historySize) current?.points?.add(PointF(
                        (e.getHistoricalX(h) - dest.left) / dest.width(), (e.getHistoricalY(h) - dest.top) / dest.height()))
                    current?.points?.add(PointF(nx, ny)); invalidate()
                }
                MotionEvent.ACTION_UP -> { current?.let { annots.add(it); onChanged?.invoke() }; current = null; invalidate() }
                MotionEvent.ACTION_CANCEL -> cancelStroke()
            }
            Tool.ERASER -> if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
                val hit = annots.lastOrNull { hits(it, nx, ny) }
                if (hit != null) { annots.remove(hit); onChanged?.invoke(); invalidate() }
            }
            Tool.TEXT -> when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y }
                MotionEvent.ACTION_UP -> if (hypot(e.x - downX, e.y - downY) < slop && nx in 0f..1f && ny in 0f..1f) onTextTap?.invoke(nx, ny)
            }
        }
        return true
    }

    private fun hits(a: Annot, x: Float, y: Float): Boolean {
        val tol = 0.02f / zoom
        val ar = dest.height() / dest.width()
        return when (a) {
            is Stroke -> a.points.any { hypot(it.x - x, (it.y - y) * ar) < tol + a.widthNorm }
            is TextAnnot -> {
                textPaint.typeface = Fonts.typeface(context, a.fontFile)
                textPaint.textSize = a.sizeNorm * dest.width()
                val lines = a.text.split('\n')
                val w = lines.maxOf { textPaint.measureText(it) } / dest.width()
                val h = a.sizeNorm / ar * (lines.size * 1.25f)
                x in (a.x - w - tol)..(a.x + tol) && y in (a.y - a.sizeNorm / ar - tol)..(a.y + h)
            }
        }
    }
}
