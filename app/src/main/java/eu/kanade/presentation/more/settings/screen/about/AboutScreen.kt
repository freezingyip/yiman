package eu.kanade.presentation.more.settings.screen.about

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.LogoHeader
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.rememberIs24HourSystem
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.updater.AppUpdateChecker
import eu.kanade.tachiyomi.ui.more.NewUpdateScreen
import eu.kanade.tachiyomi.util.lang.toDateTimestampString
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.updaterEnabled
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import mihon.app.di.appGraph
import tachiyomi.domain.release.interactor.GetApplicationRelease.Result
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.util.Locale
import kotlin.time.Instant

object AboutScreen : Screen() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        val uriHandler = LocalUriHandler.current
        val handleBack = LocalBackPress.current
        val navigator = LocalNavigator.currentOrThrow
        val is24HourSystem = rememberIs24HourSystem()
        val automaticUpdates = context.appGraph.basePreferences.automaticAppUpdates
        val automaticallyUpdate by automaticUpdates.collectAsState()
        val scope = rememberCoroutineScope()
        var checkingForUpdates by remember { mutableStateOf(false) }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.pref_category_about),
                    navigateUp = if (handleBack != null) handleBack::invoke else null,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            ScrollbarLazyColumn(contentPadding = contentPadding) {
                item { LogoHeader(iconPadding = PaddingValues(vertical = 56.dp)) }
                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.version),
                        subtitle = remember(is24HourSystem) { getVersionName(getFormattedBuildTime(context)) },
                        onPreferenceClick = {
                            context.copyToClipboard("Debug information", context.appGraph.crashLogUtil.getDebugInfo())
                        },
                    )
                }
                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.shiman_about_app),
                        subtitle = stringResource(MR.strings.shiman_about_description),
                    )
                }
                if (updaterEnabled) {
                    item {
                        SwitchPreferenceWidget(
                            title = stringResource(MR.strings.yiman_auto_update),
                            subtitle = stringResource(MR.strings.yiman_auto_update_summary),
                            checked = automaticallyUpdate,
                            onCheckedChanged = automaticUpdates::set,
                        )
                    }
                }
                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.check_for_updates),
                        subtitle = stringResource(MR.strings.yiman_update_releases),
                        onPreferenceClick = {
                            if (!updaterEnabled) {
                                uriHandler.openUri(AppUpdateChecker.RELEASES_URL)
                            } else if (!checkingForUpdates) {
                                checkingForUpdates = true
                                scope.launch {
                                    try {
                                        when (val result = context.appGraph.updateChecker.checkForUpdate()) {
                                            is Result.NewUpdate -> {
                                                val release = result.release
                                                if (navigator.items.none { it is NewUpdateScreen }) {
                                                    navigator.push(
                                                        NewUpdateScreen(
                                                            versionName = release.version,
                                                            changelogInfo = release.info,
                                                            releaseLink = release.releaseLink,
                                                            downloadLink = release.downloadLink,
                                                        ),
                                                    )
                                                }
                                            }
                                            Result.NoNewUpdate -> context.toast(MR.strings.update_check_no_new_updates)
                                            Result.OsTooOld -> context.toast(MR.strings.update_check_eol)
                                        }
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (_: Exception) {
                                        context.toast(MR.strings.yiman_update_check_failed)
                                    } finally {
                                        checkingForUpdates = false
                                    }
                                }
                            }
                        },
                    )
                }
                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.shiman_open_source),
                        subtitle = stringResource(MR.strings.shiman_mihon_credit),
                        onPreferenceClick = { uriHandler.openUri("https://github.com/mihonapp/mihon") },
                    )
                }
                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.licenses),
                        onPreferenceClick = { navigator.push(OpenSourceLicensesScreen()) },
                    )
                }
            }
        }
    }

    fun getVersionName(buildTime: String?): String {
        val version = BuildConfig.VERSION_NAME
        return if (buildTime != null) "$version ($buildTime)" else version
    }

    internal fun getFormattedBuildTime(context: Context): String {
        return try {
            val uiPreferences = context.appGraph.uiPreferences
            Instant.parse(BuildConfig.BUILD_TIME)
                .toLocalDateTime(TimeZone.currentSystemDefault())
                .toDateTimestampString(
                    UiPreferences.dateTimeFormat(
                        uiPreferences.dateFormat.get(),
                        uiPreferences.timeFormat.get(),
                        Locale.getDefault(),
                        DateFormat.is24HourFormat(context),
                    ),
                )
        } catch (_: Exception) {
            BuildConfig.BUILD_TIME
        }
    }
}
