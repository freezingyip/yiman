package local.hengdu.reader.bridge;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Synthetic chapter images are generated in the WebView; no source or user data is accessed. */
public final class DebugOnlineTransferActivity extends Activity {
    private static final String URL = "https://reader.local/";
    private OfflineChapterTransfer transfer;
    private WebView web;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        transfer = new OfflineChapterTransfer(this);
        transfer.attach(web);
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (!URL.equals(request.getUrl().toString())) return null;
                String html = "<!doctype html><script>const onlineFetchChapter=async()=>({title:'Online synthetic',nextChapter:'next',images:['one','two']});" +
                    "window.fetch=async function(u){let n=u==='one'?150000:1024,a=new Uint8Array(n);" +
                    "for(let i=0;i<n;i++)a[i]=u==='one'?i%251:255-i%251;" +
                    "return new Response(new Blob([a],{type:'image/png'}),{status:200,headers:{'Content-Type':'image/png'}})};</script>";
                return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
            }
            @Override public void onPageFinished(WebView view, String url) { if (URL.equals(url)) runTransfer(); }
        });
        setContentView(web);
        web.loadUrl(URL);
    }

    private void runTransfer() {
        File destination = new File(getCacheDir(), "synthetic-online-" + UUID.randomUUID());
        transfer.startOnline("https://example.invalid/chapter", destination, new OfflineChapterTransfer.Callback() {
            @Override public void onSuccess(OfflineChapterTransfer.Result result) {
                try {
                    if (result.pageCount != 2 || !"Online synthetic".equals(result.title) || !"next".equals(result.nextChapter)) throw new AssertionError("metadata");
                    check(new File(result.directory, "00000001.png"), 150000, false);
                    check(new File(result.directory, "00000002.png"), 1024, true);
                    Log.i("OnlineTransferTest", "PASS synthetic online images, multi-chunk exact bytes");
                } catch (Throwable e) { Log.e("OnlineTransferTest", "FAIL", e); }
                finally { delete(result.directory); finish(); }
            }
            @Override public void onFailure(String message) { Log.e("OnlineTransferTest", "FAIL " + message); finish(); }
        });
    }

    private static void check(File file, int size, boolean reverse) throws Exception {
        if (file.length() != size) throw new AssertionError("size");
        try (FileInputStream input = new FileInputStream(file)) {
            for (int i = 0; i < size; i++) if (input.read() != (reverse ? 255-i%251 : i%251)) throw new AssertionError("content at " + i);
        }
    }
    private static void delete(File directory) {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) file.delete();
        directory.delete();
    }
    @Override protected void onDestroy() { if (transfer != null) transfer.detach(); if (web != null) web.destroy(); super.onDestroy(); }
}
