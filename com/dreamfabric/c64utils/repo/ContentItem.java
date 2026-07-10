package com.dreamfabric.c64utils.repo;

/**
 * One downloadable C64 release (demo, game, music, ...) as listed by a
 * ContentRepo. Plain data holder - no Android or AWT dependencies so it
 * compiles in both the desktop and Android builds.
 */
public class ContentItem {

    /** Repo that produced this item, e.g. "csdb" or "curated". */
    public String source;
    /** Source-specific id (CSDb release id, curated index id). */
    public String id;

    public String title;
    /** Releasing group or scener, if known. */
    public String author;
    /** Release type, e.g. "C64 Demo", "C64 Game", "C64 One-File Demo". */
    public String type;
    public int year;

    /** Direct download URL (may be null until details() is called). */
    public String downloadUrl;
    public String screenshotUrl;

    /** License / permission note - curated index only. */
    public String license;
    /** Optional integrity hash of the download - curated index only. */
    public String sha256;
    /** True if the item is known to need original Commodore ROMs. */
    public boolean needsOriginalRoms;

    public ContentItem() {
    }

    public ContentItem(String source, String id, String title) {
        this.source = source;
        this.id = id;
        this.title = title;
    }

    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(source).append(':').append(id).append("] ");
        sb.append(title);
        if (author != null) sb.append(" / ").append(author);
        if (year > 0) sb.append(" (").append(year).append(')');
        if (type != null) sb.append(" - ").append(type);
        return sb.toString();
    }
}
