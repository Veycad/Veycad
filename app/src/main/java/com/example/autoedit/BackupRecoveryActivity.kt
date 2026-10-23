package com.veycad.app

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import java.util.concurrent.Executors

/** Project copies use the system file chooser; recovery always creates a separate project. */
class BackupRecoveryActivity : AppCompatActivity() {
    private val jobs = Executors.newSingleThreadExecutor { Thread(it, "Veycad-project-backup") }
    private var admission: ManualEditorAdmission.Request? = null
    private var manualLease: ManualEditorAdmission.Lease? = null
    private var started = false
    private var busy = false
    private var pending: Pair<Uri, Boolean>? = null
    private var backupUri: Uri? = null
    private var projectId: String? = null
    private lateinit var status: TextView
    private lateinit var export: MaterialButton
    private lateinit var restore: MaterialButton
    private lateinit var share: MaterialButton
    private val saveDocument = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) begin(uri, false)
    }
    private val openDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) begin(uri, true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        projectId = intent.getStringExtra(EXTRA_PROJECT_ID)
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        fun label(resource: Int, size: Float) = TextView(this).apply {
            setText(resource); textSize = size; setTextColor(getColor(R.color.text_primary))
        }.also(column::addView)
        label(R.string.backup_title, 26f)
        label(R.string.backup_description, 16f)
        label(R.string.backup_version_policy, 14f)
        status = label(R.string.backup_ready, 16f).apply {
            tag = "backup-status"; accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        fun button(resource: Int, tagValue: String, action: () -> Unit) = MaterialButton(this).apply {
            setText(resource); tag = tagValue; isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setOnClickListener { action() }
        }.also(column::addView)
        export = button(R.string.backup_save, "backup-save") {
            saveDocument.launch("Veycad-project-${projectId.orEmpty().take(12)}-${System.currentTimeMillis()}.zip")
        }.apply { visibility = if (projectId == null) View.GONE else View.VISIBLE }
        restore = button(R.string.backup_restore, "backup-restore") { openDocument.launch(arrayOf("application/zip", "application/octet-stream")) }
        share = button(R.string.backup_share, "backup-share") {
            backupUri?.let { uri ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"; putExtra(Intent.EXTRA_STREAM, uri)
                    clipData = ClipData.newUri(contentResolver, getString(R.string.backup_title), uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, getString(R.string.backup_share)))
            }
        }.apply { visibility = View.GONE }
        button(R.string.backup_done, "backup-done") { leave() }
        val scroll = ScrollView(this).apply { addView(column); setBackgroundColor(getColor(R.color.background)) }
        setContentView(scroll)
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
        }
        ViewCompat.requestApplyInsets(scroll)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        updateControls()
    }

    override fun onStart() {
        super.onStart(); started = true
        if (manualLease == null && admission == null) {
            admission = ManualEditorAdmission.acquireForCleanup { lease ->
                admission = null; manualLease = lease; updateControls()
                pending?.let { pending = null; begin(it.first, it.second) }
            }
        }
    }

    override fun onStop() {
        started = false; admission?.cancel(); admission = null
        if (!busy) releaseManual()
        super.onStop()
    }

    override fun onDestroy() { jobs.shutdown(); super.onDestroy() }

    private fun leave() {
        if (busy) status.setText(R.string.backup_wait) else finish()
    }

    private fun releaseManual() { manualLease?.release(); manualLease = null }
    private fun updateControls() {
        val enabled = started && manualLease != null && !busy
        export.isEnabled = enabled; restore.isEnabled = enabled; share.isEnabled = enabled
    }

    private fun begin(uri: Uri, restoring: Boolean) {
        if (busy) return
        if (!started || manualLease == null) { pending = uri to restoring; return }
        busy = true; updateControls(); status.setText(if (restoring) R.string.backup_restoring else R.string.backup_writing)
        try {
            jobs.execute {
                val result = runCatching {
                        val backup = HybridProjectBackup(filesDir)
                        if (restoring) backup.restore(requireNotNull(contentResolver.openInputStream(uri)))
                        else backup.export(requireNotNull(projectId), requireNotNull(contentResolver.openOutputStream(uri, "w")))
                }
                runOnUiThread {
                    busy = false
                    if (!started || isDestroyed) releaseManual()
                    if (isDestroyed) return@runOnUiThread
                    result.fold(onSuccess = { receipt ->
                        status.text = getString(if (restoring) R.string.backup_restored else R.string.backup_saved,
                            receipt.fileCount, receipt.bytes)
                        if (receipt.missingOutputs > 0) status.append("\n" + getString(R.string.backup_missing_outputs, receipt.missingOutputs))
                        if (receipt.legacyUnverifiedSources > 0) status.append("\n" + getString(R.string.backup_legacy_unverified, receipt.legacyUnverifiedSources))
                        if (receipt.cleanupIncomplete) status.append("\n" + getString(R.string.backup_cleanup_warning))
                        if (restoring) setResult(RESULT_OK, Intent().putExtra(EXTRA_PROJECT_ID, receipt.projectId))
                        else { backupUri = uri; share.visibility = View.VISIBLE }
                    }, onFailure = { status.text = getString(R.string.backup_failed, it.message ?: it.javaClass.simpleName) })
                    updateControls()
                }
            }
        } catch (error: Throwable) {
            busy = false; updateControls()
            status.text = getString(R.string.backup_failed, error.message ?: error.javaClass.simpleName)
        }
    }

    companion object { const val EXTRA_PROJECT_ID = "projectId" }
}
