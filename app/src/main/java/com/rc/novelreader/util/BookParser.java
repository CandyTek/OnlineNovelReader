package com.rc.novelreader.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Turns a text file or an EPUB into a title and a list of chapters. Pure Java, runs in JVM tests. */
public final class BookParser {
    private BookParser() {}

    public static final class Section {
        public String title = "";
        public String text = "";
    }

    public static final class Parsed {
        public String title = "", author = "";
        public final List<Section> sections = new ArrayList<>();
    }

    /** Longest chapter made when a text has no headings. */
    private static final int CHUNK = 6000;

    // ------------------------------------------------------------------ plain text

    /** UTF-8 (with or without BOM) or UTF-16 by BOM; anything that is not valid UTF-8 is read as GB18030. */
    public static String decode(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xEF && (b[1] & 0xFF) == 0xBB && (b[2] & 0xFF) == 0xBF) {
            return new String(b, 3, b.length - 3, StandardCharsets.UTF_8);
        }
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xFE) return new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        if (b.length >= 2 && (b[0] & 0xFF) == 0xFE && (b[1] & 0xFF) == 0xFF) return new String(b, 2, b.length - 2, StandardCharsets.UTF_16BE);
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(b)).toString();
        } catch (CharacterCodingException e) {
            return new String(b, Charset.forName("GB18030"));
        }
    }

    private static boolean heading(String line) {
        if (line.isEmpty() || line.length() > 40) return false;
        if (!Texts.isChapterTitle(line)) return false;
        // a sentence that merely mentions "chapter 3" ends like a sentence
        char last = line.charAt(line.length() - 1);
        return last != '。' && last != '，' && last != ',' && last != '”' && last != ';' && last != '；';
    }

    /** Splits at chapter headings; a text without any is cut into pieces of readable size at paragraph ends. */
    public static Parsed splitTxt(String name, String all) {
        Parsed p = new Parsed();
        p.title = name == null ? "" : name.replaceAll("\\.[A-Za-z0-9]{2,5}$", "").trim();
        String[] lines = all.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        int headings = 0;
        for (String l : lines) if (heading(Texts.trim(l))) headings++;
        Section cur = null;
        StringBuilder sb = new StringBuilder();
        for (String raw : lines) {
            String l = Texts.trim(raw);
            if (l.isEmpty()) continue;
            boolean cut = headings >= 2 ? heading(l) : sb.length() >= CHUNK;
            if (cut) {
                close(p, cur, sb);
                cur = new Section();
                sb.setLength(0);
                if (headings >= 2) {
                    cur.title = l;
                    continue;
                }
            }
            if (cur == null) cur = new Section();
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
        }
        close(p, cur, sb);
        return p;
    }

    private static void close(Parsed p, Section s, StringBuilder text) {
        if (s == null) return;
        s.text = text.toString();
        // text before the first heading (blurb, copyright) is kept when it is more than a line or two
        if (s.title.isEmpty() && p.sections.isEmpty() && Texts.wlen(s.text) < 40 && text.indexOf("\n") < 0) return;
        if (s.text.isEmpty() && s.title.isEmpty()) return;
        p.sections.add(s);
    }

    // ------------------------------------------------------------------ EPUB

    public static Parsed parseEpub(File file) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            Document container = xml(zip, "META-INF/container.xml");
            if (container == null) throw new IOException("Not an EPUB (no container.xml)");
            Element root = container.selectFirst("rootfile[full-path]");
            if (root == null) throw new IOException("Not an EPUB (no package file)");
            String opfPath = root.attr("full-path");
            Document opf = xml(zip, opfPath);
            if (opf == null) throw new IOException("EPUB package file is missing");
            String base = opfPath.contains("/") ? opfPath.substring(0, opfPath.lastIndexOf('/') + 1) : "";

            Parsed p = new Parsed();
            p.title = first(opf, "metadata > dc|title, metadata > title");
            p.author = first(opf, "metadata > dc|creator, metadata > creator");
            Map<String, String> hrefById = new HashMap<>();
            String navHref = null, ncxHref = null;
            for (Element item : opf.select("manifest > item")) {
                String href = path(base, item.attr("href"));
                hrefById.put(item.attr("id"), href);
                if (item.attr("properties").contains("nav")) navHref = href;
                if (item.attr("media-type").equals("application/x-dtbncx+xml")) ncxHref = href;
            }
            Map<String, String> titles = new HashMap<>();
            if (ncxHref != null) {
                Document ncx = xml(zip, ncxHref);
                String dir = ncxHref.contains("/") ? ncxHref.substring(0, ncxHref.lastIndexOf('/') + 1) : "";
                if (ncx != null) {
                    for (Element np : ncx.select("navPoint")) {
                        Element label = np.selectFirst("> navLabel > text"), content = np.selectFirst("> content[src]");
                        if (label != null && content != null) putTitle(titles, path(dir, content.attr("src")), label.text());
                    }
                }
            }
            if (navHref != null) {
                Document nav = xml(zip, navHref);
                String dir = navHref.contains("/") ? navHref.substring(0, navHref.lastIndexOf('/') + 1) : "";
                if (nav != null) for (Element a : nav.select("nav a[href]")) putTitle(titles, path(dir, a.attr("href")), a.text());
            }
            for (Element ref : opf.select("spine > itemref")) {
                String href = hrefById.get(ref.attr("idref"));
                if (href == null || href.equals(navHref)) continue;
                byte[] bytes = bytes(zip, href);
                if (bytes == null) continue;
                Document doc = Jsoup.parse(decode(bytes));
                Section s = new Section();
                s.title = Texts.or(titles.get(href), "");
                Element body = doc.body();
                if (body == null) continue;
                StringBuilder sb = new StringBuilder();
                text(body, sb);
                List<String> lines = new ArrayList<>();
                for (String l : sb.toString().split("\n")) {
                    l = Texts.trim(l);
                    if (!l.isEmpty()) lines.add(l);
                }
                if (s.title.isEmpty()) {
                    Element h = body.selectFirst("h1, h2, h3");
                    if (h != null) s.title = Texts.trim(h.text());
                }
                // the heading is shown by the reader; it must not open the text a second time
                if (!lines.isEmpty() && !s.title.isEmpty() && Texts.bare(lines.get(0)).equals(Texts.bare(s.title))) lines.remove(0);
                s.text = join(lines, '\n');
                // cover and title pages: nothing to read
                if (Texts.wlen(s.text) < 2) continue;
                p.sections.add(s);
            }
            if (p.sections.isEmpty()) throw new IOException("No readable text in this EPUB");
            return p;
        }
    }

    private static void putTitle(Map<String, String> titles, String href, String title) {
        int hash = href.indexOf('#');
        if (hash >= 0) href = href.substring(0, hash);
        title = Texts.trim(title);
        // several entries into one file: the first names it
        if (!title.isEmpty() && !titles.containsKey(href)) titles.put(href, title);
    }

    private static final String BLOCKS = " p div h1 h2 h3 h4 h5 h6 li blockquote section article tr pre dd dt figure hr ";

    private static void text(Node n, StringBuilder sb) {
        if (n instanceof TextNode) {
            sb.append(((TextNode) n).text());
            return;
        }
        if (!(n instanceof Element)) return;
        Element e = (Element) n;
        String tag = e.normalName();
        if (tag.equals("script") || tag.equals("style") || tag.equals("head")) return;
        boolean block = BLOCKS.contains(" " + tag + " ");
        if (block || tag.equals("br")) sb.append('\n');
        for (Node c : e.childNodes()) text(c, sb);
        if (block) sb.append('\n');
    }

    private static String first(Document d, String css) {
        Element e = d.selectFirst(css);
        return e == null ? "" : Texts.trim(e.text());
    }

    /** Resolves a manifest reference against the package folder; percent escapes and "../" are undone. */
    private static String path(String base, String href) {
        int hash = href.indexOf('#');
        String frag = hash >= 0 ? href.substring(hash) : "";
        if (hash >= 0) href = href.substring(0, hash);
        try {
            href = java.net.URLDecoder.decode(href.replace("+", "%2B"), "UTF-8");
        } catch (Exception ignored) {
        }
        List<String> parts = new ArrayList<>();
        for (String s : (base + href).split("/")) {
            if (s.equals("..")) {
                if (!parts.isEmpty()) parts.remove(parts.size() - 1);
            } else if (!s.isEmpty() && !s.equals(".")) parts.add(s);
        }
        return join(parts, '/') + frag;
    }

    /** String.join needs API 26. */
    private static String join(List<String> parts, char sep) {
        StringBuilder sb = new StringBuilder();
        for (String s : parts) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(s);
        }
        return sb.toString();
    }

    private static byte[] bytes(ZipFile zip, String name) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null || e.getSize() > 32L * 1024 * 1024) return null;
        try (InputStream in = zip.getInputStream(e)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static Document xml(ZipFile zip, String name) throws IOException {
        byte[] b = bytes(zip, name);
        return b == null ? null : Jsoup.parse(decode(b), "", Parser.xmlParser());
    }
}
