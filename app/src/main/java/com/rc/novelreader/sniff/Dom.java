package com.rc.novelreader.sniff;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** DOM helpers shared by both engines. */
final class Dom {
    private Dom() {}

    static final Set<String> BLOCK = new HashSet<>(Arrays.asList(
            "div", "p", "table", "ul", "ol", "dl", "li", "h1", "h2", "h3", "h4", "h5", "h6", "form",
            "article", "section", "tr", "td", "th", "blockquote", "pre", "dd", "dt", "header",
            "footer", "nav", "aside", "main", "figure", "center", "hr"));

    private static final Pattern HIDDEN = Pattern.compile(
            "display\\s*:\\s*none|visibility\\s*:\\s*hidden|font-size\\s*:\\s*0(px)?\\s*(;|$)", Pattern.CASE_INSENSITIVE);

    /** Removes everything that is never content: scripts, frames, hidden nodes. */
    static void clean(Document doc) {
        doc.select("script,style,noscript,iframe,ins,svg,canvas,template,textarea,object,embed,video,audio,link").remove();
        List<Element> hidden = new ArrayList<>(doc.select("[hidden],[aria-hidden=true]"));
        for (Element e : doc.select("[style]")) {
            if (HIDDEN.matcher(e.attr("style")).find()) hidden.add(e);
        }
        // Long chapter lists are often collapsed behind a "show all chapters" button: the links are in the page
        // but hidden, either one by one or as a block. A run of hidden links is such a list, not hidden junk.
        java.util.IdentityHashMap<Element, Integer> hiddenLinks = new java.util.IdentityHashMap<>();
        for (Element e : hidden) {
            if (e.parent() != null && e.normalName().equals("a") && e.hasAttr("href")) {
                Integer n = hiddenLinks.get(e.parent());
                hiddenLinks.put(e.parent(), n == null ? 1 : n + 1);
            }
        }
        for (Element e : hidden) {
            if (e.parent() == null || "body".equals(e.normalName()) || "html".equals(e.normalName())) continue;
            Integer siblings = hiddenLinks.get(e.parent());
            boolean isLink = e.normalName().equals("a") && e.hasAttr("href");
            if (isLink ? siblings != null && siblings >= COLLAPSED_LIST : e.select("a[href]").size() >= COLLAPSED_LIST) continue;
            e.remove();
        }
    }

    private static final int COLLAPSED_LIST = 8;

    static Element first(Element root, String selector) {
        try {
            return root.selectFirst(stripAttr(selector));
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Elements all(Element root, String selector) {
        try {
            return root.select(stripAttr(selector));
        } catch (RuntimeException e) {
            return new Elements();
        }
    }

    /** Value of "css@attr" (attribute) or "css" (text) for the first match; null when missing/empty. */
    static String value(Element root, String selector) {
        if (selector == null) return null;
        String attr = attrOf(selector);
        Element e = first(root, selector);
        if (e == null) return null;
        String v;
        if (attr == null) v = e.text();
        else if (attr.equals("href") || attr.equals("src")) v = e.absUrl(attr);
        else v = e.attr(attr);
        v = v.trim();
        return v.isEmpty() ? null : v;
    }

    private static String attrOf(String selector) {
        int at = selector.lastIndexOf('@');
        if (at <= 0 || selector.indexOf(']', at) >= 0 || selector.indexOf(')', at) >= 0) return null;
        return selector.substring(at + 1).trim();
    }

    private static String stripAttr(String selector) {
        return attrOf(selector) == null ? selector : selector.substring(0, selector.lastIndexOf('@')).trim();
    }

    static boolean hasBlockDescendant(Element e) {
        for (Element c : e.children()) {
            if (BLOCK.contains(c.normalName())) return true;
            if (hasBlockDescendant(c)) return true;
        }
        return false;
    }

    static String idClass(Element e) {
        return (e.id() + " " + e.className()).toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * A reasonably stable selector for e ("div#content", "div.read-content"), or null when the
     * element has no usable id/class or the selector would not be unique in the document.
     */
    static String stableSelector(Element e, Document doc) {
        String tag = e.normalName();
        String id = e.id();
        if (!id.isEmpty() && id.matches("[A-Za-z][A-Za-z_\\-]*[0-9]{0,2}")) return tag + "#" + id;
        StringBuilder sb = new StringBuilder(tag);
        int n = 0;
        for (String c : e.classNames()) {
            if (c.matches("[A-Za-z][A-Za-z_\\-]*[0-9]{0,2}")) {
                sb.append('.').append(c);
                n++;
            }
        }
        if (n == 0) return null;
        String sel = sb.toString();
        try {
            return doc.select(sel).size() == 1 ? sel : null;
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
