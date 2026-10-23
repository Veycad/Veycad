package com.veycad.app

import android.os.Looper
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer

/** Catch each subscriber before its exception can interrupt LiveData's dispatch loop. */
internal class ObserverSafeLiveData<T>(initial: T, private val onFailure: (Exception) -> Unit) : MutableLiveData<T>(initial) {
    private class Registration<T>(val owner: LifecycleOwner?, val guarded: Observer<T>)
    private val registrations = mutableMapOf<Observer<in T>, Registration<T>>()

    override fun observe(owner: LifecycleOwner, observer: Observer<in T>) {
        checkMainThread()
        if (owner.lifecycle.currentState == Lifecycle.State.DESTROYED) return
        register(owner, observer) { super.observe(owner, it) }
    }

    override fun observeForever(observer: Observer<in T>) {
        checkMainThread()
        register(null, observer) { super.observeForever(it) }
    }

    private fun register(owner: LifecycleOwner?, observer: Observer<in T>, attach: (Observer<T>) -> Unit) {
        registrations[observer]?.let {
            require(it.owner === owner) { "Cannot add the same observer with different lifecycles" }
            return
        }
        val guarded = Observer<T> { value ->
            try { observer.onChanged(value) }
            catch (failure: Exception) { runCatching { onFailure(failure) } }
        }
        registrations[observer] = Registration(owner, guarded)
        try { attach(guarded) }
        catch (failure: Exception) {
            removeObserver(observer)
            throw failure
        }
    }

    override fun removeObserver(observer: Observer<in T>) {
        checkMainThread()
        // Lifecycle destruction and removeObservers(owner) call this with the guarded observer.
        val original = if (registrations.containsKey(observer)) observer
            else registrations.entries.firstOrNull { it.value.guarded === observer }?.key
        val registration = original?.let { registrations.remove(it) }
        if (registration == null) super.removeObserver(observer)
        else super.removeObserver(registration.guarded)
    }

    private fun checkMainThread() = check(Looper.myLooper() == Looper.getMainLooper()) {
        "Observer registration must run on the main thread"
    }
}
