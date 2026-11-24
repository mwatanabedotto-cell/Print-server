package com.escuela.impresion;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.PowerManager;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintJob;
import android.print.PrintManager;
import android.provider.Settings;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.MimeTypeMap;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.print.PrintHelper;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Pantalla de la tablet: muestra el panel (HTML) y hace la impresión con Android. */
public class MainActivity extends Activity {

    private static final String PANEL_URL = "http://127.0.0.1:" + WebServer.PORT + "/panel";

    private WebView web;
    private JobStore store;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        store = JobStore.get(this);
        ServerService.start(this);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        web.addJavascriptInterface(new Bridge(), "Android");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !url.startsWith("http://127.0.0.1:" + WebServer.PORT + "/");
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
                // El servidor puede tardar un instante en arrancar: reintentar
                if (Build.VERSION.SDK_INT < 21 || req.isForMainFrame()) {
                    handler.postDelayed(() -> web.loadUrl(PANEL_URL), 1000);
                }
            }
        });
        setContentView(web);
        handler.postDelayed(() -> web.loadUrl(PANEL_URL), 400);
    }

    @Override
    public void onBackPressed() {
        moveTaskToBack(true); // el servidor sigue funcionando
    }

    // ------------------------------------------------------------ puente JS
    private class Bridge {
        @JavascriptInterface
        public String info() {
            try {
                JSONObject o = new JSONObject();
                List<String> ips = ServerService.localIps();
                JSONArray urls = new JSONArray();
                for (String ip : ips) urls.put("http://" + ip + ":" + WebServer.PORT);
                o.put("urls", urls);
                o.put("qr", ips.isEmpty() ? "" : qrDataUrl("http://" + ips.get(0) + ":" + WebServer.PORT));
                o.put("school_name", store.schoolName());
                o.put("upload_code", store.uploadCode());
                o.put("keep_days", store.keepDays());
                o.put("max_mb", store.maxMb());
                o.put("error", ServerService.lastError);
                o.put("battery_ok", batteryOptimizationIgnored());
                return o.toString();
            } catch (Exception e) {
                return "{}";
            }
        }

        @JavascriptInterface
        public void print(String id) {
            runOnUiThread(() -> printJob(id));
        }

        @JavascriptInterface
        public String usbPrinters() {
            return UsbPrint.list(MainActivity.this).toString();
        }

        @JavascriptInterface
        public void printUsb(String id, String device) {
            JSONObject job = store.find(id);
            if (job == null) return;
            store.setStatus(id, "imprimiendo", "");
            UsbPrint.print(MainActivity.this, device, job, store.fileFor(job), (ok, msg) ->
                    store.setStatus(id, ok ? "impreso" : "error", ok ? "" : msg));
        }

        @JavascriptInterface
        public void open(String id) {
            runOnUiThread(() -> openJob(id));
        }

        @JavascriptInterface
        public void saveSettings(String json) {
            try {
                store.saveSettings(new JSONObject(json));
            } catch (Exception ignored) {
            }
        }

        @JavascriptInterface
        public void fixBattery() {
            runOnUiThread(MainActivity.this::requestBatteryExemption);
        }
    }

    // ------------------------------------------------------------ impresión
    private void printJob(String id) {
        JSONObject job = store.find(id);
        if (job == null) return;
        File file = store.fileFor(job);
        String ext = job.optString("ext");
        String name = job.optString("filename");
        boolean color = job.optBoolean("color");

        if (".pdf".equals(ext)) {
            PrintAttributes.Builder attrs = new PrintAttributes.Builder()
                    .setColorMode(color ? PrintAttributes.COLOR_MODE_COLOR : PrintAttributes.COLOR_MODE_MONOCHROME);
            if (Build.VERSION.SDK_INT >= 23) {
                attrs.setDuplexMode(job.optBoolean("duplex")
                        ? PrintAttributes.DUPLEX_MODE_LONG_EDGE : PrintAttributes.DUPLEX_MODE_NONE);
            }
            PrintManager pm = (PrintManager) getSystemService(PRINT_SERVICE);
            store.setStatus(id, "imprimiendo", "");
            PrintJob pj = pm.print(name, new PdfAdapter(file, name), attrs.build());
            watch(pj, id, 0);
        } else if (".jpg".equals(ext) || ".jpeg".equals(ext) || ".png".equals(ext)
                || ".bmp".equals(ext) || ".gif".equals(ext) || ".webp".equals(ext)) {
            PrintHelper ph = new PrintHelper(this);
            ph.setScaleMode(PrintHelper.SCALE_MODE_FIT);
            ph.setColorMode(color ? PrintHelper.COLOR_MODE_COLOR : PrintHelper.COLOR_MODE_MONOCHROME);
            try {
                store.setStatus(id, "imprimiendo", "");
                ph.printBitmap(name, Uri.fromFile(file), () -> store.setStatus(id, "impreso", ""));
            } catch (Exception e) {
                store.setStatus(id, "error", "No se pudo leer la imagen");
            }
        } else {
            // Word, Excel, PowerPoint, etc.: se abren en una app que sepa imprimirlos
            openJob(id);
        }
    }

    /** Sigue el estado del trabajo de Android para actualizar la cola. */
    private void watch(PrintJob pj, String id, int tries) {
        handler.postDelayed(() -> {
            if (pj.isCompleted() || pj.isQueued() || pj.isStarted()) {
                store.setStatus(id, "impreso", "");
            } else if (pj.isCancelled()) {
                store.setStatus(id, "pendiente", "");
            } else if (pj.isFailed()) {
                store.setStatus(id, "error", "Falló la impresión");
            } else if (pj.isBlocked()) {
                store.setStatus(id, "error", "Impresora bloqueada (¿sin papel o tinta?)");
            } else if (tries < 1200) { // sigue en el diálogo de impresión
                watch(pj, id, tries + 1);
            }
        }, 1500);
    }

    private void openJob(String id) {
        JSONObject job = store.find(id);
        if (job == null) return;
        File file = store.fileFor(job);
        String ext = job.optString("ext").replace(".", "");
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase(Locale.ROOT));
        Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".files", file);
        Intent view = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, mime == null ? "*/*" : mime)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(view, "Abrir con… (luego menú ⋮ → Imprimir)"));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Instala una app que abra ." + ext
                    + " (por ejemplo WPS Office o Google Docs)", Toast.LENGTH_LONG).show();
        }
    }

    private static class PdfAdapter extends PrintDocumentAdapter {
        private final File file;
        private final String name;

        PdfAdapter(File file, String name) {
            this.file = file;
            this.name = name;
        }

        @Override
        public void onLayout(PrintAttributes oldA, PrintAttributes newA, CancellationSignal cancel,
                             LayoutResultCallback cb, Bundle extras) {
            if (cancel.isCanceled()) {
                cb.onLayoutCancelled();
                return;
            }
            cb.onLayoutFinished(new PrintDocumentInfo.Builder(name)
                    .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT).build(), true);
        }

        @Override
        public void onWrite(PageRange[] pages, ParcelFileDescriptor dest, CancellationSignal cancel,
                            WriteResultCallback cb) {
            try (InputStream in = new FileInputStream(file);
                 OutputStream out = new FileOutputStream(dest.getFileDescriptor())) {
                byte[] buf = new byte[64 * 1024];
                int r;
                while ((r = in.read(buf)) > 0) {
                    if (cancel.isCanceled()) {
                        cb.onWriteCancelled();
                        return;
                    }
                    out.write(buf, 0, r);
                }
                cb.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
            } catch (Exception e) {
                cb.onWriteFailed(e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------ utilidades
    private boolean batteryOptimizationIgnored() {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        return pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    @SuppressLint("BatteryLife")
    private void requestBatteryExemption() {
        if (Build.VERSION.SDK_INT < 23) return;
        try {
            startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())));
        } catch (ActivityNotFoundException e) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private static String qrDataUrl(String text) throws Exception {
        BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 400, 400,
                Collections.singletonMap(EncodeHintType.MARGIN, 1));
        int w = m.getWidth(), h = m.getHeight();
        int[] px = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? Color.BLACK : Color.WHITE;
        }
        Bitmap bmp = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos);
        return "data:image/png;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
    }
}
