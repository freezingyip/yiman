package eu.kanade.tachiyomi.ui.more

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.more.NewUpdateScreen
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.openInBrowser

class NewUpdateScreen(
    private val versionName: String,
    private val changelogInfo: String,
    private val releaseLink: String,
    private val downloadLink: String,
    private val automatic: Boolean = false,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel =
            assistedMetroViewModel<NewUpdateScreenModel, NewUpdateScreenModel.Factory> {
                create(changelogInfo = changelogInfo, downloadLink = downloadLink)
            }

        val state by viewModel.state.collectAsState()

        val lifecycle = LocalLifecycleOwner.current.lifecycle
        val installPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            viewModel.installUpdate()
        }

        LaunchedEffect(viewModel) {
            if (automatic && viewModel.state.value.stage == NewUpdateScreenModel.Stage.Available) {
                viewModel.startDownload()
            }
        }
        LaunchedEffect(state.stage, lifecycle) {
            if (automatic && state.stage == NewUpdateScreenModel.Stage.Downloaded) {
                lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                    viewModel.installUpdate(automatic = true)
                }
            }
        }

        NewUpdateScreen(
            versionName = versionName,
            stage = state.stage,
            downloadProgress = { state.downloadProgress },
            changelogInfo = state.changelogInfo,
            onOpenInBrowser = { context.openInBrowser(releaseLink) },
            onAcceptUpdate = {
                when (state.stage) {
                    NewUpdateScreenModel.Stage.Available, NewUpdateScreenModel.Stage.Failed -> viewModel.startDownload()
                    NewUpdateScreenModel.Stage.Downloaded -> {
                        if (context.packageManager.canRequestPackageInstalls()) {
                            viewModel.installUpdate()
                        } else {
                            installPermission.launch(
                                Intent(
                                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    "package:${context.packageName}".toUri(),
                                ),
                            )
                        }
                    }
                    NewUpdateScreenModel.Stage.Installing, NewUpdateScreenModel.Stage.Downloading -> Unit
                }
            },
            onRejectUpdate = navigator::pop,
            onSkipUpdate = {
                viewModel.skipUpdate(versionName)
                navigator.pop()
            },
        )
    }
}
