package com.deepseek.chatdemo;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final String TAG = "DSChatAPK";
    // 本地内置官方 chat-demo（低审核版）。用本地 HTTP 服务提供，
    // 让页面有正常的 http origin（file:// 下getUserMedia/文件选择等能力被限制）。
    private static final int LOCAL_PORT = 8899;
    private static final String SITE_URL = "http://127.0.0.1:" + LOCAL_PORT + "/";
    private static final String FILES_API = "https://api.deepseek.com/anthropic/v1/files";
    private static final String ASR_API = "https://api.deepseek.com/v1/tools/asr";
    private LocalServer localServer;
    private static final int REQ_FILE_CHOOSER = 1001;
    private static final int REQ_WRITE_STORAGE = 1002;

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    // 导出用
    private OutputStream currentOut;
    private String currentName;
    private File currentTemp;

    // 导出用：拦截 <a download href="blob:...">.click()，分片经 JS 桥写 Downloads
    private static final String DOWNLOAD_HOOK = """
            (function(){
              if (window.__dsDownloadHooked) return;
              window.__dsDownloadHooked = true;
              var blobs = {};
              var origCreate = URL.createObjectURL;
              URL.createObjectURL = function(obj){
                var url = origCreate.apply(URL, arguments);
                try { blobs[url] = obj; } catch(e){}
                return url;
              };
              var origClick = HTMLAnchorElement.prototype.click;
              HTMLAnchorElement.prototype.click = function(){
                try {
                  var href = this.href || '';
                  var name = this.getAttribute('download');
                  if (href.indexOf('blob:') === 0 && name && blobs[href]) {
                    var blob = blobs[href];
                    var CHUNK = 256 * 1024, offset = 0;
                    function sendChunk(){
                      var end = Math.min(offset + CHUNK, blob.size);
                      var fr = new FileReader();
                      fr.onload = function(){
                        var dataUrl = String(fr.result);
                        var comma = dataUrl.indexOf(',');
                        var b64 = comma >= 0 ? dataUrl.substring(comma + 1) : '';
                        var last = end >= blob.size;
                        try { window.DSDownload.saveChunk(name, b64, last); } catch(e){}
                        offset = end;
                        if (!last) sendChunk();
                        else { try { URL.revokeObjectURL(href); } catch(e){} delete blobs[href]; }
                      };
                      fr.onerror = function(){};
                      fr.readAsDataURL(blob.slice(offset, end));
                    }
                    sendChunk();
                    return;
                  }
                } catch(e){}
                return origClick.apply(this, arguments);
              };
            })();
            """;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        // 让 file:// 页面能加载同目录的相对资源
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        }

        webView.addJavascriptInterface(new DownloadBridge(), "DSDownload");
        webView.addJavascriptInterface(new NativeBridge(), "DSNative");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                view.evaluateJavascript(DOWNLOAD_HOOK, null);
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            // 授予网页的 getUserMedia 权限（麦克风），否则语音输入报"没权限"
            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                final String[] wanted = request.getResources();
                boolean needsMic = false;
                for (String r : wanted) {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)) needsMic = true;
                }
                if (needsMic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    request.deny();
                    requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1003);
                    return;
                }
                request.grant(wanted);
            }

            @Override
            public boolean onShowFileChooser(WebView view,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                // 直接用通用 */* 选择器，不依赖 params.createIntent() 的
                // 特殊 accept 类型（华为机上常找不到处理应用）
                try {
                    Intent pick = new Intent(Intent.ACTION_GET_CONTENT);
                    pick.setType("*/*");
                    pick.addCategory(Intent.CATEGORY_OPENABLE);
                    pick.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    pick.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    pick.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                    startActivityForResult(Intent.createChooser(pick, "选择文件"), REQ_FILE_CHOOSER);
                } catch (ActivityNotFoundException e) {
                    filePathCallback = null;
                    toastOnUi("没有可用的文件选择器");
                    return false;
                }
                return true;
            }
        });

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_WRITE_STORAGE);
        }

        // 启动本地 HTTP 服务，把 assets 里的 chat-demo 提供出去
        try {
            localServer = new LocalServer(LOCAL_PORT);
            localServer.start();
        } catch (Exception e) {
            Log.e(TAG, "local server start failed", e);
            toastOnUi("本地服务启动失败");
        }
        webView.loadUrl(SITE_URL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE_CHOOSER) {
            if (filePathCallback == null) return;
            final ValueCallback<Uri[]> cb = filePathCallback;
            filePathCallback = null;
            if (resultCode != RESULT_OK || data == null) {
                cb.onReceiveValue(null);
                return;
            }
            List<Uri> picked = new ArrayList<>();
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                for (int i = 0; i < n; i++) picked.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) {
                picked.add(data.getData());
            }
            if (picked.isEmpty()) {
                cb.onReceiveValue(null);
                return;
            }
            final List<Uri> finalPicked = picked;
            new Thread(() -> {
                List<Uri> out = new ArrayList<>();
                boolean ok = true;
                for (Uri u : finalPicked) {
                    File cached = copyToCache(u);
                    if (cached != null) {
                        Uri contentUri = androidx.core.content.FileProvider.getUriForFile(
                                MainActivity.this, getPackageName() + ".fileprovider", cached);
                        grantUriPermission(getPackageName(), contentUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        out.add(contentUri);
                    } else { ok = false; break; }
                }
                final Uri[] result = ok ? out.toArray(new Uri[0]) : null;
                runOnUiThread(() -> {
                    if (result == null) toastOnUi("读取文件失败，请重试");
                    cb.onReceiveValue(result);
                });
            }).start();
        }
    }

    // ============ 原生上传：绕开 WebView 的 fetch/FormData ============
    public class NativeBridge {
        @JavascriptInterface
        public void uploadFile(String id, String apiKey, String base64,
                               String fileName, String mime, String purpose,
                               String authKind, String uploadKind) {
            new Thread(() -> doUpload(id, apiKey, base64, fileName, mime, purpose, authKind, uploadKind), "ds-upload").start();
        }
    }

    private void doUpload(String id, String apiKey, String base64,
                          String fileName, String mime, String purpose,
                          String authKind, String uploadKind) {
        String resultJson;
        boolean ok;
        boolean isAsr = "asr".equals(uploadKind);
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            String boundary = "----DSCURLBoundary" + UUID.randomUUID().toString().replace("-", "");
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            // purpose 字段（如果有）
            if (purpose != null && !purpose.isEmpty()) {
                body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
                body.write(("Content-Disposition: form-data; name=\"purpose\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                body.write(purpose.getBytes(StandardCharsets.UTF_8));
                body.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            // file 字段
            body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            String safeName = (fileName == null || fileName.isEmpty()) ? "file" : fileName.replace("\"", "_");
            String safeMime = (mime == null || mime.isEmpty()) ? "application/octet-stream" : mime;
            body.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + safeName + "\"\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(("Content-Type: " + safeMime + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(bytes);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            byte[] payload = body.toByteArray();

            String endpoint = isAsr ? ASR_API : FILES_API;
            HttpURLConnection conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(120000);
            if ("bearer".equals(authKind)) {
                conn.setRequestProperty("Authorization", "Bearer " + (apiKey == null ? "" : apiKey));
            } else {
                conn.setRequestProperty("x-api-key", apiKey == null ? "" : apiKey);
                conn.setRequestProperty("anthropic-beta", "files-api-2025-04-14");
            }
            conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
                os.flush();
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String resp = readAllText(is);
            conn.disconnect();
            ok = (code >= 200 && code < 300);
            resultJson = resp == null || resp.isEmpty() ? "{}" : resp;
            Log.d(TAG, "upload code=" + code + " resp=" + resultJson.substring(0, Math.min(200, resultJson.length())));
            if (!ok) {
                final String brief = "上传失败 code=" + code + " " + resultJson.substring(0, Math.min(120, resultJson.length()));
                runOnUiThread(() -> toastOnUi(brief));
            }
        } catch (Exception e) {
            Log.e(TAG, "upload failed", e);
            ok = false;
            resultJson = "{\"error\":{\"message\":\"" + escapeJson(e.getMessage() == null ? "upload failed" : e.getMessage()) + "\"}}";
            final String brief = "上传异常 " + String.valueOf(e.getMessage()).substring(0, Math.min(100, String.valueOf(e.getMessage()).length()));
            runOnUiThread(() -> toastOnUi(brief));
        }
        final boolean fOk = ok;
        final String fJson = resultJson;
        runOnUiThread(() -> {
            try {
                String js = "window.DSUploadDone && window.DSUploadDone("
                        + jsString(id) + "," + jsString(fJson) + "," + fOk + ");";
                webView.evaluateJavascript(js, null);
            } catch (Exception e) {
                Log.e(TAG, "upload callback failed", e);
            }
        });
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    private static String jsString(String s) {
        if (s == null) return "''";
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r") + "'";
    }

    private static String readAllText(InputStream in) throws Exception {
        if (in == null) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            return sb.toString();
        }
    }

    private File copyToCache(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) return null;
            String name = displayName(uri);
            if (name == null || name.isEmpty()) name = "import.zip";
            name = name.replace("/", "_").replace("\\", "_");
            File dst = new File(getCacheDir(), name);
            try (OutputStream out = new FileOutputStream(dst)) {
                byte[] buf = new byte[128 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            return dst.length() > 0 ? dst : null;
        } catch (Exception e) {
            Log.e(TAG, "copyToCache failed", e);
            return null;
        }
    }

    private String displayName(Uri uri) {
        String name = null;
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = c.getString(idx);
            }
        } catch (Exception ignored) {}
        return name;
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (currentOut != null) {
            try { currentOut.close(); } catch (Exception ignored) {}
        }
        if (localServer != null) localServer.shutdown();
        super.onDestroy();
    }

    // ============ 本地静态服务器：把 assets/chat-demo 以 http 提供 ============
    private class LocalServer extends Thread {
        private final int port;
        private ServerSocket server;
        private volatile boolean running = true;

        LocalServer(int port) { this.port = port; setName("ds-local-server"); }

        void shutdown() {
            running = false;
            try { if (server != null) server.close(); } catch (Exception ignored) {}
        }

        @Override
        public void run() {
            try {
                server = new ServerSocket();
                server.setReuseAddress(true);
                server.bind(new InetSocketAddress("127.0.0.1", port));
                while (running) {
                    try {
                        Socket client = server.accept();
                        handle(client);
                    } catch (Exception e) {
                        if (running) Log.e(TAG, "accept failed", e);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "server run failed", e);
            }
        }

        private void handle(Socket client) {
            try {
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(client.getInputStream(), StandardCharsets.UTF_8));
                String line = reader.readLine();
                if (line == null || line.isEmpty()) { client.close(); return; }
                // GET /path HTTP/1.1
                String[] parts = line.split(" ");
                String path = parts.length > 1 ? parts[1] : "/";
                while ((line = reader.readLine()) != null && !line.isEmpty()) { /* 跳过 header */ }
                int q = path.indexOf('?');
                if (q >= 0) path = path.substring(0, q);
                String rel = URLDecoder.decode(path, "UTF-8");
                if (rel.equals("/") || rel.isEmpty()) rel = "/index.html";
                while (rel.startsWith("/")) rel = rel.substring(1);

                byte[] data = readAsset("chat-demo/" + rel);
                if (data == null) {
                    String resp = "HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n";
                    client.getOutputStream().write(resp.getBytes(StandardCharsets.US_ASCII));
                    client.getOutputStream().flush();
                    client.close();
                    return;
                }
                String mime = mimeFor(rel);
                String head = "HTTP/1.1 200 OK\r\n"
                        + "Content-Type: " + mime + "\r\n"
                        + "Content-Length: " + data.length + "\r\n"
                        + "Cache-Control: no-cache\r\n"
                        + "Connection: close\r\n\r\n";
                OutputStream os = client.getOutputStream();
                os.write(head.getBytes(StandardCharsets.US_ASCII));
                os.write(data);
                os.flush();
                client.close();
            } catch (Exception e) {
                Log.e(TAG, "handle request failed", e);
                try { client.close(); } catch (Exception ignored) {}
            }
        }

        private byte[] readAsset(String name) {
            InputStream in = null;
            try {
                in = getAssets().open(name);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toByteArray();
            } catch (Exception e) {
                return null;
            } finally {
                if (in != null) try { in.close(); } catch (Exception ignored) {}
            }
        }

        private String mimeFor(String path) {
            String p = path.toLowerCase();
            if (p.endsWith(".html") || p.endsWith(".htm")) return "text/html; charset=utf-8";
            if (p.endsWith(".js")) return "application/javascript; charset=utf-8";
            if (p.endsWith(".css")) return "text/css; charset=utf-8";
            if (p.endsWith(".json")) return "application/json; charset=utf-8";
            if (p.endsWith(".woff2")) return "font/woff2";
            if (p.endsWith(".woff")) return "font/woff";
            if (p.endsWith(".ttf")) return "font/ttf";
            if (p.endsWith(".svg")) return "image/svg+xml";
            if (p.endsWith(".png")) return "image/png";
            if (p.endsWith(".jpg") || p.endsWith(".jpeg")) return "image/jpeg";
            return "application/octet-stream";
        }
    }

    private void toastOnUi(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    public class DownloadBridge {
        @JavascriptInterface
        public void saveChunk(String name, String base64, boolean last) {
            try {
                if (currentOut == null || !name.equals(currentName)) {
                    if (currentOut != null) { try { currentOut.close(); } catch (Exception ignored) {} }
                    currentName = name;
                    currentTemp = new File(getCacheDir(), "ds_export_tmp");
                    if (currentTemp.exists() && !currentTemp.delete()) {
                        toastOnUi("无法准备导出文件");
                        return;
                    }
                    currentOut = new FileOutputStream(currentTemp);
                }
                byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
                currentOut.write(bytes);
                currentOut.flush();
                if (last) {
                    currentOut.close();
                    currentOut = null;
                    publishDownload(name, currentTemp);
                }
            } catch (Exception e) {
                Log.e(TAG, "saveChunk failed", e);
                toastOnUi("导出失败：" + e.getMessage());
            }
        }
    }

    private void publishDownload(String name, File src) {
        String shownPath;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
                cv.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
                cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new IllegalStateException("MediaStore 返回空");
                try (OutputStream out = getContentResolver().openOutputStream(uri);
                     InputStream in = new FileInputStream(src)) {
                    copy(in, out);
                }
                shownPath = "下载/" + name;
            } else {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.exists()) dir.mkdirs();
                File dest = new File(dir, name);
                try (OutputStream out = new FileOutputStream(dest);
                     InputStream in = new FileInputStream(src)) {
                    copy(in, out);
                }
                shownPath = dest.getAbsolutePath();
            }
            toastOnUi("已导出到 " + shownPath);
        } catch (Exception e) {
            Log.e(TAG, "publishDownload failed", e);
            toastOnUi("导出失败：" + e.getMessage());
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }
}
