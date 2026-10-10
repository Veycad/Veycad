package com.veycad.app

import android.app.Application
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.Executors

/** Keeps provider copying and analysis alive across Activity recreation without retaining its views. */
internal class CustomMusicSession(application: Application) : AndroidViewModel(application) {
    private val store = CustomMusicStore(application)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cleared = false
    data class State(val selection: CustomMusicStore.Selection? = null, val analysis: AudioBeatMap? = null,
        val busy: Boolean = false, val activateCustomStyle: Boolean = false, val error: String? = null) {
        val ready get() = !busy && selection?.file?.isFile == true && analysis != null
    }
    private val mutableState = MutableLiveData(State(selection = store.restore()))
    val state: LiveData<State> = mutableState
    val current get() = requireNotNull(state.value)

    fun load() {
        val value = current
        if (!value.busy && value.selection != null && value.analysis == null) analyze(value.selection)
    }
    fun import(uri: Uri) {
        val previous = current
        if (previous.busy) return
        mutableState.value = previous.copy(busy = true, error = null)
        worker.execute {
            var imported: CustomMusicStore.Selection? = null
            val result = runCatching {
                val selected = store.importFile(uri, ::checkCurrent).also { imported = it }
                selected to store.analyze(selected, ::checkCurrent)
            }
            main.post {
                if (cleared) { imported?.file?.delete(); return@post }
                result.onSuccess { (selected, map) ->
                    runCatching { store.save(selected) }.onSuccess {
                        // Persist activation as well as the file, including completion between screens.
                        getApplication<Application>().getSharedPreferences("montage_style", 0).edit()
                            .putString("selected", MontageStyleCatalog.customMusic.id).apply()
                        mutableState.value = State(selected, map, activateCustomStyle = true)
                    }.onFailure { error -> imported?.file?.delete(); failed(previous, error) }
                }.onFailure { error -> imported?.file?.delete(); failed(previous, error) }
            }
        }
    }
    fun analyze(selected: CustomMusicStore.Selection) {
        val previous = current
        if (previous.busy) return
        mutableState.value = State(selected, busy = true)
        worker.execute {
            val result = runCatching { store.analyze(selected, ::checkCurrent) }
            main.post {
                if (cleared) return@post
                result.onSuccess { map ->
                    runCatching { store.save(selected) }.onSuccess { mutableState.value = State(selected, map) }
                        .onFailure { failed(previous, it) }
                }.onFailure { failed(previous, it) }
            }
        }
    }
    fun consumeEvents() {
        val value = current
        if (value.activateCustomStyle || value.error != null)
            mutableState.value = value.copy(activateCustomStyle = false, error = null)
    }
    private fun failed(previous: State, error: Throwable) {
        mutableState.value = previous.copy(busy = false,
            error = error.message ?: "Не удалось открыть трек. Выберите MP3 или WAV")
    }
    private fun checkCurrent() { check(!cleared) { "Анализ музыки отменён" } }
    override fun onCleared() {
        cleared = true
        worker.shutdownNow()
    }
}
