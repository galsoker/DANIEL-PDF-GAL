package com.personal.hebpdf

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as M

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.attrColor(attr: Int) = MaterialColors.getColor(this, attr, 0)

object C {
    val primary = M.attr.colorPrimary
    val onPrimary = M.attr.colorOnPrimary
    val primaryContainer = M.attr.colorPrimaryContainer
    val onPrimaryContainer = M.attr.colorOnPrimaryContainer
    val surface = M.attr.colorSurface
    val surfaceContainer = M.attr.colorSurfaceContainer
    val surfaceContainerHigh = M.attr.colorSurfaceContainerHigh
    val surfaceContainerLow = M.attr.colorSurfaceContainerLow
    val onSurface = M.attr.colorOnSurface
    val onSurfaceVariant = M.attr.colorOnSurfaceVariant
    val outline = M.attr.colorOutlineVariant
}

fun pill(color: Int, radius: Float) = GradientDrawable().apply { setColor(color); cornerRadius = radius }

fun Context.ripple(borderless: Boolean = false) = TypedValue().let {
    theme.resolveAttribute(if (borderless) android.R.attr.selectableItemBackgroundBorderless else android.R.attr.selectableItemBackground, it, true)
    ContextCompat.getDrawable(this, it.resourceId)
}

/** Android 15 מצייר מתחת לשורת הסטטוס - מוסיפים ריווח לפי גודל שורות המערכת */
fun View.applySystemBarPadding() {
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val b = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(b.left, b.top, b.right, b.bottom); insets
    }
}
