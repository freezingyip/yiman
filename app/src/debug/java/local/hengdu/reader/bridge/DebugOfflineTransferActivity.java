package local.hengdu.reader.bridge;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Exercises only a new synthetic IndexedDB database in the emulator. */
public class DebugOfflineTransferActivity extends Activity {
    private static final String TAG = "OfflineTransferTest";
    private static final String URL = "https://reader.local/";
    private static final String CHAPTER = "https://example.invalid/synthetic-chapter";
    private OfflineChapterTransfer transfer;
    private WebView web;
    private String database;
    private boolean testSucceeded;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        database = "hengdu-transfer-test-" + UUID.randomUUID();
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        transfer = new OfflineChapterTransfer(this);
        transfer.attach(web);
        web.addJavascriptInterface(this, "OfflineTestHarness");
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (!URL.equals(request.getUrl().toString())) return null;
                String html = "<!doctype html><script>const dbReady=new Promise((resolve,reject)=>{" +
                    "let req=indexedDB.open('" + database + "',1);" +
                    "req.onupgradeneeded=()=>req.result.createObjectStore('chapters',{keyPath:'url'});" +
                    "req.onerror=()=>reject(req.error);req.onsuccess=()=>{" +
                    "const db=req.result,one=new Uint8Array(150000),two=new Uint8Array(1024);" +
                    "for(let i=0;i<one.length;i++)one[i]=i%251;for(let i=0;i<two.length;i++)two[i]=255-i%251;" +
                    "const tx=db.transaction('chapters','readwrite');" +
                    "tx.objectStore('chapters').put({url:'" + CHAPTER + "',title:'Synthetic',nextChapter:null," +
                    "blobs:[new Blob([one],{type:'image/png'}),new Blob([two],{type:'image/jpeg'})]});" +
                    "tx.oncomplete=()=>resolve(db);tx.onerror=()=>reject(tx.error);};});</script>";
                return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
            }
            @Override public void onPageFinished(WebView view, String url) { if (URL.equals(url)) runTransfer(); }
        });
        setContentView(web);
        web.loadUrl(URL);
    }

    private void runTransfer() {
        File destination = new File(getCacheDir(), "synthetic-transfer-" + UUID.randomUUID());
        transfer.start(CHAPTER, destination, new OfflineChapterTransfer.Callback() {
            @Override public void onSuccess(OfflineChapterTransfer.Result result) {
                try {
                    if (result.pageCount != 2 || !"Synthetic".equals(result.title) || result.nextChapter != null) throw new AssertionError("metadata");
                    check(new File(result.directory, "00000001.png"), 150000, false);
                    check(new File(result.directory, "00000002.jpg"), 1024, true);
                    delete(result.directory);
                    checkMissing();
                } catch (Throwable e) { failed(e); }
            }
            @Override public void onFailure(String message) { failed(new AssertionError(message)); }
        });
    }

    private void checkMissing() {
        File missing = new File(getCacheDir(), "synthetic-missing-" + UUID.randomUUID());
        transfer.start("https://example.invalid/missing", missing, new OfflineChapterTransfer.Callback() {
            @Override public void onSuccess(OfflineChapterTransfer.Result result) { failed(new AssertionError("missing chapter succeeded")); }
            @Override public void onFailure(String message) {
                if (missing.exists()) { failed(new AssertionError("scratch directory retained")); return; }
                testSucceeded = true;
                cleanAndFinish();
            }
        });
    }

    private static void check(File file, int size, boolean reverse) throws Exception {
        if (file.length() != size) throw new AssertionError("size");
        try (FileInputStream input = new FileInputStream(file)) {
            for (int i = 0; i < size; i++) {
                int expected = reverse ? 255 - i % 251 : i % 251;
                if (input.read() != expected) throw new AssertionError("content at " + i);
            }
        }
    }

    private void failed(Throwable e) { Log.e(TAG, "FAIL", e); cleanAndFinish(); }

    private void cleanAndFinish() {
        web.evaluateJavascript("dbReady.then(db=>{db.close();let r=indexedDB.deleteDatabase('" + database + "');" +
            "r.onsuccess=()=>OfflineTestHarness.cleaned(true);r.onerror=()=>OfflineTestHarness.cleaned(false);})" +
            ".catch(()=>OfflineTestHarness.cleaned(false));", null);
    }

    @JavascriptInterface public void cleaned(boolean ok) {
        runOnUiThread(() -> {
            if (ok && testSucceeded) Log.i(TAG, "PASS synthetic IndexedDB, multi-chunk page, exact bytes, missing chapter cleanup");
            else if (!ok) Log.e(TAG, "FAIL synthetic database cleanup");
            finish();
        });
    }
    private static void delete(File directory) {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) file.delete();
        directory.delete();
    }
    @Override protected void onDestroy() {
        if (transfer != null) transfer.detach();
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
