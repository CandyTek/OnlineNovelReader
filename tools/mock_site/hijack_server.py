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
    def log_message(self, *a): pass
http.server.ThreadingHTTPServer(("0.0.0.0", 8767), H).serve_forever()
