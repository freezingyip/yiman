package eu.kanade.tachiyomi.ui.more

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.domain.base.BasePreferences
import eu.kanade.tachiyomi.data.updater.installAppUpdate
import eu.kanade.tachiyomi.data.updater.verifyAppUpdate
import eu.kanade.tachiyomi.extension.util.ExtensionInstaller
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.ProgressListener
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.newCachelessCallWithProgress
import eu.kanade.tachiyomi.util.storage.getUriCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import logcat.LogPriority
import okio.Buffer
import okio.buffer
import okio.sink
import tachiyomi.core.common.util.system.logcat
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.time.Duration.Companion.minutes

@AssistedInject
class NewUpdateScreenModel(
    @Assisted changelogInfo: String,
    @Assisted private val downloadLink: String,
    private val context: Context,
    private val network: NetworkHelper,
    private val preferences: BasePreferences,
) : ViewModel() {

    val state: StateFlow<NewUpdateScreenModel.State>
        field = MutableStateFlow<NewUpdateScreenModel.State>(State(changelogInfo = changelogInfo))

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(changelogInfo: String, downloadLink: String): NewUpdateScreenModel
    }

    private val apkFile = File(context.cacheDir, "yiman-update-${UUID.randomUUID()}.apk")

    private var downloadJob: Job? = null
    private var installJob: Job? = null
    private var automaticInstallAttempted = false

    fun startDownload() {
        if (downloadJob?.isActive == true) return

        downloadJob = viewModelScope.launch {
            state.update { it.copy(downloadProgress = 0, stage = Stage.Downloading) }
            try {
                withContext(Dispatchers.IO) {
                    downloadApk()
                    context.verifyAppUpdate(apkFile)
                }
                state.update { it.copy(downloadProgress = 100, stage = Stage.Downloaded) }
            } catch (e: CancellationException) {
                apkFile.delete()
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                apkFile.delete()
                state.update { it.copy(stage = Stage.Failed) }
            }
        }
    }

    private suspend fun downloadApk() {
        val progressListener = object : ProgressListener {
            // Progress of the download
            var savedProgress = 0

            // Keep track of the last update sent to avoid updating the state too often.
            var lastTick = 0L

            override fun update(bytesRead: Long, contentLength: Long, done: Boolean) {
                if (contentLength <= 0L) return
                val progress = (100 * (bytesRead.toFloat() / contentLength)).toInt()
                val currentTime = System.currentTimeMillis()
                if (progress > savedProgress && currentTime - 200 > lastTick) {
                    savedProgress = progress
                    lastTick = currentTime
                    state.update { it.copy(downloadProgress = progress) }
                }
            }
        }

        val call = network.client.newCachelessCallWithProgress(GET(downloadLink), progressListener)
        call.awaitSuccess().use { response ->
            apkFile.sink().buffer().use { output ->
                val input = response.body.source()
                val buffer = Buffer()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = input.read(buffer, 64 * 1024L)
                    if (read == -1L) break
                    output.write(buffer, read)
                }
            }
        }
    }

    fun skipUpdate(versionName: String) {
        downloadJob?.cancel()
        preferences.skippedAppUpdate.set(versionName)
    }

    fun installUpdate(automatic: Boolean = false) {
        if (state.value.stage != Stage.Downloaded || installJob?.isActive == true) return
        if (automatic && (automaticInstallAttempted || !preferences.automaticAppUpdates.get())) return
        if (!context.packageManager.canRequestPackageInstalls()) return
        automaticInstallAttempted = true

        installJob = viewModelScope.launch {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    state.update { it.copy(stage = Stage.Installing) }
                    val finished = withTimeoutOrNull(5.minutes) {
                        context.installAppUpdate(apkFile)
                        true
                    }
                    if (finished == null) throw IOException("Android did not complete the update installation")
                } else {
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(apkFile.getUriCompat(context), ExtensionInstaller.APK_MIME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }
                    context.startActivity(intent)
                }
                state.update { it.copy(stage = Stage.Downloaded) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e)
                state.update { it.copy(stage = Stage.Failed) }
            }
        }
    }

    override fun onCleared() {
        apkFile.delete()
    }

    @Immutable
    data class State(
        val changelogInfo: String,
        val downloadProgress: Int = 0,
        val stage: Stage = Stage.Available,
    )

    enum class Stage {
        Available,
        Downloading,
        Downloaded,
        Installing,
        Failed,
    }
}
