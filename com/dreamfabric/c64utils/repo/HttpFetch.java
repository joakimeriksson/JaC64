package com.dreamfabric.c64utils.repo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Minimal HTTP helper on top of HttpURLConnection - works unchanged on
 * desktop Java and Android (no external dependencies). Follows redirects
 * manually so http->https and cross-host hops (common for CSDb download
 * links) work.
 */
public class HttpFetch {

    public static final String USER_AGENT = "JaC64/1.0 (+https://github.com/joakimeriksson/JaC64)";
    private static final int MAX_REDIRECTS = 6;
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;

    /** GET a URL and return the body, following redirects. */
    public static byte[] get(String url) throws IOException {
        HttpURLConnection conn = open(url);
        try {
            InputStream in = conn.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(in, out);
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    /** GET a URL and return the body decoded as UTF-8 text. */
    public static String getString(String url) throws IOException {
        return new String(get(url), "UTF-8");
    }

    /**
     * Download a URL into destDir. The file name is taken from the
     * Content-Disposition header when present, otherwise from the last
     * path segment of the (post-redirect) URL. Returns the written file.
     */
    public static File download(String url, File destDir) throws IOException {
        if (!destDir.isDirectory() && !destDir.mkdirs())
            throw new IOException("cannot create dir: " + destDir);
        HttpURLConnection conn = open(url);
        try {
            String name = fileNameFor(conn);
            File dest = new File(destDir, name);
            InputStream in = conn.getInputStream();
            OutputStream out = new FileOutputStream(dest);
            try {
                copy(in, out);
            } finally {
                out.close();
            }
            return dest;
        } finally {
            conn.disconnect();
        }
    }

    /** Open a connection with redirects already followed. */
    private static HttpURLConnection open(String url) throws IOException {
        String current = url;
        for (int i = 0; i < MAX_REDIRECTS; i++) {
            HttpURLConnection conn =
                (HttpURLConnection) new URL(current).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setInstanceFollowRedirects(false);
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = conn.getHeaderField("Location");
                conn.disconnect();
                if (loc == null)
                    throw new IOException("redirect without Location: " + current);
                current = new URL(new URL(current), loc).toString();
                continue;
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect();
                throw new IOException("HTTP " + code + " for " + current);
            }
            return conn;
        }
        throw new IOException("too many redirects for " + url);
    }

    private static String fileNameFor(HttpURLConnection conn) {
        String cd = conn.getHeaderField("Content-Disposition");
        if (cd != null) {
            int i = cd.toLowerCase().indexOf("filename=");
            if (i >= 0) {
                String name = cd.substring(i + 9).trim();
                if (name.startsWith("\"")) {
                    int end = name.indexOf('"', 1);
                    if (end > 1) name = name.substring(1, end);
                } else {
                    int end = name.indexOf(';');
                    if (end > 0) name = name.substring(0, end);
                }
                name = sanitize(name);
                if (name.length() > 0) return name;
            }
        }
        String path = conn.getURL().getPath();
        int slash = path.lastIndexOf('/');
        String name = sanitize(slash >= 0 ? path.substring(slash + 1) : path);
        return name.length() > 0 ? name : "download.bin";
    }

    /** Keep only a safe basename - no path separators or shell surprises. */
    private static String sanitize(String name) {
        name = name.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) name = name.substring(slash + 1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '.' || c == '-'
                    || c == '_' || c == ' ' || c == '(' || c == ')'
                    || c == '[' || c == ']' || c == '+' || c == '!')
                sb.append(c);
        }
        String out = sb.toString().trim();
        // no hidden/relative names
        while (out.startsWith(".")) out = out.substring(1);
        return out;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0)
            out.write(buf, 0, n);
    }
}
