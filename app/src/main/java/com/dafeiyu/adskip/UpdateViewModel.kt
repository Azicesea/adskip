package com.dafeiyu.adskip

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.Executors

internal class UpdateViewModel(application: Application) : AndroidViewModel(application) {
    sealed interface State {
        data class Message(@StringRes val text: Int, val busy: Boolean = false) : State
        data class Available(
            val release: GitHubUpdates.Release,
            @StringRes val error: Int? = null
        ) : State
        data class Ready(val apk: GitHubUpdates.Downloaded, @StringRes val error: Int? = null) : State
    }

    private val updates = GitHubUpdates(application)
    private val currentState = MutableLiveData<State>()
    val state: LiveData<State> = currentState
    private var announcedVersion: Long? = null

    init {
        val autoCheck = Prefs.autoCheckUpdates(application)
        runUpdate(if (autoCheck) R.string.update_checking else R.string.update_loading) {
            val cached = updates.cached()?.let { State.Ready(it) }
            if (autoCheck) checkRelease(cached) else cached ?: State.Message(R.string.update_idle)
        }
    }

    fun check() {
        if ((state.value as? State.Message)?.busy == true) return
        val cached = state.value as? State.Ready
        runUpdate(R.string.update_checking) { checkRelease(cached) }
    }

    fun download() {
        val available = state.value as? State.Available ?: return
        runUpdate(R.string.update_downloading) {
            try {
                State.Ready(updates.download(available.release))
            } catch (error: Exception) {
                available.copy(error = failureMessage(error, R.string.update_download_failed))
            }
        }
    }

    fun downloadMissing() {
        currentState.value = State.Message(R.string.update_download_missing)
    }

    fun announce(versionCode: Long): Boolean {
        if (announcedVersion == versionCode) return false
        announcedVersion = versionCode
        return true
    }

    private fun checkRelease(cached: State.Ready?): State = try {
        val release = updates.latest()
        when {
            release == null -> State.Message(R.string.update_current)
            cached?.apk?.versionCode == release.versionCode && cached.apk.file.isFile -> cached.copy(error = null)
            else -> State.Available(release)
        }
    } catch (error: Exception) {
        val message = failureMessage(error, R.string.update_check_failed)
        cached?.copy(error = message) ?: State.Message(message)
    }

    private fun runUpdate(@StringRes message: Int, work: () -> State) {
        currentState.value = State.Message(message, busy = true)
        executor.execute {
            val result = try {
                work()
            } catch (error: Exception) {
                State.Message(failureMessage(error, R.string.update_check_failed))
            }
            currentState.postValue(result)
        }
    }

    private fun failureMessage(error: Exception, @StringRes fallback: Int): Int =
        (error as? GitHubUpdates.Failure)?.messageRes ?: fallback

    private companion object {
        // Serialize access to the shared APK cache across activity instances.
        val executor = Executors.newSingleThreadExecutor()
    }
}
