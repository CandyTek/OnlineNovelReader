package com.rc.novelreader.testing;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An invented novel site served from inside the test process on 127.0.0.1, so the tests need no network and
 * no PC. Every request is counted (the app has a request budget, and the tests check it).
 *
 * <pre>
 * /b/              contents of a book with {@link #chapters} chapters; /b/N.html chapter N
 *                  (chapters in {@link #broken} answer 404, chapters in {@link #soft} an error page with 200)
 * /solo/N.html     a book without a contents page, {@link #soloChapters} chapters chained by "next" links
 * /gbk/1.html      a chapter in GBK
 * /wide/           contents with 3000 chapters; /wide/N.html
 * /big.html        a chapter of 4000 paragraphs      /para.html  a chapter that is one paragraph of 200 000 characters
 * /deep.html       a chapter under 4000 nested elements
 * /redir           302 to /b/1.html                  /loop       302 to itself
 * /huge            8 MB of text                      /cut.html   announces more bytes than it sends
 * /slow.html       a chapter after 3 seconds         /empty.html nothing     /junk.html  random bytes
 * /check.html      403 and a human check             /portal/    a page of links that is no novel
 * </pre>
 */
public final class MiniSite implements Closeable {
    private static final String[] SENT = {
            "夜色如墨，山风从谷口灌进来，吹得篝火忽明忽暗。", "少年把最后一根枯枝丢进火堆，抬头望向远处连绵的群山。",
            "老者捋着胡须说道，明日过了青石渡，便是剑阁的地界了。", "他没有回答，只是把膝上的旧剑又擦了一遍。",
            "远处传来几声狼嚎，林间的雾气渐渐浓了起来。", "这一路走来，同行的人越来越少，脚下的路却越来越宽。",
            "他忽然想起离家那天，母亲站在村口的老槐树下，一句话也没有说。", "剑身映着火光，像一泓流动的秋水。",
            "天边泛起鱼肚白的时候，他们终于看见了那座传说中的城。"};

    public volatile int chapters = 12;
    public volatile int soloChapters = 4;
    public final Set<Integer> broken = Collections.synchronizedSet(new HashSet<Integer>());
    public final Set<Integer> soft = Collections.synchronizedSet(new HashSet<Integer>());
    private final Map<String, Integer> hits = new ConcurrentHashMap<>();
    private final ServerSocket server;
    private volatile boolean closed;

    public MiniSite() throws Exception {
        server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(() -> {
            while (!closed) {
                try {
                    final Socket s = server.accept();
                    new Thread(() -> serve(s), "minisite").start();
                } catch (Exception e) {
                    if (closed) return;
                }
            }
        }, "minisite-accept");
        t.setDaemon(true);
        t.start();
    }

    public String url(String path) {
        return "http://127.0.0.1:" + server.getLocalPort() + path;
    }

    /** Requests seen for exactly this path. */
    public int hits(String path) {
        Integer n = hits.get(path);
        return n == null ? 0 : n;
    }

    public int totalHits() {
        int n = 0;
        for (Integer v : hits.values()) n += v;
        return n;
    }

    public void resetHits() {
        hits.clear();
    }

    @Override
    public void close() {
        closed = true;
        try {
            server.close();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------ text

    public static String paragraph(int seed) {
        Random r = new Random(seed);
        StringBuilder sb = new StringBuilder();
        for (int i = 0, n = 2 + r.nextInt(3); i < n; i++) sb.append(SENT[r.nextInt(SENT.length)]);
        return sb.toString();
    }

    public static List<String> paragraphs(int seed, int n) {
        List<String> ps = new ArrayList<>();
        for (int i = 0; i < n; i++) ps.add(paragraph(seed * 1000 + i));
        return ps;
    }

    public static String title(int n) {
        return "第" + n + "章 山门" + n;
    }

    private static String head(String title, String charset) {
        return "<html><head><meta http-equiv=\"Content-Type\" content=\"text/html; charset=" + charset + "\" />"
                + "<title>" + title + "</title></head><body>"
                + "<div class=\"header\"><a href=\"/\">首页</a> <a href=\"/xuanhuan/\">玄幻小说</a> <a href=\"/top/\">排行榜</a></div>";
    }

    private static String chapterPage(String dir, String book, int n, int count, List<String> ps, String charset, boolean toc) {
        String prev = n > 1 ? dir + (n - 1) + ".html" : (toc ? dir : dir + "1.html");
        StringBuilder nav = new StringBuilder();
        if (n > 1) nav.append("<a href=\"").append(prev).append("\">上一章</a> ");
        if (toc) nav.append("<a href=\"").append(dir).append("\">章节目录</a> ");
        if (n < count) nav.append("<a href=\"").append(dir).append(n + 1).append(".html\">下一章</a>");
        StringBuilder sb = new StringBuilder(head(title(n) + "_" + book + "_某某书屋", charset));
        sb.append("<div class=\"box_con\"><div class=\"bookname\"><h1>").append(title(n)).append("</h1><div class=\"bottem1\">")
                .append(nav).append("</div></div><div id=\"content\">");
        for (String p : ps) sb.append("&nbsp;&nbsp;&nbsp;&nbsp;").append(p).append("<br/><br/>\n");
        sb.append("</div><div class=\"bottem2\">").append(nav).append("</div></div>")
                .append("<div class=\"footer\">小说").append(book).append("由网友上传</div></body></html>");
        return sb.toString();
    }

    private static String contents(String dir, String book, int count) {
        StringBuilder sb = new StringBuilder(head(book + "最新章节_" + book + "全文阅读_某某书屋", "utf-8"));
        sb.append("<div id=\"maininfo\"><div id=\"info\"><h1>").append(book).append("</h1><p>作者:青山客</p></div>")
                .append("<div id=\"intro\"><p>少年自山中来，一剑开天门。十年磨一剑，霜刃未曾试。</p></div></div><div id=\"list\"><dl><dt>")
                .append(book).append("正文</dt>");
        for (int i = 1; i <= count; i++) {
            sb.append("<dd><a href=\"").append(dir).append(i).append(".html\">").append(title(i)).append("</a></dd>\n");
        }
        return sb.append("</dl></div><div class=\"footer\">本站所有小说为转载作品</div></body></html>").toString();
    }

    private static String errorPage() {
        return head("出错了_某某书屋", "utf-8") + "<div class=\"box_con\"><div class=\"bookname\"><h1>章节不存在</h1></div>"
                + "<div id=\"content\">抱歉，您访问的章节不存在或已被删除，请稍后再试。</div></div></body></html>";
    }

    // ------------------------------------------------------------------ serving

    private static final class Answer {
        int code = 200;
        String type = "text/html; charset=utf-8";
        byte[] body = new byte[0];
        String location;
        long delay;
        /** Bytes announced beyond the ones sent. */
        int missing;
    }

    private static Answer html(String s) {
        Answer a = new Answer();
        a.body = s.getBytes(StandardCharsets.UTF_8);
        return a;
    }

    private static int number(String path) {
        try {
            return Integer.parseInt(path.substring(path.lastIndexOf('/') + 1).replace(".html", ""));
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private Answer answer(String path) {
        Answer a;
        int n = number(path);
        if (path.equals("/b/")) return html(contents("/b/", "万古剑神", chapters));
        if (path.startsWith("/b/") && n >= 1 && n <= chapters) {
            if (broken.contains(n)) {
                a = html(errorPage());
                a.code = 404;
                return a;
            }
            if (soft.contains(n)) return html(errorPage());
            return html(chapterPage("/b/", "万古剑神", n, chapters, paragraphs(n, 16), "utf-8", true));
        }
        if (path.startsWith("/solo/") && n >= 1 && n <= soloChapters) {
            return html(chapterPage("/solo/", "长安行", n, soloChapters, paragraphs(100 + n, 14), "utf-8", false));
        }
        if (path.equals("/gbk/1.html")) {
            a = new Answer();
            a.type = "text/html";
            a.body = chapterPage("/gbk/", "寒江雪", 1, 3, paragraphs(7, 16), "gbk", false).getBytes(Charset.forName("GBK"));
            return a;
        }
        if (path.equals("/wide/")) return html(contents("/wide/", "三千里", 3000));
        if (path.startsWith("/wide/") && n >= 1 && n <= 3000) {
            return html(chapterPage("/wide/", "三千里", n, 3000, paragraphs(n, 12), "utf-8", true));
        }
        if (path.equals("/big.html")) return html(chapterPage("/x/", "大章", 1, 1, paragraphs(3, 4000), "utf-8", false));
        if (path.equals("/para.html")) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; sb.length() < 200000; i++) sb.append(paragraph(i));
            return html(chapterPage("/x/", "长段", 1, 1, Collections.singletonList(sb.toString()), "utf-8", false));
        }
        if (path.equals("/deep.html")) {
            StringBuilder sb = new StringBuilder(head(title(1) + "_深渊_某某书屋", "utf-8"));
            for (int i = 0; i < 4000; i++) sb.append("<div>");
            sb.append("<h1>").append(title(1)).append("</h1><div id=\"content\">");
            for (String p : paragraphs(9, 16)) sb.append(p).append("<br/><br/>");
            sb.append("</div>");
            for (int i = 0; i < 4000; i++) sb.append("</div>");
            return html(sb.append("</body></html>").toString());
        }
        if (path.equals("/redir") || path.equals("/loop")) {
            a = new Answer();
            a.code = 302;
            a.location = path.equals("/loop") ? "/loop" : "/b/1.html";
            return a;
        }
        if (path.equals("/huge")) {
            a = new Answer();
            a.type = "text/plain; charset=utf-8";
            a.body = new byte[8 * 1024 * 1024];
            java.util.Arrays.fill(a.body, (byte) 'a');
            return a;
        }
        if (path.equals("/cut.html")) {
            a = html(chapterPage("/x/", "断章", 1, 1, paragraphs(5, 16), "utf-8", false));
            a.missing = 50000;
            return a;
        }
        if (path.equals("/slow.html")) {
            a = html(chapterPage("/x/", "慢章", 1, 1, paragraphs(6, 16), "utf-8", false));
            a.delay = 3000;
            return a;
        }
        if (path.equals("/empty.html")) return html("");
        if (path.equals("/junk.html")) {
            a = new Answer();
            a.body = new byte[60000];
            new Random(1).nextBytes(a.body);
            return a;
        }
        if (path.equals("/check.html")) {
            a = html("<html><head><title>Just a moment...</title></head><body><h1>Security check</h1>"
                    + "<p>Please verify that you are human to continue.</p><div class=\"captcha-box\"></div></body></html>");
            a.code = 403;
            return a;
        }
        if (path.equals("/portal/") || path.equals("/")) {
            StringBuilder sb = new StringBuilder(head("某某书屋_好看的小说", "utf-8"));
            String[] one = {"斗破", "大道", "长安", "万古", "寒江", "青山", "星河", "剑阁", "雪夜", "归途"}, two = {"星河", "朝天", "行", "剑神", "雪"};
            sb.append("<h2>热门推荐</h2>");
            for (int i = 0; i < 30; i++) {
                sb.append("<p>[").append(i % 2 == 0 ? "玄幻" : "都市").append("] <a href=\"/book/").append(i * 37 % 911 + 3).append("/\">")
                        .append(one[i % 10]).append(two[i % 5]).append("</a> ").append(two[(i + 2) % 5]).append("客</p>");
            }
            return html(sb.append("</body></html>").toString());
        }
        a = html(errorPage());
        a.code = 404;
        return a;
    }

    private void serve(Socket s) {
        try (Socket sock = s) {
            sock.setSoTimeout(15000);
            InputStream in = sock.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            int state = 0;
            for (int c; state < 4 && (c = in.read()) >= 0; ) {
                head.write(c);
                state = (c == '\r' && (state == 0 || state == 2)) || (c == '\n' && (state == 1 || state == 3)) ? state + 1 : (c == '\r' ? 1 : 0);
            }
            String first = head.toString("ISO-8859-1");
            String[] parts = first.split(" ");
            if (parts.length < 2) return;
            String path = parts[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            synchronized (hits) {
                Integer seen = hits.get(path);
                hits.put(path, seen == null ? 1 : seen + 1);
            }
            Answer a = answer(path);
            if (a.delay > 0) Thread.sleep(a.delay);
            StringBuilder h = new StringBuilder("HTTP/1.1 ").append(a.code).append(a.code == 200 ? " OK" : " X").append("\r\n")
                    .append("Content-Type: ").append(a.type).append("\r\n")
                    .append("Content-Length: ").append(a.body.length + a.missing).append("\r\n")
                    .append("Connection: close\r\n");
            if (a.location != null) h.append("Location: ").append(a.location).append("\r\n");
            OutputStream out = sock.getOutputStream();
            out.write(h.append("\r\n").toString().getBytes(StandardCharsets.ISO_8859_1));
            if (!parts[0].equals("HEAD")) out.write(a.body);
            out.flush();
        } catch (Exception ignored) {
        }
    }
}
