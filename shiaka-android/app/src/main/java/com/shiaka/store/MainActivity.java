package com.shiaka.store;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {

    private static final int REQ_FILE = 1;
    private static final int REQ_CAMERA = 2;
    private static final int REQ_STORAGE = 3;
    private static final String LOCAL_URL = "file:///android_asset/index.html";

    private WebView web;
    private ValueCallback<Uri[]> fileCallback;
    private PermissionRequest pendingPermission;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("cfg", MODE_PRIVATE);

        web = new WebView(this);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccess(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        web.addJavascriptInterface(new Bridge(), "AndroidBridge");

        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) {
                    showError();
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView w, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                }
                fileCallback = callback;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.getResources());
                    } else {
                        pendingPermission = request;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                    }
                });
            }
        });

        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
        }

        if (!prefs.getBoolean("asked", false)) {
            askServer(true);
        } else {
            load();
        }
    }

    private void load() {
        String url = prefs.getString("server", "");
        web.loadUrl(url.isEmpty() ? LOCAL_URL : url);
    }

    private String normalize(String raw) {
        String u = raw.trim();
        if (u.isEmpty()) return "";
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://" + u;
        if (u.matches("^https?://[^/:]+(/.*)?$") && u.startsWith("http://")) {
            int slash = u.indexOf('/', 7);
            u = slash < 0 ? u + ":8000" : u.substring(0, slash) + ":8000" + u.substring(slash);
        }
        return u;
    }

    private void askServer(final boolean first) {
        final EditText input = new EditText(this);
        input.setHint("http://192.168.1.10:8000");
        input.setText(prefs.getString("server", ""));
        input.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("عنوان سيرفر الكمبيوتر")
                .setMessage("اكتب العنوان اللي ظاهر في زرار «الموبايل» على الكمبيوتر، أو اختار «بدون سيرفر» لتشغيل البرنامج على الموبايل لوحده.")
                .setView(input)
                .setCancelable(!first)
                .setPositiveButton("اتصال", (d, w) -> {
                    prefs.edit().putString("server", normalize(input.getText().toString())).putBoolean("asked", true).apply();
                    load();
                })
                .setNegativeButton("بدون سيرفر", (d, w) -> {
                    prefs.edit().remove("server").putBoolean("asked", true).apply();
                    load();
                })
                .show();
    }

    private void showError() {
        String html = "<html dir='rtl'><head><meta name='viewport' content='width=device-width,initial-scale=1'></head>"
                + "<body style='font-family:sans-serif;text-align:center;padding:28px'>"
                + "<h2>مقدرتش أوصل للسيرفر</h2>"
                + "<p>اتأكد إن الكمبيوتر شغّال والبرنامج مفتوح، وإن الموبايل على نفس الواي فاي.</p>"
                + "<p><button onclick='AndroidBridge.reload()' style='font-size:18px;padding:12px 22px'>إعادة المحاولة</button></p>"
                + "<p><button onclick='AndroidBridge.chooseServer()' style='font-size:18px;padding:12px 22px'>تغيير العنوان</button></p>"
                + "<p><button onclick='AndroidBridge.useLocal()' style='font-size:18px;padding:12px 22px'>تشغيل بدون سيرفر</button></p>"
                + "</body></html>";
        web.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
            fileCallback = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA && pendingPermission != null) {
            boolean ok = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (ok) {
                pendingPermission.grant(pendingPermission.getResources());
            } else {
                pendingPermission.deny();
            }
            pendingPermission = null;
        }
    }

    @Override
    public void onBackPressed() {
        web.evaluateJavascript(
                "(function(){var m=document.getElementById('modal');if(m&&m.innerHTML){m.innerHTML='';return 'c'}return 'n'})()",
                value -> {
                    if (value != null && value.contains("c")) return;
                    finish();
                });
    }

    private void toast(final String msg) {
        runOnUiThread(() -> Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show());
    }

    private class Bridge {

        @JavascriptInterface
        public String getServer() {
            return prefs.getString("server", "");
        }

        @JavascriptInterface
        public void chooseServer() {
            runOnUiThread(() -> askServer(false));
        }

        @JavascriptInterface
        public void useLocal() {
            runOnUiThread(() -> {
                prefs.edit().remove("server").putBoolean("asked", true).apply();
                load();
            });
        }

        @JavascriptInterface
        public void reload() {
            runOnUiThread(MainActivity.this::load);
        }

        @JavascriptInterface
        public void print(final String title) {
            runOnUiThread(() -> {
                PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
                PrintDocumentAdapter adapter = web.createPrintDocumentAdapter(title);
                pm.print(title, adapter, new PrintAttributes.Builder().build());
            });
        }

        @JavascriptInterface
        public void save(String name, String data, String mime) {
            try {
                byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
                String type = mime == null ? "application/octet-stream" : mime.split(";")[0];
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues v = new ContentValues();
                    v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
                    v.put(MediaStore.MediaColumns.MIME_TYPE, type);
                    v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                    Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                    OutputStream out = getContentResolver().openOutputStream(uri);
                    out.write(bytes);
                    out.close();
                } else {
                    File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                    dir.mkdirs();
                    FileOutputStream out = new FileOutputStream(new File(dir, name));
                    out.write(bytes);
                    out.close();
                }
                toast("اتحفظ الملف في التنزيلات: " + name);
            } catch (Exception e) {
                toast("تعذّر حفظ الملف: " + e.getMessage());
            }
        }
    }
}
