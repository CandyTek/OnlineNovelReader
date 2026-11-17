package com.rc.novelreader.sniff;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.rc.novelreader.util.Texts;
import com.rc.novelreader.util.Urls;

import org.junit.Test;

import java.util.List;

public class SnifferTest {
    private static final String PARA = "夜色如墨,山风从谷口灌进来,吹得篝火忽明忽暗。少年把最后一根枯枝丢进火堆,抬头望向远处连绵的群山,心里盘算着明日的路程。";
    private static final String PARA_EN = "The wind came down from the pass and worried at the campfire until the flames lay almost flat. "
            + "He fed it the last dry branch and looked toward the mountains, counting the days of walking that were left.";

    private static String paras(int n, String sep, String open, String close) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(open).append("    ").append(PARA).append(i).append(close).append(sep);
        return sb.toString();
    }

    private static String cnNum(int n) {
        String[] d = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        if (n < 10) return d[n];
        if (n < 20) return "十" + (n % 10 == 0 ? "" : d[n % 10]);
        if (n < 100) return d[n / 10] + "十" + (n % 10 == 0 ? "" : d[n % 10]);
        return d[n / 100] + "百" + (n % 100 == 0 ? "" : (n % 100 < 10 ? "零" + d[n % 100] : cnNum(n % 100).replaceFirst("^十", "一十")));
    }

    /** Classic biquge-style detail page: 9 "latest chapters" (newest first) then the full list, in one dl. */
    private static String biqugeDetail(int chapters) {
        StringBuilder sb = new StringBuilder("<html><head><title>万古剑神最新章节_万古剑神无弹窗全文阅读_笔趣阁</title>"
                + "<meta property='og:type' content='novel'/><meta property='og:novel:book_name' content='万古剑神'/>"
                + "<meta property='og:novel:author' content='青山客'/><meta property='og:image' content='/files/cover/88.jpg'/>"
                + "<meta property='og:description' content='少年自山中来,一剑开天门。'/></head><body>"
                + "<div class='header'><a href='/'>首页</a><a href='/xuanhuan/'>玄幻小说</a><a href='/top/'>排行榜</a></div>"
                + "<div id='maininfo'><div id='info'><h1>万古剑神</h1><p>作    者:青山客</p><p>最后更新:2026-01-01</p></div>"
                + "<div id='intro'><p>少年自山中来,一剑开天门。</p></div></div>"
                + "<div id='list'><dl><dt>《万古剑神》最新章节</dt>");
        for (int i = chapters; i > chapters - 9; i--) sb.append("<dd><a href='/book/88/").append(1000 + i).append(".html'>第").append(cnNum(i)).append("章 风起").append(i).append("</a></dd>");
        sb.append("<dt>《万古剑神》正文</dt>");
        for (int i = 1; i <= chapters; i++) sb.append("<dd><a href='/book/88/").append(1000 + i).append(".html'>第").append(cnNum(i)).append("章 风起").append(i).append("</a></dd>");
        sb.append("</dl></div><div class='footer'>本站所有小说为转载作品 <a href='/sitemap.xml'>网站地图</a></div></body></html>");
        return sb.toString();
    }

    private static String biqugeChapter() {
        return "<html><head><title>第十二章 夜雨_万古剑神_笔趣阁</title></head><body>"
                + "<div class='header'><a href='/'>首页</a><a href='/xuanhuan/'>玄幻小说</a><a href='/top/'>排行榜</a><a href='/full/'>完本小说</a></div>"
                + "<script>var preview_page = '/book/88/1011.html'; var next_page = '/book/88/1013.html'; var index_page = '/book/88/';</script>"
                + "<div class='box_con'><div class='con_top'><a href='/'>笔趣阁</a> &gt; <a href='/xuanhuan/'>玄幻小说</a> &gt; <a href='/book/88/'>万古剑神</a> &gt; 第十二章 夜雨</div>"
                + "<div class='bookname'><h1>第十二章 夜雨</h1><div class='bottem1'><a href='/book/88/1011.html'>上一章</a> ← <a href='/book/88/'>章节目录</a> → <a href='/book/88/1013.html'>下一章</a> <a href='javascript:;'>加入书签</a></div></div>"
                + "<div id='ad_top'><script src='//bad.example-ads.com/a.js'></script></div>"
                + "<div id='content'>&nbsp;&nbsp;&nbsp;&nbsp;天才一秒记住本站地址:www.biquge-fake.com 最快更新!<br/><br/>"
                + paras(14, "<br/><br/>", "&nbsp;&nbsp;&nbsp;&nbsp;", "")
                + "<div style='display:none'>这是反爬隐藏文字不应该出现</div>"
                + "(本章未完,请点击下一页继续阅读)<br/>请记住本书首发域名:biquge-fake.com。手机版阅读网址:m.biquge-fake.com</div>"
                + "<div class='bottem2'><a href='/book/88/1011.html'>上一章</a> ← <a href='/book/88/'>章节目录</a> → <a href='/book/88/1013.html'>下一章</a></div>"
                + "<div id='hotlist'><h3>热门推荐</h3><ul><li><a href='/book/1/'>斗破星河</a></li><li><a href='/book/2/'>大道朝天</a></li><li><a href='/book/3/'>剑来无双</a></li></ul></div>"
                + "</div><div class='footer'>小说万古剑神 第十二章 夜雨 由网友上传</div></body></html>";
    }

    /** Site home page: a table of other books with their latest chapters - must not look like a chapter list. */
    private static String homePage() {
        StringBuilder sb = new StringBuilder("<html><head><title>笔趣阁_书友最值得收藏的网络小说阅读网</title></head><body>"
                + "<div class='nav'><a href='/'>首页</a><a href='/xuanhuan/'>玄幻</a><a href='/dushi/'>都市</a></div><div id='newscontent'><ul>");
        int[] nums = {1523, 88, 407, 2210, 13, 964, 351, 78, 1190, 642, 305, 57, 880, 1432, 9, 760};
        for (int i = 0; i < nums.length; i++) {
            sb.append("<li><span class='s2'><a href='/book/").append(i * 37 + 5).append("/'>某某小说").append(i).append("</a></span>")
                    .append("<span class='s3'><a href='/book/").append(i * 37 + 5).append("/").append(90000 - i * 811).append(".html'>第").append(nums[i]).append("章 某某情节").append("</a></span>")
                    .append("<span class='s4'>作者").append(i).append("</span></li>");
        }
        sb.append("</ul></div></body></html>");
        return sb.toString();
    }

    private final Sniffer sniffer = new Sniffer();

    @Test
    public void chineseNumerals() {
        assertEquals(123, Texts.parseCnNumber("一百二十三"));
        assertEquals(15, Texts.parseCnNumber("十五"));
        assertEquals(1002, Texts.parseCnNumber("一千零二"));
        assertEquals(20500, Texts.parseCnNumber("两万零五百"));
        assertEquals(305, Texts.parseCnNumber("三〇五"));
        assertEquals(12, Texts.chapterNumber("第十二章 夜雨"));
        assertEquals(7, Texts.chapterNumber("Chapter 7: The Pass"));
        assertEquals(-1, Texts.chapterNumber("后记"));
        assertTrue(Texts.isChapterTitle("第1024章 归来"));
        assertTrue(Texts.isChapterTitle("楔子"));
        assertFalse(Texts.isChapterTitle("玄幻小说排行榜"));
    }

    @Test
    public void urlHelpers() {
        assertTrue(Urls.sameChapter("http://a.com/b/1/22.html", "http://a.com/b/1/22_2.html"));
        assertTrue(Urls.sameChapter("http://a.com/b/1/22_2.html", "http://a.com/b/1/22_3.html"));
        assertFalse(Urls.sameChapter("http://a.com/b/1/22.html", "http://a.com/b/1/23.html"));
        assertFalse(Urls.sameChapter("http://a.com/b/1/22_3.html", "http://a.com/b/1/23.html"));
        assertEquals(Urls.normalize("https://www.a.com/b/1/"), Urls.normalize("http://a.com/b/1/index.html"));
        assertEquals("example.com.cn", Urls.registrable("m.example.com.cn"));
        assertEquals("http://a.com/x", Urls.fromInput("a.com/x"));
        assertNull(Urls.fromInput("斗破苍穹 小说"));
    }

    @Test
    public void detailPageWithLatestBlock() {
        PageResult r = sniffer.analyze("http://www.biquge-fake.com/book/88/", biqugeDetail(60));
        assertEquals(PageResult.Type.DETAIL, r.type);
        assertEquals("万古剑神", r.book.title);
        assertEquals("青山客", r.book.author);
        assertEquals("http://www.biquge-fake.com/files/cover/88.jpg", r.book.cover);
        assertEquals(60, r.toc.size());
        assertEquals("第一章 风起1", r.toc.get(0).title);
        assertEquals("http://www.biquge-fake.com/book/88/1060.html", r.toc.get(59).url);
    }

    @Test
    public void chapterPageIsCleaned() {
        PageResult r = sniffer.analyze("http://www.biquge-fake.com/book/88/1012.html", biqugeChapter());
        assertEquals(PageResult.Type.CHAPTER, r.type);
        assertEquals("第十二章 夜雨", r.chapter.title);
        assertEquals("万古剑神", r.chapter.bookTitle);
        assertEquals("http://www.biquge-fake.com/book/88/1013.html", r.chapter.nextUrl);
        assertEquals("http://www.biquge-fake.com/book/88/1011.html", r.chapter.prevUrl);
        assertEquals("http://www.biquge-fake.com/book/88/", r.chapter.tocUrl);
        assertFalse(r.chapter.nextInChapter);
        String t = r.chapter.text;
        assertEquals(14, t.split("\n").length);
        assertFalse(t.contains("biquge"));
        assertFalse(t.contains("记住"));
        assertFalse(t.contains("本章未完"));
        assertFalse(t.contains("隐藏文字"));
        assertFalse(t.contains("热门推荐"));
        assertTrue(t.startsWith("夜色如墨"));
    }

    @Test
    public void homePageIsNotANovelPage() {
        PageResult r = sniffer.analyze("http://www.biquge-fake.com/", homePage());
        assertEquals(PageResult.Type.NONE, r.type);
    }

    @Test
    public void newsArticleIsNotAChapter() {
        String html = "<html><head><title>Local council approves new bridge - Daily Herald</title></head><body>"
                + "<nav><a href='/'>Home</a><a href='/world'>World</a><a href='/sport'>Sport</a></nav>"
                + "<article><h1>Local council approves new bridge</h1><div class='article-body'>"
                + "<p>" + PARA_EN + "</p><p>" + PARA_EN + "</p><p>" + PARA_EN + "</p><p>" + PARA_EN + "</p></div></article>"
                + "<aside><a href='/news/1182'>Storm closes coastal road</a><a href='/news/1177'>School wins award</a></aside></body></html>";
        PageResult r = sniffer.analyze("https://herald.example.org/news/1190", html);
        assertEquals(PageResult.Type.NONE, r.type);
    }

    @Test
    public void englishChapterWithParagraphTags() {
        StringBuilder p = new StringBuilder();
        for (int i = 0; i < 12; i++) p.append("<p>").append(PARA_EN).append("</p>\n");
        String html = "<html><head><title>Chapter 7: The Pass - Iron Road | NovelSite</title></head><body>"
                + "<header><a href='/'>Home</a> <a href='/fiction/55/iron-road'>Iron Road</a></header>"
                + "<h1>Chapter 7: The Pass</h1>"
                + "<div class='nav-buttons'><a class='btn' href='/fiction/55/iron-road/chapter/106'>Previous Chapter</a>"
                + "<a class='btn' href='/fiction/55/iron-road'>Index</a><a class='btn' href='/fiction/55/iron-road/chapter/108'>Next Chapter</a></div>"
                + "<div class='chapter-inner chapter-content'>" + p + "<p>Read the latest chapters at www.pirate-mirror.net</p></div>"
                + "<div class='comments'><div class='comment'>Thanks for the chapter!</div><div class='comment'>First</div></div></body></html>";
        PageResult r = sniffer.analyze("https://novelsite.example.com/fiction/55/iron-road/chapter/107", html);
        assertEquals(PageResult.Type.CHAPTER, r.type);
        assertEquals("Chapter 7: The Pass", r.chapter.title);
        assertEquals("Iron Road", r.chapter.bookTitle);
        assertEquals("https://novelsite.example.com/fiction/55/iron-road/chapter/108", r.chapter.nextUrl);
        assertEquals("https://novelsite.example.com/fiction/55/iron-road", r.chapter.tocUrl);
        assertEquals(12, r.chapter.text.split("\n").length);
        assertFalse(r.chapter.text.contains("pirate-mirror"));
        assertFalse(r.chapter.text.contains("Thanks for the chapter"));
    }

    @Test
    public void paginatedChapterIsFlagged() {
        String html = "<html><head><title>第3章 入城(1/2)_长安行_某某书屋</title></head><body>"
                + "<h1 class='title'>第3章 入城(1/2)</h1><div id='chaptercontent'>" + paras(8, "", "<p>", "</p>") + "</div>"
                + "<div class='pager'><a href='/read/7/3.html'>上一章</a><a href='/read/7/'>目录</a><a href='/read/7/4_2.html'>下一页</a></div></body></html>";
        PageResult r = sniffer.analyze("http://m.shuwu.example.com/read/7/4.html", html);
        assertEquals(PageResult.Type.CHAPTER, r.type);
        assertTrue(r.chapter.nextInChapter);
        assertEquals("http://m.shuwu.example.com/read/7/4_2.html", r.chapter.nextUrl);
        assertEquals("第3章入城", Texts.bare(ContentCleaner.stripPageMark(r.chapter.title)));
    }

    @Test
    public void unnumberedNewestFirstListIsReordered() {
        StringBuilder sb = new StringBuilder("<html><head><title>The Glass Harbor - Web Novel - Chapters</title></head><body>"
                + "<h1>The Glass Harbor</h1><div>Author: M. Verne</div><ul class='chapter-list'>");
        String[] names = {"Low Tide", "The Lamplighter", "Salt", "What the Gulls Know", "Ferry", "A Door in the Sea Wall",
                "Brine", "Night Market", "Undertow", "The Harbormaster", "Glass", "Homecoming"};
        for (int i = names.length - 1; i >= 0; i--) sb.append("<li><a href='/novel/glass-harbor/").append(500 + i * 3).append("'>").append(names[i]).append("</a></li>");
        sb.append("</ul></body></html>");
        PageResult r = sniffer.analyze("https://tales.example.com/novel/glass-harbor", sb.toString());
        assertEquals(PageResult.Type.DETAIL, r.type);
        assertEquals(12, r.toc.size());
        assertEquals("Low Tide", r.toc.get(0).title);
        assertEquals("M. Verne", r.book.author);
    }

    @Test
    public void paginatedListExposesItsPages() {
        StringBuilder sb = new StringBuilder("<html><head><title>长安行章节目录_某某书屋</title></head><body><h1>长安行</h1><p>作者:柳三</p><div class='listmain'>");
        for (int i = 1; i <= 20; i++) sb.append("<p><a href='/read/7/").append(i).append(".html'>第").append(i).append("章 行路").append("</a></p>");
        sb.append("</div><select onchange='location.href=this.value'><option value='/read/7/index_1.html' selected>第1页</option>"
                + "<option value='/read/7/index_2.html'>第2页</option><option value='/read/7/index_3.html'>第3页</option></select>"
                + "<a href='/read/7/index_2.html'>下一页</a></body></html>");
        PageResult r = sniffer.analyze("http://m.shuwu.example.com/read/7/index_1.html", sb.toString());
        assertEquals(PageResult.Type.DETAIL, r.type);
        assertEquals(20, r.toc.size());
        assertEquals(2, r.tocPages.size());
        assertEquals("http://m.shuwu.example.com/read/7/index_2.html", r.tocNextUrl);
        assertEquals("柳三", r.book.author);
    }

    @Test
    public void userRuleOverridesHeuristics() {
        String html = "<html><head><title>x</title></head><body><div class='wrap'><div class='t'>夜雨</div>"
                + "<div class='noise'>" + paras(10, "", "<p>", "</p>").replace("夜色如墨", "这是评论区") + "</div>"
                + "<div class='real'><p>正文第一段在这里,只有很短的两段。</p><p>正文第二段。</p></div>"
                + "<a id='n' href='/x/9.html'>»</a></div></body></html>";
        List<SiteRule> rules = SiteRule.parseList("[{\"name\":\"t\",\"host\":\"rule\\\\.example\\\\.com\",\"content\":\"div.real\","
                + "\"chapterTitle\":\"div.t\",\"next\":\"a#n\"}]", SiteRule.USER);
        Sniffer s = new Sniffer();
        s.rules.setUser(rules);
        PageResult r = s.analyze("http://rule.example.com/x/8.html", html);
        assertEquals("rule:t", r.engine);
        assertNotNull(r.chapter);
        assertEquals("夜雨", r.chapter.title);
        assertTrue(r.chapter.text.startsWith("正文第一段"));
        assertEquals("http://rule.example.com/x/9.html", r.chapter.nextUrl);
        assertEquals(PageResult.Type.CHAPTER, r.type);
    }

    /** Full-width punctuation is written as escapes so the test cannot silently degrade to ASCII. */
    @Test
    public void fullWidthPunctuationIsUnderstood() {
        String colon = "：", lp = "（", rp = "）", comma = "，";
        String html = "<html><head><title>第１２章 入城" + lp + "1/2" + rp + "_长安行_某某书屋</title></head><body>"
                + "<div class='crumbs'><a href='/'>首页</a> &gt; <a href='/read/7/'>长安行</a></div>"
                + "<h1>第１２章 入城" + lp + "1/2" + rp + "</h1><p class='who'>作者" + colon + "柳三</p>"
                + "<div id='chaptercontent'>" + paras(8, "", "<p>", "</p>")
                + "<p>" + lp + "本章未完" + comma + "请点击下一页继续阅读" + rp + "</p></div>"
                + "<div class='pager'><a href='/read/7/11.html'>上一章</a><a href='/read/7/12_2.html'>下一页</a></div></body></html>";
        PageResult r = sniffer.analyze("http://m.shuwu.example.com/read/7/12.html", html);
        assertEquals(PageResult.Type.CHAPTER, r.type);
        assertEquals(12, Texts.chapterNumber(r.chapter.title));
        assertEquals("第１２章 入城", ContentCleaner.stripPageMark(r.chapter.title));
        assertEquals("长安行", r.chapter.bookTitle);
        assertEquals("http://m.shuwu.example.com/read/7/", r.chapter.tocUrl);
        assertEquals("柳三", r.book.author);
        assertTrue(r.chapter.nextInChapter);
        assertEquals(8, r.chapter.text.split("\n").length);
        assertFalse(r.chapter.text.contains("本章未完"));
    }

    @Test
    public void hardWrappedTextIsReflowed() {
        StringBuilder sb = new StringBuilder("<html><head><title>《西游记》 第一回 灵根育孕 (纯文学网站)</title></head><body><pre>");
        String line = "盖闻天地之数有十二万九千六百岁为一元将一元分为十二会乃子丑寅";
        for (int p = 0; p < 6; p++) {
            sb.append("　　").append(line.substring(2)).append("<br>");
            for (int i = 0; i < 3; i++) sb.append(line).append("<br>");
            sb.append("卯辰巳。<br>");
        }
        sb.append("</pre><a href='002.htm'>下一回</a></body></html>");
        PageResult r = sniffer.analyze("http://www.purepen.example.com/xyj/001.htm", sb.toString());
        assertNotNull(r.chapter);
        assertEquals(6, r.chapter.text.split("\n").length);
        assertEquals("西游记", r.chapter.bookTitle);
        assertEquals("第一回 灵根育孕", r.chapter.title);
        assertEquals("http://www.purepen.example.com/xyj/002.htm", r.chapter.nextUrl);
    }

    /** Chapter addresses that end in a slash (/book/20261/) and titles without any numbering. */
    @Test
    public void directoryStyleChapterUrlsFormAList() {
        String[] names = {"云意春深", "归来何定", "华枝春满", "百计避敌", "棠梨春深", "余更衣", "杏花天影", "夜雨", "寒鸦", "惊鸿"};
        StringBuilder sb = new StringBuilder("<html><head><title>深宫旧事最新章节_深宫旧事免费在线阅读</title></head><body>"
                + "<div id='info'><h1>深宫旧事</h1><p>作者:<a href='/author/7/'>某某</a></p></div><div id='list'><dl>"
                + "<dt>最新章节</dt>");
        for (int i = 40; i > 37; i--) sb.append("<a href='/shengong/2026").append(i).append("/'><dd>").append(names[i % 10]).append(i).append("</dd></a>");
        sb.append("<dt>正文</dt>");
        for (int i = 1; i <= 40; i++) {
            // everything after the first screenful is collapsed behind a "show all" button
            sb.append(i > 12 ? "<a hidden data-extra" : "<a").append(" href='/shengong/2026").append(i).append("/'><dd>").append(names[i % 10])
                    .append(i % 2 == 0 ? "（上）" : "（下）").append("</dd></a>");
        }
        sb.append("</dl></div></body></html>");
        PageResult r = sniffer.analyze("https://www.fake-shuwu.com/shengong/", sb.toString());
        assertEquals(PageResult.Type.DETAIL, r.type);
        assertEquals(40, r.toc.size());
        assertEquals("https://www.fake-shuwu.com/shengong/20261/", r.toc.get(0).url);
        assertEquals("https://www.fake-shuwu.com/shengong/202640/", r.toc.get(39).url);
    }

    /** A "next chapter" link that an ad network has pointed at another site is not the next chapter. */
    @Test
    public void offSiteNextLinkIsFlaggedNotFollowed() {
        String page = "<html><head><title>第12章 夜雨_长安行_某某书屋</title></head><body><h1>第12章 夜雨</h1>"
                + "<div id='content'>" + paras(8, "", "<p>", "</p>") + "</div>"
                + "<div class='nav'><a href='/book/5/11.html'>上一章</a><a href='/book/5/'>目录</a>"
                + "<a id='next_url' href='%s'>下一章</a></div></body></html>";
        PageResult bad = sniffer.analyze("http://www.fake-shuwu.com/book/5/12.html",
                String.format(page, "https://promo.casino-ads-example.com/land?id=3"));
        assertEquals(PageResult.Type.CHAPTER, bad.type);
        assertNull(bad.chapter.nextUrl);
        assertTrue(bad.chapter.nextHijacked);
        PageResult good = sniffer.analyze("http://www.fake-shuwu.com/book/5/12.html", String.format(page, "/book/5/13.html"));
        assertEquals("http://www.fake-shuwu.com/book/5/13.html", good.chapter.nextUrl);
        assertFalse(good.chapter.nextHijacked);
    }

    /** Scripted navigation: the href is a stub and the address sits in a data attribute or in the click handler. */
    @Test
    public void nextLinkKeptInDataAttributeOrOnclick() {
        String page = "<html><head><title>第12章 夜雨_长安行_某某书屋</title></head><body><h1>第12章 夜雨</h1>"
                + "<div id='content'>" + paras(8, "", "<p>", "</p>") + "</div><div class='nav'>%s</div></body></html>";
        PageResult data = sniffer.analyze("http://www.fake-shuwu.com/chapter/3012", String.format(page,
                "<a class='btn' data-href='http://www.fake-shuwu.com/chapter/2990' href='javascript:'>上一章</a>"
                        + "<a href='/book/5'>目录</a>"
                        + "<a class='btn' data-href='http://www.fake-shuwu.com/chapter/3019' href='javascript:'>下一章</a>"));
        assertEquals(PageResult.Type.CHAPTER, data.type);
        assertEquals("http://www.fake-shuwu.com/chapter/3019", data.chapter.nextUrl);
        assertEquals("http://www.fake-shuwu.com/chapter/2990", data.chapter.prevUrl);
        PageResult click = sniffer.analyze("http://www.fake-shuwu.com/chapter/3012", String.format(page,
                "<a href='/book/5'>目录</a><a href='javascript:void(0)' onclick=\"location.href='/chapter/3019'\">下一章</a>"));
        assertEquals("http://www.fake-shuwu.com/chapter/3019", click.chapter.nextUrl);
    }

    /** A token repeated at the end of many paragraphs is an anti-copy watermark; a single trailing word is text. */
    @Test
    public void repeatedTrailingTokenIsWatermark() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append("\u96e8\u8fd8\u5728\u4e0b\u3002").append(i % 2 == 0 ? "qZk7P" : "").append('\n');
        sb.append("\u4ed6\u8bf4\uff1aOK");
        String out = ContentCleaner.clean(sb.toString(), "", null);
        assertFalse(out.contains("qZk7P"));
        assertTrue(out.endsWith("OK"));
    }

    /** The heading contains a title separator itself: the book is what follows the whole heading. */
    @Test
    public void headingWithSeparatorDoesNotBecomeTheBook() {
        String page = "<html><head><title>\u5e8f. Night Rain\u2014\u2014MIRROR - \u957f\u5b89\u884c - \u67d0\u67d0\u4e66\u5c4b</title></head><body>"
                + "<h1>\u5e8f. Night Rain\u2014\u2014MIRROR</h1><div id='content'>" + paras(8, "", "<p>", "</p>") + "</div>"
                + "<div class='nav'><a href='/book/5'>\u76ee\u5f55</a><a href='/chapter/3019'>\u4e0b\u4e00\u7ae0</a></div></body></html>";
        PageResult r = sniffer.analyze("http://www.fake-shuwu.com/chapter/3012", page);
        assertEquals(PageResult.Type.CHAPTER, r.type);
        assertEquals("\u957f\u5b89\u884c", r.chapter.bookTitle);
    }

    /** "Load next chapter" style labels (a verb in front of the usual words) are next links too. */
    @Test
    public void nextLinkWithVerbPrefix() {
        String page = "<html><head><title>\u7b2c12\u7ae0 \u591c\u96e8_\u957f\u5b89\u884c_\u67d0\u67d0\u4e66\u5c4b</title></head><body><h3>\u7b2c12\u7ae0 \u591c\u96e8</h3>"
                + "<div class='chapter-content'>" + paras(8, "", "<p>", "</p>") + "</div>"
                + "<div><a href='/chapter/77/3019' class='btn'>\u52a0\u8f7d\u4e0b\u4e00\u7ae0 \u2b07</a></div></body></html>";
        PageResult r = sniffer.analyze("http://m.fake-shuwu.com/chapter/77/3012", page);
        assertEquals(PageResult.Type.CHAPTER, r.type);
        assertEquals("http://m.fake-shuwu.com/chapter/77/3019", r.chapter.nextUrl);
    }

    private static String readerFrame(String docTitle, String heading, String body) {
        return "<html><head><title>" + docTitle + "</title></head><body>"
                + "<div class='header'><a href='/'>首页</a><a href='/top/'>排行榜</a></div>"
                + "<h1>" + heading + "</h1><div id='content'>" + body + "</div>"
                + "<div class='nav'><a href='/read/5/4010.html'>上一章</a><a href='/read/5/'>目录</a>"
                + "<a href='/read/5/4012.html'>下一章</a></div></body></html>";
    }

    /** A site's own "could not load" page sits in the reader template, navigation and all: it is not a chapter. */
    @Test
    public void errorPageInReaderTemplateIsNotAChapter() {
        String page = readerFrame("页面加载失败_某某书斋", "页面加载失败",
                "<p>抱歉,您访问的章节内容暂时无法显示,可能是网络繁忙,请稍后刷新页面重试。</p>");
        PageResult r = sniffer.analyze("http://www.fake-shuzhai.com/read/5/4011.html", page);
        assertFalse(r.type == PageResult.Type.CHAPTER);
        assertTrue(r.chapter == null || r.chapter.errorPage);

        // the heading is the chapter's, the text is only the frame of a script-built reader
        page = readerFrame("第12章 夜雨_长安行_某某书斋", "第12章 夜雨", "<p>正文正在加载中,请稍候,如长时间没有显示请刷新。</p>");
        r = sniffer.analyze("http://www.fake-shuzhai.com/read/5/4011.html", page);
        assertFalse(r.type == PageResult.Type.CHAPTER);
        assertTrue(r.chapter == null || r.chapter.errorPage);

        page = "<html><head><title>404 Not Found</title></head><body><h1>404 Not Found</h1>"
                + "<p>The requested URL was not found on this server. Please check the address and try again later.</p>"
                + "<hr><address>nginx</address></body></html>";
        r = sniffer.analyze("http://www.fake-shuzhai.com/read/5/4011.html", page);
        assertFalse(r.type == PageResult.Type.CHAPTER);
        assertTrue(r.chapter == null || r.chapter.errorPage);
    }

    /** Chapters may be numbered 404, be called "error", or be a few lines long. */
    @Test
    public void chaptersThatLookLikeErrorsAreKept() {
        String page = readerFrame("第404章 系统错误_长安行_某某书斋", "第404章 系统错误", paras(8, "", "<p>", "</p>"));
        PageResult r = sniffer.analyze("http://www.fake-shuzhai.com/read/5/4011.html", page);
        assertEquals(PageResult.Type.CHAPTER, r.type);
        assertFalse(r.chapter.errorPage);

        page = readerFrame("第404章 系统错误_长安行_某某书斋", "第404章 系统错误", "<p>" + PARA + "</p>");
        r = sniffer.analyze("http://www.fake-shuzhai.com/read/5/4011.html", page);
        assertNotNull(r.chapter);
        assertFalse(r.chapter.errorPage);

        assertFalse(ErrorPage.looksLike("请假条_长安行_某某书斋", "请假条", "今天有事,请假一天,明天补上。"));
        assertFalse(ErrorPage.looksLike("出错了_长安行", "出错了", paras(8, "\n", "", "")));
        assertTrue(ErrorPage.looksLike("某某书斋", "", "章节内容加载失败,请刷新重试"));
        assertTrue(ErrorPage.looksLike("502 Bad Gateway", "502 Bad Gateway", "nginx is unable to reach the upstream server right now"));
    }

    @Test
    public void heuristicSuccessIsLearnedAsRule() {
        Sniffer s = new Sniffer();
        s.analyze("http://www.biquge-fake.com/book/88/1012.html", biqugeChapter());
        SiteRule learned = s.rules.getLearned("www.biquge-fake.com");
        assertNotNull(learned);
        assertEquals("div#content", learned.content);
        // a very short chapter on the same site is now extracted through the learned selector
        String shortPage = "<html><head><title>请假条_万古剑神_笔趣阁</title></head><body><div class='bookname'><h1>请假条</h1></div>"
                + "<div id='content'>今天有事,请假一天,明天补上。</div>"
                + "<div class='bottem2'><a href='/book/88/1012.html'>上一章</a><a href='/book/88/'>章节目录</a><a href='/book/88/1014.html'>下一章</a></div>"
                + "<div class='footer'>" + PARA + PARA + PARA + "</div></body></html>";
        PageResult r = s.analyze("http://www.biquge-fake.com/book/88/1013.html", shortPage);
        assertEquals(PageResult.Type.CHAPTER, r.type);
        assertEquals("今天有事,请假一天,明天补上。", r.chapter.text);
        assertTrue(r.engine.startsWith("rule:"));
    }
}
