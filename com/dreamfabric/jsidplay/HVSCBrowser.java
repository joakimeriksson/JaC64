package com.dreamfabric.jsidplay;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.util.ArrayList;
import java.util.List;

/**
 * HVSC (High Voltage SID Collection) browser dialog.
 * Searches the HVSC API and allows downloading SID files directly.
 */
public class HVSCBrowser extends JDialog {

    private static final String API_BASE = "https://hvsc.c64.org/api/v1/sids";
    private static final String DOWNLOAD_BASE = "https://hvsc.c64.org/download/sids/";

    private static final Color BG_DARK = new Color(0x16, 0x16, 0x3B);
    private static final Color BG_PANEL = new Color(0x1E, 0x1E, 0x50);
    private static final Color TEXT_PRIMARY = new Color(0x7B, 0x71, 0xD5);
    private static final Color TEXT_BRIGHT = new Color(0xA0, 0x98, 0xF0);
    private static final Color BUTTON_BG = new Color(0x2A, 0x2A, 0x60);
    private static final Color ACCENT = new Color(0x50, 0xD0, 0xE0);
    private static final Color TABLE_BG = new Color(0x12, 0x12, 0x34);
    private static final Color TABLE_SEL = new Color(0x3A, 0x34, 0x80);

    private JTextField searchField;
    private JButton searchButton;
    private JTable resultsTable;
    private DefaultTableModel tableModel;
    private JLabel statusLabel;
    private JButton loadButton;

    private List<SIDEntry> results = new ArrayList<>();
    private SIDEntry selectedEntry;
    private File downloadedFile;

    public static class SIDEntry {
        public int id;
        public String title;
        public String author;
        public String released;

        public SIDEntry(int id, String title, String author, String released) {
            this.id = id;
            this.title = title;
            this.author = author;
            this.released = released;
        }
    }

    public HVSCBrowser(Frame owner) {
        super(owner, "HVSC - High Voltage SID Collection", true);
        initUI();
        setSize(700, 500);
        setMinimumSize(new Dimension(500, 350));
        setLocationRelativeTo(owner);
    }

    private void initUI() {
        getContentPane().setBackground(BG_DARK);
        setLayout(new BorderLayout(0, 0));

        // Search bar
        JPanel searchPanel = new JPanel(new BorderLayout(6, 0));
        searchPanel.setBackground(BG_DARK);
        searchPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 6, 8));

        JLabel searchLabel = new JLabel("Search HVSC: ");
        searchLabel.setForeground(TEXT_PRIMARY);
        searchLabel.setFont(new Font("Monospaced", Font.PLAIN, 12));

        searchField = new JTextField(30);
        searchField.setBackground(BG_PANEL);
        searchField.setForeground(TEXT_BRIGHT);
        searchField.setCaretColor(TEXT_BRIGHT);
        searchField.setFont(new Font("Monospaced", Font.PLAIN, 13));
        searchField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(0x3A, 0x34, 0x80)),
                BorderFactory.createEmptyBorder(4, 6, 4, 6)));
        searchField.addActionListener(e -> doSearch());

        searchButton = new JButton("Search");
        searchButton.setBackground(BUTTON_BG);
        searchButton.setForeground(TEXT_PRIMARY);
        searchButton.setFont(new Font("Monospaced", Font.PLAIN, 12));
        searchButton.setFocusPainted(false);
        searchButton.addActionListener(e -> doSearch());

        searchPanel.add(searchLabel, BorderLayout.WEST);
        searchPanel.add(searchField, BorderLayout.CENTER);
        searchPanel.add(searchButton, BorderLayout.EAST);
        add(searchPanel, BorderLayout.NORTH);

        // Results table
        tableModel = new DefaultTableModel(
                new String[]{"Title", "Author", "Released"}, 0) {
            public boolean isCellEditable(int row, int col) { return false; }
        };
        resultsTable = new JTable(tableModel);
        resultsTable.setBackground(TABLE_BG);
        resultsTable.setForeground(TEXT_BRIGHT);
        resultsTable.setGridColor(new Color(0x2A, 0x2A, 0x50));
        resultsTable.setSelectionBackground(TABLE_SEL);
        resultsTable.setSelectionForeground(ACCENT);
        resultsTable.setFont(new Font("Monospaced", Font.PLAIN, 12));
        resultsTable.setRowHeight(22);
        resultsTable.getTableHeader().setBackground(BG_PANEL);
        resultsTable.getTableHeader().setForeground(TEXT_PRIMARY);
        resultsTable.getTableHeader().setFont(new Font("Monospaced", Font.BOLD, 11));

        // Column widths
        resultsTable.getColumnModel().getColumn(0).setPreferredWidth(250);
        resultsTable.getColumnModel().getColumn(1).setPreferredWidth(200);
        resultsTable.getColumnModel().getColumn(2).setPreferredWidth(150);

        resultsTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultsTable.addMouseListener(new MouseAdapter() {
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    loadSelected();
                }
            }
        });
        resultsTable.getSelectionModel().addListSelectionListener(e -> {
            int row = resultsTable.getSelectedRow();
            selectedEntry = (row >= 0 && row < results.size()) ? results.get(row) : null;
            loadButton.setEnabled(selectedEntry != null);
        });

        JScrollPane scrollPane = new JScrollPane(resultsTable);
        scrollPane.getViewport().setBackground(TABLE_BG);
        scrollPane.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        add(scrollPane, BorderLayout.CENTER);

        // Bottom panel
        JPanel bottomPanel = new JPanel(new BorderLayout(8, 0));
        bottomPanel.setBackground(BG_DARK);
        bottomPanel.setBorder(BorderFactory.createEmptyBorder(6, 8, 8, 8));

        statusLabel = new JLabel("Enter a search term and press Enter");
        statusLabel.setForeground(TEXT_PRIMARY);
        statusLabel.setFont(new Font("Monospaced", Font.PLAIN, 11));

        loadButton = new JButton("Load & Play");
        loadButton.setBackground(BUTTON_BG);
        loadButton.setForeground(ACCENT);
        loadButton.setFont(new Font("Monospaced", Font.BOLD, 12));
        loadButton.setFocusPainted(false);
        loadButton.setEnabled(false);
        loadButton.addActionListener(e -> loadSelected());

        JButton cancelButton = new JButton("Cancel");
        cancelButton.setBackground(BUTTON_BG);
        cancelButton.setForeground(TEXT_PRIMARY);
        cancelButton.setFont(new Font("Monospaced", Font.PLAIN, 12));
        cancelButton.setFocusPainted(false);
        cancelButton.addActionListener(e -> dispose());

        JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        buttonPanel.setOpaque(false);
        buttonPanel.add(loadButton);
        buttonPanel.add(cancelButton);

        bottomPanel.add(statusLabel, BorderLayout.CENTER);
        bottomPanel.add(buttonPanel, BorderLayout.EAST);
        add(bottomPanel, BorderLayout.SOUTH);
    }

    private void doSearch() {
        String query = searchField.getText().trim();
        if (query.isEmpty()) return;

        searchButton.setEnabled(false);
        statusLabel.setText("Searching...");
        tableModel.setRowCount(0);
        results.clear();

        SwingWorker<List<SIDEntry>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<SIDEntry> doInBackground() throws Exception {
                String encoded = URLEncoder.encode(query, "UTF-8");
                URL url = new URL(API_BASE + "?q=" + encoded);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setRequestProperty("Accept", "application/json");
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);

                int status = conn.getResponseCode();
                if (status != 200) {
                    throw new IOException("HTTP " + status);
                }

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();

                return parseResults(sb.toString());
            }

            @Override
            protected void done() {
                searchButton.setEnabled(true);
                try {
                    results = get();
                    for (SIDEntry entry : results) {
                        tableModel.addRow(new Object[]{
                                entry.title, entry.author, entry.released
                        });
                    }
                    statusLabel.setText(results.size() + " results found");
                } catch (Exception ex) {
                    statusLabel.setText("Error: " + ex.getMessage());
                }
            }
        };
        worker.execute();
    }

    /**
     * Simple JSON array parser for HVSC search results.
     * Parses: [{"id":123,"title":"...","author":"...","released":"..."}, ...]
     */
    private List<SIDEntry> parseResults(String json) {
        List<SIDEntry> entries = new ArrayList<>();
        json = json.trim();
        if (!json.startsWith("[")) return entries;

        // Simple state-machine parser for the flat JSON array
        int i = 1; // skip opening [
        while (i < json.length()) {
            int objStart = json.indexOf('{', i);
            if (objStart < 0) break;
            int objEnd = json.indexOf('}', objStart);
            if (objEnd < 0) break;

            String obj = json.substring(objStart, objEnd + 1);
            int id = extractInt(obj, "id");
            String title = extractString(obj, "title");
            String author = extractString(obj, "author");
            String released = extractString(obj, "released");

            if (id > 0 && title != null) {
                entries.add(new SIDEntry(id, title,
                        author != null ? author : "",
                        released != null ? released : ""));
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
        return unescapeJson(json.substring(start, end));
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

    private String unescapeJson(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\")
                .replace("\\/", "/").replace("\\n", "\n");
    }

    private static File getCacheDir() {
        File dir = new File(System.getProperty("user.home"), ".jsidplay/cache");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static File getCachedFile(int id) {
        return new File(getCacheDir(), id + ".sid");
    }

    private void loadSelected() {
        if (selectedEntry == null) return;

        // Check cache first
        File cached = getCachedFile(selectedEntry.id);
        if (cached.exists() && cached.length() > 0) {
            downloadedFile = cached;
            statusLabel.setText("Loaded from cache: " + selectedEntry.title);
            dispose();
            return;
        }

        loadButton.setEnabled(false);
        statusLabel.setText("Downloading " + selectedEntry.title + "...");

        SwingWorker<File, Void> worker = new SwingWorker<>() {
            @Override
            protected File doInBackground() throws Exception {
                URL url = new URL(DOWNLOAD_BASE + selectedEntry.id);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setInstanceFollowRedirects(true);

                int status = conn.getResponseCode();
                if (status != 200) {
                    throw new IOException("HTTP " + status);
                }

                // Save to cache directory
                File target = getCachedFile(selectedEntry.id);
                try (InputStream is = conn.getInputStream();
                     FileOutputStream fos = new FileOutputStream(target)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) != -1) {
                        fos.write(buf, 0, n);
                    }
                }
                return target;
            }

            @Override
            protected void done() {
                loadButton.setEnabled(true);
                try {
                    downloadedFile = get();
                    statusLabel.setText("Downloaded: " + selectedEntry.title);
                    dispose();
                } catch (Exception ex) {
                    statusLabel.setText("Download failed: " + ex.getMessage());
                    downloadedFile = null;
                }
            }
        };
        worker.execute();
    }

    /**
     * Returns the downloaded file after the dialog is closed, or null if cancelled.
     */
    public File getDownloadedFile() {
        return downloadedFile;
    }

    /**
     * Returns the selected entry info, or null.
     */
    public SIDEntry getSelectedEntry() {
        return selectedEntry;
    }
}
