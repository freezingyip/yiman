package local.hengdu.reader

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.widget.Toast
import androidx.core.view.doOnPreDraw
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import java.lang.ref.WeakReference
import java.util.UUID

/** Keeps the current reader visible while the trusted shelf resolves the next chapter. */
object HengduChapterHandoff {
    private var host = WeakReference<MainActivity>(null)
    private var web = WeakReference<WebView>(null)
    private var reader = WeakReference<ReaderActivity>(null)
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { failed("載入章節逾時，請重試。") }
    private var callbacks: Application.ActivityLifecycleCallbacks? = null
    private var handoffToken: String? = null

    private fun removeCallbacks() {
        callbacks?.let { host.get()?.application?.unregisterActivityLifecycleCallbacks(it) }
        callbacks = null
    }

    @JvmStatic fun attach(activity: MainActivity, view: WebView) {
        host = WeakReference(activity)
        web = WeakReference(view)
    }

    @JvmStatic fun detach(activity: MainActivity) {
        if (host.get() === activity) {
            failed("無法載入章節，請重試。")
            host.clear()
            web.clear()
        }
    }

    fun begin(activity: ReaderActivity): Boolean {
        if (eu.kanade.tachiyomi.BuildConfig.DEBUG) android.util.Log.d("HengduHandoff", "begin")
        val owner = host.get() ?: return false
        val view = web.get() ?: return false
        if (owner.isFinishing || owner.isDestroyed ||
            view.url?.let { it != "https://reader.local/" && it != "https://reader.local/index.html" } != false
        ) {
            return false
        }
        reader = WeakReference(activity)
        val token = UUID.randomUUID().toString()
        handoffToken = token
        removeCallbacks()
        val app = owner.application
        val listener = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(next: Activity) {
                if (next !is ReaderActivity || next === activity || reader.get() !== activity ||
                    next.intent.getStringExtra("hengdu_handoff_token") != token
                ) {
                    return
                }
                next.window.decorView.doOnPreDraw {
                    if (reader.get() !== activity || next.isFinishing || next.isDestroyed) return@doOnPreDraw
                    handler.removeCallbacks(timeout)
                    reader.clear()
                    handoffToken = null
                    removeCallbacks()
                    if (!activity.isFinishing && !activity.isDestroyed) activity.finish()
                }
            }
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        callbacks = listener
        app.registerActivityLifecycleCallbacks(listener)
        handler.removeCallbacks(timeout)
        handler.postDelayed(timeout, 125000)
        HengduProgress.deliver(owner, view)
        return true
    }

    fun opened() {
        // startActivity returns before the new reader has drawn. The lifecycle callback
        // keeps the old reader in place until its replacement is ready to render.
    }

    fun currentToken(): String? = handoffToken

    fun startReader(owner: Activity, intent: Intent, requestToken: String?) {
        if (eu.kanade.tachiyomi.BuildConfig.DEBUG) {
            android.util.Log.d(
                "HengduHandoff",
                "startReader token=${requestToken != null} active=${handoffToken != null}",
            )
        }
        if (requestToken != null) check(handoffToken == requestToken) { "章節切換已取消" }
        val previous = reader.get()
        val token = handoffToken
        if (previous != null && token != null) {
            check(!previous.isFinishing && !previous.isDestroyed) { "閱讀已結束" }
            // CLEAR_TOP would destroy the previous reader before the new one can draw.
            intent.removeFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.putExtra("hengdu_handoff_token", token)
            previous.startActivity(intent)
        } else {
            check(requestToken == null) { "章節切換已取消" }
            owner.startActivity(intent)
        }
    }

    fun failed(message: String) {
        handler.removeCallbacks(timeout)
        removeCallbacks()
        val previous = reader.get()
        reader.clear()
        handoffToken = null
        if (previous != null && !previous.isFinishing && !previous.isDestroyed) {
            HengduProgress.discardChapterRequest(previous)
            previous.hengduChapterHandoffFailed()
            Toast.makeText(previous, message, Toast.LENGTH_LONG).show()
        }
    }

    fun isPending(): Boolean = reader.get()?.let { !it.isFinishing && !it.isDestroyed } == true
}
