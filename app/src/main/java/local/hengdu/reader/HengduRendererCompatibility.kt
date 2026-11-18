package local.hengdu.reader

import android.content.Context
import android.content.pm.PackageManager

/** Dawn's Vulkan backend requires Vulkan 1.1 or newer. */
object HengduRendererCompatibility {
    private const val VULKAN_1_1 = (1 shl 22) or (1 shl 12)

    fun supportsHighQuality(context: Context): Boolean = context.packageManager.systemAvailableFeatures
        .any { it.name == PackageManager.FEATURE_VULKAN_HARDWARE_VERSION && it.version >= VULKAN_1_1 }
}
