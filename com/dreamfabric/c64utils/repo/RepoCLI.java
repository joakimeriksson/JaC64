package com.dreamfabric.c64utils.repo;

import java.io.File;
import java.util.List;

/**
 * Desktop CLI harness for the content-repo module. This is how the
 * download/extract/identify pipeline is developed and tested in an
 * environment without the Android SDK (see docs/android/CONTENT_BROWSER_PLAN.md).
 *
 * Usage:
 *   java com.dreamfabric.c64utils.repo.RepoCLI latest [n]
 *   java com.dreamfabric.c64utils.repo.RepoCLI info <csdb-id>
 *   java com.dreamfabric.c64utils.repo.RepoCLI get <csdb-id> [destDir]
 *   java com.dreamfabric.c64utils.repo.RepoCLI curated <url|file> [query]
 *   java com.dreamfabric.c64utils.repo.RepoCLI curated-get <url|file> <id> [destDir]
 *
 * "get" prints the path of the resulting loadable file (.d64/.t64/.prg/.p00)
 * on the last line, so scripts can feed it straight into the desktop
 * emulator or the MCP load_file tool.
 */
public class RepoCLI {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        String cmd = args[0];
        if ("latest".equals(cmd)) {
            int n = args.length > 1 ? Integer.parseInt(args[1]) : 10;
            List<ContentItem> items = new CsdbRepo().latest(n);
            for (int i = 0; i < items.size(); i++)
                System.out.println(items.get(i));
        } else if ("search".equals(cmd) && args.length >= 2) {
            List<ContentItem> items = new CsdbRepo().search(args[1], 25);
            for (int i = 0; i < items.size(); i++)
                System.out.println(items.get(i));
        } else if ("info".equals(cmd) && args.length >= 2) {
            ContentItem item = new CsdbRepo().details(args[1]);
            if (item == null) {
                System.out.println("not found: " + args[1]);
                System.exit(1);
            }
            System.out.println(item);
            System.out.println("  download:   " + item.downloadUrl);
            System.out.println("  screenshot: " + item.screenshotUrl);
        } else if ("get".equals(cmd) && args.length >= 2) {
            File destDir = new File(args.length > 2 ? args[2] : ".");
            fetch(new CsdbRepo(), args[1], destDir);
        } else if ("curated".equals(cmd) && args.length >= 2) {
            CuratedIndexRepo repo = new CuratedIndexRepo(args[1]);
            List<ContentItem> items = args.length > 2
                ? repo.search(args[2], 50) : repo.latest(50);
            for (int i = 0; i < items.size(); i++) {
                ContentItem item = items.get(i);
                System.out.println(item);
                if (item.license != null)
                    System.out.println("    license: " + item.license);
            }
        } else if ("curated-get".equals(cmd) && args.length >= 3) {
            File destDir = new File(args.length > 3 ? args[3] : ".");
            fetch(new CuratedIndexRepo(args[1]), args[2], destDir);
        } else {
            usage();
            System.exit(1);
        }
    }

    /** Download an item, unpack to a loadable file, print its path last. */
    private static void fetch(ContentRepo repo, String id, File destDir)
            throws Exception {
        ContentItem item = repo.details(id);
        if (item == null) {
            System.out.println("not found in " + repo.name() + ": " + id);
            System.exit(1);
        }
        if (item.downloadUrl == null) {
            System.out.println("no download link for: " + item);
            System.exit(1);
        }
        System.out.println("fetching " + item);
        System.out.println("  from " + item.downloadUrl);
        File downloaded = HttpFetch.download(item.downloadUrl, destDir);
        System.out.println("  downloaded " + downloaded + " ("
            + downloaded.length() + " bytes, kind=" + C64Files.kind(downloaded) + ")");
        File loadable = C64Files.toLoadable(downloaded, destDir);
        if (loadable == null) {
            System.out.println("no loadable C64 file in download");
            System.exit(1);
        }
        System.out.println(loadable.getAbsolutePath());
    }

    private static void usage() {
        System.out.println("usage: RepoCLI latest [n]");
        System.out.println("       RepoCLI search <query>");
        System.out.println("       RepoCLI info <csdb-id>");
        System.out.println("       RepoCLI get <csdb-id> [destDir]");
        System.out.println("       RepoCLI curated <url|file> [query]");
        System.out.println("       RepoCLI curated-get <url|file> <id> [destDir]");
    }
}
