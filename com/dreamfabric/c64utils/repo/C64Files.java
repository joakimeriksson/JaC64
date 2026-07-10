package com.dreamfabric.c64utils.repo;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Identify and unpack downloaded C64 files. This is the shared-core home
 * of the logic that previously lived only in the Android MainActivity
 * (extractFromZip + extension dispatch), so both the desktop CLI harness
 * and the Android browser use the same pipeline.
 */
public class C64Files {

    /** Loadable file kinds understood by the emulator loaders. */
    public static final String[] LOADABLE_EXT = {
        ".d64", ".t64", ".prg", ".p00"
    };

    public static boolean isLoadable(String name) {
        String lower = name.toLowerCase();
        for (int i = 0; i < LOADABLE_EXT.length; i++)
            if (lower.endsWith(LOADABLE_EXT[i])) return true;
        return false;
    }

    /**
     * Kind of a file: "d64", "t64", "prg", "p00", "zip" or "unknown".
     * Uses the extension first, then falls back to magic bytes (zip "PK",
     * t64 "C64", p00 "C64File").
     */
    public static String kind(File f) throws IOException {
        String lower = f.getName().toLowerCase();
        for (int i = 0; i < LOADABLE_EXT.length; i++)
            if (lower.endsWith(LOADABLE_EXT[i]))
                return LOADABLE_EXT[i].substring(1);
        if (lower.endsWith(".zip")) return "zip";

        byte[] head = new byte[8];
        InputStream in = new FileInputStream(f);
        int n;
        try {
            n = in.read(head);
        } finally {
            in.close();
        }
        if (n >= 2 && head[0] == 'P' && head[1] == 'K') return "zip";
        if (n >= 7 && startsWith(head, "C64File")) return "p00";
        if (n >= 3 && startsWith(head, "C64")) return "t64";
        long len = f.length();
        if (len == 174848 || len == 175531 || len == 196608 || len == 197376)
            return "d64";
        return "unknown";
    }

    /**
     * Extract the first loadable C64 file from a zip into destDir.
     * Returns the extracted file, or null if the zip holds none.
     * Zip entry names are flattened to a safe basename (no zip-slip).
     */
    public static File extractFromZip(File zipFile, File destDir) throws IOException {
        if (!destDir.isDirectory() && !destDir.mkdirs())
            throw new IOException("cannot create dir: " + destDir);
        ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile));
        try {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().toLowerCase();
                if (isLoadable(name)) {
                    String base = entry.getName();
                    int slash = base.replace('\\', '/').lastIndexOf('/');
                    if (slash >= 0) base = base.substring(slash + 1);
                    File out = new File(destDir, base);
                    OutputStream os = new FileOutputStream(out);
                    try {
                        byte[] buf = new byte[16384];
                        int n;
                        while ((n = zis.read(buf)) > 0)
                            os.write(buf, 0, n);
                    } finally {
                        os.close();
                    }
                    return out;
                }
            }
        } finally {
            zis.close();
        }
        return null;
    }

    /**
     * Full pipeline: take a downloaded file, unzip if needed, and return
     * a directly loadable .d64/.t64/.prg/.p00 file - or null if the
     * download contained nothing loadable.
     */
    public static File toLoadable(File downloaded, File workDir) throws IOException {
        String k = kind(downloaded);
        if ("zip".equals(k))
            return extractFromZip(downloaded, workDir);
        if (isLoadable(downloaded.getName()) || !"unknown".equals(k))
            return downloaded;
        return null;
    }

    private static boolean startsWith(byte[] data, String prefix) {
        for (int i = 0; i < prefix.length(); i++)
            if (data[i] != (byte) prefix.charAt(i)) return false;
        return true;
    }
}
