package com.rc.novelreader.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class BookParserTest {
    @Test
    public void textIsSplitAtHeadings() {
        String txt = "《灯下》\r\n作者 某人\r\n\r\n"
                + "第一章 河岸\r\n　　夜色如墨。\r\n他走了很久。\r\n"
                + "第二章 城墙\r\n风里有潮湿的气味。\r\n"
                + "他想起第三章里写过的那句话。\r\n";
        BookParser.Parsed p = BookParser.splitTxt("lamp.txt", txt);
        assertEquals("lamp", p.title);
        assertEquals(3, p.sections.size());
        assertEquals("", p.sections.get(0).title);
        assertEquals("第一章 河岸", p.sections.get(1).title);
        assertEquals("夜色如墨。\n他走了很久。", p.sections.get(1).text);
        // a sentence that mentions a chapter is text, not a heading
        assertTrue(p.sections.get(2).text.contains("第三章"));
    }

    @Test
    public void textWithoutHeadingsIsCutIntoPieces() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 400; i++) sb.append("The rain had stopped by the time she reached the harbour, line ").append(i).append(".\n");
        BookParser.Parsed p = BookParser.splitTxt("rain.txt", sb.toString());
        assertTrue(p.sections.size() >= 3);
        int lines = 0;
        for (BookParser.Section s : p.sections) lines += s.text.split("\n").length;
        assertEquals(400, lines);
    }

    @Test
    public void legacyChineseEncodingIsRecognised() {
        String s = "第一章 河岸\n夜色如墨。";
        assertEquals(s, BookParser.decode(s.getBytes(Charset.forName("GBK"))));
        assertEquals(s, BookParser.decode(("﻿" + s).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void epubSpineAndContentsAreRead() throws Exception {
        File f = File.createTempFile("book", ".epub");
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            put(z, "mimetype", "application/epub+zip");
            put(z, "META-INF/container.xml", "<?xml version=\"1.0\"?><container><rootfiles>"
                    + "<rootfile full-path=\"OPS/book.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>");
            put(z, "OPS/book.opf", "<?xml version=\"1.0\"?><package xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><metadata>"
                    + "<dc:title>Glass Harbor</dc:title><dc:creator>A. Writer</dc:creator></metadata><manifest>"
                    + "<item id=\"ncx\" href=\"toc.ncx\" media-type=\"application/x-dtbncx+xml\"/>"
                    + "<item id=\"cover\" href=\"text/cover.xhtml\" media-type=\"application/xhtml+xml\"/>"
                    + "<item id=\"c1\" href=\"text/c%201.xhtml\" media-type=\"application/xhtml+xml\"/>"
                    + "<item id=\"c2\" href=\"text/c2.xhtml\" media-type=\"application/xhtml+xml\"/></manifest>"
                    + "<spine toc=\"ncx\"><itemref idref=\"cover\"/><itemref idref=\"c1\"/><itemref idref=\"c2\"/></spine></package>");
            put(z, "OPS/toc.ncx", "<?xml version=\"1.0\"?><ncx><navMap>"
                    + "<navPoint><navLabel><text>The Tide</text></navLabel><content src=\"text/c%201.xhtml#top\"/></navPoint>"
                    + "</navMap></ncx>");
            put(z, "OPS/text/cover.xhtml", "<html><body><img src=\"../cover.jpg\"/></body></html>");
            put(z, "OPS/text/c 1.xhtml", "<html><head><title>x</title><style>p{color:red}</style></head><body><h2>The Tide</h2>"
                    + "<p>Nobody was <i>waiting</i>.</p><p>The rain had stopped.<br/>She went on.</p></body></html>");
            put(z, "OPS/text/c2.xhtml", "<html><body><h1>Lanterns</h1><div><p>One lamp was lit.</p></div></body></html>");
        }
        BookParser.Parsed p = BookParser.parseEpub(f);
        f.delete();
        assertEquals("Glass Harbor", p.title);
        assertEquals("A. Writer", p.author);
        assertEquals(2, p.sections.size());
        assertEquals("The Tide", p.sections.get(0).title);
        assertEquals("Nobody was waiting.\nThe rain had stopped.\nShe went on.", p.sections.get(0).text);
        assertEquals("Lanterns", p.sections.get(1).title);
        assertEquals("One lamp was lit.", p.sections.get(1).text);
    }

    private static void put(ZipOutputStream z, String name, String content) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    @Test
    public void chineseConversionBothWays() throws Exception {
        // the asset's format: comment, simplified->traditional pairs, traditional->simplified pairs
        String table = "# test\n书書读讀\n書书讀读\n";
        Hanzi h = new Hanzi(new ByteArrayInputStream(table.getBytes(StandardCharsets.UTF_8)));
        assertEquals("讀書 abc", h.convert("读书 abc", Hanzi.TO_TRADITIONAL));
        assertEquals("读书", h.convert("讀書", Hanzi.TO_SIMPLIFIED));
        assertEquals("读书", h.convert("读书", Hanzi.NONE));
    }
}
