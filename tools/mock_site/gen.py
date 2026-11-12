# Generates a deliberately messy fake novel site for end-to-end testing.
import io, os, sys, random

root = sys.argv[1]
random.seed(7)

SENT = ["夜色如墨,山风从谷口灌进来,吹得篝火忽明忽暗。", "少年把最后一根枯枝丢进火堆,抬头望向远处连绵的群山。",
        "“明日过了青石渡,便是剑阁的地界了。”老者捋着胡须说道。", "他没有回答,只是把膝上的旧剑又擦了一遍。",
        "远处传来几声狼嚎,林间的雾气渐渐浓了起来。", "这一路走来,同行的人越来越少,脚下的路却越来越宽。",
        "他忽然想起离家那天,母亲站在村口的老槐树下,一句话也没有说。", "剑身映着火光,像一泓流动的秋水。",
        "“你怕吗?”“怕。可是怕也要走。”", "天边泛起鱼肚白的时候,他们终于看见了那座传说中的城。"]
EN = ["The wind came down from the pass and worried at the campfire until the flames lay almost flat.",
      "He fed it the last dry branch and looked toward the mountains, counting the days of walking that were left.",
      "\"We reach the ferry tomorrow,\" the old man said, not looking up from his pipe.",
      "Nobody answered. Somewhere below them the river kept up its long argument with the stones.",
      "By morning the fog had come in so thick that the harbor lamps looked like drowned stars."]

def write(path, html, enc="utf-8"):
    p = os.path.join(root, path.lstrip("/"))
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with io.open(p, "w", encoding=enc, newline="\n") as f:
        f.write(html)

def paras(seed, n, lang="cn"):
    r = random.Random(seed)
    src = SENT if lang == "cn" else EN
    return ["".join(r.choice(src) for _ in range(r.randint(2, 4))) if lang == "cn"
            else " ".join(r.choice(src) for _ in range(r.randint(2, 4))) for _ in range(n)]

CN = "零一二三四五六七八九"
def cn(n):
    if n < 10: return CN[n]
    if n < 20: return "十" + (CN[n % 10] if n % 10 else "")
    if n < 100: return CN[n // 10] + "十" + (CN[n % 10] if n % 10 else "")
    return CN[n // 100] + "百" + ("" if n % 100 == 0 else ("零" + CN[n % 100] if n % 100 < 10 else cn(n % 100) if n % 100 >= 20 else "一" + cn(n % 100)))

ADS = """
<script src="http://ads.doubleclick.net/pagead/show.js"></script>
<script src="http://cpro.baidu.com/cpro/ui/c.js"></script>
<div id="float_ad" style="position:fixed;bottom:0;left:0;width:100%;height:90px;z-index:99999;background:#ff0;">
  <a href="http://www.casino-ads-example.com/go"><img src="http://www.casino-ads-example.com/banner.gif" width="100%" height="90" alt="AD"></a></div>
<div id="hijack" style="position:fixed;top:0;left:0;width:100%;height:100%;z-index:99998;opacity:0.01;" onclick="window.open('http://www.casino-ads-example.com/pop')"></div>
<div class="app_dl" style="position:fixed;right:8px;top:40%;z-index:5000;width:70px;height:70px;background:#f33;border-radius:35px;">
  <a href="http://app-download-example.net/a.apk"><img src="http://app-download-example.net/i.png" width="70" height="70"></a></div>
<script>setTimeout(function(){ if(!sessionStorage.r){ sessionStorage.r=1; location.href='http://www.casino-ads-example.com/redirect'; } }, 2500);</script>
"""

# ---------------------------------------------------------------- book 88: biquge template, GBK, 60 chapters, two paginated
N = int(os.environ.get("N88", "60"))
titles = ["第%s章 %s" % (cn(i), random.choice(["风起", "夜雨", "入城", "试剑", "故人", "山门", "渡口", "雪夜", "归途", "惊雷"]) + str(i)) for i in range(1, N + 1)]
HEAD = '<meta http-equiv="Content-Type" content="text/html; charset=gbk" />'
def b88_detail():
    s = ['<html><head>', HEAD, '<title>万古剑神最新章节_万古剑神无弹窗全文阅读_笔趣阁</title>',
         '<meta property="og:type" content="novel"/><meta property="og:novel:book_name" content="万古剑神"/>',
         '<meta property="og:novel:author" content="青山客"/><meta property="og:image" content="/cover/88.png"/>',
         '<meta property="og:description" content="少年自山中来,一剑开天门。十年磨一剑,霜刃未曾试。"/></head><body>',
         '<div class="header"><a href="/">首页</a> <a href="/xuanhuan/">玄幻小说</a> <a href="/top/">排行榜</a></div>', ADS,
         '<div id="maininfo"><div id="fmimg"><img src="/cover/88.png" alt="万古剑神"></div><div id="info"><h1>万古剑神</h1>',
         '<p>作&nbsp;&nbsp;&nbsp;&nbsp;者:青山客</p><p>最后更新:2026-11-01</p></div>',
         '<div id="intro"><p>少年自山中来,一剑开天门。十年磨一剑,霜刃未曾试。</p></div></div><div id="list"><dl><dt>《万古剑神》最新章节</dt>']
    for i in range(N, N - 9, -1): s.append('<dd><a href="/book/88/%d.html">%s</a></dd>' % (1000 + i, titles[i - 1]))
    s.append('<dt>《万古剑神》正文</dt>')
    for i in range(1, N + 1): s.append('<dd><a href="/book/88/%d.html">%s</a></dd>' % (1000 + i, titles[i - 1]))
    s.append('</dl></div><div class="footer">本站所有小说为转载作品</div></body></html>')
    return "\n".join(s)

def b88_chapter(i, part):
    paged = i in (5, 6)
    ps = paras(i, 16)
    if paged: ps = ps[:8] if part == 1 else ps[8:]
    cur = "/book/88/%d%s.html" % (1000 + i, "_2" if part == 2 else "")
    prev = "/book/88/%d.html" % (1000 + i - 1) if i > 1 else "/book/88/"
    nxt = "/book/88/%d.html" % (1000 + i + 1) if i < N else "/book/88/"
    title = titles[i - 1] + ("(%d/2)" % part if paged else "")
    if paged and part == 1:
        nav = '<a href="%s">上一章</a> ← <a href="/book/88/">章节目录</a> → <a href="/book/88/%d_2.html">下一页</a>' % (prev, 1000 + i)
    elif paged:
        nav = '<a href="/book/88/%d.html">上一页</a> ← <a href="/book/88/">章节目录</a> → <a href="%s">下一章</a>' % (1000 + i, nxt)
    else:
        nav = '<a href="%s">上一章</a> ← <a href="/book/88/">章节目录</a> → <a href="%s">下一章</a>' % (prev, nxt)
    body = "<br/><br/>".join("&nbsp;&nbsp;&nbsp;&nbsp;" + p for p in ps)
    return "\n".join(['<html><head>', HEAD, '<title>%s_万古剑神_笔趣阁</title></head><body>' % title,
        '<div class="header"><a href="/">首页</a> <a href="/xuanhuan/">玄幻小说</a> <a href="/top/">排行榜</a></div>', ADS,
        '<div class="box_con"><div class="con_top"><a href="/">笔趣阁</a> &gt; <a href="/xuanhuan/">玄幻小说</a> &gt; <a href="/book/88/">万古剑神</a> &gt; %s</div>' % title,
        '<div class="bookname"><h1>%s</h1><div class="bottem1">%s <a href="javascript:;">加入书签</a></div></div>' % (title, nav),
        '<div id="content">&nbsp;&nbsp;&nbsp;&nbsp;天才一秒记住本站地址:www.biquge-fake.com 最快更新!<br/><br/>', body,
        '<div style="display:none">这是反爬隐藏文字不应该出现</div>',
        '<br/><br/>(本章未完,请点击下一页继续阅读)<br/>请记住本书首发域名:biquge-fake.com。手机版阅读网址:m.biquge-fake.com</div>',
        '<div class="bottem2">%s</div>' % nav,
        '<div id="hotlist"><h3>热门推荐</h3><ul><li><a href="/book/1/">斗破星河</a></li><li><a href="/book/2/">大道朝天</a></li></ul></div>',
        '</div><div class="footer">小说万古剑神 %s 由网友上传</div></body></html>' % title])

write("/book/88/index.html", b88_detail(), "gbk")
for i in range(1, N + 1):
    write("/book/88/%d.html" % (1000 + i), b88_chapter(i, 1), "gbk")
    if i in (5, 6): write("/book/88/%d_2.html" % (1000 + i), b88_chapter(i, 2), "gbk")

# ---------------------------------------------------------------- book 7: unknown mobile template, paginated list (3 x 20), UTF-8
M = 60
def b7_list(page):
    s = ['<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>长安行章节目录_第%d页_某某书屋</title></head><body>' % page,
         '<div class="top"><a href="/">书屋首页</a> <a href="/shujia">我的书架</a></div><h2 class="name">长安行</h2><p class="au">作者:柳三</p>',
         '<p class="jj">一个书生,一头瘦驴,一路向西。长安还有三千里。</p><div class="zjlist">']
    for i in range((page - 1) * 20 + 1, page * 20 + 1): s.append('<p><a href="/m/7/%d.html">第%d章 行路%d</a></p>' % (i, i, i))
    s.append('</div><div class="fy">')
    if page > 1: s.append('<a href="/m/7/index_%d.html">上一页</a>' % (page - 1))
    s.append('<select onchange="location.href=this.value">' + "".join('<option value="/m/7/index_%d.html"%s>第%d页</option>' % (p, " selected" if p == page else "", p) for p in (1, 2, 3)) + '</select>')
    if page < 3: s.append('<a href="/m/7/index_%d.html">下一页</a>' % (page + 1))
    s.append('</div></body></html>')
    return "\n".join(s)
for p in (1, 2, 3): write("/m/7/index_%d.html" % p, b7_list(p))
write("/m/7/index.html", b7_list(1))
for i in range(1, M + 1):
    ps = "".join("<p>%s</p>" % p for p in paras(1000 + i, 12))
    write("/m/7/%d.html" % i, "\n".join(['<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>第%d章 行路%d_长安行_某某书屋</title></head><body>' % (i, i),
        '<div class="top"><a href="/">书屋首页</a> &gt; <a href="/m/7/">长安行</a></div><div class="tt">第%d章 行路%d</div>' % (i, i),
        '<div class="zw">%s<p>喜欢长安行请大家收藏:(www.shuwu-fake.com)长安行某某书屋更新速度最快。</p></div>' % ps,
        '<div class="fy"><a href="%s">上一章</a><a href="/m/7/">目录</a><a href="%s">下一章</a></div></body></html>' % (
            "/m/7/%d.html" % (i - 1) if i > 1 else "/m/7/", "/m/7/%d.html" % (i + 1) if i < M else "/m/7/")]))

# ---------------------------------------------------------------- English book: unnumbered titles, newest first
names = ["Low Tide", "The Lamplighter", "Salt", "What the Gulls Know", "Ferry", "A Door in the Sea Wall",
         "Brine", "Night Market", "Undertow", "The Harbormaster", "Glass", "Homecoming"]
ids = [500 + i * 3 for i in range(len(names))]
s = ['<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>The Glass Harbor - Web Novel - Chapters</title></head><body>',
     '<h1>The Glass Harbor</h1><div class="meta">Author: M. Verne</div><div class="synopsis">A harbor town where the tide brings in more than driftwood, and a lamplighter who keeps count of what it takes back.</div><ul class="chapter-list">']
for i in range(len(names) - 1, -1, -1): s.append('<li><a href="/novel/glass-harbor/%d">%s</a> <span>3 days ago</span></li>' % (ids[i], names[i]))
s.append('</ul></body></html>')
write("/novel/glass-harbor/index.html", "\n".join(s))
for i, nm in enumerate(names):
    ps = "".join("<p>%s</p>\n" % p for p in paras(2000 + i, 14, "en"))
    nav = ""
    if i > 0: nav += '<a class="btn" href="/novel/glass-harbor/%d">Previous Chapter</a>' % ids[i - 1]
    nav += '<a class="btn" href="/novel/glass-harbor/">Index</a>'
    if i + 1 < len(names): nav += '<a class="btn" href="/novel/glass-harbor/%d">Next Chapter</a>' % ids[i + 1]
    write("/novel/glass-harbor/%d/index.html" % ids[i], "\n".join(['<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>%s - The Glass Harbor | NovelSite</title></head><body>' % nm,
        '<header><a href="/">Home</a> <a href="/novel/glass-harbor/">The Glass Harbor</a></header><h1>%s</h1><div class="nav-buttons">%s</div>' % (nm, nav),
        '<div class="chapter-inner chapter-content">%s<p>Read the latest chapters at www.pirate-mirror.net</p></div>' % ps,
        '<div class="nav-buttons">%s</div><div class="comments"><div class="comment">Thanks for the chapter!</div></div></body></html>' % nav]))

# ---------------------------------------------------------------- JS-rendered book: nothing in the HTML until scripts run
K = 15
write("/js/3/index.html", """<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>云海录_章节列表</title></head><body>
<h1 id="bn"></h1><p id="au"></p><div id="app">加载中…</div>
<script>setTimeout(function(){document.getElementById('bn').textContent='云海录';document.getElementById('au').textContent='作者:白鹤';
var h='<ul class="mulu">';for(var i=1;i<=%d;i++){h+='<li><a href="/js/3/'+i+'.html">第'+i+'章 云海'+i+'</a></li>';}document.getElementById('app').innerHTML=h+'</ul>';},300);</script>
</body></html>""" % K)
for i in range(1, K + 1):
    import json
    write("/js/3/%d.html" % i, """<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>第%d章 云海%d_云海录</title></head><body>
<h1>第%d章 云海%d</h1><div id="txt">正在加载…</div><div id="nav"></div>
<script>var ps=%s;setTimeout(function(){document.getElementById('txt').innerHTML=ps.map(function(p){return '<p>'+p+'</p>'}).join('');
document.getElementById('nav').innerHTML='%s<a href="/js/3/">目录</a>%s';},300);</script></body></html>""" % (
        i, i, i, i, json.dumps(paras(3000 + i, 10), ensure_ascii=False),
        '<a href="/js/3/%d.html">上一章</a>' % (i - 1) if i > 1 else "", '<a href="/js/3/%d.html">下一章</a>' % (i + 1) if i < K else ""))

# ---------------------------------------------------------------- no contents page at all: only next/prev links
L = 8
for i in range(1, L + 1):
    ps = "<br><br>".join("  " + p for p in paras(4000 + i, 10))
    nav = ('<a href="/solo/c%d.html">上一章</a> ' % (i - 1) if i > 1 else "") + ('<a href="/solo/c%d.html">下一章</a>' % (i + 1) if i < L else "")
    write("/solo/c%d.html" % i, '<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>第%d章 孤舟%d - 寒江独钓 - 无名小站</title></head><body><h1>第%d章 孤舟%d</h1><div class="read">%s</div><div>%s</div></body></html>' % (i, i, i, i, ps, nav))

# ---------------------------------------------------------------- home page: other books' latest chapters (must NOT be detected)
s = ['<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>笔趣阁_书友最值得收藏的网络小说阅读网</title></head><body>',
     '<div class="nav"><a href="/">首页</a> <a href="/xuanhuan/">玄幻</a> <a href="/dushi/">都市</a></div><h3>测试入口</h3><ul>',
     '<li><a href="/book/88/">万古剑神(GBK 经典模板)</a></li><li><a href="/m/7/">长安行(目录分页)</a></li>',
     '<li><a href="/novel/glass-harbor/">The Glass Harbor (English)</a></li><li><a href="/js/3/">云海录(JS 渲染)</a></li>',
     '<li><a href="/solo/c1.html">寒江独钓(无目录)</a></li></ul><h3>最近更新</h3><div id="newscontent"><ul>']
nums = [1523, 88, 407, 2210, 13, 964, 351, 78, 1190, 642, 305, 57, 880, 1432, 9, 760]
for i, n in enumerate(nums):
    s.append('<li><span class="s2"><a href="/book/%d/">某某小说%d</a></span><span class="s3"><a href="/book/%d/%d.html">第%d章 某某情节</a></span><span class="s4">作者%d</span></li>' % (i * 37 + 5, i, i * 37 + 5, 90000 - i * 811, n, i))
s.append('</ul></div></body></html>')
write("/index.html", "\n".join(s))

# cover image (tiny PNG)
import struct, zlib
def png(w, h, rgb):
    raw = b"".join(b"\x00" + bytes(rgb) * w for _ in range(h))
    def chunk(t, d): return struct.pack(">I", len(d)) + t + d + struct.pack(">I", zlib.crc32(t + d) & 0xffffffff)
    return b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")
os.makedirs(os.path.join(root, "cover"), exist_ok=True)
open(os.path.join(root, "cover", "88.png"), "wb").write(png(120, 160, (122, 48, 52)))
print("generated into", root)
