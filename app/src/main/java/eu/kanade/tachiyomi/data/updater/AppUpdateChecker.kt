package eu.kanade.tachiyomi.data.updater

import dev.zacsweers.metro.Inject
import tachiyomi.domain.release.interactor.GetApplicationRelease

@Inject
class AppUpdateChecker {
    @Suppress("UNUSED_PARAMETER")
    suspend fun checkForUpdate(forceCheck: Boolean = false): GetApplicationRelease.Result {
        throw IllegalStateException("App update repository is not configured")
    }
}
