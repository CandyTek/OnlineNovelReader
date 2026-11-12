package com.rc.novelreader.sniff;

import com.rc.novelreader.util.Urls;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Engine #1: declarative rules. Priority: user rules, then rules learned per host from earlier
 * heuristic successes, then built-in template rules (matched by structural signature, so one rule
 * covers every site cloned from the same template).
 */
public final class RuleEngine {
    private volatile List<SiteRule> user = new ArrayList<>();
    private volatile List<SiteRule> builtin = new ArrayList<>();
    private final Map<String, SiteRule> learned = new ConcurrentHashMap<>();

    public void setBuiltin(List<SiteRule> rules) {
        builtin = new ArrayList<>(rules);
    }

    public void setUser(List<SiteRule> rules) {
        user = new ArrayList<>(rules);
    }

    public void putLearned(String host, SiteRule rule) {
        learned.put(host, rule);
    }

    public SiteRule getLearned(String host) {
        return learned.get(host);
    }

    public Map<String, SiteRule> learned() {
        return learned;
    }

    public int count() {
        return user.size() + builtin.size() + learned.size();
    }

    /** First rule applicable to this page, or null. Must be called before the document is cleaned. */
    public SiteRule match(String url, Document doc) {
        String host = Urls.host(url);
        for (SiteRule r : user) if (applies(r, host, doc)) return r;
        SiteRule l = learned.get(host);
        if (l != null && hits(l, doc)) return l;
        for (SiteRule r : builtin) if (applies(r, host, doc)) return r;
        return null;
    }

    private static boolean applies(SiteRule r, String host, Document doc) {
        if (r.host != null && !r.host.matcher(host).find()) return false;
        if (r.signature != null && Dom.first(doc, r.signature) == null) return false;
        return true;
    }

    /** A learned rule is only used on pages where at least one of its selectors still hits. */
    private static boolean hits(SiteRule r, Document doc) {
        Element c = r.content == null ? null : Dom.first(doc, r.content);
        if (c != null) return true;
        return r.chapterList != null && Dom.first(doc, r.chapterList) != null;
    }
}
