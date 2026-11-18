package eu.kanade.tachiyomi.data.updater

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class YimanVersionTest {
    @Test
    fun `versions sort previews numerically before the corresponding stable release`() {
        val tags = listOf("v0.1.0-preview.3", "v0.1.0-preview.10", "v0.1.0", "v0.1.1-preview.1", "v1.0.0")

        tags.shuffled().map { checkNotNull(YimanVersion.parse(it)) }.sorted() shouldBe tags.map(YimanVersion::parse)
        YimanVersion.parse("0.1.0-preview.3") shouldBe YimanVersion.parse("v0.1.0-preview.3")
    }

    @Test
    fun `unrelated and malformed tags do not identify updates`() {
        listOf("r123", "v0.1.0-dev", "v0.1.0-preview.", "v0.1.0-preview.9999999999").forEach {
            YimanVersion.parse(it) shouldBe null
        }
    }
}
