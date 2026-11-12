package com.rc.novelreader.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** The pure parts of the browser: block-list parsing and user-script matching. */
public class BrowserLogicTest {
    @Test
    public void blockListFormatsAreUnderstood() {
        String list = "# a hosts file\n"
                + "0.0.0.0 ads.example.com\n"
                + "127.0.0.1 tracker.example.net # trailing comment\n"
                + "127.0.0.1 localhost\n"
                + "0.0.0.0 localhost.localdomain\n"
                + "plain-domain.org\r\n"
                + "! an Adblock comment\n"
                + "[Adblock Plus 2.0]\n"
                + "||pop.example.cn^\n"
                + "||third.example.io^$third-party\n"
                + "@@||allowed.example.com^\n"
                + "||example.com/banner/*\n"
                + "example.com##.ad-box\n"
                + "/ads/*$script\n";
        Set<String> expected = new LinkedHashSet<>(Arrays.asList("ads.example.com", "tracker.example.net",
                "plain-domain.org", "pop.example.cn", "third.example.io"));
        assertEquals(expected, AdBlocker.parseList(list));
    }

    @Test
    public void userScriptPatterns() {
        assertTrue(UserScripts.matches("*", "https://a.example.com/x"));
        assertTrue(UserScripts.matches("example.com", "https://www.example.com/book/1.html"));
        assertTrue(UserScripts.matches("example.com", "http://example.com"));
        assertFalse(UserScripts.matches("example.com", "https://example.org/"));
        assertTrue(UserScripts.matches("https://*.example.com/book/*", "https://m.example.com/book/12/3.html"));
        assertFalse(UserScripts.matches("https://*.example.com/book/*", "https://m.example.com/list/"));
        assertTrue(UserScripts.matches("a.com, b.com\nc.com", "https://c.com/page"));
        assertFalse(UserScripts.matches("", "https://c.com/page"));
    }

    @Test
    public void userScriptHeaderIsRead() {
        UserScripts.Script s = new UserScripts.Script();
        s.code = "// ==UserScript==\n// @name   Hide footer\n// @match  https://*.example.com/*\n"
                + "// @include https://example.org/*\n// ==/UserScript==\ndocument.title = 'x';";
        UserScripts.readHeader(s);
        assertEquals("Hide footer", s.name);
        assertEquals("https://*.example.com/*\nhttps://example.org/*", s.match);
    }
}
