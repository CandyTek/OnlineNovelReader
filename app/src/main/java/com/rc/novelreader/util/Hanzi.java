package com.rc.novelreader.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Simplified / traditional Chinese conversion, character by character. The table is OpenCC's character
 * dictionary (Apache-2.0), first candidate only: a simplified character that stands for several traditional
 * ones always becomes the commonest, so traditional output has the usual one-to-many slips.
 */
public final class Hanzi {
    public static final int NONE = 0, TO_TRADITIONAL = 1, TO_SIMPLIFIED = 2;

    private final char[] s2t = new char[0x10000], t2s = new char[0x10000];

    /** Reads the asset written by the build script: comment lines, then the two lines of pairs. */
    public Hanzi(InputStream in) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            int n = 0;
            for (String line; (line = r.readLine()) != null; ) {
                if (line.startsWith("#") || line.isEmpty()) continue;
                char[] map = n++ == 0 ? s2t : t2s;
                for (int i = 0; i + 1 < line.length(); i += 2) map[line.charAt(i)] = line.charAt(i + 1);
            }
        }
    }

    public String convert(String text, int direction) {
        if (text == null || direction == NONE) return text;
        char[] map = direction == TO_TRADITIONAL ? s2t : t2s;
        char[] out = null;
        for (int i = 0, n = text.length(); i < n; i++) {
            char c = text.charAt(i), to = map[c];
            if (to == 0) continue;
            if (out == null) out = text.toCharArray();
            out[i] = to;
        }
        return out == null ? text : new String(out);
    }
}
