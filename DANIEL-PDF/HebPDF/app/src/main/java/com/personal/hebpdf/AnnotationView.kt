package com.personal.hebpdf

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

enum class Tool { VIEW, PEN, HIGHLIGHTER, ERASER, TEXT }

class AnnotationView(ctx: Context) : View(ctx) {

    var bitmap: Bitmap? = null
        set(v) { field = v; invalidate() }
    var annots: MutableList<Annot> = mutableListOf()
        set(v) { field = v; invalidate() }
    var tool = Tool.PEN
    var color = Color.BLUE
    var penWidthNorm = 0.004f
    var onTextTap: ((Float, Float) -> Unit)? = null
    var onChanged: (() -> Unit)? = null

    private val dest = RectF()
    private var current: Stroke? = null
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }

    init { setBackgroundColor(Color.LTGRAY) }

    private fun layoutDest() {
        val b = bitmap ?: return
        val s = minOf(width / b.width.toFloat(), height / b.height.toFloat())
        val w = b.width * s; val h = b.height * s
        dest.set((width - w) / 2, (height - h) / 2, (width + w) / 2, (height + h) / 2)
    }

    override fun onDraw(c: Canvas) {
        val b = bitmap ?: return
        layoutDest()
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
                // Canvas.drawText מטפל בכיווניות ובניקוד באופן מלא
                a.text.split('\n').forEach { line -> c.drawText(line, sx(a.x), y, textPaint); y += textPaint.textSize * 1.25f }
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null || tool == Tool.VIEW) return false
        val nx = (e.x - dest.left) / dest.width()
        val ny = (e.y - dest.top) / dest.height()
              // דחיית כף יד: אם יש עט סטיילוס, מתעלמים ממגע אצבע בזמן ציור
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_PALM) return true
        when (tool) {
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
                MotionEvent.ACTION_CANCEL -> { current = null; invalidate() }
            }
            Tool.ERASER -> if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
                val hit = annots.lastOrNull { hits(it, nx, ny) }
                if (hit != null) { annots.remove(hit); onChanged?.invoke(); invalidate() }
            }
            Tool.TEXT -> if (e.actionMasked == MotionEvent.ACTION_UP) onTextTap?.invoke(nx, ny)
            Tool.VIEW -> {}
        }
        return true
    }

    private fun hits(a: Annot, x: Float, y: Float): Boolean {
        val tol = 0.02f
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
