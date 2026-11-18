package local.hengdu.reader.bridge;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import local.hengdu.reader.HengduOfflineEntry;
import local.hengdu.reader.HengduProgress;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Isolated synthetic chapter to exercise the offline-to-reader integration. */
public final class DebugReaderBridgeActivity extends Activity {
    private static final String URL = "https://reader.local/";
    private WebView web;
    private HengduOfflineEntry entry;
    private String database;
    private boolean pageLoaded;
    private int testPage;
    private boolean wideLastPage;
    private boolean rejectNext;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        testPage = Math.max(0, getIntent().getIntExtra("test_page", 0));
        wideLastPage = getIntent().getBooleanExtra("wide_last_page", false);
        rejectNext = getIntent().getBooleanExtra("reject_next", false);
        database = "hengdu-reader-test-" + UUID.randomUUID();
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setDomStorageEnabled(true);
        entry = new HengduOfflineEntry(this, web);
        web.addJavascriptInterface(entry, "HengduOfflineEntry");
        web.addJavascriptInterface(this, "ReaderTestHarness");
        web.setWebViewClient(new WebViewClient() {
            @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (!URL.equals(request.getUrl().toString())) return null;
                String html = "<!doctype html><meta name='viewport' content='width=device-width'><p id='result'>Preparing test…</p><script>" +
                    "const dbReady=new Promise((resolve,reject)=>{let r=indexedDB.open('" + database + "',1);" +
                    "r.onupgradeneeded=()=>r.result.createObjectStore('chapters',{keyPath:'url'});r.onerror=()=>reject(r.error);" +
                    "r.onsuccess=async()=>{try{let db=r.result,blobs=[];for(let i=0;i<3;i++){let c=document.createElement('canvas');" +
                    "c.width=(i===2&&" + wideLastPage + ")?960:320;c.height=480;let g=c.getContext('2d');g.fillStyle=['#e66','#6a6','#66e'][i];g.fillRect(0,0,c.width,480);" +
                    "g.fillStyle='white';g.font='48px sans-serif';g.fillText('PAGE '+(i+1),60,240);" +
                    "blobs.push(await new Promise(ok=>c.toBlob(ok,'image/png')))}let tx=db.transaction('chapters','readwrite');" +
                    "tx.objectStore('chapters').put({url:'https://example.invalid/chapter0',title:'Synthetic chapter 0',nextChapter:'https://example.invalid/chapter',blobs});" +
                    "tx.objectStore('chapters').put({url:'https://example.invalid/chapter',title:'Synthetic chapter',nextChapter:'https://example.invalid/chapter2',blobs});" +
                    "tx.objectStore('chapters').put({url:'https://example.invalid/chapter2',title:'Synthetic chapter 2',nextChapter:null,blobs});" +
                    "tx.oncomplete=()=>resolve(db);tx.onerror=()=>reject(tx.error)}catch(e){reject(e)}}});" +
                    "window.hengduApplyNativeProgress=e=>{ReaderTestHarness.progress(JSON.stringify(e));document.getElementById('result').textContent=JSON.stringify(e);return true};" +
                    "window.hengduNativeNextChapter=e=>{ReaderTestHarness.next(JSON.stringify(e));if(" + rejectNext + "||e.bookUrl!=='https://example.invalid/book'||!['https://example.invalid/chapter0','https://example.invalid/chapter','https://example.invalid/chapter2'].includes(e.chapterUrl))return false;setTimeout(()=>HengduOfflineEntry.open(e.bookUrl,'Synthetic book',e.chapterUrl,e.chapterUrl.endsWith('chapter0')?'Synthetic chapter 0':e.chapterUrl.endsWith('chapter2')?'Synthetic chapter 2':'Synthetic chapter',e.resume?1:0),0);return true};" +
                    "window.addEventListener('hengdu-native-error',e=>ReaderTestHarness.failure(String(e.detail)));" +
                    "dbReady.then(()=>{HengduOfflineEntry.setCatalog('https://example.invalid/book',JSON.stringify([{url:'https://example.invalid/chapter0',title:'Synthetic chapter 0'},{url:'https://example.invalid/chapter',title:'Synthetic chapter'},{url:'https://example.invalid/chapter2',title:'Synthetic chapter 2'}]));HengduOfflineEntry.open('https://example.invalid/book','Synthetic book','https://example.invalid/chapter','Synthetic chapter'," + testPage + ")}).catch(e=>ReaderTestHarness.failure(String(e)));</script>";
                return new WebResourceResponse("text/html", "UTF-8", new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
            }
            @Override public void onPageFinished(WebView view, String url) { pageLoaded = true; HengduProgress.deliver(DebugReaderBridgeActivity.this, view); }
        });
        setContentView(web);
        web.loadUrl(URL);
    }

    @JavascriptInterface public void progress(String value) { Log.i("ReaderBridgeTest", "PROGRESS " + value); }
    @JavascriptInterface public void next(String value) { Log.i("ReaderBridgeTest", "NEXT " + value); }
    @JavascriptInterface public void failure(String value) { Log.e("ReaderBridgeTest", "FAIL " + value); }
    @Override protected void onResume() { super.onResume(); if (pageLoaded) HengduProgress.deliver(this, web); }
    @Override protected void onDestroy() { if (entry != null) entry.cancel(); if (web != null) web.destroy(); super.onDestroy(); }
}
