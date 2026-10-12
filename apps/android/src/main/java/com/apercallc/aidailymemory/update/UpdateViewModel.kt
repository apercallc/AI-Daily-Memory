package com.apercallc.aidailymemory.update

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.apercallc.aidailymemory.AppContainer
import com.apercallc.aidailymemory.platform.logFailure
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the App update card (and the capture-screen snackbar) shows. */
sealed interface UpdateState {
    /** Not checked yet in this process. */
    data object Idle : UpdateState

    data object Checking : UpdateState

    data class UpToDate(val versionName: String) : UpdateState

    data class Available(val release: AppRelease) : UpdateState

    data object Downloading : UpdateState

    /** APK downloaded and verified present; [install] opens the system installer. */
    data class Ready(val release: AppRelease) : UpdateState

    /** The check itself failed (offline, rate limit). Never silent: the card says so. */
    data class CheckFailed(val versionName: String) : UpdateState

    /** The download failed; the release is still available, so the button simply retries. */
    data class DownloadFailed(val release: AppRelease) : UpdateState
}

class UpdateViewModel(private val container: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** The APK downloaded this process, if any. */
    private var downloaded: File? = null

    /** Auto-check at most once per process; manual taps re-run it. */
    private var autoChecked = false

    /** Silent check on app open: only runs while still Idle so it never clobbers user action. */
    fun checkIfIdle() {
        if (_state.value == UpdateState.Idle) check()
    }

    fun check() {
        if (_state.value == UpdateState.Checking) return
        _state.value = UpdateState.Checking
        viewModelScope.launch {
            val installed = container.installedVersionName()
            // A failed check is distinct from "no update": the card says so instead of a
            // silent "you're up to date" that would hide a broken path (AGENTS.md).
            val outcome = withContext(Dispatchers.IO) {
                try {
                    container.updates.check(installed)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logFailure(TAG, "update_check_failed", e)
                    FAILED
                }
            }
            // A manual re-check that lands on nothing newer must not downgrade a downloaded
            // APK back to "available" — the file is already on disk.
            _state.value = when {
                outcome is AppRelease -> UpdateState.Available(outcome)
                outcome == FAILED && _state.value is UpdateState.Ready -> _state.value
                outcome == FAILED -> UpdateState.CheckFailed(installed)
                _state.value is UpdateState.Ready -> _state.value
                else -> UpdateState.UpToDate(installed)
            }
        }
    }

    fun download() {
        val release = (_state.value as? UpdateState.Available)?.release
            ?: (_state.value as? UpdateState.DownloadFailed)?.release
            ?: return
        if (_state.value == UpdateState.Downloading) return
        _state.value = UpdateState.Downloading
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    container.updates.download(release, container.updateApkFile())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logFailure(TAG, "update_download_failed", e)
                    _state.value = UpdateState.DownloadFailed(release)
                    return@withContext
                }
                downloaded = container.updateApkFile()
                _state.value = UpdateState.Ready(release)
            }
        }
    }

    /** Opens the system package installer; Android then asks for install-unknown-apps consent. */
    fun install() {
        val release = (_state.value as? UpdateState.Ready)?.release ?: return
        val apk = downloaded ?: container.updateApkFile()
        if (!apk.isFile) {
            // The cache was cleared under us; back to the download button, not a dead install.
            _state.value = UpdateState.Available(release)
            return
        }
        container.installApk(apk)
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            UpdateViewModel(container) as T
    }

    private companion object {
        const val TAG = "UpdateViewModel"

        /** Sentinel for a check that errored, distinct from null ("checked, nothing newer"). */
        const val FAILED = "check-failed"
    }
}
