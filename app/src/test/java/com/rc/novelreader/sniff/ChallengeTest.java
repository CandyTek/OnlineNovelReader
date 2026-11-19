package com.rc.novelreader.sniff;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChallengeTest {
    private static final String PARA = "夜色如墨,山风从谷口灌进来,吹得篝火忽明忽暗。少年把最后一根枯枝丢进火堆,抬头望向远处连绵的群山,心里盘算着明日的路程。";

    private static String page(String title, String body) {
        return "<html><head><meta charset='utf-8'><title>" + title + "</title></head><body>" + body + "</body></html>";
    }

    @Test
    public void pagesThatAskForAPerson() {
        // a site's own check, in words
        assertTrue(Challenge.looksLike(page("安全验证", "<h2>安全验证</h2><p>请点击下方按钮完成验证后继续访问。</p><button>继续</button>")));
        assertTrue(Challenge.looksLike(page("某某书斋", "<p>您的访问过于频繁,请输入验证码后继续阅读</p><form><img src='/code.php'><input name='code'></form>")));
        assertTrue(Challenge.looksLike(page("提示", "<div class='box'>请拖动滑块完成拼图</div><script src='/static/slide.js'></script>")));
        // a protection service in front of the site
        assertTrue(Challenge.looksLike(page("Just a moment...", "<h1>www.fake-shuzhai.com</h1><p>Verifying you are human. This may take a few seconds.</p>")));
        assertTrue(Challenge.looksLike(page("Attention required", "<p>Please complete the security check to access the site.</p>")));
        // only the widget, the words are drawn by its script
        assertTrue(Challenge.looksLike(page("", "<div id='box'></div><script src='https://widgets.fake-guard.net/captcha/v2/load.js'></script>")));
        assertTrue(Challenge.looksLike(page("www.fake-shuzhai.com", "<form id='challenge-form' action='/x?__cf_chl_f_tk=1'></form>")));
    }

    @Test
    public void pagesThatDoNot() {
        assertFalse(Challenge.looksLike(null));
        assertFalse(Challenge.looksLike(""));
        // an error page is an error page
        assertFalse(Challenge.looksLike(page("页面加载失败", "<h1>页面加载失败</h1><p>抱歉,您访问的章节内容暂时无法显示,请稍后刷新页面重试。</p>")));
        assertFalse(Challenge.looksLike(page("404 Not Found", "<h1>404 Not Found</h1><hr><address>nginx</address>")));
        // a chapter that talks about a verification code, on a page with a login box that has one
        StringBuilder sb = new StringBuilder("<h1>第12章 验证码</h1><div id='content'><p>他盯着屏幕上的验证码看了半天。</p>");
        for (int i = 0; i < 30; i++) sb.append("<p>").append(PARA).append("</p>");
        sb.append("</div><form class='login'><input name='captcha'><img src='/captcha.php'></form>");
        assertFalse(Challenge.looksLike(page("第12章 验证码_长安行", sb.toString())));
    }
}
