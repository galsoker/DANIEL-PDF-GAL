package com.personal.hebpdf

import android.content.Context
import android.graphics.Color
import android.icu.text.Bidi
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType0Font
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import java.io.File
import java.io.OutputStream

object PdfSaver {

    /** PDF שומר טקסט לפי סדר חזותי - ממירים את סדר ההקלדה לסדר תצוגה (עברית + אנגלית + מספרים) */
    private fun visual(s: String): String =
        Bidi(s, Bidi.DIRECTION_DEFAULT_RIGHT_TO_LEFT).writeReordered(Bidi.DO_MIRRORING or Bidi.KEEP_BASE_COMBINING)

    fun save(ctx: Context, source: File, pages: Map<Int, List<Annot>>, out: OutputStream) {
        PDDocument.load(source).use { doc ->
            val fonts = HashMap<String, PDType0Font>()
            for ((idx, annots) in pages) {
                if (annots.isEmpty() || idx >= doc.numberOfPages) continue
                val page = doc.getPage(idx)
                val box = page.cropBox
                val w = box.width; val h = box.height
                fun px(x: Float) = box.lowerLeftX + x * w
                fun py(y: Float) = box.upperRightY - y * h

                PDPageContentStream(doc, page, PDPageContentStream.AppendMode.APPEND, true, true).use { cs ->
                    for (a in annots) {
                        cs.saveGraphicsState()
                        when (a) {
                            is Stroke -> {
                                if (a.highlighter) cs.setGraphicsStateParameters(PDExtendedGraphicsState().apply { strokingAlphaConstant = 0.35f })
                                cs.setStrokingColor(Color.red(a.color) / 255f, Color.green(a.color) / 255f, Color.blue(a.color) / 255f)
                                cs.setLineWidth(a.widthNorm * w)
                                cs.setLineCapStyle(1); cs.setLineJoinStyle(1)
                                cs.moveTo(px(a.points[0].x), py(a.points[0].y))
                                if (a.points.size == 1) cs.lineTo(px(a.points[0].x) + 0.1f, py(a.points[0].y))
                                for (i in 1 until a.points.size) cs.lineTo(px(a.points[i].x), py(a.points[i].y))
                                cs.stroke()
                            }
                            is TextAnnot -> {
                                val font = fonts.getOrPut(a.fontFile) {
                                    ctx.assets.open("fonts/${a.fontFile}").use { PDType0Font.load(doc, it, true) } // הטמעה חלקית של הגופן
                                }
                                val size = a.sizeNorm * w
                                cs.setNonStrokingColor(Color.red(a.color) / 255f, Color.green(a.color) / 255f, Color.blue(a.color) / 255f)
                                var y = py(a.y)
                                for (line in a.text.split('\n')) {
                                    if (line.isNotBlank()) {
                                        val v = visual(line)
                                        val tw = font.getStringWidth(v) / 1000f * size
                                        cs.beginText(); cs.setFont(font, size)
                                        cs.newLineAtOffset(px(a.x) - tw, y) // יישור לימין
                                        cs.showText(v); cs.endText()
                                    }
                                    y -= size * 1.25f
                                }
                            }
                        }
                        cs.restoreGraphicsState()
                    }
                }
            }
            doc.save(out)
        }
    }
}
