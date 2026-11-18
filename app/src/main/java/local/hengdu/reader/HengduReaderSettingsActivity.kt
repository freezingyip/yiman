package local.hengdu.reader

import android.os.Bundle
import androidx.compose.runtime.CompositionLocalProvider
import eu.kanade.presentation.more.settings.screen.SettingsReaderScreen
import eu.kanade.presentation.util.LocalBackPress
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.util.view.setComposeContent

class HengduReaderSettingsActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setComposeContent {
            CompositionLocalProvider(LocalBackPress provides { finish() }) {
                SettingsReaderScreen.Content()
            }
        }
    }
}
