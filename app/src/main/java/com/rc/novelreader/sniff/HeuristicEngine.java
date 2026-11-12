package com.rc.novelreader.sniff;

import com.rc.novelreader.sniff.PageResult.ChapterContent;
import com.rc.novelreader.sniff.PageResult.ChapterRef;
import com.rc.novelreader.util.Texts;
import com.rc.novelreader.util.Urls;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Engine #2: site-independent heuristics.
 *
 * Three independent detectors run on every page:
 *  - main text block (text density, link density, paragraph structure, id/class hints)
 *  - chapter navigation (previous / next / contents links, also from inline script variables)
 *  - chapter list (the DOM container whose links look most like an ordered run of chapters)
 * Their evidence is combined into a chapter score and a detail score; the larger one above its
 * threshold decides the page type. Selectors supplied by a matching {@link SiteRule} replace the
 * corresponding detector, so rules only need to describe what the heuristics get wrong.
 */
public final class HeuristicEngine {
    private static final int CI = Pattern.CASE_INSENSITIVE;

    private static final Pattern NEXT = Pattern.compile(
            "^(下一?[章页頁节節篇话話回张張]|下一章节|后一[章页]|next ?(chapter|page|part|episode)?)$", CI);
    private static final Pattern PREV = Pattern.compile(
            "^(上一?[章页頁节節篇话話回张張]|上一章节|前一[章页]|prev(ious)? ?(chapter|page|part|episode)?)$", CI);
    private static final Pattern PAGEISH = Pattern.compile("[页頁]|page", CI);
    private static final Pattern TOC = Pattern.compile(
            "^(返?回?(章节|章節|全文|小说|本书)?(目录|目錄)(列表)?|章节列表|章節列表|返回书页|返回列表|返回书籍|书页"
                    + "|index|contents?|table of contents|toc|chapter list|all chapters|chapters)$", CI);
    private static final Pattern MORE_TOC = Pattern.compile(
            "^((查看|点击查看|显示|展开)?(全部|所有|完整|更多)(章节|章節)?(目录|目錄|列表)?(列表)?|(查看)?(章节|章節)?(目录|目錄)"
                    + "|(章节|章節)列表|all chapters|view all( chapters)?|table of contents|full (chapter )?list|chapter list)$", CI);
    private static final Pattern NAV_ID = Pattern.compile("^(next|pb_next|next_url|nextchapter|next_chapter|next_chap|btn-next|linknext)$", CI);
    private static final Pattern PREV_ID = Pattern.compile("^(prev|pb_prev|prev_url|prevchapter|prev_chapter|prev_chap|btn-prev|linkprev)$", CI);

    private static final Pattern POS_HINT = Pattern.compile(
            "content|chapter|article|read|txt|text|nr1|novel|post-body|entry|story|booktext|htmlcontent|acontent");
    private static final Pattern NEG_HINT = Pattern.compile(
            "comment|footer|foot|header|nav|menu|side|recommend|related|copyright|tip|notice|share|banner|intro|desc|info|list|hot|rank|reply|review|synopsis|summary|jianjie");
    /** Sub-blocks inside the text container that are never story text. */
    private static final Pattern JUNK_BLOCK = Pattern.compile(
            "(^|[\\s_\\-])(ad|ads|adv|advert|adsense|banner|gg|guanggao|tips?|notice|share|recommend|footer|nav|bottem|pager?|pagination|operate|btn|button|toolbar|tool|comments?|reward|vote|social|related|copyright|chapter-nav|read-set|setting)s?\\d*($|[\\s_\\-])");
    private static final Pattern TITLE_HINT = Pattern.compile("title|chaptername|chapter-name|bookname|headline|chapter_name|h1title");

    private static final Pattern TITLE_SPLIT = Pattern.compile(
            "\\s*[_|—–]+\\s*|\\s+-\\s+|(?<=[\\u4e00-\\u9fff\\uFF09】》\\uFF01\\uFF1F。0-9])-|-(?=[\\u4e00-\\u9fff《【])");
    /** "(某某网站)" / "[xx.com]" appended to titles. */
    private static final Pattern SITE_TAIL = Pattern.compile(
            "[\\s\u3000]*[(\\uFF08【\\[][^)\\uFF09】\\]]*(网|站|吧|阁|屋|com|net|www|org)[^)\\uFF09】\\]]*[)\\uFF09】\\]][\\s\u3000]*$", CI);
    private static final Pattern BOOK_TITLE_TAIL = Pattern.compile(
            "(最新章节|全文阅读|全文免费|无弹窗|免费阅读|在线阅读|TXT下载|txt下载|章节目录|章节列表|全集|目录|小说|全文|最新|[\\(\\uFF08\\[【]).*$");
    private static final Pattern AUTHOR_CN = Pattern.compile("作\\s*者\\s*[:\\uFF1A]\\s*([^\\s:\\uFF1A,\\uFF0C|/<>()\\uFF08\\uFF09]{1,20})");
    private static final Pattern AUTHOR_EN = Pattern.compile("(?i)^\\s*(?:author|written by|by)\\s*[:\\uFF1A]\\s*(.{2,40})$");
    private static final Pattern AUTHOR_ZHU = Pattern.compile("^(?:[\\[【(\\uFF08〔][\u4e00-\u9fff]{1,2}[\\]】)\\uFF09〕])?\\s*([\u4e00-\u9fff·]{2,8})\\s*[著撰]$");
    private static final Pattern NOVEL_WORDS = Pattern.compile("小说|小說|章节|章節|连载|連載|完结|完本|novel|chapter|fiction|light novel", CI);
    private static final Pattern DETAIL_WORDS = Pattern.compile("最新章节|全文阅读|章节目录|章节列表|目录|table of contents|chapters|chapter list", CI);
    private static final Pattern COVER_HINT = Pattern.compile("cover|fengmian|bookimg|book-img|fmimg|thumb|poster", CI);
    private static final Pattern INTRO_HINT = Pattern.compile("intro|desc|summary|synopsis|jianjie|book-info-detail", CI);

    private static final Pattern VAR_NEXT = Pattern.compile("(?:next_page|nextpage|next_url|nexturl|url_next|nextChapterUrl|next_chapter)\\s*[=:]\\s*[\"']([^\"']{1,300})[\"']");
    private static final Pattern VAR_PREV = Pattern.compile("(?:preview_page|prev_page|prevpage|prev_url|url_previous|url_prev|preChapterUrl|prev_chapter)\\s*[=:]\\s*[\"']([^\"']{1,300})[\"']");
    private static final Pattern VAR_INDEX = Pattern.compile("(?:index_page|url_index|index_url|bookurl|book_url)\\s*[=:]\\s*[\"']([^\"']{1,300})[\"']");

    // ------------------------------------------------------------------ entry point

    public PageResult analyze(String url, Document doc, SiteRule rule) {
        PageResult r = new PageResult();
        r.url = url;
        String[] vars = scriptVars(doc, url);
        Dom.clean(doc);
        Element body = doc.body();
        if (body == null) return r;
        Map<String, String> meta = metas(doc);
        String docTitle = Texts.trim(doc.title());
        boolean ruleUsed = false;

        Nav nav = findNav(doc, url, vars, rule);
        Content content = findContent(doc, rule);
        Toc toc = findToc(doc, url, rule);

        boolean ogNovel = meta.containsKey("og:novel:book_name") || meta.containsKey("og:novel:author")
                || String.valueOf(meta.get("og:type")).matches("(?i).*(novel|book).*");
        boolean novelWords = ogNovel || NOVEL_WORDS.matcher(docTitle).find();

        // ---- chapter evidence
        int cs = 0;
        String chapterTitle = "";
        String[] titleAndBook = null;
        if (content != null) {
            titleAndBook = chapterTitle(doc, url, docTitle, rule);
            chapterTitle = titleAndBook[0];
            String text = ContentCleaner.clean(content.raw, chapterTitle, rule == null ? null : rule.purify);
            int wl = Texts.wlen(text);
            if (wl >= 20) {
                ChapterContent c = new ChapterContent();
                c.title = chapterTitle;
                c.text = text;
                c.wlen = wl;
                c.nextUrl = nav.next;
                c.prevUrl = nav.prev;
                c.nextInChapter = nav.nextInChapter;
                c.nextHijacked = nav.next == null && nav.nextOffsite;
                c.tocUrl = nav.toc;
                r.chapter = c;
                r.contentSelector = content.byRule ? null : Dom.stableSelector(content.el, doc);
                if (wl >= 400) cs += 40;
                else if (wl >= 120) cs += 20;
                // A chapter is where most of the page's words are. Search results, portals and
                // comment threads spread their text over many small blocks instead.
                if (!content.byRule) {
                    double share = wl / (double) Math.max(1, Texts.wlen(body.text()));
                    r.contentShare = share;
                    if (share < 0.15) cs -= 40;
                    else if (share < 0.3) cs -= 15;
                }
                if (content.byRule) {
                    cs += 40;
                    ruleUsed = true;
                }
            }
        }
        boolean tocStrong = toc != null && !toc.weak && toc.links.size() >= 10;
        if (r.chapter != null) {
            if (nav.hasChapterNav || nav.nextInChapter) cs += nav.hasNext && nav.hasPrev ? 35 : 25;
            else if (nav.hasPageNav && !tocStrong) cs += nav.hasNext && nav.hasPrev ? 20 : 10;
            if (nav.tocFromLink) cs += 10;
            if (Texts.isChapterTitle(chapterTitle)) cs += 25;
            if (novelWords) cs += 5;
        }

        // ---- detail evidence
        int ds = 0;
        if (toc != null) {
            if (toc.byRule) {
                ds += 80;
                ruleUsed = true;
            } else if (toc.weak) {
                int n = toc.links.size();
                ds += (n >= 50 ? 50 : n >= 20 ? 35 : 20) + (novelWords ? 20 : 0);
            }
            else ds += toc.links.size() >= 10 ? 55 : 40;
        }
        if (ogNovel) ds += 30;
        String author = author(doc, body, meta, rule);
        if (!author.isEmpty()) ds += 10;
        if (DETAIL_WORDS.matcher(docTitle).find()) ds += 10;
        if (r.chapter != null && nav.hasChapterNav && r.chapter.wlen >= 400) ds -= 30;

        r.chapterScore = cs;
        r.detailScore = ds;

        boolean chapterOk = r.chapter != null && cs >= 60
                && (r.chapter.wlen >= 120 || (content.byRule && nav.hasChapterNav));
        boolean detailOk = ds >= 50 && (toc != null || (ogNovel && meta.containsKey("og:novel:book_name")));
        if (chapterOk && detailOk) {
            if (cs > ds || (cs == ds && r.chapter.wlen >= 1000)) detailOk = false;
            else chapterOk = false;
        }

        // ---- fill results (both parts are filled whenever found, the verdict is r.type)
        if (toc != null) {
            for (Link l : toc.links) r.toc.add(new ChapterRef(l.text, l.url));
            r.tocSelector = toc.selector;
            r.tocPages = tocPages(doc, url);
            r.tocNextUrl = nav.rawNext;
        }
        r.moreTocUrl = moreToc(doc, url, toc, meta);
        fillBook(r, doc, body, meta, docTitle, author, rule);
        if (r.chapter != null) {
            String book = titleAndBook[1];
            if (rule != null && rule.bookTitleInChapter != null) book = Texts.or(Dom.value(doc, rule.bookTitleInChapter), book);
            if (meta.containsKey("og:novel:book_name")) book = meta.get("og:novel:book_name");
            if (Texts.empty(book) && nav.tocText != null) book = nav.tocText;
            r.chapter.bookTitle = cleanBookTitle(Texts.or(book, ""));
        }
        if (chapterOk) r.type = PageResult.Type.CHAPTER;
        else if (detailOk) r.type = PageResult.Type.DETAIL;
        if (ruleUsed || nav.byRule) r.engine = "rule:" + rule.name;
        return r;
    }

    // ------------------------------------------------------------------ metadata

    private static Map<String, String> metas(Document doc) {
        Map<String, String> m = new HashMap<>();
        for (Element e : doc.select("meta[content]")) {
            String k = e.hasAttr("property") ? e.attr("property") : e.attr("name");
            String v = e.attr("content").trim();
            if (!k.isEmpty() && !v.isEmpty() && !m.containsKey(k.toLowerCase())) m.put(k.toLowerCase(), v);
        }
        return m;
    }

    /** next / prev / index URLs that many templates keep in inline script variables. */
    private static String[] scriptVars(Document doc, String url) {
        String[] out = new String[5];
        for (Element s : doc.select("script:not([src])")) {
            String d = s.data();
            if (d.isEmpty()) continue;
            if (out[3] == null && d.contains("BreadcrumbList") && d.length() < 200000) ldBreadcrumb(d, url, out);
            if (d.length() > 60000) continue;
            if (out[0] == null) out[0] = find(VAR_NEXT, d);
            if (out[1] == null) out[1] = find(VAR_PREV, d);
            if (out[2] == null) out[2] = find(VAR_INDEX, d);
        }
        return out;
    }

    private static final Pattern LD_ITEM = Pattern.compile(
            "\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*,\\s*\"item\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\""
                    + "|\"item\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"\\s*,\\s*\"name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** schema.org BreadcrumbList: the entry above the current page is the book. Fills out[3]=url, out[4]=name. */
    private static void ldBreadcrumb(String json, String url, String[] out) {
        List<String[]> items = new ArrayList<>();
        Matcher m = LD_ITEM.matcher(json);
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(4);
            String item = m.group(2) != null ? m.group(2) : m.group(3);
            String abs = Urls.resolve(url, unescapeJson(item));
            if (abs != null) items.add(new String[]{abs, unescapeJson(name)});
        }
        String self = Urls.normalize(url);
        for (int i = items.size() - 1; i >= 1; i--) {
            if (Urls.normalize(items.get(i)[0]).equals(self)) continue;
            out[3] = items.get(i)[0];
            out[4] = items.get(i)[1];
            return;
        }
    }

    private static String unescapeJson(String s) {
        if (s.indexOf('\\') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                sb.append(c);
                continue;
            }
            char n = s.charAt(++i);
            if (n == 'u' && i + 4 < s.length()) {
                try {
                    sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    i += 4;
                } catch (NumberFormatException e) {
                    sb.append(n);
                }
            } else if (n == 'n' || n == 't' || n == 'r') sb.append(' ');
            else sb.append(n);
        }
        return sb.toString();
    }

    private static final Pattern CRUMB = Pattern.compile("crumb|bread|con_top|(^|[\\s_\\-])(path|location|position|weizhi|srcbox)($|[\\s_\\-])");
    private static final Pattern HOME = Pattern.compile("^(首页|首頁|主页|网站首页|home|index|homepage)$", CI);

    /** Visible breadcrumb trail: its last link (other than "home" and the page itself) is the book. */
    private static String[] htmlBreadcrumb(Document doc, String url) {
        String self = Urls.normalize(url);
        for (Element e : doc.select("div,nav,p,ol,ul,span,section,header")) {
            if (!CRUMB.matcher(Dom.idClass(e)).find()) continue;
            if (e.text().length() > 200) continue;
            List<Element> links = e.select("[href]");
            for (int i = links.size() - 1; i >= 0; i--) {
                Element a = links.get(i);
                String href = abs(a, url);
                String t = Texts.trim(a.text());
                if (href == null || t.isEmpty() || t.length() > 40) continue;
                if (Urls.normalize(href).equals(self) || HOME.matcher(navText(t)).matches()) continue;
                if (NEXT.matcher(navText(t)).matches() || PREV.matcher(navText(t)).matches()) continue;
                return new String[]{href, TOC.matcher(navText(t)).matches() ? null : t};
            }
        }
        return null;
    }

    private static String find(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private void fillBook(PageResult r, Document doc, Element body, Map<String, String> meta,
                          String docTitle, String author, SiteRule rule) {
        PageResult.BookInfo b = r.book;
        String title = rule != null ? Dom.value(doc, rule.bookTitle) : null;
        if (title == null) title = meta.get("og:novel:book_name");
        if (title == null) title = meta.get("og:title");
        if (title == null) {
            Element h1 = body.selectFirst("h1");
            if (h1 != null && h1.text().length() <= 40 && !h1.text().trim().isEmpty()) title = h1.text();
        }
        if (title == null) {
            String[] seg = TITLE_SPLIT.split(docTitle);
            title = seg.length > 0 ? seg[0] : docTitle;
        }
        b.title = cleanBookTitle(title);
        b.author = author;

        String cover = rule != null ? Dom.value(doc, rule.cover) : null;
        if (cover == null) cover = Urls.resolve(r.url, meta.get("og:image"));
        if (cover == null) {
            for (Element img : body.select("img")) {
                String src = imgSrc(img);
                if (src == null) continue;
                String hint = src + " " + Dom.idClass(img) + " " + (img.parent() == null ? "" : Dom.idClass(img.parent()));
                boolean altIsTitle = !b.title.isEmpty() && img.attr("alt").contains(b.title);
                if (COVER_HINT.matcher(hint).find() || altIsTitle) {
                    cover = src;
                    break;
                }
            }
        }
        b.cover = Texts.or(cover, "");

        String intro = rule != null ? Dom.value(doc, rule.intro) : null;
        if (intro == null) intro = meta.get("og:description");
        if (intro == null) {
            for (Element e : body.select("div,p,dd,section")) {
                if (INTRO_HINT.matcher(Dom.idClass(e)).find() && Texts.wlen(e.text()) >= 30) {
                    intro = e.text();
                    break;
                }
            }
        }
        if (intro == null) intro = meta.get("description");
        b.intro = intro == null ? "" : Texts.trim(intro);
    }

    private static String imgSrc(Element img) {
        for (String a : new String[]{"data-original", "data-src", "data-lazy-src", "src"}) {
            if (img.hasAttr(a)) {
                String u = Urls.resolve(img.baseUri(), img.attr(a));
                if (u != null) return u;
            }
        }
        return null;
    }

    private static String author(Document doc, Element body, Map<String, String> meta, SiteRule rule) {
        String a = rule != null ? Dom.value(doc, rule.author) : null;
        if (a == null) a = meta.get("og:novel:author");
        if (a == null) a = meta.get("book:author");
        if (a == null) {
            String text = body.text();
            if (text.length() > 20000) text = text.substring(0, 20000);
            Matcher m = AUTHOR_CN.matcher(text);
            if (m.find()) a = m.group(1);
            else {
                int seen = 0;
                for (Element e : body.select("span,p,div,li,td,dd,h2,h3,h4,h5,font,b,strong,em,small")) {
                    if (++seen > 4000) break;
                    if (e.childrenSize() > 3) continue;
                    String t = e.text();
                    if (t.length() > 60) continue;
                    m = AUTHOR_EN.matcher(t);
                    if (!m.find()) m = AUTHOR_ZHU.matcher(t);
                    else m.reset();
                    if (m.find()) {
                        a = m.group(1);
                        break;
                    }
                }
            }
        }
        if (a == null) return "";
        a = Texts.trim(a).replaceFirst("^作\\s*者\\s*[:\\uFF1A]\\s*", "");
        return a.length() > 40 ? "" : a;
    }

    static String cleanBookTitle(String t) {
        t = SITE_TAIL.matcher(Texts.trim(t)).replaceFirst("");
        t = Texts.trim(t).replace("《", "").replace("》", "");
        String cut = BOOK_TITLE_TAIL.matcher(t).replaceFirst("");
        if (Texts.trim(cut).length() >= 1) t = cut;
        return Texts.trim(t.replaceAll("[\\s\\-_|:\\uFF1A,\\uFF0C]+$", ""));
    }

    // ------------------------------------------------------------------ navigation

    private static final class Nav {
        String next, prev, toc, tocText;
        /** First "next"-type link regardless of meaning; on a list page this is the list's next page. */
        String rawNext;
        boolean nextInChapter, hasNext, hasPrev, hasChapterNav, hasPageNav, tocFromLink, byRule;
        /** A next control exists but points at another site (ad hijack): the real link is unknown. */
        boolean nextOffsite;
    }

    /** Normalised link label: letters and digits only, lower-case, CJK without spaces. */
    static String navText(String t) {
        StringBuilder sb = new StringBuilder();
        boolean cjk = false;
        for (int i = 0; i < t.length() && sb.length() < 60; i++) {
            char c = t.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
                if (Texts.isCjk(c)) cjk = true;
            } else if (Texts.isSpace(c)) sb.append(' ');
        }
        String s = sb.toString().trim().replaceAll("\\s+", " ");
        return cjk ? s.replace(" ", "") : s;
    }

    /** Where scripted links keep their address when the href itself is a "javascript:" stub. */
    private static final String[] LINK_ATTRS = {"href", "data-href", "data-url", "data-link", "data-target-url"};
    private static final Pattern ONCLICK_URL = Pattern.compile(
            "(?:location(?:\\.href)?\\s*=|location\\.(?:assign|replace)\\(|window\\.open\\()\\s*['\"]([^'\"]+)['\"]");

    private static String abs(Element a, String pageUrl) {
        String base = a.baseUri();
        if (Texts.empty(base)) base = pageUrl;
        for (String attr : LINK_ATTRS) {
            String u = Urls.resolve(base, a.attr(attr));
            if (u != null) return u;
        }
        String click = a.attr("onclick");
        if (!click.isEmpty()) {
            Matcher m = ONCLICK_URL.matcher(click);
            if (m.find()) return Urls.resolve(base, m.group(1));
        }
        return null;
    }

    private Nav findNav(Document doc, String url, String[] vars, SiteRule rule) {
        Nav n = new Nav();
        String curKey = Urls.normalize(url);
        String nextChapter = null, nextPage = null, prevChapter = null, prevPage = null;

        for (Element a : doc.select("a, [href], [data-href], [data-url], button[onclick]")) {
            String label = a.text();
            if (Texts.trim(label).isEmpty()) label = Texts.or(a.attr("aria-label"), a.attr("title"));
            String t = navText(label);
            if (t.length() > 24) continue;
            String rel = a.attr("rel");
            boolean isNext = NEXT.matcher(t).matches() || "next".equalsIgnoreCase(rel) || NAV_ID.matcher(a.id()).matches();
            boolean isPrev = !isNext && (PREV.matcher(t).matches() || "prev".equalsIgnoreCase(rel) || PREV_ID.matcher(a.id()).matches());
            boolean isToc = !isNext && !isPrev && TOC.matcher(t).matches();
            if (!isNext && !isPrev && !isToc) continue;
            String href = abs(a, url);
            if (href != null && Urls.normalize(href).equals(curKey)) href = null;
            boolean pageish = PAGEISH.matcher(t).find();
            // chapters never continue on another site: such a target is an ad swapped into the link
            if (href != null && !isToc && !Urls.sameSite(href, url)) {
                if (isNext) n.nextOffsite = true;
                href = null;
            }
            if (isNext) {
                n.hasNext = true;
                if (pageish) n.hasPageNav = true;
                else n.hasChapterNav = true;
                if (href != null) {
                    if (n.rawNext == null) n.rawNext = href;
                    if (pageish && nextPage == null) nextPage = href;
                    if (!pageish && nextChapter == null) nextChapter = href;
                }
            } else if (isPrev) {
                n.hasPrev = true;
                if (pageish) n.hasPageNav = true;
                else n.hasChapterNav = true;
                if (href != null) {
                    if (pageish && prevPage == null) prevPage = href;
                    if (!pageish && prevChapter == null) prevChapter = href;
                }
            } else if (href != null && n.toc == null && !Urls.path(href).replace("/", "").isEmpty()) {
                n.toc = href;
                n.tocFromLink = true;
            }
        }

        if (nextPage != null && Urls.sameChapter(url, nextPage)) n.next = nextPage;
        else n.next = nextChapter != null ? nextChapter : nextPage;
        n.prev = prevChapter != null ? prevChapter : prevPage;

        if (rule != null) {
            String v = link(doc, rule.next, url);
            if (v != null) {
                n.next = v;
                n.hasNext = n.hasChapterNav = n.byRule = true;
            }
            v = link(doc, rule.prev, url);
            if (v != null) {
                n.prev = v;
                n.hasPrev = n.hasChapterNav = n.byRule = true;
            }
            v = link(doc, rule.tocLink, url);
            if (v != null) {
                n.toc = v;
                n.tocFromLink = n.byRule = true;
            }
        }
        if (n.next == null && vars[0] != null) {
            n.next = Urls.resolve(url, vars[0]);
            if (n.next != null && !Urls.sameSite(n.next, url)) {
                n.next = null;
                n.nextOffsite = true;
            }
            if (n.next != null) n.hasNext = n.hasChapterNav = true;
        }
        if (n.prev == null && vars[1] != null) n.prev = Urls.resolve(url, vars[1]);
        if (n.toc == null && vars[2] != null) n.toc = Urls.resolve(url, vars[2]);
        if (n.toc == null) {
            String[] crumb = htmlBreadcrumb(doc, url);
            if (crumb == null && vars[3] != null) crumb = new String[]{vars[3], vars[4]};
            if (crumb != null) {
                n.toc = crumb[0];
                n.tocText = crumb[1];
            }
        }
        if (n.toc == null) n.toc = guessTocUrl(url);

        String tocKey = n.toc == null ? "" : Urls.normalize(n.toc);
        if (n.toc != null && n.tocText == null) {
            // a link to the contents page labelled with something other than "contents" carries the book title
            for (Element a : doc.select("[href]")) {
                String href = abs(a, url);
                if (href == null || !Urls.normalize(href).equals(tocKey)) continue;
                String t = Texts.trim(a.text());
                if (t.isEmpty() || t.length() > 40 || TOC.matcher(navText(t)).matches()) continue;
                n.tocText = t;
                break;
            }
        }
        if (n.next != null) {
            String k = Urls.normalize(n.next);
            if (k.equals(curKey) || k.equals(tocKey)) n.next = null;
        }
        if (n.prev != null) {
            String k = Urls.normalize(n.prev);
            if (k.equals(curKey) || k.equals(tocKey)) n.prev = null;
        }
        n.nextInChapter = n.next != null && Urls.sameChapter(url, n.next);
        return n;
    }

    private static String link(Document doc, String selector, String url) {
        if (selector == null) return null;
        Element e = Dom.first(doc, selector);
        if (e == null) return null;
        if (selector.contains("@")) return Urls.resolve(url, Dom.value(doc, selector));
        return abs(e, url);
    }

    /** /book/123/456.html -> /book/123/ ; /456.html -> / (one-book sites). Callers must verify the guess. */
    private static String guessTocUrl(String url) {
        String p = Urls.path(url);
        int i = p.lastIndexOf('/');
        if (i < 0) return null;
        if (i == p.length() - 1 && i > 0) { // directory-style chapter URL: /book/123/456/
            p = p.substring(0, i);
            i = p.lastIndexOf('/');
            if (i < 0) return null;
        }
        String last = p.substring(i + 1);
        if (!last.matches(".*\\d.*")) return null;
        try {
            java.net.URL u = new java.net.URL(url);
            return u.getProtocol() + "://" + u.getAuthority() + p.substring(0, i + 1);
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ main text block

    private static final class Content {
        Element el;
        String raw;
        boolean byRule;
    }

    private static final Set<String> CANDIDATE = new HashSet<>(java.util.Arrays.asList(
            "div", "article", "section", "td", "main", "p", "span", "body", "dd", "pre", "font", "blockquote"));

    private Content findContent(Document doc, SiteRule rule) {
        Element body = doc.body();
        Content c = new Content();
        if (rule != null && rule.content != null) {
            Element e = Dom.first(doc, rule.content);
            if (e != null) {
                c.el = e;
                c.byRule = true;
            }
        }
        if (c.el == null) {
            double best = 0;
            for (Element e : body.getAllElements()) {
                if (!CANDIDATE.contains(e.normalName())) continue;
                int[] s = stat(e);
                int len = s[0], linkLen = s[1], breaks = s[2];
                if (len < 30) continue;
                double ld = linkLen / (double) (len + linkLen);
                double score = len * (1 - ld) * (1 - ld);
                if (breaks >= 3) score *= 1.3;
                String ic = Dom.idClass(e);
                if (POS_HINT.matcher(ic).find()) score *= 1.6;
                if (NEG_HINT.matcher(ic).find()) score *= 0.35;
                if (score > best) {
                    best = score;
                    c.el = e;
                }
            }
        }
        if (c.el == null) return null;
        int structural = c.el.select("br,p").size();
        StringBuilder sb = new StringBuilder();
        walk(c.el, sb, structural < 3, true);
        String raw = sb.toString();
        // source line wraps inside a paragraph: drop between CJK, space otherwise
        raw = raw.replaceAll("(?<=[^\\x00-\\x7f])\\u0001(?=[^\\x00-\\x7f])", "").replace('\u0001', ' ');
        c.raw = raw;
        return c;
    }

    /** {own text weighted length, link text weighted length, structural breaks}. */
    private static int[] stat(Element e) {
        int len = 0, link = 0, breaks = 0;
        for (Node n : e.childNodes()) {
            if (n instanceof TextNode) {
                int l = Texts.wlen(((TextNode) n).getWholeText());
                if (l > 0) {
                    len += l;
                    breaks++;
                }
            } else if (n instanceof Element) {
                Element c = (Element) n;
                String tag = c.normalName();
                if (tag.equals("br")) continue;
                if (tag.equals("a")) {
                    link += Texts.wlen(c.text());
                    continue;
                }
                if (isJunkBlock(c)) continue;
                if (Dom.hasBlockDescendant(c)) continue;
                if (tag.matches("h[1-6]|ul|ol|dl|table|select|button|label|nav|header|footer|aside|form")) continue;
                int linkIn = 0;
                for (Element a : c.select("a")) linkIn += Texts.wlen(a.text());
                int l = Texts.wlen(c.text()) - linkIn;
                link += linkIn;
                if (l > 0) {
                    len += l;
                    if (Dom.BLOCK.contains(tag)) breaks++;
                }
            }
        }
        return new int[]{len, link, breaks};
    }

    private static boolean isJunkBlock(Element e) {
        String ic = Dom.idClass(e).trim();
        return !ic.isEmpty() && JUNK_BLOCK.matcher(ic).find();
    }

    private void walk(Node node, StringBuilder sb, boolean newlineIsBreak, boolean root) {
        if (node instanceof TextNode) {
            String t = ((TextNode) node).getWholeText();
            if (newlineIsBreak) sb.append(t);
            else sb.append(t.replaceAll("\\s*[\\r\\n]+\\s*", "\u0001"));
            return;
        }
        if (!(node instanceof Element)) return;
        Element e = (Element) node;
        String tag = e.normalName();
        switch (tag) {
            case "br":
            case "hr":
                sb.append('\n');
                return;
            case "select":
            case "button":
            case "input":
            case "img":
            case "label":
            case "nav":
            case "footer":
            case "form":
                return;
            case "a": {
                String t = navText(e.text());
                if (NEXT.matcher(t).matches() || PREV.matcher(t).matches() || TOC.matcher(t).matches()) return;
                String raw = e.text();
                if (raw.contains("http") || raw.contains("www.") || e.select("img").size() > 0) return;
                break;
            }
            default:
                break;
        }
        if (!root && isJunkBlock(e)) return;
        boolean block = Dom.BLOCK.contains(tag);
        if (block) sb.append('\n');
        for (Node c : e.childNodes()) walk(c, sb, newlineIsBreak, false);
        if (block) sb.append('\n');
    }

    /** @return {chapter title, book title guessed from the same sources (may be empty)}. */
    private String[] chapterTitle(Document doc, String pageUrl, String docTitle, SiteRule rule) {
        String book = "";
        // <title>: "第1章 xx_书名_站名" / "书名 第1章 xx - 站名" / "Chapter 1 - Book | Site"
        String fromTitle = null;
        String[] seg = TITLE_SPLIT.split(docTitle);
        for (int i = 0; i < seg.length; i++) {
            int at = Texts.chapterTitleStart(seg[i]);
            if (at < 0) continue;
            fromTitle = Texts.trim(seg[i].substring(at));
            String before = Texts.trim(seg[i].substring(0, at));
            fromTitle = SITE_TAIL.matcher(fromTitle).replaceFirst("");
            if (before.length() >= 2) book = before;
            else if (i > 0) book = seg[i - 1];
            else if (i + 1 < seg.length) book = seg[i + 1];
            break;
        }
        if (fromTitle == null && seg.length >= 3) book = seg[1];

        String title = rule != null ? Dom.value(doc, rule.chapterTitle) : null;
        if (title == null) {
            String firstHeading = null;
            List<Element> cands = new ArrayList<>(doc.select("h1"));
            cands.addAll(doc.select("h2,h3"));
            for (Element e : doc.select("div,span,p,strong")) {
                if (TITLE_HINT.matcher(Dom.idClass(e)).find() && !Dom.hasBlockDescendant(e)) cands.add(e);
            }
            String self = Urls.normalize(pageUrl);
            for (Element e : cands) {
                String t = Texts.trim(e.text());
                if (t.isEmpty() || t.length() > 80) continue;
                Element link = e.selectFirst("a[href]");
                if (link == null) link = e.closest("a[href]");
                if (link != null && Texts.bare(link.text()).equals(Texts.bare(t))) {
                    String href = abs(link, pageUrl);
                    if (href != null && !Urls.normalize(href).equals(self)) continue;
                }
                if (Texts.isChapterTitle(t)) {
                    title = t;
                    break;
                }
                if (firstHeading == null && e.normalName().equals("h1")) firstHeading = t;
            }
            if (title == null) title = fromTitle;
            if (title == null) title = firstHeading;
            if (title == null) title = seg.length > 0 ? Texts.trim(seg[0]) : "";
            if (firstHeading != null && book.isEmpty() && !firstHeading.equals(title)) book = firstHeading;
            // "<heading><sep>Book<sep>Site" where the heading itself contains a separator ("Prologue——Mirror"):
            // splitting blindly would take a piece of the heading for the book
            String dt = Texts.trim(docTitle);
            if (fromTitle == null && title.length() >= 2 && dt.startsWith(title) && TITLE_SPLIT.matcher(title).find()) {
                for (String part : TITLE_SPLIT.split(dt.substring(title.length()))) {
                    if (Texts.trim(part).isEmpty()) continue;
                    book = part;
                    break;
                }
            }
        }
        return new String[]{title, Texts.trim(book)};
    }

    // ------------------------------------------------------------------ chapter list

    private static final class Link {
        Element a;
        String url, text, shape, dir;
        boolean strong, weak;
        int num;
        long urlNum;
    }

    private static final class Toc {
        List<Link> links;
        boolean weak, byRule;
        String selector;
    }

    private Toc findToc(Document doc, String url, SiteRule rule) {
        Element body = doc.body();
        if (rule != null && rule.chapterList != null) {
            List<Link> list = new ArrayList<>();
            for (Element e : Dom.all(doc, rule.chapterList)) {
                if (e.normalName().equals("a")) addLink(list, e, url);
                else for (Element a : e.select("[href]")) addLink(list, a, url);
            }
            if (!list.isEmpty()) {
                Toc t = new Toc();
                t.links = orient(dedupe(list));
                t.byRule = true;
                return t;
            }
        }
        List<Link> links = new ArrayList<>();
        for (Element a : body.select("[href]")) addLink(links, a, url);
        if (links.size() < 3) return null;

        // pass 1: containers ranked by number and purity of chapter-titled links
        IdentityHashMap<Element, int[]> stats = new IdentityHashMap<>();
        for (Link l : links) {
            boolean cl = l.strong || l.weak;
            for (Element p = l.a.parent(); p != null; p = p.parent()) {
                int[] s = stats.get(p);
                if (s == null) stats.put(p, s = new int[2]);
                s[0]++;
                if (cl) s[1]++;
                if (p == body) break;
            }
        }
        List<Element> cands = new ArrayList<>();
        for (Map.Entry<Element, int[]> en : stats.entrySet()) if (en.getValue()[1] >= 3) cands.add(en.getKey());
        final IdentityHashMap<Element, Double> score = new IdentityHashMap<>();
        for (Element e : cands) {
            int[] s = stats.get(e);
            // purity-weighted size; deeper containers win ties
            score.put(e, s[1] * (double) s[1] / s[0] + Math.min(e.parents().size(), 30) * 0.001);
        }
        Collections.sort(cands, (x, y) -> Double.compare(score.get(y), score.get(x)));
        for (int i = 0; i < cands.size() && i < 5; i++) {
            Toc t = evaluate(under(links, cands.get(i)), url, false);
            if (t != null) {
                String sel = Dom.stableSelector(cands.get(i), doc);
                t.selector = sel == null ? null : sel + " a";
                return t;
            }
        }

        // pass 2: titles without numbering - a container of same-shaped links in id order
        IdentityHashMap<Element, List<Link>> near = new IdentityHashMap<>();
        for (Link l : links) {
            Element p = l.a.parent();
            for (int d = 0; d < 5 && p != null; d++, p = p.parent()) {
                List<Link> ls = near.get(p);
                if (ls == null) near.put(p, ls = new ArrayList<>());
                ls.add(l);
                if (p == body) break;
            }
        }
        Toc best = null;
        for (Map.Entry<Element, List<Link>> en : near.entrySet()) {
            if (en.getValue().size() < 8) continue;
            Toc t = evaluate(en.getValue(), url, true);
            if (t != null && (best == null || t.links.size() > best.links.size())) {
                best = t;
                String sel = Dom.stableSelector(en.getKey(), doc);
                t.selector = sel == null ? null : sel + " a";
            }
        }
        return best;
    }

    private void addLink(List<Link> out, Element a, String pageUrl) {
        String href = abs(a, pageUrl);
        if (href == null) return;
        String text = Texts.trim(a.text());
        if (text.isEmpty()) text = Texts.trim(a.attr("title"));
        if (text.isEmpty() || text.length() > 120) return;
        String nt = navText(text);
        if (NEXT.matcher(nt).matches() || PREV.matcher(nt).matches() || TOC.matcher(nt).matches()) return;
        Link l = new Link();
        l.a = a;
        l.url = href;
        l.text = text;
        if (!Texts.isChapterTitle(text)) {
            // "第一回 <a>title</a>" or <td>第一回</td><td><a>title</a></td>
            Element p = a.parent();
            String around = null;
            if (p != null && p.select("[href]").size() == 1) {
                String pt = Texts.trim(p.text());
                if (pt.length() <= 100 && pt.contains(text) && Texts.isChapterTitle(pt)) around = pt;
                else {
                    Element prev = p.previousElementSibling();
                    String st = prev == null ? "" : Texts.trim(prev.text());
                    if (!st.isEmpty() && st.length() <= 14 && prev.select("[href]").isEmpty() && Texts.isChapterTitle(st)) {
                        around = st.replaceAll("[\\s\u3000\u00a0]+", "") + " " + text;
                    }
                }
            }
            if (around != null) text = around.replaceAll("[\\s\u3000\u00a0]+", " ");
        }
        l.text = text;
        l.strong = Texts.isChapterTitle(text);
        l.weak = !l.strong && Texts.isWeakChapterTitle(text);
        l.num = l.strong || l.weak ? Texts.chapterNumber(text) : -1;
        l.urlNum = Urls.lastNumber(href);
        l.shape = Urls.shape(href);
        l.dir = Urls.dirOf(href);
        out.add(l);
    }

    private static List<Link> under(List<Link> links, Element container) {
        List<Link> out = new ArrayList<>();
        for (Link l : links) {
            for (Element p = l.a.parent(); p != null; p = p.parent()) {
                if (p == container) {
                    out.add(l);
                    break;
                }
            }
        }
        return out;
    }

    /** Decides whether a group of links is one book's chapter list; returns it cleaned and ordered. */
    private Toc evaluate(List<Link> group, String pageUrl, boolean shapeOnly) {
        Map<String, Integer> shapes = new HashMap<>();
        for (Link l : group) {
            if (shapeOnly || l.strong || l.weak) {
                Integer c = shapes.get(l.shape);
                shapes.put(l.shape, c == null ? 1 : c + 1);
            }
        }
        String dominant = null;
        int max = 0;
        for (Map.Entry<String, Integer> e : shapes.entrySet()) {
            if (e.getValue() > max) {
                max = e.getValue();
                dominant = e.getKey();
            }
        }
        if (dominant == null) return null;
        List<Link> chosen = new ArrayList<>();
        int strong = 0, textLen = 0, numeric = 0;
        for (Link l : group) {
            if (!l.shape.equals(dominant)) continue;
            chosen.add(l);
            if (l.strong) strong++;
            textLen += l.text.length();
            if (l.text.matches("[\\d\\s.]+")) numeric++;
        }
        chosen = dedupe(chosen);
        int n = chosen.size();
        if (n < 3) return null;
        if (shapeOnly && (n < 8 || n < group.size() * 0.7 || numeric > n / 4
                || textLen / n < 2 || !Urls.sameSite(pageUrl, chosen.get(0).url))) return null;

        int asc = 0, desc = 0, pairs = 0, up = 0, down = 0;
        Set<String> dirs = new HashSet<>();
        for (int i = 0; i < n; i++) {
            Link b = chosen.get(i);
            dirs.add(b.dir);
            if (i == 0) continue;
            Link a = chosen.get(i - 1);
            if (a.num >= 0 && b.num >= 0) {
                pairs++;
                if (b.num == a.num + 1) asc++;
                else if (b.num == a.num - 1) desc++;
            }
            if (a.urlNum >= 0 && b.urlNum >= 0) {
                if (b.urlNum > a.urlNum) up++;
                else if (b.urlNum < a.urlNum) down++;
            }
        }
        double seq = pairs >= 2 ? Math.max(asc, desc) / (double) pairs : 0;
        double mono = Math.max(up, down) / (double) (n - 1);
        boolean ordered = mono >= 0.8 && dirs.size() <= 2;
        boolean ok;
        if (shapeOnly) ok = mono >= 0.85 && dirs.size() <= 2;
        else if (strong >= 3) ok = seq >= 0.4 || ordered;
        else ok = seq >= 0.4 && mono >= 0.7 && dirs.size() <= 2;
        if (!ok) return null;

        Toc t = new Toc();
        t.links = orient(chosen);
        t.weak = shapeOnly;
        return t;
    }

    /** Newest-first lists are flipped so chapter 1 comes first. */
    private static List<Link> orient(List<Link> list) {
        int asc = 0, desc = 0, up = 0, down = 0;
        for (int i = 1; i < list.size(); i++) {
            Link a = list.get(i - 1), b = list.get(i);
            if (a.num >= 0 && b.num >= 0) {
                if (b.num > a.num) asc++;
                else if (b.num < a.num) desc++;
            }
            if (a.urlNum >= 0 && b.urlNum >= 0) {
                if (b.urlNum > a.urlNum) up++;
                else if (b.urlNum < a.urlNum) down++;
            }
        }
        boolean reversed = asc + desc >= 2 ? desc > asc * 2 : down > up * 2 && down >= 3;
        if (reversed) {
            list = new ArrayList<>(list);
            Collections.reverse(list);
        }
        return list;
    }

    /**
     * Removes repeated URLs. Templates often show a "latest chapters" block before the full list;
     * whichever of keep-first / keep-last yields the better ordered list is used.
     */
    private static List<Link> dedupe(List<Link> in) {
        Set<String> seen = new HashSet<>();
        boolean dup = false;
        for (Link l : in) if (!seen.add(Urls.normalize(l.url))) dup = true;
        if (!dup) return in;
        List<Link> first = new ArrayList<>();
        seen.clear();
        for (Link l : in) if (seen.add(Urls.normalize(l.url))) first.add(l);
        List<Link> last = new ArrayList<>();
        seen.clear();
        for (int i = in.size() - 1; i >= 0; i--) if (seen.add(Urls.normalize(in.get(i).url))) last.add(in.get(i));
        Collections.reverse(last);
        return order(last) >= order(first) ? last : first;
    }

    private static int order(List<Link> list) {
        int s = 0;
        for (int i = 1; i < list.size(); i++) {
            Link a = list.get(i - 1), b = list.get(i);
            if (a.num >= 0 && b.num >= 0) {
                if (b.num == a.num + 1) s++;
            } else if (a.urlNum >= 0 && b.urlNum > a.urlNum) s++;
        }
        return s;
    }

    /** Page URLs offered by a "jump to page" selector on paginated chapter lists. */
    private static List<String> tocPages(Document doc, String url) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String cur = Urls.normalize(url);
        for (Element sel : doc.select("select")) {
            LinkedHashSet<String> pages = new LinkedHashSet<>();
            for (Element o : sel.select("option[value]")) {
                String v = o.attr("value").trim();
                if (!(v.startsWith("/") || v.startsWith("http") || v.matches(".*\\.(html?|php|aspx?)([?#].*)?$") || v.startsWith("?"))) continue;
                String u = Urls.resolve(url, v);
                if (u != null && Urls.sameSite(u, url) && !Urls.normalize(u).equals(cur)) pages.add(u);
            }
            if (pages.size() >= 1 && pages.size() > out.size()) {
                out.clear();
                out.addAll(pages);
            }
        }
        return new ArrayList<>(out);
    }

    /** Link to the full chapter list when this page shows none or only the latest few. */
    private static String moreToc(Document doc, String url, Toc toc, Map<String, String> meta) {
        String cur = Urls.normalize(url);
        Set<String> inToc = new HashSet<>();
        if (toc != null) for (Link l : toc.links) inToc.add(Urls.normalize(l.url));
        for (Element a : doc.select("[href]")) {
            String t = navText(a.text());
            if (t.isEmpty() || t.length() > 24 || !MORE_TOC.matcher(t).matches()) continue;
            String href = abs(a, url);
            if (href == null || !Urls.sameSite(href, url)) continue;
            String k = Urls.normalize(href);
            if (k.equals(cur) || inToc.contains(k) || Urls.path(href).replace("/", "").isEmpty()) continue;
            return href;
        }
        String read = meta.get("og:novel:read_url");
        if (read != null) {
            String u = Urls.resolve(url, read);
            if (u != null && !Urls.normalize(u).equals(cur)) return u;
        }
        return null;
    }
}
