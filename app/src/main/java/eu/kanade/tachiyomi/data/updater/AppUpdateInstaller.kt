package eu.kanade.tachiyomi.data.updater

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.pm.PackageInfoCompat
import eu.kanade.tachiyomi.util.system.getParcelableExtraCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.IOException

@Suppress("DEPRECATION")
internal fun Context.verifyAppUpdate(apk: File) {
    val manager = packageManager
    val installed = manager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_SIGNATURES)
    val candidate = manager.getPackageArchiveInfo(apk.path, android.content.pm.PackageManager.GET_SIGNATURES)
    val signatures = installed.signatures.orEmpty().toSet()
    if (
        candidate == null || candidate.packageName != packageName || signatures.isEmpty() ||
        candidate.signatures.orEmpty().toSet() != signatures ||
        PackageInfoCompat.getLongVersionCode(candidate) <= PackageInfoCompat.getLongVersionCode(installed)
    ) {
        throw IOException("The downloaded APK is not a signed upgrade for this app")
    }
}

@SuppressLint("RequestInstallPackagesPolicy")
internal suspend fun Context.installAppUpdate(apk: File) {
    val installer = packageManager.packageInstaller
    val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
        setAppPackageName(packageName)
        setSize(apk.length())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
    }
    val id = installer.createSession(params)
    val action = "$packageName.APP_UPDATE_RESULT.$id"
    try {
        callbackFlow {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != id) return
                    when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                            val confirmation = intent.getParcelableExtraCompat<Intent>(Intent.EXTRA_INTENT)
                            val result = runCatching {
                                requireNotNull(confirmation).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(confirmation)
                            }
                            result.exceptionOrNull()?.let { close(it) }
                        }
                        PackageInstaller.STATUS_SUCCESS, PackageInstaller.STATUS_FAILURE_ABORTED -> trySend(Unit)
                        else -> close(IOException(intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)))
                    }
                }
            }
            ContextCompat.registerReceiver(
                this@installAppUpdate,
                receiver,
                IntentFilter(action),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            try {
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
                val callback = PendingIntent.getBroadcast(
                    this@installAppUpdate,
                    id,
                    Intent(action).setPackage(packageName),
                    flags,
                )
                try {
                    installer.openSession(id).use { session ->
                        apk.inputStream().use { input ->
                            session.openWrite("base.apk", 0, apk.length()).use { output ->
                                input.copyTo(output)
                                session.fsync(output)
                            }
                        }
                        currentCoroutineContext().ensureActive()
                        session.commit(callback.intentSender)
                    }
                    awaitClose()
                } finally {
                    callback.cancel()
                }
            } finally {
                unregisterReceiver(receiver)
            }
        }.flowOn(Dispatchers.IO).first()
    } finally {
        runCatching { installer.abandonSession(id) }
    }
}
