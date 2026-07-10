package com.dreamfabric.c64utils.repo;

import java.io.IOException;
import java.util.List;

/**
 * A browsable source of C64 content (CSDb, curated index, ...).
 *
 * Named ContentRepo rather than ContentProvider to avoid clashing with
 * android.content.ContentProvider in the Android build.
 */
public interface ContentRepo {

    /** Short stable name, e.g. "csdb" or "curated". */
    String name();

    /** Most recent items, newest first. */
    List<ContentItem> latest(int max) throws IOException;

    /** Title/author substring search. May return empty if unsupported. */
    List<ContentItem> search(String query, int max) throws IOException;

    /**
     * Fill in full details (notably downloadUrl) for an item id.
     * Returns null if the id is unknown.
     */
    ContentItem details(String id) throws IOException;
}
