package com.escuela.impresion;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;

import androidx.core.content.ContextCompat;

import org.json.JSONObject;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Servicio en primer plano: mantiene el servidor encendido aunque la app quede en segundo plano. */
public class ServerService extends Service implements WebServer.Listener {

    private static final String CH_SERVER = "server";
    private static final String CH_JOBS = "jobs";
    private static final int ID_SERVER = 1;
    private static final int ID_JOB = 2;

    public static volatile String lastError = "";

    private WebServer server;
    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable cleanup = new Runnable() {
        @Override
        public void run() {
            JobStore.get(ServerService.this).cleanup();
            handler.postDelayed(this, 3600_000L);
        }
    };

    public static void start(Context ctx) {
        ContextCompat.startForegroundService(ctx, new Intent(ctx, ServerService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createChannels();
        startForeground(ID_SERVER, serverNotification());

        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "impresion:server");
        wakeLock.acquire();
        WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        if (wm != null) {
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "impresion:wifi");
            wifiLock.acquire();
        }

        server = new WebServer(this, this);
        try {
            server.start(NanoTimeout.SOCKET_READ_TIMEOUT, false);
            lastError = "";
        } catch (IOException e) {
            lastError = "No se pudo abrir el puerto " + WebServer.PORT + ": " + e.getMessage();
        }
        handler.post(cleanup);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(cleanup);
        if (server != null) server.stop();
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        if (wifiLock != null && wifiLock.isHeld()) wifiLock.release();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onNewJob(JSONObject job) {
        String text = job.optString("teacher") + " · " + job.optString("filename")
                + " · " + job.optInt("copies", 1) + " copia(s)";
        Notification n = builder(CH_JOBS)
                .setContentTitle("Nuevo archivo para imprimir")
                .setContentText(text)
                .setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_ALL)
                .build();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        try {
            nm.notify(ID_JOB, n);
        } catch (SecurityException ignored) {
            // sin permiso de notificaciones: el panel igual se actualiza
        }
    }

    // ------------------------------------------------------------ notificaciones
    private void createChannels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CH_SERVER, "Servidor activo",
                NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel(CH_JOBS, "Archivos nuevos",
                NotificationManager.IMPORTANCE_HIGH));
    }

    private Notification serverNotification() {
        List<String> ips = localIps();
        String url = ips.isEmpty() ? "Conecta la tablet al Wi-Fi"
                : "http://" + ips.get(0) + ":" + WebServer.PORT;
        return builder(CH_SERVER)
                .setContentTitle("Recibiendo archivos para imprimir")
                .setContentText(url)
                .setOngoing(true)
                .build();
    }

    @SuppressWarnings("deprecation")
    private Notification.Builder builder(String channel) {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, channel)
                : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        return b.setSmallIcon(R.drawable.ic_stat_print)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, flags));
    }

    // ------------------------------------------------------------ red
    public static List<String> localIps() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        String ip = a.getHostAddress();
                        // Wi-Fi primero (wlan), datos móviles al final
                        if (ni.getName().startsWith("wlan")) out.add(0, ip);
                        else out.add(ip);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Evita depender de una constante protegida de NanoHTTPD. */
    private static final class NanoTimeout {
        static final int SOCKET_READ_TIMEOUT = 60_000;
    }
}
