package local.hengdu.reader.bridge;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Copies one existing IndexedDB chapter into a new private scratch directory. */
public final class OfflineChapterTransfer {
    private static final String INTERFACE = "HengduOfflineNative";
    private static final int CHUNK = 64 * 1024;
    private static final long MAX_PAGE = 32L * 1024 * 1024;
    private static final long MAX_CHAPTER = 500L * 1024 * 1024;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private WebView webView;
    private Callback callback;
    private File directory;
    private String token;
    private long[] sizes;
    private String[] types;
    private long totalSize;
    private int page;
    private long offset;
    private FileOutputStream output;
    private String title;
    private String nextChapter;
    private boolean finished;
    private boolean onlineTransfer;
    private final Runnable timeout = () -> fail("讀取緩存逾時。");

    public interface Callback {
        void onSuccess(Result result);
        void onFailure(String message);
    }

    public static final class Result {
        public final File directory;
        public final String title;
        public final String nextChapter;
        public final int pageCount;
        Result(File directory, String title, String nextChapter, int pageCount) {
            this.directory = directory;
            this.title = title;
            this.nextChapter = nextChapter;
            this.pageCount = pageCount;
        }
    }

    public OfflineChapterTransfer(Context context) {
        this.context = context.getApplicationContext();
    }

    /** Register before the trusted local page is first loaded. */
    public synchronized void attach(WebView view) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Main thread required");
        if (webView != null) throw new IllegalStateException("Already attached");
        webView = view;
        view.addJavascriptInterface(this, INTERFACE);
    }

    /** Must run on the main thread; the destination must not already exist. */
    public synchronized void start(String chapterUrl, File destination, Callback callback) {
        start(chapterUrl, destination, callback, false);
    }

    public synchronized void startOnline(String chapterUrl, File destination, Callback callback) {
        start(chapterUrl, destination, callback, true);
    }

    private void start(String chapterUrl, File destination, Callback callback, boolean online) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Main thread required");
        if (this.callback != null) throw new IllegalStateException("A transfer is already running");
        if (webView == null) throw new IllegalStateException("Attach before loading the page");
        if (callback == null) throw new IllegalArgumentException("Callback required");
        WebView view = webView;
        finished = false;
        onlineTransfer = online;
        sizes = null;
        types = null;
        totalSize = 0;
        page = 0;
        offset = 0;
        title = null;
        nextChapter = null;
        directory = null;
        this.callback = callback;
        try {
            Uri origin = Uri.parse(view.getUrl() == null ? "" : view.getUrl());
            if (!"https".equals(origin.getScheme()) || !"reader.local".equals(origin.getHost()) ||
                    !("/".equals(origin.getPath()) || "/index.html".equals(origin.getPath()))) {
                throw new IllegalArgumentException("不可信的閱讀器頁面。");
            }
            if (chapterUrl == null || chapterUrl.isEmpty()) throw new IllegalArgumentException("缺少章節網址。");
            File cache = context.getCacheDir().getCanonicalFile();
            File target = destination.getCanonicalFile();
            if (!target.getPath().startsWith(cache.getPath() + File.separator) || !target.mkdir()) {
                throw new IllegalArgumentException("無法建立新的私人暫存資料夾。");
            }
            directory = target;
            token = UUID.randomUUID().toString();
            String prototype;
            try (InputStream input = context.getAssets().open(online ? "hengdu-online-page-bridge.js" : "hengdu-offline-page-bridge.js");
                 ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int n;
                while ((n = input.read(buffer)) != -1) bytes.write(buffer, 0, n);
                prototype = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
            }
            String script = online ? prototype + "\n;HengduOnlinePageBridge.run(" + JSONObject.quote(chapterUrl) + "," + JSONObject.quote(token) + ",HengduOfflineNative,onlineFetchChapter,fetch);" : prototype + "\n;(async function(){const t=" + JSONObject.quote(token) +
                ",u=" + JSONObject.quote(chapterUrl) + ";try{const b=HengduOfflinePageBridge.create(dbReady);" +
                "const m=await b.descriptor(u);if(!HengduOfflineNative.begin(t,JSON.stringify(m)))return;" +
                "for(let p=0;p<m.pages.length;p++){let o=0;while(o<m.pages[p].size){" +
                "const c=await b.readChunk(u,p,o,65536);" +
                "if(!HengduOfflineNative.append(t,p,o,c.data,c.done))return;o=c.nextOffset;}}" +
                "HengduOfflineNative.complete(t);}catch(e){HengduOfflineNative.error(t,String(e&&e.message||e));}})();";
            view.evaluateJavascript(script, null);
            renewTimeout();
        } catch (Exception e) {
            fail(e.getMessage());
        }
    }

    @JavascriptInterface public synchronized boolean ping(String received) {
        if (!valid(received)) return false;
        renewTimeout();
        return true;
    }

    @JavascriptInterface public synchronized boolean beginOnline(String received, String metadata) {
        if (!valid(received)) return false;
        try {
            JSONObject data = new JSONObject(metadata);
            int count = data.getInt("pageCount");
            if (count > 1000) throw new LimitExceeded();
            if (count < 1 || sizes != null) throw new IllegalArgumentException();
            sizes = new long[count];
            types = new String[count];
            title = data.optString("title", "");
            nextChapter = data.isNull("nextChapter") ? null : data.optString("nextChapter", null);
            renewTimeout();
            return true;
        } catch (LimitExceeded e) {
            fail("章節超過閱讀器的限制：最多 1000 頁、每頁 32 MB、每章 500 MB。");
        } catch (Exception e) {
            fail("無法識別線上章節。");
        }
        return false;
    }

    @JavascriptInterface public synchronized boolean beginOnlinePage(String received, int pageIndex, long size, String type) {
        if (!valid(received)) return false;
        try {
            if (sizes == null || pageIndex != page || page >= sizes.length || sizes[page] != 0 || size <= 0) throw new IllegalArgumentException();
            if (size > MAX_PAGE || totalSize + size > MAX_CHAPTER) throw new LimitExceeded();
            sizes[page] = size;
            types[page] = type == null ? "" : type;
            totalSize += size;
            renewTimeout();
            return true;
        } catch (LimitExceeded e) {
            fail("章節超過閱讀器的限制：最多 1000 頁、每頁 32 MB、每章 500 MB。");
        } catch (Exception e) {
            fail("無法識別線上圖片。");
        }
        return false;
    }

    @JavascriptInterface public synchronized boolean begin(String received, String metadata) {
        if (!valid(received)) return false;
        try {
            JSONObject data = new JSONObject(metadata);
            JSONArray pages = data.getJSONArray("pages");
            if (pages.length() > 1000) throw new LimitExceeded();
            if (pages.length() == 0 || sizes != null) throw new IllegalArgumentException();
            sizes = new long[pages.length()];
            types = new String[pages.length()];
            for (int i = 0; i < pages.length(); i++) {
                JSONObject item = pages.getJSONObject(i);
                sizes[i] = item.getLong("size");
                types[i] = item.optString("type", "");
                if (sizes[i] <= 0) throw new IllegalArgumentException();
                if (sizes[i] > MAX_PAGE || (totalSize += sizes[i]) > MAX_CHAPTER) throw new LimitExceeded();
            }
            title = data.optString("title", "");
            nextChapter = data.isNull("nextChapter") ? null : data.optString("nextChapter", null);
            renewTimeout();
            return true;
        } catch (LimitExceeded e) {
            fail("章節超過閱讀器的限制：最多 1000 頁、每頁 32 MB、每章 500 MB。");
            return false;
        } catch (Exception e) {
            fail("緩存章節資料無效。");
            return false;
        }
    }

    @JavascriptInterface public synchronized boolean append(String received, int pageIndex, long chunkOffset, String encoded, boolean done) {
        if (!valid(received)) return false;
        try {
            if (sizes == null || page >= sizes.length || pageIndex != page || chunkOffset != offset || encoded == null || encoded.length() > ((CHUNK + 2) / 3) * 4) throw new IllegalArgumentException();
            byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
            if (bytes.length == 0 || bytes.length > CHUNK || bytes.length > sizes[page] - offset || done != (offset + bytes.length == sizes[page])) throw new IllegalArgumentException();
            if (output == null) output = new FileOutputStream(new File(directory, String.format(java.util.Locale.ROOT, "%08d.%s", page + 1, extension(types[page]))));
            output.write(bytes);
            offset += bytes.length;
            if (done) {
                output.close();
                output = null;
                offset = 0;
                page++;
            }
            renewTimeout();
            return true;
        } catch (Exception e) {
            fail("讀取緩存圖片失敗。");
            return false;
        }
    }

    @JavascriptInterface public synchronized void complete(String received) {
        if (!valid(received)) return;
        if (sizes == null || page != sizes.length || output != null) {
            fail("緩存圖片不完整。");
            return;
        }
        finished = true;
        Result result = new Result(directory, title, nextChapter, page);
        Callback done = callback;
        cleanup(false);
        main.post(() -> done.onSuccess(result));
    }

    @JavascriptInterface public synchronized void error(String received, String ignored) {
        if (valid(received)) fail(onlineTransfer ? "無法載入線上章節或圖片，請檢查網絡或來源。" : "無法讀取已緩存的章節。");
    }

    public synchronized void cancel() { fail("已取消讀取緩存。"); }

    /** Detach when the owning WebView is destroyed. */
    public synchronized void detach() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Main thread required");
        if (callback != null) fail("已取消讀取緩存。");
        if (webView != null) webView.removeJavascriptInterface(INTERFACE);
        webView = null;
    }

    private boolean valid(String received) { return !finished && token != null && token.equals(received); }

    private void renewTimeout() {
        main.removeCallbacks(timeout);
        main.postDelayed(timeout, 30000);
    }

    private synchronized void fail(String message) {
        if (finished || callback == null) return;
        finished = true;
        Callback done = callback;
        cleanup(true);
        main.post(() -> done.onFailure(message == null ? "讀取緩存失敗。" : message));
    }

    private void cleanup(boolean removeDirectory) {
        main.removeCallbacks(timeout);
        try { if (output != null) output.close(); } catch (Exception ignored) { }
        output = null;
        if (removeDirectory && directory != null) {
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) file.delete();
            directory.delete();
        }
        callback = null;
        token = null;
    }

    private static String extension(String type) {
        switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "image/png": return "png";
            case "image/webp": return "webp";
            case "image/gif": return "gif";
            case "image/bmp": return "bmp";
            case "image/avif": return "avif";
            default: return "jpg";
        }
    }

    private static final class LimitExceeded extends Exception { }
}
