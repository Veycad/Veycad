package com.veycad.app

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Process
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/** Fresh-process recovery checkpoints exist exclusively in the isolated test APK. */
internal class UiProjectRecoveryProbeActivity : AppCompatActivity() {
    private lateinit var directory: File
    private lateinit var runId: String
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(packageName == "com.veycad.app.uitest")
        runId = requireNotNull(intent.getStringExtra("runId"))
        check(UUID.fromString(runId).toString() == runId)
        directory = File(filesDir, "project-recovery-probe/$runId").apply { check(mkdirs() || isDirectory) }
        setContentView(TextView(this).apply { text = "Project recovery probe: ${intent.getStringExtra("command")}" })
        Thread({
            try {
                when (intent.getStringExtra("command")) {
                    "prepare" -> prepare()
                    "verify" -> verify()
                    else -> error("Unknown project recovery command")
                }
            } catch (failure: Exception) { report("error.json", JSONObject().put("error", failure.toString())) }
        }, "Veycad-project-recovery-probe").start()
    }
    private fun prepare() {
        val lease = requireNotNull(MediaWorkCoordinator.process.tryBeginWork())
        try {
            val video = SyntheticVideo.create(File(directory, "original.mp4"))
            val (source, metadata) = MediaInputInspector().inspectForProject("recovery-source", video, video.name)
            val id = "recovery-$runId"
            val store = HybridProjectStore(filesDir)
            val assets = ProjectAssetStore(store.directory(id))
            val videoAsset = assets.importVideo(source, metadata.geometry.pixelAspectRatio)
            val style = MontageStyleCatalog.heartbeat
            val musicFile = BuiltInMusicCatalog.select(applicationContext, style.id).file
            val duration = MediaMetadataRetriever().use { retriever ->
                retriever.setDataSource(musicFile.path)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong() * 1_000
            }
            val music = assets.import(musicFile, ProjectAsset.Kind.AUDIO, duration)
            val graph = ManualMvpBasicGraph.create(listOf(source), music.id)
            val selection = SelectedVideo.fromInspected(source, videoAsset, SourceOwnership.IMPORTED)
            val project = EditableMontageImporter.create(id, graph, requireNotNull(style.recipe),
                ProjectExportSettings(1080, 1920, 30, 8_000_000, 192_000), listOf(videoAsset), music, null, listOf(selection)).shared
            store.create(project)
            // Real owned spill remains open until the host kills this process. No simulated OOM.
            val pages = PreparedAttachmentPages(cacheDir)
            val values = pages.append(20_000) { it / 20_000f }
            pages.seal()
            check(values[19_999] > .99f)
            val scratch = mutableListOf<File>()
            scratch += cacheDir.listFiles().orEmpty().filter { it.name.startsWith("preview-floats-") }
            for (name in listOf("project-speech-work/interrupted.pcm", "manual-renders/preview-audio/interrupted.wav")) {
                scratch += File(cacheDir, name).apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1, 3, 5, 7)) }
            }
            val staged = File(filesDir, ".project-restore-$runId/backup.zip").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(7, 5, 3, 1)) }
            scratch += staged
            check(MediaWorkCoordinator.process.tryBeginCleanup() == null)
            report("checkpoint.json", JSONObject().put("projectId", id).put("projectFiles", inventory(store.directory(id)))
                .put("scratch", JSONArray().apply { scratch.forEach { put(it.canonicalPath.removePrefix(applicationInfo.dataDir.let { File(it).canonicalPath } + "/")) } })
                .put("openSpillValues", values.size).put("cleanupDeniedWhileOwned", true))
            CountDownLatch(1).await() // The host must demonstrate actual force-stop and a new PID.
        } finally { lease.close() }
    }
    private fun verify() {
        val expected = JSONObject(File(directory, "checkpoint.json").readText())
        check(expected.getString("runId") == runId && expected.getInt("pid") != Process.myPid())
        val id = expected.getString("projectId")
        val store = HybridProjectStore(filesDir)
        val before = inventory(store.directory(id))
        check(before.toString() == expected.getJSONArray("projectFiles").toString()) { "Project bytes changed before recovery" }
        // Exercise the same startup entry MainActivity uses, rather than deleting probe files itself.
        val recovered = CountDownLatch(1)
        runOnUiThread {
            try { (application as UiTestApplication).editSession.restoreDraft(MontageStyleCatalog.heartbeat) }
            finally { recovered.countDown() }
        }
        check(recovered.await(30, TimeUnit.SECONDS)) { "Startup recovery did not drain" }
        val scratch = expected.getJSONArray("scratch")
        for (index in 0 until scratch.length()) check(!File(applicationInfo.dataDir, scratch.getString(index)).exists())
        check(cacheDir.listFiles().orEmpty().none { it.name.startsWith("preview-floats-") })
        val project = store.load(id)
        project.assets.forEach { asset -> check(hash(ProjectAssetStore(store.directory(id)).resolve(asset)) == asset.contentHash) }
        check(inventory(store.directory(id)).toString() == before.toString())
        report("verdict.json", JSONObject().put("projectId", id).put("projectFiles", before)
            .put("scratchRemoved", scratch.length()).put("sourcesVerified", project.assets.size).put("projectUnchanged", true))
        runOnUiThread { startActivity(Intent(this, ManualMvpActivity::class.java).putExtra("projectId", id)) }
    }
    private fun inventory(root: File) = JSONArray().apply {
        root.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(root).invariantSeparatorsPath }.forEach {
            put(JSONObject().put("path", it.relativeTo(root).invariantSeparatorsPath).put("bytes", it.length()).put("sha256", hash(it)))
        }
    }
    private fun hash(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(64 * 1024)
        while (true) { val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
        digest.digest().joinToString("") { "%02x".format(it) }
    }
    private fun report(name: String, payload: JSONObject) {
        payload.put("runId", runId).put("pid", Process.myPid()).put("source", BuildConfig.SOURCE_SHA)
        atomicWrite(File(directory, name), payload.toString().toByteArray(Charsets.UTF_8))
    }
}
