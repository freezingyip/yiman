package eu.kanade.tachiyomi.data.updater

import android.os.Build
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import tachiyomi.data.release.GithubRelease
import tachiyomi.domain.release.interactor.GetApplicationRelease.Result
import tachiyomi.domain.release.model.Release

@Inject
class AppUpdateChecker(
    private val network: NetworkHelper,
    private val json: Json,
) {
    suspend fun checkForUpdate(): Result = withContext(Dispatchers.IO) {
        val installed = YimanVersion.parse(BuildConfig.VERSION_NAME) ?: return@withContext Result.NoNewUpdate
        val releases = with(json) {
            network.client.newCall(GET("https://api.github.com/repos/freezingyip/yiman/releases?per_page=30"))
                .awaitSuccess().use { it.parseAs<List<GithubRelease>>() }
        }

        val update = releases.asSequence()
            .filterNot { it.draft }
            .mapNotNull { release ->
                val version = YimanVersion.parse(release.version) ?: return@mapNotNull null
                if (version <= installed) return@mapNotNull null
                val versionName = release.version.removePrefix("v")
                val downloadLink = (Build.SUPPORTED_ABIS.asList() + "universal").firstNotNullOfOrNull { abi ->
                    val name = "yiman-$versionName-$abi.apk"
                    val expectedLink = "$RELEASES_URL/download/${release.version}/$name"
                    release.assets.firstOrNull { it.name == name && it.downloadLink == expectedLink }?.downloadLink
                } ?: return@mapNotNull null
                val releaseLink = "$RELEASES_URL/tag/${release.version}"
                version to Release(release.version, release.info.orEmpty(), releaseLink, downloadLink)
            }
            .maxByOrNull { it.first }?.second
        update?.let { Result.NewUpdate(it) } ?: Result.NoNewUpdate
    }

    companion object {
        const val RELEASES_URL = "https://github.com/freezingyip/yiman/releases"
    }
}

internal data class YimanVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preview: Int?,
) : Comparable<YimanVersion> {
    override fun compareTo(other: YimanVersion): Int = compareValuesBy(
        this,
        other,
        { it.major },
        { it.minor },
        { it.patch },
        { it.preview?.toLong() ?: Long.MAX_VALUE },
    )

    companion object {
        private val pattern = Regex("""v?([0-9]+)\.([0-9]+)\.([0-9]+)(?:-preview\.([0-9]+))?""")

        fun parse(value: String): YimanVersion? {
            val (major, minor, patch, preview) = pattern.matchEntire(value)?.destructured ?: return null
            return YimanVersion(
                major.toIntOrNull() ?: return null,
                minor.toIntOrNull() ?: return null,
                patch.toIntOrNull() ?: return null,
                if (preview.isEmpty()) null else preview.toIntOrNull() ?: return null,
            )
        }
    }
}
