package local.hengdu.reader

import android.os.Bundle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.NavigatorDisposeBehavior
import eu.kanade.presentation.util.DefaultNavigatorScreenTransition
import eu.kanade.tachiyomi.ui.base.activity.BaseActivity
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.util.view.setComposeContent
import mihon.app.di.appGraph

/** Hosts Mihon's existing home screens while the library migration is developed. */
class HengduMihonPreviewActivity : BaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!eu.kanade.tachiyomi.BuildConfig.DEBUG) {
            finish()
            return
        }
        val languages = appGraph.sourcePreferences.enabledLanguages
        if (!languages.isSet()) languages.set(languages.get() + "zh")
        setComposeContent {
            var showNotice by rememberSaveable { mutableStateOf(true) }
            Navigator(
                screen = HomeScreen,
                disposeBehavior = NavigatorDisposeBehavior(disposeNestedNavigators = false, disposeSteps = true),
            ) { navigator ->
                DefaultNavigatorScreenTransition(navigator = navigator)
            }
            if (showNotice) {
                AlertDialog(
                    onDismissRequest = { showNotice = false },
                    title = { Text("Mihon 書架測試版") },
                    text = {
                        Text(
                            "已複製 ${intent.getIntExtra("imported", 0)} 部漫畫人、包子漫畫及漫畫櫃收藏；" +
                                "${intent.getIntExtra("unsupported", 0)} 部作品的來源或資料暫不支援匯入，仍在原書架。\n" +
                                "${intent.getIntExtra("awaitingCatalog", 0)} 部作品的章節清單尚未準備好或包含無法匯入的章節，稍後可從原書架重新整理再試。\n" +
                                "${intent.getIntExtra(
                                    "preservedNative",
                                    0,
                                )} 部作品已有原生資料或已從原生書架刪除；為保留原生變更，沒有自動覆蓋或重新加入。\n\n" +
                                "測試書架的修改和閱讀進度不會同步回原書架，原有離線緩存不會自動轉移。可在原書架選擇複製一話漫畫人緩存測試；副本不會顯示為下載，刪除原緩存時副本也不會自動刪除。",
                        )
                    },
                    confirmButton = { TextButton(onClick = { showNotice = false }) { Text("知道了") } },
                )
            }
        }
    }
}
