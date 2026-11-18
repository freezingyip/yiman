package eu.kanade.presentation.more.settings.screen.about

import android.content.Context
import android.text.format.DateFormat
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.LogoHeader
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.rememberIs24HourSystem
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.lang.toDateTimestampString
import eu.kanade.tachiyomi.util.system.copyToClipboard
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
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
                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.shiman_github_account),
                        subtitle = "freezingyip",
                        onPreferenceClick = { uriHandler.openUri("https://github.com/freezingyip") },
                    )
                }
                item {
                    TextPreferenceWidget(
                        title = stringResource(MR.strings.check_for_updates),
                        subtitle = stringResource(MR.strings.shiman_update_unconfigured),
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
