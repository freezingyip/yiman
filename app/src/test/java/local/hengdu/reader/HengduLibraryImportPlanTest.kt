package local.hengdu.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class HengduLibraryImportPlanTest {
    @Test
    fun `delays only new books without a chapter list`() {
        HengduLibraryImportPlan.decide(false, false, false) shouldBe HengduLibraryImportPlan.Action.AwaitCatalog
        HengduLibraryImportPlan.decide(false, false, true) shouldBe HengduLibraryImportPlan.Action.Import
    }

    @Test
    fun `preserves existing native entries and never resurrects deleted ones`() {
        HengduLibraryImportPlan.decide(false, true, true) shouldBe HengduLibraryImportPlan.Action.PreserveNative
        HengduLibraryImportPlan.decide(false, true, false) shouldBe HengduLibraryImportPlan.Action.PreserveNative
        HengduLibraryImportPlan.decide(true, false, true) shouldBe HengduLibraryImportPlan.Action.PreserveNative
        HengduLibraryImportPlan.decide(true, false, false) shouldBe HengduLibraryImportPlan.Action.PreserveNative
        HengduLibraryImportPlan.decide(true, true, false) shouldBe HengduLibraryImportPlan.Action.AlreadyImported
    }
}
