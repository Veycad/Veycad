package com.veycad.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Observer
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import org.json.JSONArray
import org.json.JSONObject

/** Host-driven checkpoints exist only in the isolated test APK, never in production. */
internal class UiImportRestartProbeActivity : AppCompatActivity() {
    private lateinit var runId: String
    private lateinit var phase: SourceDraftStore.Phase
    private lateinit var directory: File
    private var observer: Observer<EditSession.State>? = null
    private val app get() = application as UiTestApplication
    private val style get() = MontageStyleCatalog.dualityLoop

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(packageName == "com.veycad.app.uitest")
        runId = requireNotNull(intent.getStringExtra("runId"))
        check(UUID.fromString(runId).toString() == runId)
        phase = SourceDraftStore.Phase.valueOf(requireNotNull(intent.getStringExtra("phase")))
        directory = File(filesDir, "import-restart-probe/$runId").apply { check(mkdirs() || isDirectory) }
        setContentView(TextView(this).apply { text = "Import restart probe: ${intent.getStringExtra("command")}" })
        try {
            when (intent.getStringExtra("command")) {
                "prepare" -> prepare()
                "replace" -> replace()
                "verify" -> verify()
                else -> error("Unknown probe command")
            }
        } catch (failure: Exception) { failed(failure) }
    }

    private fun prepare() {
        Thread {
            try {
                listOf("a0.mp4", "a1.mp4", "b0.mp4", "b1.mp4").forEachIndexed { index, name ->
                    SyntheticVideo.create(fixture(name), (index + 1) * 1_000L)
                }
                check(!fixture("a0.mp4").readBytes().contentEquals(fixture("b0.mp4").readBytes()))
                runOnUiThread {
                    try {
                        check(getSharedPreferences("montage_style", 0).edit().putString("selected", style.id).commit())
                        watch(listOf("a0.mp4", "a1.mp4")) {
                            val snapshot = requireNotNull(SourceDraftStore(filesDir).read())
                            report("ready.json", JSONObject().put("old", choice(snapshot)))
                        }
                        app.editSession.importVideos(listOf(uri("a0.mp4"), uri("a1.mp4")), style)
                    } catch (failure: Exception) { failed(failure) }
                }
            } catch (failure: Exception) { failed(failure) }
        }.start()
    }

    private fun replace() {
        val ready = JSONObject(File(directory, "ready.json").readText())
        check(ready.getString("runId") == runId)
        val old = ready.getJSONObject("old")
        val oldRows = old.getJSONArray("sources")
        val oldNames = (0 until oldRows.length()).map { oldRows.getJSONObject(it).getString("fileName") }.toSet()
        app.sourceIo = object : SourceDraftStore.Io by SourceDraftStore.DiskIo {
            override fun checkpoint(current: SourceDraftStore.Phase) {
                if (current != phase) return
                val root = File(filesDir, "source-draft")
                val candidates = root.listFiles()!!.filter { it.name.matches(Regex("source-[01]-[0-9a-f-]{36}\\.mp4")) && it.name !in oldNames }.sortedBy { it.name }
                check(candidates.size == 2)
                val id = UUID.fromString(candidates.first().name.removePrefix("source-0-").removeSuffix(".mp4"))
                val snapshot = SourceDraftManifest.Snapshot(id, SourceDraftManifest.Origin.IMPORT,
                    candidates.mapIndexed { index, file -> SourceDraftManifest.Entry(file.name, "b$index.mp4", fixture("b$index.mp4").length()) })
                SourceDraftManifest.encode(snapshot) // Prove contiguous indices and one UUID.
                val actual = requireNotNull(SourceDraftStore(filesDir).read())
                check(actual.batchId.toString() == if (phase == SourceDraftStore.Phase.FILES_PREPARED) old.getString("batchId") else id.toString())
                report("checkpoint.json", JSONObject().put("old", old).put("new", choice(snapshot)))
                // Only the host's real force-stop releases this test worker.
                CountDownLatch(1).await()
            }
        }
        watch(listOf("b0.mp4", "b1.mp4")) { error("Checkpoint did not stop publication") }
        app.editSession.importVideos(listOf(uri("b0.mp4"), uri("b1.mp4")), style)
    }

    private fun verify() {
        val checkpoint = JSONObject(File(directory, "checkpoint.json").readText())
        check(checkpoint.getString("runId") == runId && checkpoint.getString("phase") == phase.name)
        val expected = checkpoint.getJSONObject(if (phase == SourceDraftStore.Phase.FILES_PREPARED) "old" else "new")
        val rows = expected.getJSONArray("sources")
        watch((0 until rows.length()).map { rows.getJSONObject(it).getString("displayName") }) {
            val snapshot = requireNotNull(SourceDraftStore(filesDir).read())
            check(snapshot.batchId.toString() == expected.getString("batchId"))
            report("verdict.json", JSONObject().put("batchId", snapshot.batchId.toString())
                .put("confirmed", app.editSession.state.value!!.sourceSelectionConfirmed))
        }
        startActivity(Intent(this, MainActivity::class.java))
    }

    private fun watch(names: List<String>, ready: () -> Unit) {
        observer?.let { app.editSession.state.removeObserver(it) }
        val listener = Observer<EditSession.State> { state ->
            try {
                if (!state.busy && state.sourceSelectionConfirmed && state.draft?.names == names) {
                    observer?.let { app.editSession.state.removeObserver(it) }
                    observer = null
                    ready()
                } else if (!state.busy && state.error != null) failed(IllegalStateException(state.error))
            } catch (failure: Exception) { failed(failure) }
        }
        observer = listener
        app.editSession.state.observeForever(listener)
    }
    private fun choice(snapshot: SourceDraftManifest.Snapshot): JSONObject = JSONObject()
        .put("batchId", snapshot.batchId.toString()).put("sources", JSONArray().apply {
            snapshot.entries.forEach { entry ->
                val expected = fixture(entry.displayName)
                val source = File(filesDir, "source-draft/${entry.fileName}")
                val hash = sha(expected)
                check(source.length() == expected.length() && sha(source) == hash)
                put(JSONObject().put("fileName", entry.fileName).put("displayName", entry.displayName)
                    .put("length", expected.length()).put("sha256", hash))
            }
        })
    private fun sha(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    private fun fixture(name: String) = File(getDir("ui-imports", 0), name)
    private fun uri(name: String) = Uri.Builder().scheme("content").authority("$packageName.ui_fixtures").appendPath(name).build()
    private fun report(name: String, payload: JSONObject) {
        payload.put("runId", runId).put("phase", phase.name).put("pid", Process.myPid())
        atomicWrite(File(directory, name), payload.toString().toByteArray(Charsets.UTF_8))
    }
    private fun failed(failure: Exception) = report("error.json", JSONObject().put("error", failure.toString()))
    override fun onDestroy() {
        observer?.let { app.editSession.state.removeObserver(it) }
        super.onDestroy()
    }
}
