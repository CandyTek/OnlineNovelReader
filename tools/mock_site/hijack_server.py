# Mock novel site that sells its navigation: the first request for some chapters is answered with a redirect
# to an advertiser, and the first rendering of others carries the advertiser in the "next" link.
import http.server, sys
AD = "http://promo.ads-hijack-test.invalid/land?id=7"
PARA = "夜色如墨，山风从谷口灌进来，吹得篝火忽明忽暗。少年把最后一根枯枝丢进火堆，抬头望向远处连绵的群山，心里盘算着明日的路程。"
hits = {}
class H(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        p = self.path.split("?")[0]
        n = hits[p] = hits.get(p, 0) + 1
        sys.stderr.write("HIT %s #%d\n" % (p, n)); sys.stderr.flush()
        if p.startswith("/e/c") and p.endswith(".html"):
            return self.flaky(int(p[4:-5]), n)
        if p.startswith("/v/c") and p.endswith(".html"):
            return self.guarded(int(p[4:-5]))
        if not (p.startswith("/h/c") and p.endswith(".html")):
            self.send_response(404); self.end_headers(); return
        i = int(p[4:-5])
        if i > 8:
            self.send_response(404); self.end_headers(); return
        if i in (2, 5) and n == 1:           # redirect hijack
            self.send_response(302); self.send_header("Location", AD); self.end_headers(); return
        nxt = "/h/c%d.html" % (i + 1)
        if i in (3, 6) and n == 1: nxt = AD  # link hijack
        nav = "<div class='nav'>" + ("<a href='/h/c%d.html'>上一章</a>" % (i - 1) if i > 1 else "") \
              + ("<a id='next_url' href='%s'>下一章</a>" % nxt if i < 8 else "") + "</div>"
        body = ("<html><head><meta charset='utf-8'><title>第%d章 山行其%d_孤城记_某某书屋</title></head><body>"
                "<h1>第%d章 山行其%d</h1><div id='content'>%s</div>%s</body></html>") % (
                i, i, i, i, "".join("<p>%s（%d-%d）</p>" % (PARA, i, k) for k in range(10)), nav)
        b = body.encode("utf-8")
        self.send_response(200); self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    # /e/c1.html .. c8: a book without a contents page on a site that fails now and then. Chapter 3 answers its
    # first two requests with 404 and the site's error page (one load = plain request + rendered one, so the
    # first load fails and a retry works); chapter 5 answers its first two with the same page and status 200.
    def flaky(self, i, n):
        nav = "<div class='nav'>" + ("<a href='/e/c%d.html'>上一章</a>" % (i - 1) if i > 1 else "") \
              + ("<a id='next_url' href='/e/c%d.html'>下一章</a>" % (i + 1) if i < 8 else "") + "</div>"
        bad = i > 8 or (i in (3, 5) and n <= 2)
        if bad:
            body = ("<html><head><meta charset='utf-8'><title>页面加载失败_某某书屋</title></head><body><h1>页面加载失败</h1>"
                    "<div id='content'><p>抱歉，您访问的章节内容暂时无法显示，请稍后刷新页面重试。</p></div>%s</body></html>") % nav
        else:
            body = ("<html><head><meta charset='utf-8'><title>第%d章 渡口其%d_寒江记_某某书屋</title></head><body>"
                    "<h1>第%d章 渡口其%d</h1><div id='content'>%s</div>%s</body></html>") % (
                    i, i, i, i, "".join("<p>%s（%d-%d）</p>" % (PARA, i, k) for k in range(10)), nav)
        b = body.encode("utf-8")
        self.send_response(404 if bad and i != 5 else 200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    # /v/c1.html .. c8: a book without a contents page behind a human check. From chapter 3 on, a visitor without
    # the cookie "human" gets the check instead (status 403): a page with one button that sets the cookie and
    # loads the address again. /v/reset takes the pass away again (the cookie must then be "human=2").
    need = "1"
    def guarded(self, i):
        ck = self.headers.get("Cookie") or ""
        if i >= 3 and ("human=" + H.need) not in ck:
            sys.stderr.write("    -> check\n"); sys.stderr.flush()
            body = ("<html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width'>"
                    "<title>安全验证</title></head><body style='text-align:center;font-size:20px'>"
                    "<h2>安全验证</h2><p>请点击下方按钮完成验证后继续访问。</p>"
                    "<button id='pass' style='font-size:28px;padding:40px 80px;margin-top:40px' "
                    "onclick=\"document.cookie='human=%s;path=/';location.reload()\">我不是机器人</button>"
                    "</body></html>") % H.need
            b = body.encode("utf-8")
            self.send_response(403); self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b); return
        nav = "<div class='nav'>" + ("<a href='/v/c%d.html'>上一章</a>" % (i - 1) if i > 1 else "") \
              + ("<a id='next_url' href='/v/c%d.html'>下一章</a>" % (i + 1) if i < 8 else "") + "</div>"
        body = ("<html><head><meta charset='utf-8'><title>第%d章 关隘其%d_雁门记_某某书屋</title></head><body>"
                "<h1>第%d章 关隘其%d</h1><div id='content'>%s</div>%s</body></html>") % (
                i, i, i, i, "".join("<p>%s（%d-%d）</p>" % (PARA, i, k) for k in range(10)), nav)
        b = body.encode("utf-8")
        self.send_response(200 if i <= 8 else 404); self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(b))); self.end_headers(); self.wfile.write(b)
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(("0.0.0.0", 8767), H).serve_forever()
