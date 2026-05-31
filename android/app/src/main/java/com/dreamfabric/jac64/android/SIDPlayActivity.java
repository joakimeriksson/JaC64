package com.dreamfabric.jac64.android;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.dreamfabric.jac64.C64Screen;
import com.dreamfabric.jac64.SIDMixer;
import com.dreamfabric.jsidplay.PSIDFile;
import com.dreamfabric.jsidplay.SIDPlayerEngine;
import com.dreamfabric.jsidplay.SIDStateSnapshot;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Android SID music player activity.
 * Uses SIDPlayerEngine for playback with real-time visualization.
 */
public class SIDPlayActivity extends Activity {

    private static final String TAG = "SIDPlay";
    private static final int REQUEST_OPEN_SID = 2001;

    // HVSC API
    private static final String HVSC_API = "https://hvsc.c64.org/api/v1/sids";
    private static final String HVSC_DOWNLOAD = "https://hvsc.c64.org/download/sids/";

    private SIDPlayerEngine engine;
    private AudioDriverAndroid audioDriver;
    private SIDVisualizationView vizView;
    private TextView titleView, metadataView, songLabel;
    private ImageButton btnPlay;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean vizRunning;

    // 50Hz visualization refresh
    private final Runnable vizRefresh = new Runnable() {
        @Override
        public void run() {
            if (!vizRunning) return;
            SIDStateSnapshot snap = engine.getLatestSnapshot();
            vizView.setSnapshot(snap);
            vizView.invalidate();
            handler.postDelayed(this, 20);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setupFullscreen();
        setContentView(R.layout.activity_sidplay);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Find views
        vizView = findViewById(R.id.sid_visualization);
        titleView = findViewById(R.id.sid_title);
        metadataView = findViewById(R.id.sid_metadata);
        songLabel = findViewById(R.id.sid_song_label);

        // Toolbar buttons
        findViewById(R.id.sid_btn_back).setOnClickListener(v -> finish());
        findViewById(R.id.sid_btn_open).setOnClickListener(v -> openFilePicker());
        findViewById(R.id.sid_btn_hvsc).setOnClickListener(v -> showHVSCSearch());
        findViewById(R.id.sid_btn_menu).setOnClickListener(this::showMenu);

        // Transport buttons
        btnPlay = findViewById(R.id.sid_btn_play);
        btnPlay.setOnClickListener(v -> togglePlayPause());
        findViewById(R.id.sid_btn_stop).setOnClickListener(v -> {
            engine.stop();
            updateUI();
        });
        findViewById(R.id.sid_btn_prev).setOnClickListener(v -> {
            engine.prevSong();
            engine.play();
            updateUI();
        });
        findViewById(R.id.sid_btn_next).setOnClickListener(v -> {
            engine.nextSong();
            engine.play();
            updateUI();
        });

        // Initialize engine
        initEngine();

        // Check for .sid file from intent
        Intent intent = getIntent();
        if (intent != null && intent.getData() != null) {
            loadFromUri(intent.getData());
        }
    }

    private void initEngine() {
        SIDMixer.DL_BUFFER_SIZE = 16384;
        audioDriver = new AudioDriverAndroid();
        AndroidLoader loader = new AndroidLoader(this);

        engine = new SIDPlayerEngine();
        engine.init(audioDriver, loader);
        engine.setOnStateChange(() -> runOnUiThread(this::updateUI));
    }

    private void setupFullscreen() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    @Override
    protected void onResume() {
        super.onResume();
        setupFullscreen();
        vizRunning = true;
        handler.post(vizRefresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        vizRunning = false;
        handler.removeCallbacks(vizRefresh);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        engine.stop();
        if (audioDriver != null) {
            audioDriver.shutdown();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) setupFullscreen();
    }

    // ---- Playback controls ----

    private void togglePlayPause() {
        if (engine.isPlaying() && !engine.isPaused()) {
            engine.pause();
        } else {
            engine.play();
        }
        updateUI();
    }

    private void updateUI() {
        PSIDFile psid = engine.getPSID();
        if (psid != null) {
            titleView.setText(psid.title);
            songLabel.setText(" " + engine.getCurrentSong() + "/" + psid.songs + " ");
            vizView.setCurrentSong(engine.getCurrentSong());

            String state = engine.isPlaying()
                    ? (engine.isPaused() ? "Paused" : "Playing")
                    : "Stopped";
            metadataView.setText(state + " - " + psid.author + " - " + psid.copyright);

            // Update play/pause icon
            btnPlay.setImageResource(
                    engine.isPlaying() && !engine.isPaused()
                            ? android.R.drawable.ic_media_pause
                            : android.R.drawable.ic_media_play);
        } else {
            songLabel.setText(" --/-- ");
            btnPlay.setImageResource(android.R.drawable.ic_media_play);
        }
    }

    // ---- File loading ----

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_OPEN_SID);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_OPEN_SID && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                loadFromUri(uri);
            }
        }
    }

    private void loadFromUri(Uri uri) {
        new Thread(() -> {
            try {
                InputStream is = getContentResolver().openInputStream(uri);
                if (is == null) throw new Exception("Cannot open file");

                // Read into byte array
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int len;
                while ((len = is.read(buf)) > 0) baos.write(buf, 0, len);
                is.close();
                byte[] data = baos.toByteArray();

                // Parse PSID
                PSIDFile psid = PSIDFile.load(new java.io.ByteArrayInputStream(data));
                Log.i(TAG, "Loaded: " + psid);

                runOnUiThread(() -> {
                    engine.loadSID(psid);
                    vizView.setPSIDInfo(psid);
                    updateUI();
                    engine.play();
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to load SID", e);
                runOnUiThread(() ->
                        Toast.makeText(this, "Failed to load: " + e.getMessage(),
                                Toast.LENGTH_LONG).show());
            }
        }, "SIDLoader").start();
    }

    private void loadFromFile(File file) {
        try {
            PSIDFile psid = PSIDFile.load(file);
            Log.i(TAG, "Loaded: " + psid);
            engine.loadSID(psid);
            vizView.setPSIDInfo(psid);
            updateUI();
            engine.play();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load SID", e);
            Toast.makeText(this, "Failed to load: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    // ---- HVSC Search ----

    private void showHVSCSearch() {
        // Build custom dialog layout
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(16), dp(12), dp(16), 0);

        EditText searchInput = new EditText(this);
        searchInput.setHint("Search artist, title...");
        searchInput.setSingleLine();
        layout.addView(searchInput);

        TextView statusText = new TextView(this);
        statusText.setText("Enter search term and press Search");
        statusText.setPadding(0, dp(8), 0, dp(4));
        layout.addView(statusText);

        ListView listView = new ListView(this);
        listView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(250)));
        layout.addView(listView);

        List<HVSCEntry> results = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, new ArrayList<>());
        listView.setAdapter(adapter);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("HVSC - High Voltage SID Collection")
                .setView(layout)
                .setPositiveButton("Search", null) // Override below
                .setNegativeButton("Cancel", null)
                .create();

        dialog.show();

        // Override search button to not dismiss
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String query = searchInput.getText().toString().trim();
            if (query.isEmpty()) return;
            statusText.setText("Searching...");
            adapter.clear();
            results.clear();

            new Thread(() -> {
                try {
                    List<HVSCEntry> found = searchHVSC(query);
                    runOnUiThread(() -> {
                        results.clear();
                        results.addAll(found);
                        adapter.clear();
                        for (HVSCEntry e : found) {
                            adapter.add(e.title + " - " + e.author);
                        }
                        statusText.setText(found.size() + " results");
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> statusText.setText("Error: " + e.getMessage()));
                }
            }, "HVSCSearch").start();
        });

        // Handle list item click → download and play
        listView.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= results.size()) return;
            HVSCEntry entry = results.get(position);
            statusText.setText("Downloading " + entry.title + "...");

            new Thread(() -> {
                try {
                    File file = downloadHVSC(entry.id);
                    runOnUiThread(() -> {
                        dialog.dismiss();
                        loadFromFile(file);
                    });
                } catch (Exception e) {
                    runOnUiThread(() -> statusText.setText("Download failed: " + e.getMessage()));
                }
            }, "HVSCDownload").start();
        });
    }

    private List<HVSCEntry> searchHVSC(String query) throws Exception {
        String encoded = URLEncoder.encode(query, "UTF-8");
        URL url = new URL(HVSC_API + "?q=" + encoded);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);

        int status = conn.getResponseCode();
        if (status != 200) throw new Exception("HTTP " + status);

        BufferedReader reader = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) sb.append(line);
        reader.close();

        return parseHVSCResults(sb.toString());
    }

    private List<HVSCEntry> parseHVSCResults(String json) {
        List<HVSCEntry> entries = new ArrayList<>();
        json = json.trim();
        if (!json.startsWith("[")) return entries;

        int i = 1;
        while (i < json.length()) {
            int objStart = json.indexOf('{', i);
            if (objStart < 0) break;
            int objEnd = json.indexOf('}', objStart);
            if (objEnd < 0) break;

            String obj = json.substring(objStart, objEnd + 1);
            int id = extractInt(obj, "id");
            String title = extractString(obj, "title");
            String author = extractString(obj, "author");

            if (id > 0 && title != null) {
                entries.add(new HVSCEntry(id, title, author != null ? author : ""));
            }
            i = objEnd + 1;
        }
        return entries;
    }

    private String extractString(String json, String key) {
        String pattern = "\"" + key + "\":\"";
        int idx = json.indexOf(pattern);
        if (idx < 0) return null;
        int start = idx + pattern.length();
        int end = json.indexOf("\"", start);
        if (end < 0) return null;
        return json.substring(start, end)
                .replace("\\\"", "\"").replace("\\\\", "\\")
                .replace("\\/", "/").replace("\\n", "\n");
    }

    private int extractInt(String json, String key) {
        String pattern = "\"" + key + "\":";
        int idx = json.indexOf(pattern);
        if (idx < 0) return -1;
        int start = idx + pattern.length();
        int end = start;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        if (end == start) return -1;
        return Integer.parseInt(json.substring(start, end));
    }

    private File downloadHVSC(int id) throws Exception {
        // Check cache
        File cacheDir = new File(getCacheDir(), "hvsc");
        if (!cacheDir.exists()) cacheDir.mkdirs();
        File cached = new File(cacheDir, id + ".sid");
        if (cached.exists() && cached.length() > 0) return cached;

        URL url = new URL(HVSC_DOWNLOAD + id);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        conn.setInstanceFollowRedirects(true);

        int status = conn.getResponseCode();
        if (status != 200) throw new Exception("HTTP " + status);

        try (InputStream is = conn.getInputStream();
             FileOutputStream fos = new FileOutputStream(cached)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) fos.write(buf, 0, n);
        }
        return cached;
    }

    // ---- Menu ----

    private void showMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        popup.getMenu().add(0, 1, 0, "SID: ReSID 6581");
        popup.getMenu().add(0, 2, 1, "SID: ReSID 8580");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    engine.setChipModel(C64Screen.RESID_6581);
                    Toast.makeText(this, "SID 6581", Toast.LENGTH_SHORT).show();
                    return true;
                case 2:
                    engine.setChipModel(C64Screen.RESID_8580);
                    Toast.makeText(this, "SID 8580", Toast.LENGTH_SHORT).show();
                    return true;
            }
            return false;
        });
        popup.show();
    }

    private int dp(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density);
    }

    // ---- Data classes ----

    private static class HVSCEntry {
        final int id;
        final String title;
        final String author;

        HVSCEntry(int id, String title, String author) {
            this.id = id;
            this.title = title;
            this.author = author;
        }
    }
}
