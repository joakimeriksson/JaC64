package com.dreamfabric.c64utils.repo;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * CSDb (csdb.dk) content repo.
 *
 * latest():  RSS feed https://csdb.dk/rss/latestreleases.php - one item per
 *            release with id, title, type, author, screenshot and (usually)
 *            a direct download URL in the Download anchor's title attribute.
 * details(): webservice https://csdb.dk/webservice/?type=release&id=N
 *            XML - fills in year and the best DownloadLink.
 * search():  CSDb has no search webservice, but the HTML result page uses a
 *            stable anchor format - scraped with a regex; if the markup ever
 *            changes this degrades to an empty result, never an error.
 */
public class CsdbRepo implements ContentRepo {

    public static final String RSS_URL = "https://csdb.dk/rss/latestreleases.php";
    public static final String WEBSERVICE_URL = "https://csdb.dk/webservice/?type=release&depth=2&id=";
    public static final String SEARCH_URL = "https://csdb.dk/search/?seinsel=releases&search=";

    private static final Pattern ITEM = Pattern.compile("<item>(.*?)</item>", Pattern.DOTALL);
    private static final Pattern TITLE = Pattern.compile("<title>(.*?)</title>", Pattern.DOTALL);
    private static final Pattern GUID_ID = Pattern.compile("<guid>[^<]*[?&]id=(\\d+)");
    private static final Pattern RELEASED_BY = Pattern.compile("Released by: <a [^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern REL_TYPE = Pattern.compile("Type: <a [^>]*>(.*?)</a>", Pattern.DOTALL);
    private static final Pattern DOWNLOAD = Pattern.compile("<a href=\"https?://csdb\\.dk/release/download\\.php\\?id=\\d+\" title=\"([^\"]+)\">Download</a>");
    private static final Pattern SCREENSHOT = Pattern.compile("<img [^>]*src=\"([^\"]+)\"");

    public String name() {
        return "csdb";
    }

    public List<ContentItem> latest(int max) throws IOException {
        String rss = HttpFetch.getString(RSS_URL);
        List<ContentItem> items = new ArrayList<ContentItem>();
        Matcher im = ITEM.matcher(rss);
        while (im.find() && items.size() < max) {
            String block = im.group(1);
            Matcher m = GUID_ID.matcher(block);
            if (!m.find()) continue;
            ContentItem item = new ContentItem(name(), m.group(1), null);
            m = TITLE.matcher(block);
            if (m.find()) item.title = unescape(m.group(1).trim());
            m = RELEASED_BY.matcher(block);
            if (m.find()) item.author = unescape(stripTags(m.group(1)).trim());
            m = REL_TYPE.matcher(block);
            if (m.find()) item.type = unescape(m.group(1).trim());
            m = DOWNLOAD.matcher(block);
            if (m.find()) item.downloadUrl = m.group(1);
            m = SCREENSHOT.matcher(block);
            if (m.find()) item.screenshotUrl = m.group(1);
            items.add(item);
        }
        return items;
    }

    private static final Pattern SEARCH_HIT = Pattern.compile(
        "<a href=\"/release/\\?id=(\\d+)\"[^>]*>([^<]+)</a>");

    public List<ContentItem> search(String query, int max) throws IOException {
        String html = HttpFetch.getString(
            SEARCH_URL + java.net.URLEncoder.encode(query, "UTF-8"));
        List<ContentItem> items = new ArrayList<ContentItem>();
        java.util.HashSet<String> seen = new java.util.HashSet<String>();
        Matcher m = SEARCH_HIT.matcher(html);
        while (m.find() && items.size() < max) {
            if (!seen.add(m.group(1))) continue;
            items.add(new ContentItem(name(), m.group(1), unescape(m.group(2).trim())));
        }
        return items;
    }

    public ContentItem details(String id) throws IOException {
        byte[] xml = HttpFetch.get(WEBSERVICE_URL + id);
        Element release = firstChild(parse(xml).getDocumentElement(), "Release");
        if (release == null) return null;

        ContentItem item = new ContentItem(name(), id, childText(release, "Name"));
        item.type = childText(release, "Type");
        item.screenshotUrl = childText(release, "ScreenShot");
        String year = childText(release, "ReleaseYear");
        if (year != null) {
            try { item.year = Integer.parseInt(year.trim()); }
            catch (NumberFormatException e) { /* leave 0 */ }
        }
        Element by = firstChild(release, "ReleasedBy");
        if (by != null) {
            Element group = firstChild(by, "Group");
            if (group != null) {
                item.author = childText(group, "Name");
            } else {
                Element handle = firstChild(by, "Handle");
                if (handle != null) item.author = childText(handle, "Handle");
            }
        }
        item.downloadUrl = pickDownload(release);
        return item;
    }

    /**
     * Best DownloadLink: among Status=Ok links prefer .zip/.d64 (releases
     * like Krestage 3 list bonus .prg files first, but the main download
     * is the archive), then any Ok link, then the first link at all.
     */
    private String pickDownload(Element release) {
        Element links = firstChild(release, "DownloadLinks");
        if (links == null) return null;
        String first = null, firstOk = null;
        NodeList list = links.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (!(n instanceof Element) || !"DownloadLink".equals(n.getNodeName()))
                continue;
            Element dl = (Element) n;
            String url = childText(dl, "Link");
            if (url == null) continue;
            if (first == null) first = url;
            if ("Ok".equalsIgnoreCase(childText(dl, "Status"))) {
                if (firstOk == null) firstOk = url;
                String lower = url.toLowerCase();
                if (lower.endsWith(".zip") || lower.endsWith(".d64"))
                    return url;
            }
        }
        return firstOk != null ? firstOk : first;
    }

    private static Document parse(byte[] xml) throws IOException {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            try {
                f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (Exception e) { /* not supported everywhere - best effort */ }
            f.setExpandEntityReferences(false);
            DocumentBuilder b = f.newDocumentBuilder();
            return b.parse(new ByteArrayInputStream(xml));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("bad CSDb XML: " + e);
        }
    }

    /** Direct child element by name (no descendant search). */
    private static Element firstChild(Element parent, String name) {
        NodeList list = parent.getChildNodes();
        for (int i = 0; i < list.getLength(); i++) {
            Node n = list.item(i);
            if (n instanceof Element && name.equals(n.getNodeName()))
                return (Element) n;
        }
        return null;
    }

    private static String childText(Element parent, String name) {
        Element e = firstChild(parent, name);
        return e == null ? null : e.getTextContent();
    }

    private static String stripTags(String s) {
        return s.replaceAll("<[^>]*>", "");
    }

    /**
     * Named entities seen in CSDb titles/handles - " name=X" pairs where X
     * is the decoded char. Scene names are heavy on Nordic/German letters.
     */
    private static final String NAMED_ENTITIES =
        " amp=& lt=< gt=> quot=\" apos=' nbsp= "
        + " auml=ä Auml=Ä ouml=ö Ouml=Ö uuml=ü Uuml=Ü"
        + " aring=å Aring=Å oslash=ø Oslash=Ø aelig=æ AElig=Æ"
        + " eacute=é Eacute=É egrave=è agrave=à szlig=ß ntilde=ñ";

    /** Minimal entity decode for RSS text fields. */
    private static String unescape(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '&') { sb.append(c); continue; }
            int end = s.indexOf(';', i);
            if (end < 0 || end - i > 8) { sb.append(c); continue; }
            String ent = s.substring(i + 1, end);
            i = end;
            int named = NAMED_ENTITIES.indexOf(' ' + ent + '=');
            if (named >= 0) sb.append(NAMED_ENTITIES.charAt(named + ent.length() + 2));
            else if (ent.startsWith("#")) {
                try {
                    int code = ent.startsWith("#x") || ent.startsWith("#X")
                        ? Integer.parseInt(ent.substring(2), 16)
                        : Integer.parseInt(ent.substring(1));
                    sb.append((char) code);
                } catch (NumberFormatException e) {
                    sb.append('&').append(ent).append(';');
                }
            } else sb.append('&').append(ent).append(';');
        }
        return sb.toString();
    }
}
