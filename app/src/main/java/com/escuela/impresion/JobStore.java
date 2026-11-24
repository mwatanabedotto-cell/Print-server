package com.escuela.impresion;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Locale;

/** Guarda la cola de trabajos (jobs.json) y los archivos recibidos. */
public final class JobStore {

    private static JobStore instance;

    private final File dir;
    private final File jsonFile;
    private final SharedPreferences prefs;
    private JSONArray jobs;

    public static synchronized JobStore get(Context ctx) {
        if (instance == null) instance = new JobStore(ctx.getApplicationContext());
        return instance;
    }

    private JobStore(Context ctx) {
        dir = new File(ctx.getFilesDir(), "jobs");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        jsonFile = new File(ctx.getFilesDir(), "jobs.json");
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        jobs = load();
    }

    // ------------------------------------------------------------ ajustes
    public String schoolName() { return prefs.getString("school_name", "Asistente de Impresión"); }
    public String uploadCode() { return prefs.getString("upload_code", ""); }
    public int keepDays() { return prefs.getInt("keep_days", 7); }
    public int maxMb() { return prefs.getInt("max_mb", 50); }

    public void saveSettings(JSONObject s) {
        SharedPreferences.Editor e = prefs.edit();
        if (s.has("school_name")) e.putString("school_name", s.optString("school_name").trim());
        if (s.has("upload_code")) e.putString("upload_code", s.optString("upload_code").trim());
        if (s.has("keep_days")) e.putInt("keep_days", Math.max(1, Math.min(90, s.optInt("keep_days", 7))));
        if (s.has("max_mb")) e.putInt("max_mb", Math.max(1, Math.min(500, s.optInt("max_mb", 50))));
        e.apply();
    }

    // ------------------------------------------------------------ trabajos
    public synchronized JSONObject add(File tmp, String filename, String ext, String teacher,
                                       String notes, int copies, boolean duplex, boolean color)
            throws IOException, JSONException {
        String id = newId();
        String stored = id + ext;
        File dest = new File(dir, stored);
        copy(tmp, dest);
        JSONObject j = new JSONObject();
        j.put("id", id);
        j.put("filename", filename);
        j.put("stored", stored);
        j.put("ext", ext);
        j.put("size", dest.length());
        j.put("teacher", teacher);
        j.put("notes", notes);
        j.put("copies", copies);
        j.put("duplex", duplex);
        j.put("color", color);
        j.put("status", "pendiente");
        j.put("message", "");
        j.put("created", System.currentTimeMillis());
        jobs.put(j);
        save();
        return j;
    }

    public synchronized String listJson() {
        return jobs.toString();
    }

    public synchronized JSONObject find(String id) {
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject j = jobs.optJSONObject(i);
            if (j != null && id.equals(j.optString("id"))) {
                try {
                    return new JSONObject(j.toString());
                } catch (JSONException e) {
                    return null;
                }
            }
        }
        return null;
    }

    public synchronized boolean setStatus(String id, String status, String message) {
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject j = jobs.optJSONObject(i);
            if (j != null && id.equals(j.optString("id"))) {
                try {
                    j.put("status", status);
                    j.put("message", message == null ? "" : message);
                    if ("impreso".equals(status)) j.put("printed", System.currentTimeMillis());
                } catch (JSONException ignored) {
                }
                save();
                return true;
            }
        }
        return false;
    }

    public synchronized void delete(String id) {
        JSONArray keep = new JSONArray();
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject j = jobs.optJSONObject(i);
            if (j == null) continue;
            if (id.equals(j.optString("id"))) {
                //noinspection ResultOfMethodCallIgnored
                new File(dir, j.optString("stored")).delete();
            } else {
                keep.put(j);
            }
        }
        jobs = keep;
        save();
    }

    public synchronized void cleanup() {
        long limit = System.currentTimeMillis() - keepDays() * 86400000L;
        JSONArray keep = new JSONArray();
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject j = jobs.optJSONObject(i);
            if (j == null) continue;
            if (j.optLong("created") < limit) {
                //noinspection ResultOfMethodCallIgnored
                new File(dir, j.optString("stored")).delete();
            } else {
                keep.put(j);
            }
        }
        if (keep.length() != jobs.length()) {
            jobs = keep;
            save();
        }
    }

    public synchronized int pendingCount() {
        int n = 0;
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject j = jobs.optJSONObject(i);
            if (j != null && "pendiente".equals(j.optString("status"))) n++;
        }
        return n;
    }

    public File fileFor(JSONObject job) {
        return new File(dir, job.optString("stored"));
    }

    // ------------------------------------------------------------ disco
    private JSONArray load() {
        if (!jsonFile.exists()) return new JSONArray();
        try (InputStream in = new FileInputStream(jsonFile)) {
            byte[] buf = new byte[(int) jsonFile.length()];
            int off = 0;
            while (off < buf.length) {
                int r = in.read(buf, off, buf.length - off);
                if (r < 0) break;
                off += r;
            }
            return new JSONArray(new String(buf, 0, off, StandardCharsets.UTF_8));
        } catch (IOException | JSONException e) {
            return new JSONArray();
        }
    }

    private void save() {
        File tmp = new File(jsonFile.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            out.write(jobs.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        //noinspection ResultOfMethodCallIgnored
        tmp.renameTo(jsonFile);
    }

    private static void copy(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
        }
    }

    private static String newId() {
        byte[] b = new byte[8];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }
}
