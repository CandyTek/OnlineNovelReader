package com.rc.novelreader.util;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;

/**
 * Reads a QR code (also Data Matrix, Aztec and PDF417: the square codes, which read the same whichever way the
 * camera is held) out of a camera frame or a picture. Pure Java around ZXing's core, so it runs in JVM tests.
 */
public final class QrDecoder {
    private QrDecoder() {
    }

    private static Map<DecodeHintType, Object> hints(boolean hard) {
        Map<DecodeHintType, Object> m = new EnumMap<>(DecodeHintType.class);
        m.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX,
                BarcodeFormat.AZTEC, BarcodeFormat.PDF_417));
        if (hard) m.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        return m;
    }

    private static String read(LuminanceSource src, boolean hybrid, boolean hard) {
        MultiFormatReader reader = new MultiFormatReader();
        try {
            Result r = reader.decode(new BinaryBitmap(hybrid ? new HybridBinarizer(src) : new GlobalHistogramBinarizer(src)), hints(hard));
            return r == null ? null : r.getText();
        } catch (Exception e) {
            // not found, or found and damaged: both mean "nothing read"
            return null;
        } finally {
            reader.reset();
        }
    }

    /**
     * One camera frame, quickly: the middle square of an NV21 (or any luminance-first) buffer.
     * @param inverted look for a light code on a dark ground instead
     */
    public static String decodeYuv(byte[] data, int w, int h, boolean inverted) {
        if (data == null || w <= 0 || h <= 0 || data.length < w * h) return null;
        int side = Math.min(w, h);
        LuminanceSource src = new PlanarYUVLuminanceSource(data, w, h, (w - side) / 2, (h - side) / 2, side, side, false);
        return read(inverted ? src.invert() : src, true, false);
    }

    /** A still picture, thoroughly: both ways of telling dark from light, then inverted. */
    public static String decodeRgb(int[] argb, int w, int h) {
        if (argb == null || w <= 0 || h <= 0 || argb.length < w * h) return null;
        LuminanceSource src = new RGBLuminanceSource(w, h, argb);
        String s = read(src, true, false);
        if (s == null) s = read(src, false, true);
        if (s == null) s = read(src, true, true);
        if (s == null) s = read(src.invert(), true, true);
        return s;
    }
}
