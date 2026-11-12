package com.rc.novelreader.net;

import android.webkit.CookieManager;

import com.rc.novelreader.App;
import com.rc.novelreader.util.Urls;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Plain HTTP fetches that look like the in-app browser: same user agent, same cookie jar, charset sniffing. */
public final class Http {
    private Http() {}

    public static final class Resp {
        public int code;
        public String url;
        public String html;
    }

    private static final int MAX_BYTES = 6 * 1024 * 1024;
    private static final Pattern CHARSET = Pattern.compile("charset\\s*=\\s*[\"']?\\s*([\\w\\-]+)", Pattern.CASE_INSENSITIVE);

    public static Resp get(String url, String referer) throws IOException {
        Object[] r = fetch(url, referer, "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
        Resp resp = new Resp();
        resp.code = (Integer) r[0];
        resp.url = (String) r[1];
        resp.html = decode((byte[]) r[2], (String) r[3]);
        return resp;
    }

    public static byte[] getBytes(String url, String referer) throws IOException {
        Object[] r = fetch(url, referer, "image/avif,image/webp,image/*,*/*;q=0.8");
        if ((Integer) r[0] >= 400) throw new IOException("HTTP " + r[0]);
        return (byte[]) r[2];
    }

    /** @return {status, final url, body bytes, content-type}. Redirects are followed by hand so cookies and scheme changes work. */
    private static Object[] fetch(String url, String referer, String accept) throws IOException {
        CookieManager cookies = cookieManager();
        final String asked = url;
        for (int hop = 0; hop < 8; hop++) {
            HttpURLConnection c;
            try {
                c = connect(url, referer, accept, cookies);
                c.getResponseCode();
            } catch (IOException e) {
                // the redirect led to another site and nothing usable is there: that is a hijack, not a network fault
                if (!Urls.sameSite(url, asked)) throw new OffSite(Urls.host(url));
                throw e;
            }
            try {
                int code = c.getResponseCode();
                if (cookies != null) {
                    for (Map.Entry<String, List<String>> h : c.getHeaderFields().entrySet()) {
                        if (h.getKey() != null && h.getKey().equalsIgnoreCase("Set-Cookie")) {
                            for (String v : h.getValue()) cookies.setCookie(url, v);
                        }
                    }
                }
                if (code >= 300 && code < 400 && code != 304) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null) throw new IOException("HTTP " + code + " without Location");
                    referer = url;
                    url = new URL(new URL(url), loc).toString();
                    continue;
                }
                InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
                byte[] body = in == null ? new byte[0] : readAll(in);
                return new Object[]{code, url, body, c.getContentType()};
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    /** A redirect left the site that was asked and ended nowhere usable (see PageFetcher). */
    public static final class OffSite extends IOException {
        public final String host;

        OffSite(String host) {
            super("Redirected to another site (" + host + ")");
            this.host = host;
        }
    }

    private static HttpURLConnection connect(String url, String referer, String accept, CookieManager cookies) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(12000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", App.UA);
        c.setRequestProperty("Accept", accept);
        c.setRequestProperty("Accept-Language", acceptLanguage());
        if (referer != null && !referer.isEmpty()) c.setRequestProperty("Referer", referer);
        if (cookies != null) {
            String ck = cookies.getCookie(url);
            if (ck != null && !ck.isEmpty()) c.setRequestProperty("Cookie", ck);
        }
        return c;
    }

    private static CookieManager cookieManager() {
        try {
            return CookieManager.getInstance();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String acceptLanguage() {
        Locale l = Locale.getDefault();
        String tag = l.getLanguage() + (l.getCountry().isEmpty() ? "" : "-" + l.getCountry());
        return tag + "," + l.getLanguage() + ";q=0.9,zh-CN;q=0.8,zh;q=0.7,en;q=0.6";
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) break;
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    /** Charset from the header, else from a meta tag, else UTF-8 if valid, else GB18030 (covers GBK/GB2312 sites). */
    static String decode(byte[] body, String contentType) {
        Charset cs = null;
        if (contentType != null) cs = charset(contentType);
        if (cs == null) {
            String head = new String(body, 0, Math.min(body.length, 4096), StandardCharsets.ISO_8859_1);
            cs = charset(head);
        }
        if (cs == null) {
            try {
                return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(body)).toString();
            } catch (CharacterCodingException e) {
                cs = Charset.forName("GB18030");
            }
        }
        return new String(body, cs);
    }

    private static Charset charset(String s) {
        Matcher m = CHARSET.matcher(s);
        if (!m.find()) return null;
        String name = m.group(1).toLowerCase(Locale.ROOT);
        if (name.equals("gb2312") || name.equals("gbk") || name.equals("x-gbk")) name = "GB18030";
        try {
            return Charset.forName(name);
        } catch (Exception e) {
            return null;
        }
    }
}
