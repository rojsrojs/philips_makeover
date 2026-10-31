package se.makeover.home;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Minimal HTTP server that lets a phone on the same network upload background images.
 * It only runs while the upload dialog is open, and every upload must carry the PIN shown on the TV.
 */
final class UploadServer {
    interface Listener {
        void onImageSaved(String name);
    }

    private static final long MAX_UPLOAD_BYTES = 60L * 1024 * 1024;
    private static final int FIRST_PORT = 8080;
    private static final int LAST_PORT = 8090;

    private final Context context;
    private final String pin;
    private final Listener listener;
    private final byte[] page;
    private ServerSocket server;

    UploadServer(Context context, String pin, Listener listener) throws IOException {
        this.context = context.getApplicationContext();
        this.pin = pin;
        this.listener = listener;
        try (InputStream in = context.getAssets().open("upload.html")) {
            page = readAll(in);
        }
    }

    /** Starts listening on the first free port from 8080 and returns that port. */
    int start() throws IOException {
        IOException last = null;
        for (int port = FIRST_PORT; port <= LAST_PORT && server == null; port++) {
            try {
                server = new ServerSocket(port);
            } catch (IOException e) {
                last = e;
            }
        }
        if (server == null) throw last;
        new Thread(this::acceptLoop, "upload-server").start();
        return server.getLocalPort();
    }

    void stop() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
            // Already closed.
        }
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                new Thread(() -> handle(client), "upload-client").start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket client) {
        try (Socket socket = client;
             InputStream in = new BufferedInputStream(socket.getInputStream());
             OutputStream out = socket.getOutputStream()) {
            socket.setSoTimeout(30_000);
            String requestLine = readLine(in);
            if (requestLine == null) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;

            long length = 0;
            for (String header = readLine(in); header != null && !header.isEmpty(); header = readLine(in)) {
                int colon = header.indexOf(':');
                if (colon > 0 && header.substring(0, colon).trim().equalsIgnoreCase("Content-Length")) {
                    length = Long.parseLong(header.substring(colon + 1).trim());
                }
            }

            String method = parts[0];
            String path = parts[1];
            Map<String, String> query = new HashMap<>();
            int mark = path.indexOf('?');
            if (mark >= 0) {
                parseQuery(path.substring(mark + 1), query);
                path = path.substring(0, mark);
            }
            boolean pinOk = pin.equals(query.get("pin"));

            if (method.equals("GET") && path.equals("/")) {
                respond(out, 200, "text/html; charset=utf-8", page);
            } else if (method.equals("GET") && path.equals("/check")) {
                respond(out, pinOk ? 200 : 403, pinOk ? "OK" : "Fel kod");
            } else if (method.equals("POST") && path.equals("/upload")) {
                if (!pinOk) {
                    skip(in, length);
                    respond(out, 403, "Fel kod");
                } else if (length <= 0 || length > MAX_UPLOAD_BYTES) {
                    skip(in, length);
                    respond(out, 413, "Bilden är för stor");
                } else {
                    File saved = receiveImage(in, length);
                    if (saved == null) {
                        respond(out, 415, "Bildformatet stöds inte");
                    } else {
                        listener.onImageSaved(saved.getName());
                        respond(out, 200, "OK");
                    }
                }
            } else {
                skip(in, length);
                respond(out, 404, "Hittades inte");
            }
        } catch (IOException | RuntimeException e) {
            // The phone disconnected or sent something unexpected; nothing to clean up.
        }
    }

    private File receiveImage(InputStream in, long length) throws IOException {
        File temp = File.createTempFile("upload", ".tmp", context.getCacheDir());
        try {
            try (OutputStream file = new FileOutputStream(temp)) {
                byte[] buffer = new byte[64 * 1024];
                long remaining = length;
                while (remaining > 0) {
                    int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read < 0) throw new IOException("Upload ended early");
                    file.write(buffer, 0, read);
                    remaining -= read;
                }
            }
            return Backgrounds.saveUpload(context, temp);
        } finally {
            temp.delete();
        }
    }

    private static void respond(OutputStream out, int status, String text) throws IOException {
        respond(out, status, "text/plain; charset=utf-8", text.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(OutputStream out, int status, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + status + " " + reason(status) + "\r\n"
            + "Content-Type: " + type + "\r\n"
            + "Content-Length: " + body.length + "\r\n"
            + "Cache-Control: no-store\r\n"
            + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 413: return "Payload Too Large";
            case 415: return "Unsupported Media Type";
            default: return "Error";
        }
    }

    /** Reads one CRLF-terminated header line, or null at end of stream. */
    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int b = in.read(); b != '\n'; b = in.read()) {
            if (b < 0) return line.size() == 0 ? null : line.toString("ISO-8859-1");
            if (b != '\r') line.write(b);
            if (line.size() > 8192) throw new IOException("Header too long");
        }
        return line.toString("ISO-8859-1");
    }

    private static void parseQuery(String query, Map<String, String> into) throws UnsupportedEncodingException {
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                into.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"),
                    URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
            }
        }
    }

    private static void skip(InputStream in, long length) throws IOException {
        long remaining = Math.min(length, MAX_UPLOAD_BYTES);
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) return;
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) out.write(buffer, 0, read);
        return out.toByteArray();
    }
}
