package com.personal.hebpdf

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.pdf.PdfRenderer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.text.InputType
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
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
    private var docName = "מסמך.pdf"

    private lateinit var view: AnnotationView
    private lateinit var pageLabel: TextView
    private lateinit var zoomLabel: TextView
    private lateinit var colorIcon: ImageView
    private val toolViews = HashMap<Tool, Pair<ImageView, TextView>>()

    private val colors = intArrayOf(
        Color.rgb(21, 101, 192), Color.BLACK, Color.rgb(198, 40, 40), Color.rgb(46, 125, 50), Color.rgb(239, 108, 0),
        Color.rgb(106, 27, 154), Color.rgb(255, 214, 0), Color.rgb(0, 131, 143), Color.rgb(173, 20, 87), Color.rgb(97, 97, 97)
    )
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
        docName = intent.getStringExtra("name") ?: uri.lastPathSegment?.substringAfterLast('/') ?: docName
        file = File(cacheDir, "current.pdf")
        runCatching { contentResolver.openInputStream(uri)!!.use { i -> file.outputStream().use { i.copyTo(it) } } }
            .onFailure { toast("לא ניתן לפתוח את הקובץ"); return finish() }

        buildUi()
        runCatching { renderer = PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)) }
            .onFailure { toast("הקובץ פגום או מוגן בסיסמה"); return finish() }
        lastFont = Fonts.list(this).firstOrNull { it.startsWith("Heebo") } ?: Fonts.list(this).firstOrNull()
        selectTool(Tool.PEN)
        showPage(0)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!dirty) { finish(); return }
                MaterialAlertDialogBuilder(this@EditorActivity)
                    .setTitle("שינויים לא שמורים")
                    .setMessage("לצאת בלי לשמור את השינויים?")
                    .setPositiveButton("צא בלי לשמור") { _, _ -> finish() }
                    .setNegativeButton("הישאר", null).show()
            }
        })
    }

    // ---------- ממשק ----------

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(attrColor(C.surface)) }

        val toolbar = MaterialToolbar(this).apply {
            title = docName
            setNavigationIcon(R.drawable.ic_back)
            setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
            fun action(title: String, icon: Int, f: () -> Unit) = menu.add(title).apply {
                setIcon(icon); setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                iconTintList = ColorStateList.valueOf(attrColor(C.onSurface))
                setOnMenuItemClickListener { f(); true }
            }
            action("בטל", R.drawable.ic_undo) { undo() }
            action("בצע שוב", R.drawable.ic_redo) { redoAction() }
            action("שמור", R.drawable.ic_save) { save() }
        }
        root.addView(toolbar)

        // אזור העמוד + פקדים צפים
        val stage = FrameLayout(this)
        view = AnnotationView(this).apply {
            setBackgroundColor(attrColor(C.surfaceContainer))
            onTextTap = { x, y -> askText(x, y) }
            onChanged = { dirty = true; redo[pageIndex]?.clear() }
            onZoomChanged = { z -> zoomLabel.text = "${(z * 100).toInt()}%"; zoomLabel.visibility = if (z > 1.01f) View.VISIBLE else View.GONE }
        }
        stage.addView(view, FrameLayout.LayoutParams(-1, -1))

        val navCard = MaterialCardView(this).apply {
            radius = dp(24).toFloat(); cardElevation = dp(3).toFloat(); strokeWidth = 0
            setCardBackgroundColor(attrColor(C.surfaceContainerHigh))
        }
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, dp(4), 0) }
        nav.addView(iconBtn(R.drawable.ic_chevron_right, "עמוד קודם") { showPage(pageIndex - 1) })
        pageLabel = TextView(this).apply {
            textSize = 14f; setTextColor(attrColor(C.onSurface)); setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { jumpToPage() }
        }
        nav.addView(pageLabel)
        nav.addView(iconBtn(R.drawable.ic_chevron_left, "עמוד הבא") { showPage(pageIndex + 1) })
        zoomLabel = TextView(this).apply {
            textSize = 13f; setTextColor(attrColor(C.primary)); setPadding(dp(4), 0, dp(4), 0); visibility = View.GONE
        }
        nav.addView(zoomLabel)
        nav.addView(iconBtn(R.drawable.ic_fit, "התאם למסך") { view.resetZoom() })
        navCard.addView(nav)
        stage.addView(navCard, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(14) })
        root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f))

        // סרגל כלים תחתון
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), dp(6)) }
        fun tool(t: Tool, icon: Int, label: String) = tools.addView(toolItem(icon, label) { selectTool(t) }.also { toolViews[t] = it.tag as Pair<ImageView, TextView> })
        tool(Tool.VIEW, R.drawable.ic_hand, "הזזה")
        tool(Tool.PEN, R.drawable.ic_pen, "עט")
        tool(Tool.HIGHLIGHTER, R.drawable.ic_highlighter, "מרקר")
        tool(Tool.TEXT, R.drawable.ic_text, "טקסט")
        tool(Tool.ERASER, R.drawable.ic_eraser, "מחק")
        tools.addView(View(this).apply { setBackgroundColor(attrColor(C.outline)) }, LinearLayout.LayoutParams(dp(1), dp(36)).apply { marginStart = dp(6); marginEnd = dp(6) })
        val colorItem = toolItem(R.drawable.ic_palette, "צבע") { pickColor() }
        @Suppress("UNCHECKED_CAST")
        colorIcon = (colorItem.tag as Pair<ImageView, TextView>).first
        tools.addView(colorItem)
        tools.addView(toolItem(R.drawable.ic_weight, "עובי") { pickWidth() })
        val bar = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false; isFillViewport = true
            setBackgroundColor(attrColor(C.surfaceContainerLow)); addView(tools)
        }
        root.addView(bar)
        updateColorIcon()

        root.applySystemBarPadding()
        setContentView(root)
    }

    private fun iconBtn(icon: Int, desc: String, f: () -> Unit) = ImageButton(this).apply {
        setImageResource(icon); contentDescription = desc; background = ripple(true)
        imageTintList = ColorStateList.valueOf(attrColor(C.onSurface))
        setOnClickListener { f() }
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
    }

    private fun toolItem(icon: Int, label: String, onClick: () -> Unit): LinearLayout {
        val iv = ImageView(this).apply { setImageResource(icon); setPadding(dp(16), dp(4), dp(16), dp(4)) }
        val tv = TextView(this).apply { text = label; textSize = 12f; gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4)); background = ripple(true)
            addView(iv, LinearLayout.LayoutParams(dp(56), dp(32)))
            addView(tv)
            setOnClickListener { onClick() }
            tag = iv to tv
            styleTool(iv, tv, false)
        }
    }

    private fun styleTool(iv: ImageView, tv: TextView, on: Boolean) {
        iv.background = if (on) pill(attrColor(C.primaryContainer), dp(16).toFloat()) else null
        iv.imageTintList = ColorStateList.valueOf(attrColor(if (on) C.onPrimaryContainer else C.onSurfaceVariant))
        tv.setTextColor(attrColor(if (on) C.onSurface else C.onSurfaceVariant))
    }

    private fun updateColorIcon() {
        colorIcon.background = GradientDrawable().apply { shape = GradientDrawable.RECTANGLE; cornerRadius = dp(16).toFloat(); setColor(view.color) }
        colorIcon.imageTintList = ColorStateList.valueOf(if (view.color == Color.rgb(255, 214, 0)) Color.BLACK else Color.WHITE)
    }

    private fun selectTool(t: Tool) {
        view.tool = t
        toolViews.forEach { (k, p) -> styleTool(p.first, p.second, k == t) }
        if (t == Tool.TEXT) toast("הקש על המקום בעמוד שבו יתחיל הטקסט")
    }

    // ---------- עמודים ----------

    private fun showPage(i: Int) {
        val r = renderer ?: return
        if (i < 0 || i >= r.pageCount) return
        pageIndex = i
        pageLabel.text = "${i + 1} / ${r.pageCount}"
        view.annots = pages.getOrPut(i) { mutableListOf() }
        lifecycleScope.launch {
            val bmp = withContext(Dispatchers.Default) {
                synchronized(r) {
                    r.openPage(i).use { p ->
                        val w = 2200
                        val h = (w * p.height / p.width.toFloat()).toInt()
                        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(Color.WHITE)
                            p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        }
                    }
                }
            }
            if (pageIndex == i) { view.bitmap = bmp; zoomLabel.visibility = View.GONE }
        }
    }

    private fun jumpToPage() {
        val (til, et) = textField("מספר עמוד")
        et.inputType = InputType.TYPE_CLASS_NUMBER
        MaterialAlertDialogBuilder(this).setTitle("מעבר לעמוד").setView(wrap(til))
            .setPositiveButton("עבור") { _, _ -> et.text.toString().toIntOrNull()?.let { showPage(it - 1) } }
            .setNegativeButton("ביטול", null).show()
    }

    // ---------- דיאלוגים ----------

    private fun textField(hint: String): Pair<TextInputLayout, TextInputEditText> {
        val til = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply { this.hint = hint }
        val et = TextInputEditText(til.context)
        til.addView(et)
        return til to et
    }

    private fun wrap(v: View) = FrameLayout(this).apply { setPadding(dp(24), dp(12), dp(24), 0); addView(v) }

    private fun pickColor() {
        val grid = GridLayout(this).apply { columnCount = 5; setPadding(dp(20), dp(16), dp(20), 0) }
        lateinit var dlg: androidx.appcompat.app.AlertDialog
        colors.forEach { c ->
            grid.addView(View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL; setColor(c)
                    if (c == view.color) setStroke(dp(4), attrColor(C.primary)) else setStroke(dp(1), attrColor(C.outline))
                }
                setOnClickListener { view.color = c; updateColorIcon(); dlg.dismiss() }
            }, GridLayout.LayoutParams().apply { width = dp(44); height = dp(44); setMargins(dp(6), dp(6), dp(6), dp(6)) })
        }
        dlg = MaterialAlertDialogBuilder(this).setTitle("צבע").setView(grid).setNegativeButton("סגור", null).show()
    }

    private fun pickWidth() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val preview = View(this)
        fun updPreview(v: Float) {
            preview.background = pill(view.color, dp(10).toFloat())
            preview.layoutParams = LinearLayout.LayoutParams(-1, (v * 1.6f * resources.displayMetrics.density).toInt().coerceAtLeast(2)).apply { topMargin = dp(16); bottomMargin = dp(8) }
        }
        val slider = Slider(this).apply {
            valueFrom = 1f; valueTo = 20f; stepSize = 1f
            value = (view.penWidthNorm * 1000f).coerceIn(1f, 20f).let { kotlin.math.round(it) }
            addOnChangeListener { _, v, _ -> updPreview(v) }
        }
        updPreview(slider.value)
        box.addView(preview); box.addView(slider)
        MaterialAlertDialogBuilder(this).setTitle("עובי קו").setView(box)
            .setPositiveButton("אישור") { _, _ -> view.penWidthNorm = slider.value / 1000f }
            .setNegativeButton("ביטול", null).show()
    }

    private fun askText(x: Float, y: Float) {
        val fonts = Fonts.list(this)
        if (fonts.isEmpty()) { toast("לא נמצאו גופנים"); return }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        val (til, et) = textField("הקלד טקסט")
        et.minLines = 2
        et.textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
        et.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        et.setTextColor(view.color)
        box.addView(til)

        box.addView(TextView(this).apply { text = "גופן"; textSize = 13f; setTextColor(attrColor(C.onSurfaceVariant)); setPadding(0, dp(14), 0, dp(4)) })
        val spinner = Spinner(this).apply {
            adapter = object : ArrayAdapter<String>(this@EditorActivity, android.R.layout.simple_spinner_dropdown_item, fonts.map(Fonts::displayName)) {
                override fun getView(p: Int, c: View?, parent: ViewGroup) =
                    (super.getView(p, c, parent) as TextView).apply { typeface = Fonts.typeface(context, fonts[p]); text = "${Fonts.displayName(fonts[p])} — שָׁלוֹם"; textSize = 18f }
                override fun getDropDownView(p: Int, c: View?, parent: ViewGroup) =
                    (super.getDropDownView(p, c, parent) as TextView).apply { typeface = Fonts.typeface(context, fonts[p]); text = "${Fonts.displayName(fonts[p])} — אבגד שָׁלוֹם"; textSize = 20f }
            }
            setSelection(fonts.indexOf(lastFont).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) { et.typeface = Fonts.typeface(this@EditorActivity, fonts[pos]) }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        box.addView(spinner)

        val sizeLabel = TextView(this).apply { text = "גודל: $lastSize"; textSize = 13f; setTextColor(attrColor(C.onSurfaceVariant)); setPadding(0, dp(14), 0, 0) }
        val slider = Slider(this).apply {
            valueFrom = 6f; valueTo = 72f; stepSize = 1f; value = lastSize.toFloat()
            addOnChangeListener { _, v, _ -> sizeLabel.text = "גודל: ${v.toInt()}" }
        }
        box.addView(sizeLabel); box.addView(slider)

        MaterialAlertDialogBuilder(this).setTitle("הוספת טקסט").setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("הוסף") { _, _ ->
                val t = et.text.toString().trimEnd()
                if (t.isEmpty()) return@setPositiveButton
                lastFont = fonts[spinner.selectedItemPosition]; lastSize = slider.value.toInt()
                pages.getOrPut(pageIndex) { mutableListOf() }.add(TextAnnot(x, y, t, view.color, lastSize / 595f, lastFont!!))
                dirty = true; redo[pageIndex]?.clear(); view.invalidate()
            }
            .setNegativeButton("ביטול", null).show()
    }

    // ---------- פעולות ----------

    private fun save() = saveAs.launch(docName.removeSuffix(".pdf") + "_ערוך.pdf")

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

    override fun onDestroy() { renderer?.close(); super.onDestroy() }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
