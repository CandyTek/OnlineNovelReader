package com.rc.novelreader.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import org.junit.Test;

import java.util.EnumMap;
import java.util.Map;

public class QrDecoderTest {
    /** A code drawn by ZXing itself, placed on a larger light picture. */
    private static int[] picture(String text, int w, int h, boolean inverted) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        int side = 240;
        BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, side, side, hints);
        int[] px = new int[w * h];
        int dark = inverted ? 0xFFF4F4F4 : 0xFF101010, light = inverted ? 0xFF101010 : 0xFFF4F4F4;
        java.util.Arrays.fill(px, light);
        int left = (w - side) / 2, top = (h - side) / 2;
        for (int y = 0; y < side; y++) {
            for (int x = 0; x < side; x++) if (m.get(x, y)) px[(top + y) * w + left + x] = dark;
        }
        return px;
    }

    @Test
    public void readsAPicture() throws Exception {
        String link = "https://www.fake-shuzhai.com/book/88/1001.html";
        assertEquals(link, QrDecoder.decodeRgb(picture(link, 400, 320, false), 400, 320));
        String words = "长安行 第12章";
        assertEquals(words, QrDecoder.decodeRgb(picture(words, 320, 320, false), 320, 320));
        // a light code on a dark ground
        assertEquals(link, QrDecoder.decodeRgb(picture(link, 320, 320, true), 320, 320));
    }

    @Test
    public void readsACameraFrame() throws Exception {
        String link = "https://www.fake-shuzhai.com/";
        int w = 640, h = 360;
        int[] px = picture(link, w, h, false);
        // NV21: the luminance plane, then the colour plane this reader never looks at
        byte[] yuv = new byte[w * h * 3 / 2];
        for (int i = 0; i < w * h; i++) yuv[i] = (byte) (px[i] & 0xFF);
        assertEquals(link, QrDecoder.decodeYuv(yuv, w, h, false));
        assertNull(QrDecoder.decodeYuv(yuv, w, h, true));
    }

    @Test
    public void nothingToRead() {
        int[] blank = new int[200 * 200];
        java.util.Arrays.fill(blank, 0xFFFFFFFF);
        assertNull(QrDecoder.decodeRgb(blank, 200, 200));
        assertNull(QrDecoder.decodeRgb(null, 10, 10));
        assertNull(QrDecoder.decodeYuv(new byte[10], 200, 200, false));
    }
}
