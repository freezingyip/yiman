package local.hengdu.reader;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;

public final class MainActivity extends Activity {
  private static final String ORIGIN = "https://reader.local";
  private WebView view;
  private WebView verificationView;
  private FrameLayout root;
  private HengduOfflineEntry offlineEntry;
  private LinearLayout verificationContainer;
  private String mh160UserAgent;
  private boolean readerLandscape;
  private int previousOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

  private boolean isReaderPage() {
    Uri current = Uri.parse(view == null || view.getUrl() == null ? "" : view.getUrl());
    return "https".equals(current.getScheme()) && "reader.local".equals(current.getHost()) && ("/".equals(current.getPath()) || "/index.html".equals(current.getPath()));
  }

  // Only reader.local has this bridge. The separate website WebView has no native bridge.
  private final class SourceVerification {
    @JavascriptInterface public void openMh160() {
      runOnUiThread(() -> {
        if (!isFinishing() && isReaderPage() && verificationView == null) showMh160Verification();
      });
    }
  }

  private void showMh160Verification() {
    verificationContainer = new LinearLayout(this);
    verificationContainer.setOrientation(LinearLayout.VERTICAL);
    verificationContainer.setBackgroundColor(Color.rgb(24, 24, 28));
    LinearLayout bar = new LinearLayout(this);
    bar.setGravity(Gravity.CENTER_VERTICAL);
    TextView label = new TextView(this);
    label.setText("漫畫160 網站驗證");
    label.setTextColor(Color.WHITE);
    label.setPadding(20, 12, 10, 12);
    bar.addView(label, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
    Button close = new Button(this);
    close.setText("返回閱讀器");
    close.setOnClickListener(v -> closeMh160Verification());
    bar.addView(close);
    verificationContainer.addView(bar);
    verificationView = new WebView(this);
    WebSettings settings = verificationView.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setJavaScriptCanOpenWindowsAutomatically(false);
    settings.setSupportMultipleWindows(false);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    settings.setUserAgentString(mh160UserAgent);
    CookieManager.getInstance().setAcceptThirdPartyCookies(verificationView, true);
    verificationView.setWebChromeClient(new WebChromeClient());
    verificationView.setWebViewClient(new WebViewClient() {
      @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
        Uri u = r.getUrl();
        // Site navigation is limited to HTTPS on this source. Challenge resources
        // can still load in subframes, without a native JavaScript interface.
        if (!r.isForMainFrame()) return false;
        return !"https".equalsIgnoreCase(u.getScheme()) || u.getPort() != -1 && u.getPort() != 443 || !"www.mh160mh.com".equalsIgnoreCase(u.getHost());
      }
    });
    verificationContainer.addView(verificationView, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
    root.addView(verificationContainer, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    // The challenged endpoint is necessary: the website's home page may not trigger verification.
    verificationView.loadUrl("https://www.mh160mh.com/statics/searchelxt1e1.aspx?key=one");
  }

  private void closeMh160Verification() {
    if (verificationView == null) return;
    verificationView.stopLoading();
    verificationContainer.removeView(verificationView);
    verificationView.destroy();
    verificationView = null;
    root.removeView(verificationContainer);
    verificationContainer = null;
    CookieManager.getInstance().flush();
    if (isReaderPage()) view.evaluateJavascript("window.dispatchEvent(new Event('mh160-verification-closed'))", null);
  }

  // This exposes only the reader's orientation toggle, not a general native API.
  private final class ReaderOrientation {
    @JavascriptInterface public void setLandscape(boolean enabled) {
      runOnUiThread(() -> {
        if (view == null || isFinishing()) return;
        Uri current = Uri.parse(view.getUrl() == null ? "" : view.getUrl());
        if (!"https".equals(current.getScheme()) || !"reader.local".equals(current.getHost()) || !("/".equals(current.getPath()) || "/index.html".equals(current.getPath()))) return;
        if (enabled && !readerLandscape) {
          previousOrientation = getRequestedOrientation();
          readerLandscape = true;
          setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else if (!enabled && readerLandscape) {
          readerLandscape = false;
          setRequestedOrientation(previousOrientation);
        }
      });
    }
  }
  @Override public void onCreate(Bundle state) {
    super.onCreate(state);
    eu.kanade.tachiyomi.ui.reader.loader.HengduOnlinePageLoader.cleanupStale(this);
    getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    getWindow().setStatusBarColor(Color.BLACK);
    getWindow().setNavigationBarColor(Color.BLACK);
    view = new WebView(this);
    view.setBackgroundColor(Color.BLACK);
    WebSettings settings = view.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setJavaScriptCanOpenWindowsAutomatically(false);
    settings.setSupportMultipleWindows(false);
    mh160UserAgent = settings.getUserAgentString();
    HengduOnlineGateway.attach(this);
    view.addJavascriptInterface(new ReaderOrientation(), "ReaderOrientation");
    view.addJavascriptInterface(new SourceVerification(), "SourceVerification");
    offlineEntry = new HengduOfflineEntry(this, view);
    HengduChapterHandoff.attach(this, view);
    view.addJavascriptInterface(offlineEntry, "HengduOfflineEntry");
    view.setWebChromeClient(new WebChromeClient());
    view.setWebViewClient(new WebViewClient() {
      @Override public void onPageFinished(WebView v, String url) {
        super.onPageFinished(v, url);
        if (isReaderPage()) HengduProgress.deliver(MainActivity.this, v);
        if (eu.kanade.tachiyomi.BuildConfig.DEBUG && isReaderPage() && getIntent().getBooleanExtra("hengdu_test_enable_reader", false)) {
          getIntent().removeExtra("hengdu_test_enable_reader");
          v.evaluateJavascript("localStorage.setItem('hengdu-native-experiment','1')", null);
        }
        if (eu.kanade.tachiyomi.BuildConfig.DEBUG && isReaderPage() && getIntent().getBooleanExtra("hengdu_test_online", false)) {
          getIntent().removeExtra("hengdu_test_online");
          v.evaluateJavascript("activeBook={url:'https://www.manhuagui.com/comic/1128/',title:'ONE PIECE',source:'漫畫櫃'};HengduOfflineEntry.openOnline(activeBook.url,activeBook.title,'https://www.manhuagui.com/comic/1128/9771.html','第01卷',0)", null);
        }
      }
      @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
        Uri u=r.getUrl();
        if ("https".equals(u.getScheme()) && "reader.local".equals(u.getHost())) return !("/".equals(u.getPath()) || "/index.html".equals(u.getPath()));
        if (r.isForMainFrame() && "https".equals(u.getScheme())) {
          try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) {}
        }
        return true;
      }
      @Override public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
        Uri u=r.getUrl();
        if (!"https".equals(u.getScheme()) || !"reader.local".equals(u.getHost()) || !"GET".equals(r.getMethod())) return denied();
        try {
          if ("/".equals(u.getPath()) || "/index.html".equals(u.getPath())) {
            java.util.Map<String, String> headers = new java.util.HashMap<>();
            // The narrow native bridge is available only to this local page. Prevent
            // remote frames from loading inside it, where WebView bridges are inherited.
            headers.put("Content-Security-Policy", "frame-src 'none'; object-src 'none'");
            headers.put("X-Content-Type-Options", "nosniff");
            return new WebResourceResponse("text/html", "UTF-8", 200, "OK", headers, getAssets().open("index.html"));
          }
          if (("/manhuaren.js".equals(u.getPath()) || "/kuaikan.js".equals(u.getPath()) || "/manhuagui.js".equals(u.getPath()) || "/boylove.js".equals(u.getPath()) || "/c18h.js".equals(u.getPath()) || "/yymh.js".equals(u.getPath()) || "/kanman.js".equals(u.getPath()) || "/manwa.js".equals(u.getPath()) || "/mh160.js".equals(u.getPath())) && !r.isForMainFrame()) return new WebResourceResponse("application/javascript", "UTF-8", getAssets().open(u.getPath().substring(1)));
          if ("/proxy".equals(u.getPath()) && !r.isForMainFrame()) return proxy(u.getQueryParameter("url"), u.getQueryParameter("ref"));
        } catch (Exception ignored) {}
        return denied();
      }
    });
    root = new FrameLayout(this);
    root.addView(view, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    setContentView(root);
    view.loadUrl(ORIGIN + "/");
  }
  private static boolean allowed(URL u) {
    if (!"https".equalsIgnoreCase(u.getProtocol()) || u.getUserInfo()!=null || (u.getPort()!=-1 && u.getPort()!=443)) return false;
    String h=u.getHost().toLowerCase(Locale.ROOT);
    for (String d : new String[]{"baozimh.com","baozimh.org","baozimh.net","baoziac.com","bzmgcn.com","twmanga.com","twbzmg.com","baozicdn.com","bzcdn.net","6wm.top","manhuaren.com","cdndm5.com","kuaikanmanhua.com","kkmh.com","v3mh.com","manhuagui.com","mhgui.com","hamreus.com","boylove.cc","mm-cg.com","imgstream2.com","imgstream3.com","yymh889.com","yymhimg1.top","kanman.com","yqmh.com","kaimanhua.com","manwa.me","mwappimgs.cc","mh160mh.com","miyeye.cn","mhpic5er.tgmhfc.uk","mhpic6.tgmhfc.uk"})
      if (h.equals(d) || h.endsWith("."+d)) return true;
    return false;
  }
  private static WebResourceResponse denied() {
    return new WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", Collections.emptyMap(), new ByteArrayInputStream(new byte[0]));
  }
  private static boolean isBaoziWebsite(String host) {
    String h = host.toLowerCase(Locale.ROOT);
    for (String domain : new String[]{"baozimh.com", "baozimh.org", "baozimh.net", "baoziac.com", "bzmgcn.com", "twmanga.com", "twbzmg.com"})
      if (h.equals(domain) || h.endsWith("." + domain)) return true;
    return false;
  }
  WebResourceResponse proxy(String raw, String ref) {
    return proxy(raw, ref, mh160UserAgent);
  }
  String sourceUserAgent() { return mh160UserAgent; }
  static WebResourceResponse proxy(String raw, String ref, String mh160UserAgent) {
    try {
      URL url=new URL(raw);
      for (int i=0;i<5;i++) {
        if (!allowed(url)) return denied();
        HttpURLConnection c=(HttpURLConnection)url.openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(12000); c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36");
        if (url.getHost().equalsIgnoreCase("manhuaren.com") || url.getHost().toLowerCase(Locale.ROOT).endsWith(".manhuaren.com"))
          c.setRequestProperty("Accept-Language", "zh-TW,zh;q=0.9,en;q=0.8");
        // The mobile site redirects through HTTP; use its HTTPS desktop pages instead.
        if ("www.manhuagui.com".equalsIgnoreCase(url.getHost()) && (url.getPath().startsWith("/comic/") || url.getPath().startsWith("/rank/") || url.getPath().startsWith("/s/")))
          c.setRequestProperty("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36");
        if ("www.mh160mh.com".equalsIgnoreCase(url.getHost())) {
          c.setRequestProperty("User-Agent", mh160UserAgent);
          // Re-evaluate after each redirect; never forward this site's cookies to a CDN or another domain.
          String cookie = CookieManager.getInstance().getCookie(url.toString());
          if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
        }
        if (isBaoziWebsite(url.getHost())) {
          String cookie = CookieManager.getInstance().getCookie(url.toString());
          if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
        }
        if (url.getHost().equals("manwa.me") || url.getHost().endsWith(".manwa.me") || url.getHost().equals("mwappimgs.cc") || url.getHost().endsWith(".mwappimgs.cc")) c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36");
        if ("manwa.me".equalsIgnoreCase(url.getHost())) {
          String cookie = CookieManager.getInstance().getCookie(url.toString());
          if (cookie != null && !cookie.isEmpty()) c.setRequestProperty("Cookie", cookie);
        }
        String referer = "https://www.twmanga.com/";
        if (url.getHost().endsWith("cdndm5.com") || url.getHost().endsWith("manhuaren.com")) {
          referer = "https://www.manhuaren.com/";
          if (ref != null && ref.matches("https://www\\.manhuaren\\.com/m[0-9]+/")) referer = ref;
        }
        if (url.getHost().endsWith("boylove.cc")) referer = "https://boylove.cc/";
        if (url.getHost().endsWith("mm-cg.com") || url.getHost().endsWith("imgstream2.com") || url.getHost().endsWith("imgstream3.com")) referer = "https://18h.mm-cg.com/";
        if (url.getHost().endsWith("yymh889.com") || url.getHost().endsWith("yymhimg1.top")) referer = "https://yymh889.com/";
        if (url.getHost().endsWith("kanman.com") || url.getHost().endsWith("yqmh.com") || url.getHost().endsWith("kaimanhua.com")) referer = "https://www.kanman.com/";
        if (url.getHost().equals("manwa.me") || url.getHost().endsWith(".manwa.me") || url.getHost().equals("mwappimgs.cc") || url.getHost().endsWith(".mwappimgs.cc")) referer = "https://manwa.me/";
        if (url.getHost().equals("mh160mh.com") || url.getHost().endsWith(".mh160mh.com") || url.getHost().equals("miyeye.cn") || url.getHost().endsWith(".miyeye.cn") || url.getHost().equals("mhpic5er.tgmhfc.uk") || url.getHost().equals("mhpic6.tgmhfc.uk")) referer = "https://www.mh160mh.com/";
        if (url.getHost().endsWith("kuaikanmanhua.com") || url.getHost().endsWith("kkmh.com") || url.getHost().endsWith("v3mh.com")) referer = "https://www.kuaikanmanhua.com/";
        if (url.getHost().endsWith("manhuagui.com") || url.getHost().endsWith("mhgui.com") || url.getHost().endsWith("hamreus.com")) referer = "https://www.manhuagui.com/";
        c.setRequestProperty("Referer", referer);
        int status=c.getResponseCode();
        if(status>=300 && status<400) {
          String loc=c.getHeaderField("Location"); c.disconnect();
          if(loc==null) return denied();
          url=new URL(url,loc); continue;
        }
        if(status!=200) {c.disconnect(); if(status==403 && (isBaoziWebsite(url.getHost()) || url.getHost().equals("manwa.me") || url.getHost().endsWith(".manwa.me") || url.getHost().equals("mh160mh.com") || url.getHost().endsWith(".mh160mh.com"))) return new WebResourceResponse("text/plain","UTF-8",403,"Forbidden",Collections.emptyMap(),new ByteArrayInputStream(new byte[0])); return new WebResourceResponse("text/plain","UTF-8",502,"Bad Gateway",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));}
        String type=c.getContentType();
        String mime=type==null?"application/octet-stream":type.split(";")[0].trim();
        boolean manwaImage=(url.getHost().equals("mwappimgs.cc") || url.getHost().endsWith(".mwappimgs.cc")) && "20220724".equals(Uri.parse(url.toString()).getQueryParameter("v"));
        boolean encodedManwa=manwaImage && (mime.equals("application/octet-stream") || mime.equals("binary/octet-stream"));
        if (!(mime.startsWith("image/") || mime.equals("text/html") || mime.equals("text/plain") || mime.equals("application/json") || encodedManwa)) { c.disconnect(); return denied(); }
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(InputStream in=c.getInputStream()) {byte[] buf=new byte[16384]; int n; while((n=in.read(buf))!=-1) {if(out.size()+n>25*1024*1024) throw new Exception("too large"); out.write(buf,0,n);}}
        c.disconnect();
        byte[] body=out.toByteArray();
        if (encodedManwa) {
          byte[] key="my2ecret782ecret".getBytes(StandardCharsets.UTF_8);
          Cipher cipher=Cipher.getInstance("AES/CBC/PKCS5Padding");
          cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new IvParameterSpec(key));
          body=cipher.doFinal(body); mime=imageMime(body);
          if (mime==null) return denied();
        }
        return new WebResourceResponse(mime, (mime.startsWith("text/")||mime.equals("application/json"))?"UTF-8":null, 200, "OK", new java.util.HashMap<String,String>() {{ put("Cache-Control", "public, max-age=3600"); put("X-Content-Type-Options", "nosniff"); put("Content-Security-Policy", "default-src 'none'; sandbox"); }}, new ByteArrayInputStream(body));
      }
    } catch(Exception ignored) {}
    return new WebResourceResponse("text/plain","UTF-8",502,"Bad Gateway",Collections.emptyMap(),new ByteArrayInputStream(new byte[0]));
  }
  private static String imageMime(byte[] b) {
    if (b.length>=3 && (b[0]&255)==255 && (b[1]&255)==216 && (b[2]&255)==255) return "image/jpeg";
    if (b.length>=8 && (b[0]&255)==137 && b[1]=='P' && b[2]=='N' && b[3]=='G' && b[4]==13 && b[5]==10 && b[6]==26 && b[7]==10) return "image/png";
    if (b.length>=6 && b[0]=='G' && b[1]=='I' && b[2]=='F' && b[3]=='8' && (b[4]=='7'||b[4]=='9') && b[5]=='a') return "image/gif";
    if (b.length>=12 && b[0]=='R' && b[1]=='I' && b[2]=='F' && b[3]=='F' && b[8]=='W' && b[9]=='E' && b[10]=='B' && b[11]=='P') return "image/webp";
    return null;
  }
  @Override public void onResume(){ super.onResume(); if(view!=null && isReaderPage()) HengduProgress.deliver(this, view); }
  @Override public void onConfigurationChanged(Configuration config){ super.onConfigurationChanged(config); }
  @Override public void onBackPressed() { if (verificationView != null) closeMh160Verification(); else view.evaluateJavascript("if(window.readerBack) window.readerBack(); else history.back()",null); }
  @Override public void onDestroy(){ HengduChapterHandoff.detach(this); HengduOnlineGateway.detach(this); if(offlineEntry!=null) offlineEntry.cancel(); if(verificationView!=null) closeMh160Verification(); if(view!=null) view.destroy(); super.onDestroy(); }
}
