package com.rc.novelreader.sniff;

import org.junit.Test;

import java.io.File;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * Replays pages captured from the device (browser menu "Sniff this page" in a debuggable build writes
 * them to files/sniff/) through the engine and writes a report next to them.
 * Runs only when the SNIFF_DIR environment variable points at a folder of *.html dumps whose first
 * line is {@code <!--URL:...-->}.
 */
public class RealPagesTest {
    @Test
    public void report() throws Exception {
        String dir = System.getenv("SNIFF_DIR");
        if (dir == null || !new File(dir).isDirectory()) return;
        File[] files = new File(dir).listFiles((d, n) -> n.endsWith(".html"));
        if (files == null) return;
        Arrays.sort(files);
        try (PrintWriter out = new PrintWriter(new File(dir, "report.txt"), "UTF-8")) {
            for (File f : files) {
                String html = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                String url = "http://unknown.invalid/";
                if (html.startsWith("<!--URL:")) {
                    int end = html.indexOf("-->");
                    url = html.substring(8, end);
                    html = html.substring(end + 3);
                }
                PageResult r = new Sniffer().analyze(url, html);
                out.println("=== " + f.getName() + "  " + url);
                out.println("type=" + r.type + " chapterScore=" + r.chapterScore + " detailScore=" + r.detailScore
                        + " engine=" + r.engine + " share=" + String.format("%.2f", r.contentShare));
                out.println("book: " + r.book.title + " | author: " + r.book.author + " | cover: " + r.book.cover);
                out.println("toc: " + r.toc.size() + (r.toc.isEmpty() ? "" : "  [" + r.toc.get(0).title + " ... "
                        + r.toc.get(r.toc.size() - 1).title + "]") + " pages=" + r.tocPages.size() + " next=" + r.tocNextUrl
                        + " more=" + r.moreTocUrl + " sel=" + r.tocSelector);
                if (r.chapter != null) {
                    String t = r.chapter.text;
                    out.println("chapter: '" + r.chapter.title + "' book='" + r.chapter.bookTitle + "' wlen=" + r.chapter.wlen
                            + " paras=" + t.split("\n").length + " sel=" + r.contentSelector);
                    out.println("  next=" + r.chapter.nextUrl + (r.chapter.nextInChapter ? " (same chapter)" : "")
                            + " prev=" + r.chapter.prevUrl + " toc=" + r.chapter.tocUrl);
                    out.println("  head: " + t.substring(0, Math.min(160, t.length())).replace('\n', '|'));
                    out.println("  tail: " + t.substring(Math.max(0, t.length() - 160)).replace('\n', '|'));
                }
                out.println();
            }
        }
    }
}
