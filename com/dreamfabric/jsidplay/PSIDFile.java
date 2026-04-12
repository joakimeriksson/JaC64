package com.dreamfabric.jsidplay;

import java.io.*;

/**
 * Parser for PSID/RSID SID music file format.
 * Supports PSID v1, v2, and RSID headers.
 */
public class PSIDFile {

    public static final String MAGIC_PSID = "PSID";
    public static final String MAGIC_RSID = "RSID";

    // Header fields
    public String magic;
    public int version;
    public int dataOffset;
    public int loadAddress;
    public int initAddress;
    public int playAddress;
    public int songs;
    public int startSong;
    public long speedFlags;

    // Metadata (32 chars each, null-terminated)
    public String title = "";
    public String author = "";
    public String copyright = "";

    // v2 fields
    public int flags;

    // The raw binary program data to load into C64 memory
    public byte[] data;

    /**
     * Get the actual load address. If the header loadAddress is 0,
     * the first two bytes of data contain the address (little-endian).
     */
    public int getActualLoadAddress() {
        if (loadAddress != 0) return loadAddress;
        if (data != null && data.length >= 2) {
            return (data[0] & 0xFF) | ((data[1] & 0xFF) << 8);
        }
        return 0;
    }

    /**
     * Get the offset into data[] where the actual program bytes start.
     * If loadAddress was 0, the first 2 bytes are the address, not program data.
     */
    public int getDataStartOffset() {
        return (loadAddress == 0) ? 2 : 0;
    }

    /**
     * Returns true if the speed flag for the given song indicates CIA timing (60Hz).
     * If false, the song uses VBI timing (50Hz PAL).
     */
    public boolean isCIATiming(int songNumber) {
        return (speedFlags & (1L << (songNumber - 1))) != 0;
    }

    /**
     * Load a PSID file from a File.
     */
    public static PSIDFile load(File file) throws IOException {
        try (FileInputStream fis = new FileInputStream(file)) {
            return load(fis);
        }
    }

    /**
     * Load a PSID file from an InputStream.
     */
    public static PSIDFile load(InputStream stream) throws IOException {
        PSIDFile psid = new PSIDFile();
        DataInputStream dis = new DataInputStream(new BufferedInputStream(stream));

        // Read magic (4 bytes)
        byte[] magicBytes = new byte[4];
        dis.readFully(magicBytes);
        psid.magic = new String(magicBytes, "ASCII");
        if (!MAGIC_PSID.equals(psid.magic) && !MAGIC_RSID.equals(psid.magic)) {
            throw new IOException("Not a PSID/RSID file (magic: " + psid.magic + ")");
        }

        // Read header fields (all big-endian)
        psid.version = dis.readUnsignedShort();
        psid.dataOffset = dis.readUnsignedShort();
        psid.loadAddress = dis.readUnsignedShort();
        psid.initAddress = dis.readUnsignedShort();
        psid.playAddress = dis.readUnsignedShort();
        psid.songs = dis.readUnsignedShort();
        psid.startSong = dis.readUnsignedShort();
        psid.speedFlags = dis.readInt() & 0xFFFFFFFFL;

        // Read metadata strings (32 bytes each)
        psid.title = readString(dis, 32);
        psid.author = readString(dis, 32);
        psid.copyright = readString(dis, 32);

        // v2+ has additional fields after offset 0x76
        // We've read 4+2+2+2+2+2+2+2+4+32+32+32 = 118 = 0x76 bytes so far
        int bytesRead = 0x76;
        if (psid.version >= 2 && psid.dataOffset >= 0x7C) {
            psid.flags = dis.readUnsignedShort();
            bytesRead += 2; // now at 0x78
        }
        // Skip remaining header bytes to reach dataOffset
        int remaining = psid.dataOffset - bytesRead;
        if (remaining > 0) {
            dis.skipBytes(remaining);
        }

        // Read all remaining data (the C64 binary)
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = dis.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        psid.data = baos.toByteArray();

        // Sanitize
        if (psid.songs < 1) psid.songs = 1;
        if (psid.startSong < 1) psid.startSong = 1;
        if (psid.startSong > psid.songs) psid.startSong = 1;

        return psid;
    }

    private static String readString(DataInputStream dis, int len) throws IOException {
        byte[] buf = new byte[len];
        dis.readFully(buf);
        // Find null terminator
        int end = len;
        for (int i = 0; i < len; i++) {
            if (buf[i] == 0) {
                end = i;
                break;
            }
        }
        return new String(buf, 0, end, "ISO-8859-1").trim();
    }

    @Override
    public String toString() {
        return String.format("PSID v%d \"%s\" by %s (%s) - %d song(s), load=$%04X init=$%04X play=$%04X",
                version, title, author, copyright, songs,
                getActualLoadAddress(), initAddress, playAddress);
    }
}
