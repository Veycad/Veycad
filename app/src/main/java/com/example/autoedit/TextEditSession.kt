package com.veycad.app

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors

/** Process owner: activities only observe. Draft and source survive process death. */
internal class TextEditSession internal constructor(context: Context,
    private val coordinator: MediaWorkCoordinator = MediaWorkCoordinator.process,
    private val worker: java.util.concurrent.ExecutorService = Executors.newSingleThreadExecutor(),
    private val transcriber: SpeechTranscriber = WhisperSpeechTranscriber(),
    private val exportEngine: ExportEngine = NATIVE_EXPORT_ENGINE
) {
    internal interface Owner { val textEditSession: TextEditSession }
    internal fun interface ExportEngine {
        fun export(context: Context, project: TextEditProject, output: File,
            checkCancelled: () -> Unit, onProgress: (Int) -> Unit)
    }
    private val app=context.applicationContext
    private val preferences=app.getSharedPreferences("text_session",Context.MODE_PRIVATE)
    private val interruptedImport=preferences.getBoolean("running",false)
    private val store=TextEditStore(File(app.filesDir,"text-drafts"))
    private val main=Handler(Looper.getMainLooper())
    private val mediaWork=MediaWorkDispatch(coordinator,worker) { main.post(it) }
    private var recovered=false
    private val scratch=File(app.cacheDir,"text-work")
    private val lock=Any()
    @Volatile private var cancelled=false
    @Volatile private var publishing=false
    private var lastProgress=-1
    data class State(val project:TextEditProject?=null,val busy:Boolean=false,val progress:Int=0,
        val operation:String="",val error:String?=null,val entry:CompletedRenderStore.Entry?=null,val canCancel:Boolean=true)
    private val mutable=MutableLiveData(State(error=if(preferences.getBoolean("running",false))
        "Обработка прервана. Черновик сохранён; можно повторить." else null))
    val state:LiveData<State> = mutable
    private fun checkCancelled() { if(cancelled) throw CancellationException() }
    fun cancel() { synchronized(lock) { if(!publishing) cancelled=true } }
    fun resume() {
        if(state.value?.project!=null || state.value?.busy==true) return
        preferences.getString("source",null)?.let { if(File(it).isFile) open(File(it)) }
    }
    fun open(file:File) {
        if(state.value?.busy==true) return
        if(state.value?.project?.sourcePath==file.canonicalPath) return
        val allowed=listOf(app.filesDir.canonicalPath,app.cacheDir.canonicalPath)
        if(allowed.none { file.canonicalPath.startsWith(it+File.separator) }) {
            mutable.value=state.value!!.copy(error="Недоступный исходный файл"); return
        }
        val recoveryMessage=state.value?.error
        runJob("Открываю видео") {
            val project=store.load(file.canonicalPath) ?: TextVideoProbe.project(file,::checkCancelled)
            store.save(project); preferences.edit().putString("source",project.sourcePath).commit()
            // Publication metadata repairs a crash between publishing the MP4 and updating its draft link.
            val entry=CompletedRenderStore.list(app.filesDir).firstOrNull { it.textSourcePath==project.sourcePath }
            if(entry!=null) store.save(project,entry.file.path)
            State(project=project,error=recoveryMessage,entry=entry)
        }
    }
    fun import(uri:Uri) {
        if(state.value?.busy==true) return
        runJob("Копирую видео") {
            val directory=File(app.filesDir,"text-sources").apply { mkdirs() }
            val partial=File(directory,"${UUID.randomUUID()}.partial")
            try {
                val digest=MessageDigest.getInstance("SHA-256")
                app.contentResolver.openInputStream(uri)?.use { input ->
                    partial.outputStream().buffered().use { output ->
                        val bytes=ByteArray(128*1024)
                        while(true) { checkCancelled(); val n=input.read(bytes); if(n<0) break; digest.update(bytes,0,n); output.write(bytes,0,n) }
                    }
                } ?: error("Не удалось открыть видео")
                val source=File(directory,digest.digest().joinToString("") { "%02x".format(it) }+".mp4")
                if(!source.exists()) check(partial.renameTo(source)) { "Не удалось сохранить исходник" }
                checkCancelled()
                val project=store.load(source.canonicalPath) ?: TextVideoProbe.project(source,::checkCancelled)
                store.save(project); preferences.edit().putString("source",project.sourcePath).commit()
                State(project=project)
            } finally { partial.delete() }
        }
    }
    fun update(project:TextEditProject) {
        if(state.value?.busy==true) return
        // Edits are durable before the UI confirms them.
        runCatching { store.save(project) }.fold(
            onSuccess={ mutable.value=State(project=project) },
            onFailure={ mutable.value=state.value!!.copy(error=it.message ?: "Не удалось сохранить черновик") })
    }
    fun transcribe() {
        val project=state.value?.project ?: return
        runJob("Распознаю речь") {
            val outcome=transcriber.transcribeOutcome(app,File(project.sourcePath),project.language,::checkCancelled,::progress)
            if(outcome is SpeechOutcome.Failure) throw SpeechFailureException(outcome.code,outcome.cause)
            val cues=outcome.cuesOrThrow()
            val updated=project.copy(captions=cues,captionsEdited=false)
            checkCancelled(); store.save(updated)
            val message=if(outcome is SpeechOutcome.NoSpeech) when(outcome.evidence.reason) {
                SpeechNoSpeechReason.NO_AUDIO_TRACK -> "В видео нет аудиодорожки. Можно добавить субтитры вручную."
                SpeechNoSpeechReason.DIGITAL_SILENCE -> "В звуковой дорожке тишина. Можно добавить субтитры вручную."
                else -> "Речь не найдена. Можно добавить субтитры вручную."
            } else null
            State(project=updated,error=message)
        }
    }
    fun export() {
        val project=state.value?.project ?: return
        runJob("Экспортирую видео") {
            val result=File(scratch,"text-${UUID.randomUUID()}.mp4")
            try {
                exportEngine.export(app,project,result,::checkCancelled,::progress)
                synchronized(lock) { checkCancelled(); publishing=true }
                mediaWork.post { mutable.value=mutable.value!!.copy(canCancel=false,operation="Сохраняю результат") }
                val entry=CompletedRenderStore.publish(app.filesDir,result,"Текст и субтитры · ${project.layers.size} слоёв · ${project.captions.size} фраз",
                    textSourcePath=project.sourcePath)
                store.save(project,entry.file.path)
                State(project=project,entry=entry)
            } finally { result.delete() }
        }
    }
    private fun progress(value:Int) {
        if(lastProgress==value) return
        lastProgress=value
        mediaWork.post { if(mutable.value?.busy==true) mutable.value=mutable.value!!.copy(progress=value) }
    }
    private fun runJob(operation:String, action:()->State) {
        withMediaWork {

            if(state.value?.busy==true) return
            val before=state.value ?: State()
            synchronized(lock) { cancelled=false; publishing=false }
            lastProgress=-1
            preferences.edit().putBoolean("running",true).commit()
            mutable.value=before.copy(busy=true,progress=0,operation=operation,error=null,entry=null)
            mediaWork.execute {
                val result=runCatching(action).getOrElse {
                    before.copy(error=if(it is CancellationException) "Обработка отменена. Черновик сохранён." else it.message ?: "Не удалось обработать видео")
                }
                preferences.edit().putBoolean("running",false).commit()
                mediaWork.post { mutable.value=result }
            }
        }
    }
    internal fun close() {
        check(state.value?.busy != true)
        worker.shutdown()
    }
    private fun recoverIfIdle() {
        if(recovered) return
        val lease=coordinator.tryBeginCleanup() ?: return
        lease.use {
            TemporaryFileCleanup.clear(app.filesDir,app.cacheDir,TemporaryFileCleanup.Scope.TEXT_WORK,it)
            if(interruptedImport && TemporaryFileCleanup.clearInterruptedTextImports(app.filesDir,it).isNotEmpty())
                LocalDiagnostics.record(app,"text_import_recovery_incomplete")
            recovered=true
        }
    }
    private inline fun withMediaWork(action:()->Unit) {
        recoverIfIdle()
        val before=state.value ?: State()
        val running=preferences.getBoolean("running",false)
        val lease=coordinator.tryBeginWork()
            ?: run { mutable.value=before.copy(error=MediaWorkCoordinator.CLEANING_MESSAGE); return }
        try { action() }
        catch(failure:java.util.concurrent.RejectedExecutionException) {
            preferences.edit().putBoolean("running",running).commit()
            mutable.value=before.copy(error=failure.message ?: "Не удалось начать обработку")
        } catch(failure:Throwable) {
            preferences.edit().putBoolean("running",running).commit()
            runCatching { mutable.value=before }
            throw failure
        } finally { lease.close() }
    }
    companion object {
        internal val NATIVE_EXPORT_ENGINE=ExportEngine { context,project,output,cancel,progress ->
            TextVideoExporter.export(context,project,output,cancel,progress)
        }
        @Volatile private var instance:TextEditSession?=null
        fun get(context:Context):TextEditSession = (context.applicationContext as? Owner)?.textEditSession ?: instance ?: synchronized(this) {
            instance ?: TextEditSession(context).also { instance=it }
        }
    }
}
