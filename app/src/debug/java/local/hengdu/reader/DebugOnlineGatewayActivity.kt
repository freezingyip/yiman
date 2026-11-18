package local.hengdu.reader

import android.app.Activity
import android.os.Bundle
import android.util.Log

/** Exercises only a synthetic manifest in the separate debug application. */
class DebugOnlineGatewayActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = "hengdu-test/online-restart"
        val images = listOf("/proxy?url=https%3A%2F%2Fwww.manhuaren.com%2Fsynthetic.png")
        try {
            val prefs = getSharedPreferences("hengdu_gateway_test", MODE_PRIVATE)
            if (intent.getStringExtra("phase") == "write") {
                val token = HengduOnlineGateway.registerChapter(this, url, images)
                check(prefs.edit().putString("token", token).commit())
                Log.i("HengduGatewayTest", "WRITE PASS")
            } else {
                check(HengduOnlineGateway.hasChapter(this, url))
                check(HengduOnlineGateway.pages(this, url) == images)
                val token = checkNotNull(prefs.getString("token", null))
                HengduOnlineGateway.removeChapter(this, url, "not-the-current-token")
                check(HengduOnlineGateway.hasChapter(this, url))
                HengduOnlineGateway.removeChapter(this, url, token)
                check(!HengduOnlineGateway.hasChapter(this, url))
                prefs.edit().remove("token").commit()
                Log.i("HengduGatewayTest", "RESTART PASS")
            }
        } catch (e: Exception) {
            Log.e("HengduGatewayTest", "FAIL", e)
        }
        finish()
    }
}
