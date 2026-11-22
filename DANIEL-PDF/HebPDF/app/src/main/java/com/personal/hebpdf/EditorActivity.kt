package com.personal.hebpdf

import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class EditorActivity : AppCompatActivity() {

    private lateinit var file: File
    private var renderer: PdfRenderer? = null
    private var pageIndex = 0
    private val pages = HashMap<Int, MutableList<Annot>>()
    private val redo = HashMap<Int, ArrayDeque<Annot>>()
    private var dirty = false

    private lateinit var view: AnnotationView
    private lateinit var pageLabel: TextView
    private val toolButtons = HashMap<Tool, Button>()

    private val colors = intArrayOf(Color.BLUE, Color.BLACK, Color.RED, Color.rgb(0, 150, 0), Color.rgb(255, 140, 0), Color.MAGENTA, Color.YELLOW)
    private val colorNames = arrayOf("כחול", "שחור", "אדום", "ירוק", "כתום", "סגול", "צהוב")
    private var lastFont: String? = null
    private var lastSize = 14

    private val saveAs = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { out ->
        out ?: return@registerForActivityResult
        lifecycleScope.launch {
            val r = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openOutputStream(out)!!.use { PdfSaver.save(this@EditorActivity, file, pages, it) } }
            }
            r.onSuccess { dirty = false; toast("נשמר ✔") }.onFailure { toast("השמירה נכשלה: ${it.message}") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent.data ?: return finish()
        file = File(cacheDir, "current.pdf")
        runCatching { contentResolver.openInputStream(uri)!!.use { i -> file.outputStream().use { i.copyTo(it) } } }
            .onFailure { toast("לא ניתן לפתוח את הקובץ"); return finish() }

        buildUi()
        runCatching { renderer = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }
            .onFailure { toast("הקובץ פגום או מוגן בסיסמה"); return finish() }
        lastFont = Fonts.list(this).firstOrNull { it.startsWith("Heebo") } ?: Fonts.list(this).firstOrNull()
        selectTool(Tool.PEN)
        showPage(0)
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun b(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label; isAllCaps = false; setOnClickListener { onClick() }; bar.addView(this)
        }
        toolButtons[Tool.VIEW] = b("✋ צפייה") { selectTool(Tool.VIEW) }
        toolButtons[Tool.PEN] = b("✏️ עט") { selectTool(Tool.PEN) }
        toolButtons[Tool.HIGHLIGHTER] = b("🖍 מרקר") { selectTool(Tool.HIGHLIGHTER) }
        toolButtons[Tool.TEXT] = b("T טקסט") { selectTool(Tool.TEXT) }
        toolButtons[Tool.ERASER] = b("🧽 מחק") { selectTool(Tool.ERASER) }
        b("🎨 צבע") { pickColor() }
        b("〰 עובי") { pickWidth() }
        b("↶") { undo() }
        b("↷") { redoAction() }
        b("💾 שמור") { saveAs.launch(intent.data?.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".pdf")?.plus("_ערוך.pdf") ?: "מסמך_ערוך.pdf") }
        root.addView(HorizontalScrollView(this).apply { addView(bar) })

        view = AnnotationView(this).apply {
            onTextTap = { x, y -> askText(x, y) }
            onChanged = { dirty = true; redo[pageIndex]?.clear() }
        }
        root.addView(view, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        nav.addView(Button(this).apply { text = "הקודם"; setOnClickListener { showPage(pageIndex - 1) } })
        pageLabel = TextView(this).apply { setPadding(32, 0, 32, 0); textSize = 16f; setOnClickListener { jumpToPage() } }
        nav.addView(pageLabel)
        nav.addView(Button(this).apply { text = "הבא"; setOnClickListener { showPage(pageIndex + 1) } })
        root.addView(nav)
        setContentView(root)
    }

    private fun selectTool(t: Tool) {
        view.tool = t
        toolButtons.forEach { (k, btn) -> btn.alpha = if (k == t) 1f else 0.55f }
    }

    private fun showPage(i: Int) {
        val r = renderer ?: return
        if (i < 0 || i >= r.pageCount) return
        pageIndex = i
        pageLabel.text = "עמוד ${i + 1} / ${r.pageCount}"
        view.annots = pages.getOrPut(i) { mutableListOf() }
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                synchronized(r) {
                    r.openPage(i).use { p ->
                        val w = 1800
                        val h = (w * p.height / p.width.toFloat()).toInt()
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(Color.WHITE)
                            p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
            if (pageIndex == i) view.bitmap = bmp
        }
    }

    private fun jumpToPage() {
        val et = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "מספר עמוד" }
        AlertDialog.Builder(this).setTitle("מעבר לעמוד").setView(et)
            .setPositiveButton("עבור") { _, _ -> et.text.toString().toIntOrNull()?.let { showPage(it - 1) } }
            .setNegativeButton("ביטול", null).show()
    }

    private fun pickColor() {
        AlertDialog.Builder(this).setTitle("בחר צבע")
            .setItems(colorNames) { _, w -> view.color = colors[w] }.show()
    }

    private fun pickWidth() {
        val names = arrayOf("דק", "בינוני", "עבה", "עבה מאוד")
        val vals = floatArrayOf(0.002f, 0.004f, 0.008f, 0.014f)
        AlertDialog.Builder(this).setTitle("עובי קו").setItems(names) { _, w -> view.penWidthNorm = vals[w] }.show()
    }

    private fun undo() {
        val l = pages[pageIndex] ?: return
        if (l.isEmpty()) return
        redo.getOrPut(pageIndex) { ArrayDeque() }.addLast(l.removeAt(l.lastIndex))
        dirty = true; view.invalidate()
    }

    private fun redoAction() {
        val a = redo[pageIndex]?.removeLastOrNull() ?: return
        pages.getOrPut(pageIndex) { mutableListOf() }.add(a)
        view.invalidate()
    }

    private fun askText(x: Float, y: Float) {
        val fonts = Fonts.list(this)
        if (fonts.isEmpty()) { toast("לא נמצאו גופנים בתיקיית assets/fonts"); return }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val et = EditText(this).apply {
            hint = "הקלד טקסט..."; minLines = 2
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        val spinner = Spinner(this).apply {
            adapter = object : ArrayAdapter<String>(this@EditorActivity, android.R.layout.simple_spinner_dropdown_item, fonts.map(Fonts::displayName)) {
                override fun getView(p: Int, c: View?, parent: android.view.ViewGroup) =
                    (super.getView(p, c, parent) as TextView).apply { typeface = Fonts.typeface(context, fonts[p]); text = "${Fonts.displayName(fonts[p])} — שָׁלוֹם" }
                override fun getDropDownView(p: Int, c: View?, parent: android.view.ViewGroup) =
                    (super.getDropDownView(p, c, parent) as TextView).apply { typeface = Fonts.typeface(context, fonts[p]); text = "${Fonts.displayName(fonts[p])} — אבגד שָׁלוֹם"; textSize = 20f }
            }
            setSelection(fonts.indexOf(lastFont).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { et.typeface = Fonts.typeface(context, fonts[pos]) }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        val sizeLabel = TextView(this).apply { text = "גודל: $lastSize" }
        val seek = SeekBar(this).apply {
            max = 60; progress = lastSize
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, u: Boolean) { sizeLabel.text = "גודל: ${v.coerceAtLeast(6)}" }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        box.addView(et); box.addView(spinner); box.addView(sizeLabel); box.addView(seek)
        AlertDialog.Builder(this).setTitle("הוספת טקסט").setView(box)
            .setPositiveButton("הוסף") { _, _ ->
                val t = et.text.toString().trimEnd()
                if (t.isEmpty()) return@setPositiveButton
                lastFont = fonts[spinner.selectedItemPosition]; lastSize = seek.progress.coerceAtLeast(6)
                // גודל בנקודות ביחס לרוחב A4 (595pt)
                pages.getOrPut(pageIndex) { mutableListOf() }
                    .add(TextAnnot(x, y, t, view.color, lastSize / 595f, lastFont!!))
                dirty = true; view.invalidate()
            }
            .setNegativeButton("ביטול", null).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!dirty) { @Suppress("DEPRECATION") super.onBackPressed(); return }
        AlertDialog.Builder(this).setMessage("יש שינויים שלא נשמרו. לצאת בלי לשמור?")
            .setPositiveButton("צא") { _, _ -> finish() }
            .setNegativeButton("הישאר", null).show()
    }

    override fun onDestroy() { renderer?.close(); super.onDestroy() }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
