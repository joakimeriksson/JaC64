package com.dreamfabric.jac64.android;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.dreamfabric.c64utils.repo.ContentItem;
import com.dreamfabric.c64utils.repo.ContentRepo;
import com.dreamfabric.c64utils.repo.CsdbRepo;
import com.dreamfabric.c64utils.repo.CuratedIndexRepo;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * In-app content browser: list CSDb latest releases, browse the curated
 * open-games index, or search CSDb by name. Tapping an item resolves its
 * download URL and returns it to MainActivity, which loads it through the
 * existing download/unzip/mount pipeline (loadFromUrl).
 *
 * All network + parsing lives in the shared core
 * (com.dreamfabric.c64utils.repo); this Activity is only UI + glue.
 */
public class BrowseActivity extends Activity {

    /** Result extra: the resolved download URL for MainActivity to load. */
    public static final String EXTRA_DOWNLOAD_URL = "download_url";
    public static final String EXTRA_TITLE = "title";

    /**
     * Remote curated index (hosted, editable -> doubles as takedown
     * mechanism). When empty or unreachable we fall back to the index
     * bundled in assets/curated-index.json.
     */
    private static final String CURATED_INDEX_URL = "";

    private enum Mode { LATEST, GAMES, SEARCH }

    private final CsdbRepo csdb = new CsdbRepo();
    private final List<ContentItem> items = new ArrayList<>();
    private ItemAdapter adapter;

    private EditText searchInput;
    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupFullscreen();
        setContentView(R.layout.activity_browse);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        searchInput = findViewById(R.id.browse_search_input);
        status = findViewById(R.id.browse_status);

        ListView list = findViewById(R.id.browse_list);
        adapter = new ItemAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> onItemTapped(position));

        Button back = findViewById(R.id.browse_btn_back);
        back.setOnClickListener(v -> finish());
        findViewById(R.id.browse_tab_latest).setOnClickListener(v -> loadLatest());
        findViewById(R.id.browse_tab_games).setOnClickListener(v -> loadGames());
        findViewById(R.id.browse_btn_search).setOnClickListener(v -> doSearch());
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch();
                return true;
            }
            return false;
        });

        // Open on the latest releases.
        loadLatest();
    }

    // ---- modes ----

    private void loadLatest() {
        setStatus("Loading latest releases...");
        runRepo(new Runnable() {
            public void run() {
                try {
                    show(csdb.latest(40), Mode.LATEST, null);
                } catch (Exception e) {
                    fail(e);
                }
            }
        });
    }

    private void loadGames() {
        setStatus("Loading games...");
        runRepo(new Runnable() {
            public void run() {
                try {
                    show(curatedRepo().latest(200), Mode.GAMES, null);
                } catch (Exception e) {
                    fail(e);
                }
            }
        });
    }

    private void doSearch() {
        final String query = searchInput.getText().toString().trim();
        if (query.isEmpty()) {
            setStatus("Enter a search term.");
            return;
        }
        setStatus("Searching CSDb for \"" + query + "\"...");
        runRepo(new Runnable() {
            public void run() {
                try {
                    show(csdb.search(query, 40), Mode.SEARCH, query);
                } catch (Exception e) {
                    fail(e);
                }
            }
        });
    }

    /**
     * Curated repo backed by the remote index when configured, otherwise
     * the copy bundled in assets. Called off the main thread.
     */
    private ContentRepo curatedRepo() throws Exception {
        if (CURATED_INDEX_URL != null && CURATED_INDEX_URL.length() > 0) {
            try {
                CuratedIndexRepo remote = new CuratedIndexRepo(CURATED_INDEX_URL);
                remote.latest(1); // force load; throws if unreachable
                return remote;
            } catch (Exception e) {
                // fall through to bundled asset
            }
        }
        return CuratedIndexRepo.fromJson(readAsset("curated-index.json"));
    }

    // ---- item selection ----

    private void onItemTapped(int position) {
        if (position < 0 || position >= items.size()) return;
        final ContentItem item = items.get(position);
        setStatus("Fetching " + item.title + "...");
        runRepo(new Runnable() {
            public void run() {
                try {
                    String url = item.downloadUrl;
                    // CSDb search rows only carry id+title; resolve details.
                    if (url == null && "csdb".equals(item.source)) {
                        ContentItem full = csdb.details(item.id);
                        if (full != null) url = full.downloadUrl;
                    }
                    if (url == null) {
                        setStatus("No download link for " + item.title);
                        return;
                    }
                    returnUrl(url, item.title);
                } catch (Exception e) {
                    fail(e);
                }
            }
        });
    }

    private void returnUrl(final String url, final String title) {
        runOnUiThread(new Runnable() {
            public void run() {
                Intent data = new Intent();
                data.putExtra(EXTRA_DOWNLOAD_URL, url);
                data.putExtra(EXTRA_TITLE, title);
                setResult(RESULT_OK, data);
                finish();
            }
        });
    }

    // ---- list plumbing ----

    private void show(final List<ContentItem> found, final Mode mode, final String query) {
        runOnUiThread(new Runnable() {
            public void run() {
                items.clear();
                items.addAll(found);
                adapter.notifyDataSetChanged();
                String what = mode == Mode.LATEST ? "latest releases"
                        : mode == Mode.GAMES ? "games"
                        : "results for \"" + query + "\"";
                setStatus(found.isEmpty() ? "No " + what + "." : found.size() + " " + what);
            }
        });
    }

    private void runRepo(Runnable work) {
        new Thread(work, "BrowseRepo").start();
    }

    private void fail(final Exception e) {
        setStatus("Error: " + e.getMessage());
    }

    private void setStatus(final String text) {
        runOnUiThread(new Runnable() {
            public void run() {
                status.setText(text);
            }
        });
    }

    private String readAsset(String name) throws Exception {
        InputStream in = getAssets().open(name);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    // ---- adapter: 2-line rows built in code (no androidx) ----

    private class ItemAdapter extends BaseAdapter {
        public int getCount() { return items.size(); }
        public Object getItem(int i) { return items.get(i); }
        public long getItemId(int i) { return i; }

        public View getView(int position, View convertView, ViewGroup parent) {
            LinearLayout row;
            if (convertView instanceof LinearLayout) {
                row = (LinearLayout) convertView;
            } else {
                row = new LinearLayout(BrowseActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                int pad = dp(10);
                row.setPadding(pad, dp(8), pad, dp(8));

                TextView title = new TextView(BrowseActivity.this);
                title.setId(android.R.id.text1);
                title.setTextColor(Color.WHITE);
                title.setTextSize(16);
                row.addView(title);

                TextView sub = new TextView(BrowseActivity.this);
                sub.setId(android.R.id.text2);
                sub.setTextColor(Color.parseColor("#AAAAAA"));
                sub.setTextSize(13);
                row.addView(sub);
            }

            ContentItem item = items.get(position);
            ((TextView) row.findViewById(android.R.id.text1)).setText(item.title);
            ((TextView) row.findViewById(android.R.id.text2)).setText(subtitle(item));
            return row;
        }

        private String subtitle(ContentItem item) {
            StringBuilder sb = new StringBuilder();
            if (item.author != null) sb.append(item.author);
            if (item.year > 0) {
                if (sb.length() > 0) sb.append("  ");
                sb.append('(').append(item.year).append(')');
            }
            if (item.type != null) {
                if (sb.length() > 0) sb.append("  -  ");
                sb.append(item.type);
            }
            if (item.needsOriginalRoms) sb.append("   [needs original ROMs]");
            return sb.toString();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void setupFullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) setupFullscreen();
    }
}
