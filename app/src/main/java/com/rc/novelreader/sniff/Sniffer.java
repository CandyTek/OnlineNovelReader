package com.rc.novelreader.sniff;

import com.rc.novelreader.util.Urls;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.util.regex.Pattern;

/**
 * The dual-engine page identifier.
 *
 *  1. {@link RuleEngine} looks for a rule that applies to the page (user > learned > built-in).
 *  2. {@link HeuristicEngine} analyses the page; rule selectors override its detectors.
 *  3. When pure heuristics succeed with high confidence, the selectors they picked are stored as
 *     a learned rule for that host, so later pages of the site are extracted the same way even
 *     when an individual page is too short or too noisy to score well on its own.
 */
public final class Sniffer {
    public interface LearnListener {
        void onLearned(String host, SiteRule rule);
    }

    public final RuleEngine rules = new RuleEngine();
    private final HeuristicEngine heuristics = new HeuristicEngine();
    private volatile LearnListener learnListener;

    public void setLearnListener(LearnListener l) {
        learnListener = l;
    }

    public PageResult analyze(String url, String html) {
        return analyze(url, Jsoup.parse(html, url));
    }

    /** Note: the document is modified (cleaned) by the analysis. */
    public PageResult analyze(String url, Document doc) {
        SiteRule rule = rules.match(url, doc);
        PageResult r = heuristics.analyze(url, doc, rule);
        if (rule == null || SiteRule.LEARNED.equals(rule.source)) learn(url, r, rule);
        return r;
    }

    private void learn(String url, PageResult r, SiteRule existing) {
        String host = Urls.host(url);
        if (host.isEmpty()) return;
        String content = existing == null ? null : existing.content;
        String list = existing == null ? null : existing.chapterList;
        boolean changed = false;
        if (r.type == PageResult.Type.CHAPTER && r.chapterScore >= 85 && r.contentSelector != null
                && !r.contentSelector.equals(content)) {
            content = r.contentSelector;
            changed = true;
        }
        if (r.type == PageResult.Type.DETAIL && r.detailScore >= 65 && r.toc.size() >= 10
                && r.tocSelector != null && !r.tocSelector.equals(list)) {
            list = r.tocSelector;
            changed = true;
        }
        if (!changed) return;
        SiteRule rule = new SiteRule();
        rule.name = host;
        rule.source = SiteRule.LEARNED;
        rule.host = Pattern.compile("^" + Pattern.quote(host) + "$");
        rule.content = content;
        rule.chapterList = list;
        rules.putLearned(host, rule);
        LearnListener l = learnListener;
        if (l != null) l.onLearned(host, rule);
    }
}
