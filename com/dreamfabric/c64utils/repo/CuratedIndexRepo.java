package com.dreamfabric.c64utils.repo;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;

/**
 * Curated content index - a JSON file we control (hosted e.g. as a raw
 * GitHub URL) listing openly-licensed games and hand-picked demos. Being
 * remote and editable it doubles as the takedown mechanism.
 *
 * Index schema (version 1):
 * {
 *   "version": 1,
 *   "items": [
 *     { "id": "...", "title": "...", "author": "...", "year": 2020,
 *       "type": "game", "license": "freeware (author permission)",
 *       "download": "https://...", "screenshot": "https://...",
 *       "sha256": "...", "needsOriginalRoms": false }
 *   ]
 * }
 */
public class CuratedIndexRepo implements ContentRepo {

    /** Gson mapping of one index entry. */
    static class IndexEntry {
        String id;
        String title;
        String author;
        int year;
        String type;
        String license;
        String download;
        String screenshot;
        String sha256;
        boolean needsOriginalRoms;
    }

    static class Index {
        int version;
        List<IndexEntry> items;
    }

    private final String location;
    private String rawJson;
    private List<ContentItem> items;

    /** location is a http(s) URL or a local file path. */
    public CuratedIndexRepo(String location) {
        this.location = location;
    }

    /**
     * Build a repo from an already-loaded JSON string - used on Android to
     * read the index straight from a bundled asset (no file path needed).
     */
    public static CuratedIndexRepo fromJson(String json) {
        CuratedIndexRepo repo = new CuratedIndexRepo(null);
        repo.rawJson = json;
        return repo;
    }

    public String name() {
        return "curated";
    }

    public List<ContentItem> latest(int max) throws IOException {
        load();
        return items.subList(0, Math.min(max, items.size()));
    }

    public List<ContentItem> search(String query, int max) throws IOException {
        load();
        String q = query.toLowerCase();
        List<ContentItem> hits = new ArrayList<ContentItem>();
        for (int i = 0; i < items.size() && hits.size() < max; i++) {
            ContentItem item = items.get(i);
            if ((item.title != null && item.title.toLowerCase().contains(q))
                    || (item.author != null && item.author.toLowerCase().contains(q)))
                hits.add(item);
        }
        return hits;
    }

    public ContentItem details(String id) throws IOException {
        load();
        for (int i = 0; i < items.size(); i++)
            if (items.get(i).id.equals(id)) return items.get(i);
        return null;
    }

    private void load() throws IOException {
        if (items != null) return;
        String json;
        if (rawJson != null) {
            json = rawJson;
        } else if (location.startsWith("http://") || location.startsWith("https://")) {
            json = HttpFetch.getString(location);
        } else {
            json = readFile(new File(location));
        }
        Index index = new Gson().fromJson(json, Index.class);
        if (index == null || index.items == null)
            throw new IOException("bad curated index: " + location);
        List<ContentItem> result = new ArrayList<ContentItem>();
        for (int i = 0; i < index.items.size(); i++) {
            IndexEntry e = index.items.get(i);
            if (e.id == null || e.title == null || e.download == null)
                continue; // skip malformed entries rather than fail the list
            ContentItem item = new ContentItem(name(), e.id, e.title);
            item.author = e.author;
            item.year = e.year;
            item.type = e.type;
            item.license = e.license;
            item.downloadUrl = e.download;
            item.screenshotUrl = e.screenshot;
            item.sha256 = e.sha256;
            item.needsOriginalRoms = e.needsOriginalRoms;
            result.add(item);
        }
        items = result;
    }

    private static String readFile(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            byte[] data = new byte[(int) f.length()];
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
            return new String(data, 0, off, "UTF-8");
        } finally {
            in.close();
        }
    }
}
