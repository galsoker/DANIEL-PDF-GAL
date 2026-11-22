package com.personal.hebpdf

import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.card.MaterialCardView
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("recent", MODE_PRIVATE) }
    private lateinit var recentBox: LinearLayout
    private var mergeSources: List<Uri> = emptyList()

    private val openPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { remember(it); openEditor(it) }
    }
    private val scan = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == RESULT_OK)
            GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pdf?.uri?.let { openEditor(it, "סריקה חדשה.pdf") }
    }
    private val pickForMerge = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.size < 2) { if (uris.isNotEmpty()) toast("צריך לבחור לפחות 2 קבצים"); return@registerForActivityResult }
        mergeSources = uris
        saveMerged.launch("ממוזג.pdf")
    }
    private val saveMerged = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { out ->
        out ?: return@registerForActivityResult
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val m = PDFMergerUtility()
                    mergeSources.forEach { m.addSource(contentResolver.openInputStream(it)) }
                    contentResolver.openOutputStream(out)!!.use { os ->
                        m.destinationStream = os
                        m.mergeDocuments(MemoryUsageSetting.setupTempFileOnly())
                    }
                }.isSuccess
            }
            toast(if (ok) "הקבצים מוזגו ונשמרו" else "המיזוג נכשל")
            if (ok) { remember(out); openEditor(out) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        // כותרת
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
        }, LinearLayout.LayoutParams(dp(64), dp(64)))
        val titles = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), 0, dp(14), 0) }
        titles.addView(TextView(this).apply {
            text = "Daniel-PDF-Gal"; textSize = 26f; setTypeface(typeface, Typeface.BOLD); setTextColor(attrColor(C.onSurface))
        })
        titles.addView(TextView(this).apply {
            text = "עריכה, ציור וסריקה • בלי פרסומות"; textSize = 14f; setTextColor(attrColor(C.onSurfaceVariant))
        })
        header.addView(titles)
        root.addView(header, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(28) })

        // פעולות
        root.addView(actionCard(R.drawable.ic_folder, "פתח PDF", "בחר קובץ מהטלפון לצפייה ועריכה", true) {
            openPdf.launch(arrayOf("application/pdf"))
        })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(actionCard(R.drawable.ic_camera, "סריקה", "מסמך מהמצלמה", false) { startScan() },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        row.addView(actionCard(R.drawable.ic_merge, "מיזוג", "חיבור כמה קבצים", false) { pickForMerge.launch(arrayOf("application/pdf")) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(6) })
        root.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        // אחרונים
        root.addView(TextView(this).apply {
            text = "קבצים אחרונים"; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(attrColor(C.onSurface))
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(32); bottomMargin = dp(8) })
        recentBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(recentBox)

        val scroll = ScrollView(this).apply { addView(root); setBackgroundColor(attrColor(C.surface)) }
        scroll.applySystemBarPadding()
        setContentView(scroll)
    }

    override fun onResume() { super.onResume(); refreshRecent() }

    private fun actionCard(icon: Int, title: String, sub: String, primary: Boolean, onClick: () -> Unit): View {
        val card = MaterialCardView(this).apply {
            radius = dp(24).toFloat(); cardElevation = 0f; strokeWidth = 0
            setCardBackgroundColor(attrColor(if (primary) C.primaryContainer else C.surfaceContainerHigh))
            setOnClickListener { onClick() }
        }
        val fg = attrColor(if (primary) C.onPrimaryContainer else C.onSurface)
        val inner = LinearLayout(this).apply {
            orientation = if (primary) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
            gravity = if (primary) Gravity.CENTER_VERTICAL else Gravity.START
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        inner.addView(ImageView(this).apply {
            setImageResource(icon)
            setColorFilter(attrColor(if (primary) C.onPrimary else C.onPrimaryContainer))
            background = pill(attrColor(if (primary) C.primary else C.primaryContainer), dp(18).toFloat())
            setPadding(dp(12), dp(12), dp(12), dp(12))
        }, LinearLayout.LayoutParams(dp(52), dp(52)))
        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(if (primary) dp(16) else 0, if (primary) 0 else dp(14), 0, 0)
        }
        texts.addView(TextView(this).apply { text = title; textSize = 18f; setTypeface(typeface, Typeface.BOLD); setTextColor(fg) })
        texts.addView(TextView(this).apply { text = sub; textSize = 13f; setTextColor(fg); alpha = 0.8f })
        inner.addView(texts)
        card.addView(inner)
        return card
    }

    private fun refreshRecent() {
        recentBox.removeAllViews()
        val list = recent()
        if (list.isEmpty()) {
            recentBox.addView(TextView(this).apply {
                text = "קבצים שתפתח יופיעו כאן"; textSize = 14f; setTextColor(attrColor(C.onSurfaceVariant)); setPadding(0, dp(8), 0, 0)
            })
            return
        }
        list.forEach { (u, name) ->
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(12), dp(8), dp(12)); background = ripple()
                setOnClickListener { openEditor(Uri.parse(u), name) }
                setOnLongClickListener { forget(u); refreshRecent(); toast("הוסר מהרשימה"); true }
            }
            r.addView(ImageView(this).apply {
                setImageResource(R.drawable.ic_pdf); setColorFilter(attrColor(C.primary))
            }, LinearLayout.LayoutParams(dp(28), dp(28)))
            r.addView(TextView(this).apply {
                text = name; textSize = 16f; setTextColor(attrColor(C.onSurface)); setPadding(dp(14), 0, 0, 0)
                maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            recentBox.addView(r)
        }
    }

    private fun recent(): List<Pair<String, String>> =
        prefs.getString("list", "")!!.lines().filter { '|' in it }.map { it.substringBefore('|') to it.substringAfter('|') }

    private fun saveRecent(l: List<Pair<String, String>>) =
        prefs.edit().putString("list", l.take(12).joinToString("\n") { "${it.first}|${it.second}" }).apply()

    private fun forget(u: String) = saveRecent(recent().filter { it.first != u })

    private fun remember(uri: Uri) {
        runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        saveRecent(listOf(uri.toString() to displayName(uri)) + recent().filter { it.first != uri.toString() })
    }

    private fun displayName(uri: Uri): String = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: "מסמך.pdf"

    private fun startScan() {
        val opts = GmsDocumentScannerOptions.Builder()
            .setGalleryImportAllowed(true)
            .setResultFormats(GmsDocumentScannerOptions.RESULT_FORMAT_PDF)
            .setScannerMode(GmsDocumentScannerOptions.SCANNER_MODE_FULL)
            .build()
        GmsDocumentScanning.getClient(opts).getStartScanIntent(this)
            .addOnSuccessListener { scan.launch(IntentSenderRequest.Builder(it).build()) }
            .addOnFailureListener { toast("הסורק לא זמין: ${it.message}") }
    }

    private fun openEditor(uri: Uri, name: String? = null) {
        startActivity(Intent(this, EditorActivity::class.java).setData(uri)
            .putExtra("name", name ?: displayName(uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
