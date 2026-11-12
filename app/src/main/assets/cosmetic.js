(function (strict) {
  if (window.__nsInstalled) { if (window.__nsSweep) window.__nsSweep(); return; }
  window.__nsInstalled = true;
  var css = 'ins.adsbygoogle,.adsbygoogle,[id^="google_ads"],[id^="div-gpt-ad"],[id^="aswift_"],' +
    'iframe[src*="googlesyndication"],iframe[src*="doubleclick"],iframe[src*="pos.baidu.com"],' +
    '.google-auto-placed,.ad-banner,.adsbox,.ad-box,.ad-container,.ad-wrapper,.banner-ad,.advertisement,' +
    '[id^="BAIDU_SSP"],[id^="BAIDU_DUP"],[id^="cproIframe"],[class*="baidu_ad"],[id^="tanx-"],' +
    '[id^="ad_"],[id^="ads_"],[id^="ad-"],[class^="ad_"],[class^="ads_"],[id*="_ad_"]' +
    '{display:none!important;height:0!important;min-height:0!important;visibility:hidden!important}';
  try {
    var st = document.createElement('style');
    st.textContent = css;
    (document.head || document.documentElement).appendChild(st);
  } catch (e) {}

  var host = location.hostname;
  function reg(h) { var p = h.split('.'); return p.slice(-2).join('.'); }
  function ext(u) {
    try { var h = new URL(u, location.href).hostname; return !!h && reg(h) !== reg(host); } catch (e) { return false; }
  }
  function hide(el) { el.style.setProperty('display', 'none', 'important'); el.setAttribute('data-ns-hidden', '1'); }

  function sweep() {
    if (!document.body) return 0;
    var vw = window.innerWidth || 1, vh = window.innerHeight || 1, n = 0;
    var all = document.body.getElementsByTagName('*');
    for (var i = 0; i < all.length && i < 8000; i++) {
      var el = all[i];
      if (el.hasAttribute('data-ns-hidden')) continue;
      var tag = el.tagName;
      if (tag === 'IFRAME') {
        if (strict && el.src && ext(el.src)) { hide(el); n++; }
        continue;
      }
      if (tag !== 'DIV' && tag !== 'A' && tag !== 'SECTION' && tag !== 'ASIDE' && tag !== 'P' && tag !== 'IMG' &&
          tag !== 'SPAN' && tag !== 'UL' && tag !== 'FOOTER' && tag !== 'INS') continue;
      var cs = getComputedStyle(el), pos = cs.position, z = parseInt(cs.zIndex, 10) || 0;
      if (pos !== 'fixed' && pos !== 'sticky' && !(pos === 'absolute' && z >= 1000)) continue;
      var r = el.getBoundingClientRect();
      if (r.width < 8 || r.height < 8) continue;
      var cover = (r.width * r.height) / (vw * vh);
      var txt = (el.innerText || '').replace(/\s+/g, '');
      // 1) transparent / empty layer over the whole page: click hijacker
      if (cover > 0.6 && txt.length < 4 && z >= 10 && !el.querySelector('video,input,textarea')) { hide(el); n++; continue; }
      var extMedia = false, extLink = (tag === 'A' && ext(el.href));
      var kids = el.querySelectorAll('a[href],iframe[src],img[src]');
      for (var j = 0; j < kids.length && j < 24; j++) {
        var k = kids[j], u = k.href || k.src;
        if (!u || !ext(u)) continue;
        if (k.tagName === 'A') { extLink = true; if (k.querySelector('img')) extMedia = true; }
        else extMedia = true;
      }
      // 2) floating box that shows third-party media, or (strict) links off-site
      if (cover < 0.7 && (extMedia || (strict && extLink))) { hide(el); n++; continue; }
      // 3) strict: floating picture-only box (banner / "download app" bubble)
      if (strict && z >= 100 && txt.length < 12 && cover < 0.5 && el.querySelector('img,iframe,canvas')) { hide(el); n++; continue; }
    }
    return n;
  }
  window.__nsSweep = sweep;
  if (strict) { try { window.open = function () { return null; }; } catch (e) {} }
  var timer = null;
  function later() { if (timer) return; timer = setTimeout(function () { timer = null; sweep(); }, 500); }
  sweep(); setTimeout(sweep, 1200); setTimeout(sweep, 3500);
  try { new MutationObserver(later).observe(document.documentElement, { childList: true, subtree: true }); } catch (e) {}
})(__STRICT__);
