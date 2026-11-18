package local.hengdu.reader.bridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph

class DebugWebtoonReaderActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = metroGraph<AppGraph>()
        graph.basePreferences.highQualityRenderer.set(intent.getBooleanExtra("gpu", false))
        if (intent.getBooleanExtra("update_only", false)) {
            finish()
            return
        }
        graph.readerPreferences.defaultReadingMode.set(intent.getIntExtra("mode", ReadingMode.WEBTOON.flagValue))
        if (intent.hasExtra("dual_page_always")) {
            graph.readerPreferences.dualPageView.set(
                if (intent.getBooleanExtra("dual_page_always", false)) {
                    ReaderPreferences.DualPageView.ALWAYS
                } else {
                    ReaderPreferences.DualPageView.NEVER
                },
            )
        }
        if (intent.hasExtra("orientation")) {
            graph.readerPreferences.defaultOrientationType.set(intent.getIntExtra("orientation", 0))
        }
        startActivity(Intent(this, DebugReaderBridgeActivity::class.java))
        finish()
    }
}
