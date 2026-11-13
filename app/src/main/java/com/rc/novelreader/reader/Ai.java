package com.rc.novelreader.reader;

import android.content.Context;
import android.content.SharedPreferences;

import com.rc.novelreader.App;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The optional language-model assistant. Nothing here runs until the user has entered a service and a key, and
 * every call is the direct result of a tap. Two wire formats: the Claude Messages API, and the "chat
 * completions" format most other services speak. Plain HTTP on purpose: the app carries no SDK.
 */
final class Ai {
    private Ai() {}

    static final int CLAUDE = 0, OPENAI = 1;
    static final String CLAUDE_URL = "https://api.anthropic.com/v1/messages";
    static final String CLAUDE_MODEL = "claude-opus-5-5";

    static int format() {
        return App.prefs().getInt("ai_format", CLAUDE);
    }

    static String url() {
        String u = App.prefs().getString("ai_url", "").trim();
        return u.isEmpty() && format() == CLAUDE ? CLAUDE_URL : u;
    }

    static String key() {
        return App.prefs().getString("ai_key", "").trim();
    }

    static String model() {
        String m = App.prefs().getString("ai_model", "").trim();
        return m.isEmpty() && format() == CLAUDE ? CLAUDE_MODEL : m;
    }

    static boolean configured() {
        return !key().isEmpty() && !url().isEmpty() && !model().isEmpty();
    }

    /** One question, one answer. Blocks; long answers can take minutes. */
    static String ask(String system, String user) throws IOException {
        boolean claude = format() == CLAUDE;
        String url = url();
        JSONObject body = new JSONObject();
        try {
            body.put("model", model());
            if (claude) {
                body.put("max_tokens", 16000);
                body.put("system", system);
                body.put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", user)));
            } else {
                body.put("messages", new JSONArray()
                        .put(new JSONObject().put("role", "system").put("content", system))
                        .put(new JSONObject().put("role", "user").put("content", user)));
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(300000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            if (claude) {
                c.setRequestProperty("x-api-key", key());
                c.setRequestProperty("anthropic-version", "2023-06-01");
            } else {
                c.setRequestProperty("Authorization", "Bearer " + key());
            }
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            String text = read(code >= 400 ? c.getErrorStream() : c.getInputStream());
            JSONObject o;
            try {
                o = new JSONObject(text);
            } catch (JSONException e) {
                throw new IOException("HTTP " + code + (text.isEmpty() ? "" : ": " + text.substring(0, Math.min(200, text.length()))));
            }
            if (code >= 400) {
                JSONObject err = o.optJSONObject("error");
                throw new IOException("HTTP " + code + ": " + (err != null ? err.optString("message", text) : o.optString("message", text)));
            }
            return claude ? claudeText(o) : openAiText(o);
        } finally {
            c.disconnect();
        }
    }

    private static String claudeText(JSONObject o) throws IOException {
        String stop = o.optString("stop_reason");
        // a declined request is a normal 200 answer
        if (stop.equals("refusal")) {
            JSONObject d = o.optJSONObject("stop_details");
            String why = d == null ? "" : d.optString("explanation", "");
            throw new IOException("The model declined this request" + (why.isEmpty() ? "" : ": " + why));
        }
        StringBuilder sb = new StringBuilder();
        JSONArray content = o.optJSONArray("content");
        // thinking blocks come first; only text blocks are the answer
        for (int i = 0; content != null && i < content.length(); i++) {
            JSONObject b = content.optJSONObject(i);
            if (b != null && "text".equals(b.optString("type"))) sb.append(b.optString("text"));
        }
        if (sb.length() == 0) throw new IOException("Empty answer (" + stop + ")");
        if (stop.equals("max_tokens")) sb.append("\n…");
        return sb.toString().trim();
    }

    private static String openAiText(JSONObject o) throws IOException {
        JSONArray choices = o.optJSONArray("choices");
        JSONObject first = choices == null ? null : choices.optJSONObject(0);
        JSONObject msg = first == null ? null : first.optJSONObject("message");
        String text = msg == null ? "" : msg.optString("content", "");
        if (text.trim().isEmpty()) throw new IOException("Empty answer");
        return text.trim();
    }

    private static String read(InputStream in) throws IOException {
        if (in == null) return "";
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }

    // ------------------------------------------------------------------ chapter summaries (kept, so each is paid for once)

    private static SharedPreferences store() {
        return App.I.getSharedPreferences("reader_ai", Context.MODE_PRIVATE);
    }

    static String summary(long chapterId) {
        return store().getString("s" + chapterId, "");
    }

    static void putSummary(long chapterId, String text) {
        store().edit().putString("s" + chapterId, text).apply();
    }

    static final String SYSTEM = "You help a reader of a novel. Answer in the language of the book text you are given. "
            + "Be concrete and brief; no preamble. Never reveal or guess at events after the text you are given.";

    static String summarize(String title, String text) throws IOException {
        return ask(SYSTEM, "Summarise this chapter in three to five sentences: who does what, and what changes.\n\n"
                + "Chapter: " + title + "\n\n" + text);
    }
}
