package com.personal.hebpdf

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.mlkit.vision.documentscanner.GmsDocumentScannerOptions
import com.google.mlkit.vision.documentscanner.GmsDocumentScanning
import com.google.mlkit.vision.documentscanner.GmsDocumentScanningResult
import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private var mergeSources: List<Uri> = emptyList()

    private val openPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { openEditor(it) }
    }

    private val scan = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (res.resultCode == RESULT_OK) {
            GmsDocumentScanningResult.fromActivityResultIntent(res.data)?.pdf?.uri?.let { openEditor(it) }
        }
    }

    private val pickForMerge = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.size < 2) { toast("צריך לבחור לפחות 2 קבצים"); return@registerForActivityResult }
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
            if (ok) openEditor(out)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
        }
        root.addView(TextView(this).apply {
            text = "DANIEL PDF"; textSize = 32f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 48)
        })
        fun btn(label: String, action: () -> Unit) = root.addView(Button(this).apply {
            text = label; textSize = 18f; setOnClickListener { action() }
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 24 })

        btn("📂  פתח PDF") { openPdf.launch(arrayOf("application/pdf")) }
        btn("📷  סרוק מסמך") { startScan() }
        btn("🔗  מזג קבצי PDF") { pickForMerge.launch(arrayOf("application/pdf")) }
        setContentView(root)
    }

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

    private fun openEditor(uri: Uri) {
        startActivity(Intent(this, EditorActivity::class.java).setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
}
