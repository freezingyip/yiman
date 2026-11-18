package local.hengdu.reader

import eu.kanade.tachiyomi.source.model.SManga
import org.jsoup.Jsoup
import java.io.IOException

internal object BaoziPopularParser {
    fun parse(html: String, base: String): List<SManga> {
        val section = Jsoup.parse(html, base).selectFirst(".index-rank.index-recommend-items")
            ?: throw IOException("找不到包子漫畫熱門列表")
        return BaoziSourceParser.search(section.outerHtml(), base)
    }
}
