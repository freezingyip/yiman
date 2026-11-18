package local.hengdu.reader

internal object HengduLibraryImportPlan {
    enum class Action { Import, AlreadyImported, AwaitCatalog, PreserveNative }

    fun decide(alreadyImported: Boolean, nativeExists: Boolean, hasChapters: Boolean): Action = when {
        alreadyImported && nativeExists -> Action.AlreadyImported
        alreadyImported || nativeExists -> Action.PreserveNative
        !hasChapters -> Action.AwaitCatalog
        else -> Action.Import
    }
}
