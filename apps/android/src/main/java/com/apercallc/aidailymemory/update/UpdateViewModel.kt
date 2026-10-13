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
    data object CheckFailed : UpdateState

    /** The system installer could not be opened; the APK is still on disk, so it can retry. */
    data class InstallFailed(val release: AppRelease) : UpdateState

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
            val outcome = if (installed == null) {
                UpdateCheck.Failed
            } else {
                withContext(Dispatchers.IO) {
                    try {
                        container.updates.check(installed)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logFailure(TAG, "update_check_failed", e)
                        UpdateCheck.Failed
                    }
                }
            }
            _state.value = reduceCheck(_state.value, outcome, installed)
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
        val release = (_state.value as? UpdateState.Ready)?.release
            ?: (_state.value as? UpdateState.InstallFailed)?.release
            ?: return
        val apk = downloaded ?: container.updateApkFile()
        if (!apk.isFile) {
            // The cache was cleared under us; back to the download button, not a dead install.
            _state.value = UpdateState.Available(release)
            return
        }
        if (!container.installApk(apk)) _state.value = UpdateState.InstallFailed(release)
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            UpdateViewModel(container) as T
    }

    companion object {
        private const val TAG = "UpdateViewModel"

        /**
         * The state after a check finished while the card was in [current]. A downloaded APK
         * (Ready) is never downgraded by a later check, whatever it found or failed to find.
         */
        internal fun reduceCheck(current: UpdateState, outcome: UpdateCheck, installed: String?): UpdateState =
            when {
                current is UpdateState.Ready -> current
                outcome is UpdateCheck.Newer -> UpdateState.Available(outcome.release)
                outcome == UpdateCheck.UpToDate && installed != null -> UpdateState.UpToDate(installed)
                else -> UpdateState.CheckFailed
            }
    }
}
