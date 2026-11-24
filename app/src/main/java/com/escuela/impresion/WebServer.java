package com.escuela.impresion;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fi.iki.elonen.NanoHTTPD;

/**
 * Servidor HTTP dentro de la tablet.
 *  - "/"        página para que los docentes suban archivos (cualquier equipo de la red)
 *  - "/panel"   panel de la cola (solo desde la propia tablet)
 */
public class WebServer extends NanoHTTPD {

    public static final int PORT = 8080;

    static final Set<String> ALLOWED_EXT = new HashSet<>(Arrays.asList(
            ".pdf", ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp",
            ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx",
            ".odt", ".ods", ".odp", ".txt", ".rtf"));

    private static final Pattern JOB_ACTION = Pattern.compile("^/api/jobs/([a-f0-9]{16})/(status|delete)$");
    private static final String JSON = "application/json; charset=utf-8";

    public interface Listener {
        void onNewJob(JSONObject job);
    }

    private final Context ctx;
    private final JobStore store;
    private final Listener listener;

    public WebServer(Context ctx, Listener listener) {
        super(PORT);
        this.ctx = ctx.getApplicationContext();
        this.store = JobStore.get(ctx);
        this.listener = listener;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();
        boolean local = isLocal(session.getRemoteIpAddress());
        try {
            if (Method.GET.equals(method)) {
                switch (uri) {
                    case "/":
                    case "/index.html":
                        return asset("upload.html", "text/html; charset=utf-8");
                    case "/static/style.css":
                        return asset("style.css", "text/css; charset=utf-8");
                    case "/panel":
                        if (!local) return error(Response.Status.FORBIDDEN, "El panel solo se abre en la tablet");
                        return asset("panel.html", "text/html; charset=utf-8");
                    case "/api/info":
                        return info();
                    case "/api/jobs":
                        if (!local) return error(Response.Status.FORBIDDEN, "Solo desde la tablet");
                        return json(Response.Status.OK, store.listJson());
                    default:
                        return error(Response.Status.NOT_FOUND, "No encontrado");
                }
            }
            if (Method.POST.equals(method)) {
                if ("/api/upload".equals(uri)) return upload(session);
                Matcher m = JOB_ACTION.matcher(uri);
                if (m.matches()) {
                    if (!local) return error(Response.Status.FORBIDDEN, "Solo desde la tablet");
                    String id = m.group(1);
                    if ("delete".equals(m.group(2))) {
                        store.delete(id);
                    } else {
                        String s = param(session.getParameters(), "s");
                        if (!"pendiente".equals(s) && !"impreso".equals(s)) {
                            return error(Response.Status.BAD_REQUEST, "Estado inválido");
                        }
                        store.setStatus(id, s, "");
                    }
                    return json(Response.Status.OK, "{\"ok\":true}");
                }
            }
            return error(Response.Status.NOT_FOUND, "No encontrado");
        } catch (Exception e) {
            return error(Response.Status.INTERNAL_ERROR, "Error interno: " + e.getMessage());
        }
    }

    private Response info() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("school_name", store.schoolName());
        o.put("needs_code", !store.uploadCode().isEmpty());
        o.put("max_mb", store.maxMb());
        return json(Response.Status.OK, o.toString());
    }

    private Response upload(IHTTPSession session) throws IOException, ResponseException, JSONException {
        long max = store.maxMb() * 1024L * 1024L;
        String len = session.getHeaders().get("content-length");
        if (len != null) {
            try {
                if (Long.parseLong(len) > max + 64 * 1024) {
                    return error(Response.Status.BAD_REQUEST,
                            "Archivo demasiado grande (máximo " + store.maxMb() + " MB)");
                }
            } catch (NumberFormatException ignored) {
            }
        }

        Map<String, String> files = new HashMap<>();
        session.parseBody(files);
        Map<String, List<String>> p = session.getParameters();

        String code = store.uploadCode();
        if (!code.isEmpty() && !code.equals(text(p, "code"))) {
            return error(Response.Status.FORBIDDEN, "Código de acceso incorrecto");
        }
        String teacher = limit(text(p, "teacher"), 60);
        if (teacher.isEmpty()) return error(Response.Status.BAD_REQUEST, "Escribe tu nombre");

        String tmpPath = files.get("file");
        if (tmpPath == null) return error(Response.Status.BAD_REQUEST, "No se recibió ningún archivo");
        File tmp = new File(tmpPath);
        if (tmp.length() == 0) return error(Response.Status.BAD_REQUEST, "El archivo está vacío");
        if (tmp.length() > max) {
            return error(Response.Status.BAD_REQUEST, "Archivo demasiado grande (máximo " + store.maxMb() + " MB)");
        }

        // El nombre llega codificado (encodeURIComponent) para conservar tildes y ñ.
        String filename = text(p, "fname");
        if (filename.isEmpty()) filename = param(p, "file");
        filename = safeName(filename);
        int dot = filename.lastIndexOf('.');
        String ext = dot >= 0 ? filename.substring(dot).toLowerCase(Locale.ROOT) : "";
        if (!ALLOWED_EXT.contains(ext)) {
            return error(Response.Status.BAD_REQUEST, "Tipo de archivo no permitido: " + (ext.isEmpty() ? filename : ext));
        }

        int copies;
        try {
            copies = Math.max(1, Math.min(200, Integer.parseInt(text(p, "copies"))));
        } catch (NumberFormatException e) {
            copies = 1;
        }
        JSONObject job = store.add(tmp, filename, ext, teacher, limit(text(p, "notes"), 300), copies,
                "on".equals(text(p, "duplex")), "on".equals(text(p, "color")));
        if (listener != null) listener.onNewJob(job);
        return json(Response.Status.OK, "{\"ok\":true,\"id\":\"" + job.getString("id") + "\"}");
    }

    // ------------------------------------------------------------ utilidades
    private Response asset(String name, String mime) throws IOException {
        InputStream in = ctx.getAssets().open(name);
        Response r = newChunkedResponse(Response.Status.OK, mime, in);
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    private static Response json(Response.Status st, String body) {
        Response r = newFixedLengthResponse(st, JSON, body);
        r.addHeader("Cache-Control", "no-store");
        return r;
    }

    private static Response error(Response.Status st, String msg) {
        return json(st, "{\"error\":" + JSONObject.quote(msg) + "}");
    }

    private static String param(Map<String, List<String>> p, String key) {
        List<String> v = p.get(key);
        return v == null || v.isEmpty() || v.get(0) == null ? "" : v.get(0).trim();
    }

    /** Campo de texto enviado con encodeURIComponent desde el navegador. */
    private static String text(Map<String, List<String>> p, String key) {
        String raw = param(p, key);
        try {
            return URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8").trim();
        } catch (UnsupportedEncodingException | IllegalArgumentException e) {
            return raw;
        }
    }

    private static String limit(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    private static String safeName(String name) {
        name = name.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        name = name.replaceAll("[\\p{Cntrl}:*?\"<>|]", "_").trim();
        if (name.length() > 120) {
            int dot = name.lastIndexOf('.');
            String ext = dot > 0 ? name.substring(dot) : "";
            name = name.substring(0, 120 - ext.length()) + ext;
        }
        return name.isEmpty() ? "archivo" : name;
    }

    private static boolean isLocal(String ip) {
        return ip != null && (ip.startsWith("127.") || "::1".equals(ip) || "0:0:0:0:0:0:0:1".equals(ip));
    }
}
