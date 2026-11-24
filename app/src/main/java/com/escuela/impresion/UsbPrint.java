package com.escuela.impresion;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.pdf.PdfDocument;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Impresión directa por USB (OTG), sin ventana de Android.
 *
 * Las impresoras que entienden PDF de forma nativa (HP LaserJet Pro, etc.)
 * reciben el archivo envuelto en comandos PJL: copias y doble cara se aplican
 * automáticamente. Las imágenes se convierten antes a PDF.
 */
public final class UsbPrint {

    static final int VENDOR_HP = 0x03F0;
    static final int VENDOR_EPSON = 0x04B8;
    private static final String ACTION_PERMISSION = "com.escuela.impresion.USB_PERMISSION";
    private static final String UEL = "\u001B%-12345X";

    public interface Callback {
        void done(boolean ok, String message);
    }

    private UsbPrint() {
    }

    /** Impresoras USB conectadas (clase USB 7). */
    public static JSONArray list(Context ctx) {
        JSONArray out = new JSONArray();
        UsbManager um = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        if (um == null) return out;
        for (UsbDevice d : um.getDeviceList().values()) {
            if (findPrinterInterface(d) == null) continue;
            try {
                JSONObject o = new JSONObject();
                o.put("device", d.getDeviceName());
                o.put("name", displayName(d));
                o.put("vendor", vendorName(d.getVendorId()));
                // Envío directo solo a impresoras que aceptan PDF/PJL (HP).
                o.put("direct", d.getVendorId() == VENDOR_HP);
                o.put("permission", um.hasPermission(d));
                out.put(o);
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    /** Pide permiso si hace falta y luego imprime en un hilo aparte. */
    public static void print(Context ctx, String deviceName, JSONObject job, File file, Callback cb) {
        Context app = ctx.getApplicationContext();
        UsbManager um = (UsbManager) app.getSystemService(Context.USB_SERVICE);
        UsbDevice dev = um == null ? null : um.getDeviceList().get(deviceName);
        if (dev == null) {
            cb.done(false, "La impresora USB se desconectó");
            return;
        }
        if (um.hasPermission(dev)) {
            new Thread(() -> send(app, um, dev, job, file, cb), "usb-print").start();
            return;
        }
        BroadcastReceiver rcv = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                app.unregisterReceiver(this);
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    new Thread(() -> send(app, um, dev, job, file, cb), "usb-print").start();
                } else {
                    cb.done(false, "Permiso USB denegado");
                }
            }
        };
        ContextCompat.registerReceiver(app, rcv, new IntentFilter(ACTION_PERMISSION),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        Intent intent = new Intent(ACTION_PERMISSION).setPackage(app.getPackageName());
        um.requestPermission(dev, PendingIntent.getBroadcast(app, 0, intent, flags));
    }

    private static void send(Context ctx, UsbManager um, UsbDevice dev, JSONObject job, File file, Callback cb) {
        UsbInterface intf = findPrinterInterface(dev);
        UsbEndpoint out = intf == null ? null : bulkOut(intf);
        if (out == null) {
            cb.done(false, "No se encontró la salida USB de la impresora");
            return;
        }
        UsbDeviceConnection conn = um.openDevice(dev);
        if (conn == null) {
            cb.done(false, "No se pudo abrir la impresora USB");
            return;
        }
        try {
            if (!conn.claimInterface(intf, true)) throw new IOException("Interfaz USB ocupada");
            byte[] pdf = pdfBytes(job, file);
            write(conn, out, pjlHeader(job).getBytes(StandardCharsets.US_ASCII));
            write(conn, out, pdf);
            write(conn, out, (UEL + "@PJL EOJ\r\n" + UEL).getBytes(StandardCharsets.US_ASCII));
            cb.done(true, "Enviado por USB a " + displayName(dev));
        } catch (Exception e) {
            cb.done(false, "Error USB: " + e.getMessage());
        } finally {
            try {
                conn.releaseInterface(intf);
            } catch (Exception ignored) {
            }
            conn.close();
        }
    }

    private static String pjlHeader(JSONObject job) {
        String name = job.optString("filename").replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "'");
        if (name.length() > 70) name = name.substring(0, 70);
        int copies = Math.max(1, job.optInt("copies", 1));
        StringBuilder sb = new StringBuilder();
        sb.append(UEL).append("@PJL\r\n");
        sb.append("@PJL JOB NAME=\"").append(name).append("\"\r\n");
        sb.append("@PJL SET QTY=").append(copies).append("\r\n");
        if (job.optBoolean("duplex")) {
            sb.append("@PJL SET DUPLEX=ON\r\n@PJL SET BINDING=LONGEDGE\r\n");
        } else {
            sb.append("@PJL SET DUPLEX=OFF\r\n");
        }
        sb.append("@PJL ENTER LANGUAGE=PDF\r\n");
        return sb.toString();
    }

    private static void write(UsbDeviceConnection conn, UsbEndpoint ep, byte[] data) throws IOException {
        int chunk = 16 * 1024;
        byte[] buf = new byte[chunk];
        int off = 0;
        while (off < data.length) {
            int n = Math.min(chunk, data.length - off);
            System.arraycopy(data, off, buf, 0, n);
            int sent = conn.bulkTransfer(ep, buf, n, 30_000);
            if (sent < 0) throw new IOException("la impresora no respondió (¿apagada o sin papel?)");
            off += sent;
        }
    }

    // ------------------------------------------------------------ PDF
    private static byte[] pdfBytes(JSONObject job, File file) throws IOException {
        String ext = job.optString("ext").toLowerCase(Locale.ROOT);
        if (".pdf".equals(ext)) return readAll(file);
        if (ext.matches("\\.(jpe?g|png|gif|webp|bmp)")) return imageToPdf(file);
        throw new IOException("este tipo de archivo se imprime con 'Abrir e imprimir'");
    }

    /** Imagen → PDF A4, ajustada a la hoja con margen. */
    private static byte[] imageToPdf(File file) throws IOException {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        int sample = 1;
        while (o.outWidth / sample > 3000 || o.outHeight / sample > 3000) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        Bitmap bmp = BitmapFactory.decodeFile(file.getPath(), o);
        if (bmp == null) throw new IOException("no se pudo leer la imagen");

        boolean landscape = bmp.getWidth() > bmp.getHeight();
        int pw = landscape ? 842 : 595, ph = landscape ? 595 : 842, m = 24;
        float scale = Math.min((pw - 2f * m) / bmp.getWidth(), (ph - 2f * m) / bmp.getHeight());
        int w = Math.round(bmp.getWidth() * scale), h = Math.round(bmp.getHeight() * scale);
        int left = (pw - w) / 2, top = (ph - h) / 2;

        PdfDocument doc = new PdfDocument();
        PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(pw, ph, 1).create());
        Canvas c = page.getCanvas();
        c.drawColor(Color.WHITE);
        c.drawBitmap(bmp, null, new Rect(left, top, left + w, top + h), new Paint(Paint.FILTER_BITMAP_FLAG));
        doc.finishPage(page);
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        doc.writeTo(bos);
        doc.close();
        bmp.recycle();
        return bos.toByteArray();
    }

    private static byte[] readAll(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) bos.write(buf, 0, r);
            return bos.toByteArray();
        }
    }

    // ------------------------------------------------------------ USB
    /** Interfaz de impresora clásica (protocolo 1 o 2); se evita IPP-over-USB (protocolo 4). */
    private static UsbInterface findPrinterInterface(UsbDevice d) {
        UsbInterface fallback = null;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface intf = d.getInterface(i);
            if (intf.getInterfaceClass() != UsbConstants.USB_CLASS_PRINTER) continue;
            if (bulkOut(intf) == null) continue;
            int proto = intf.getInterfaceProtocol();
            if (proto == 1 || proto == 2) return intf;
            if (fallback == null && proto != 4) fallback = intf;
        }
        return fallback;
    }

    private static UsbEndpoint bulkOut(UsbInterface intf) {
        for (int i = 0; i < intf.getEndpointCount(); i++) {
            UsbEndpoint ep = intf.getEndpoint(i);
            if (ep.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK
                    && ep.getDirection() == UsbConstants.USB_DIR_OUT) {
                return ep;
            }
        }
        return null;
    }

    private static String displayName(UsbDevice d) {
        String name = null;
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                name = d.getProductName();
            } catch (SecurityException ignored) {
                // sin permiso todavía en algunos Android
            }
        }
        if (name == null || name.trim().isEmpty()) name = vendorName(d.getVendorId()) + " USB";
        return name.trim();
    }

    private static String vendorName(int vid) {
        if (vid == VENDOR_HP) return "HP";
        if (vid == VENDOR_EPSON) return "Epson";
        if (vid == 0x04A9) return "Canon";
        if (vid == 0x04F9) return "Brother";
        return "Impresora";
    }
}
