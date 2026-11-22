package com.personal.hebpdf

import android.graphics.PointF

/** כל הקואורדינטות מנורמלות (0..1) ביחס לרוחב/גובה העמוד, ועוביים/גדלים ביחס לרוחב העמוד */
sealed class Annot

data class Stroke(
    val points: MutableList<PointF>,
    val color: Int,
    val widthNorm: Float,
    val highlighter: Boolean
) : Annot()

/** x = הקצה הימני של הטקסט (עברית), y = קו הבסיס של השורה הראשונה */
data class TextAnnot(
    val x: Float,
    val y: Float,
    val text: String,
    val color: Int,
    val sizeNorm: Float,
    val fontFile: String
) : Annot()
