package com.rc.novelreader.browser;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.rc.novelreader.App;
import com.rc.novelreader.R;
import com.rc.novelreader.util.QrDecoder;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reads a QR code with the camera, or out of a picture from the gallery, and hands the text back
 * ({@link #EXTRA_TEXT}); what is done with it is the caller's business. Framework camera API and a
 * TextureView only: the frames go to {@link QrDecoder} one at a time, the camera waiting for each answer.
 */
@SuppressWarnings("deprecation") // android.hardware.Camera: no support library here, and it does all this needs
public class ScanActivity extends Activity implements TextureView.SurfaceTextureListener, Camera.PreviewCallback {
    public static final String EXTRA_TEXT = "text";
    private static final int REQ_CAMERA = 1, REQ_PICK = 2;

    private TextureView preview;
    private TextView hint, torchButton;
    private Camera camera;
    private int frameW, frameH, frames;
    private boolean torch, asked;
    private volatile boolean done;
    private final ExecutorService decoder = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF000000);
        preview = new TextureView(this);
        preview.setSurfaceTextureListener(this);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1));
        root.addView(new Frame(), new FrameLayout.LayoutParams(-1, -1));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setFitsSystemWindows(true);
        hint = new TextView(this);
        hint.setText(R.string.scan_hint);
        hint.setTextColor(0xFFFFFFFF);
        hint.setTextSize(14);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(App.dp(24), 0, App.dp(24), App.dp(18));
        hint.setShadowLayer(4, 0, 1, 0xCC000000);
        col.addView(hint, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        row.setPadding(App.dp(12), 0, App.dp(12), App.dp(22));
        torchButton = button(R.string.scan_torch, v -> toggleTorch());
        row.addView(torchButton);
        row.addView(button(R.string.scan_album, v -> pick()));
        row.addView(button(R.string.cancel, v -> finish()));
        col.addView(row, new LinearLayout.LayoutParams(-1, -2));
        root.addView(col, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(root);
    }

    private TextView button(int label, View.OnClickListener l) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextSize(14);
        t.setTextColor(0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setBackgroundResource(R.drawable.tip_bg);
        t.setPadding(App.dp(18), 0, App.dp(18), 0);
        t.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, App.dp(44));
        lp.setMargins(App.dp(8), 0, App.dp(8), 0);
        t.setLayoutParams(lp);
        return t;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) start();
        else if (!asked) {
            asked = true;
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        } else noCamera();
    }

    @Override
    protected void onPause() {
        release();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        done = true;
        decoder.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        if (requestCode != REQ_CAMERA) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) start();
        else noCamera();
    }

    /** Without a camera (none, refused, busy) a picture can still be read. */
    private void noCamera() {
        hint.setText(R.string.scan_no_camera);
        torchButton.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------------ camera

    private void start() {
        SurfaceTexture surface = preview.getSurfaceTexture();
        if (camera != null || surface == null || done) return;
        try {
            int id = 0;
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) {
                    id = i;
                    break;
                }
            }
            Camera.getCameraInfo(id, info);
            camera = Camera.open(id);
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            int degrees = rotation == Surface.ROTATION_90 ? 90 : rotation == Surface.ROTATION_180 ? 180 : rotation == Surface.ROTATION_270 ? 270 : 0;
            int turn = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
                    ? (360 - (info.orientation + degrees) % 360) % 360 : (info.orientation - degrees + 360) % 360;
            Camera.Parameters p = camera.getParameters();
            int vw = Math.max(1, preview.getWidth()), vh = Math.max(1, preview.getHeight());
            Camera.Size size = pickSize(p.getSupportedPreviewSizes(), turn % 180 == 90 ? (float) vh / vw : (float) vw / vh);
            p.setPreviewSize(size.width, size.height);
            List<String> focus = p.getSupportedFocusModes();
            if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
            }
            camera.setParameters(p);
            camera.setDisplayOrientation(turn);
            camera.setPreviewTexture(surface);
            frameW = size.width;
            frameH = size.height;
            camera.addCallbackBuffer(new byte[frameW * frameH * 3 / 2]);
            camera.setPreviewCallbackWithBuffer(this);
            camera.startPreview();
            List<String> flash = p.getSupportedFlashModes();
            torchButton.setVisibility(flash != null && flash.contains(Camera.Parameters.FLASH_MODE_TORCH) ? View.VISIBLE : View.GONE);
            hint.setText(R.string.scan_hint);
            // the picture fills the view and keeps its shape: what does not fit is cut off at the sides
            float pw = turn % 180 == 90 ? frameH : frameW, ph = turn % 180 == 90 ? frameW : frameH;
            float s = Math.max(vw / pw, vh / ph);
            Matrix m = new Matrix();
            m.setScale(pw * s / vw, ph * s / vh, vw / 2f, vh / 2f);
            preview.setTransform(m);
        } catch (Throwable e) {
            android.util.Log.w("Scan", e);
            release();
            noCamera();
        }
    }

    /** The largest preview up to full HD whose shape is nearest to the view's. */
    private static Camera.Size pickSize(List<Camera.Size> sizes, float shape) {
        Camera.Size best = sizes.get(0);
        float bestScore = Float.MAX_VALUE;
        for (Camera.Size s : sizes) {
            if ((long) s.width * s.height > 1920L * 1088) continue;
            float score = Math.abs((float) s.width / s.height - shape) * 4 + (s.width < 960 ? 2f : 0f) - s.width / 10000f;
            if (score < bestScore) {
                bestScore = score;
                best = s;
            }
        }
        return best;
    }

    private void release() {
        if (camera == null) return;
        try {
            camera.setPreviewCallbackWithBuffer(null);
            camera.stopPreview();
        } catch (Throwable ignored) {
        }
        try {
            camera.release();
        } catch (Throwable ignored) {
        }
        camera = null;
        torch = false;
    }

    private void toggleTorch() {
        if (camera == null) return;
        try {
            Camera.Parameters p = camera.getParameters();
            torch = !torch;
            p.setFlashMode(torch ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF);
            camera.setParameters(p);
        } catch (Throwable e) {
            torch = false;
        }
    }

    @Override
    public void onPreviewFrame(final byte[] data, final Camera from) {
        if (done || data == null) return;
        final int w = frameW, h = frameH;
        // every third frame is also read as a light code on a dark ground
        final boolean inverted = ++frames % 3 == 0;
        try {
            decoder.execute(() -> {
                final String text = done ? null : QrDecoder.decodeYuv(data, w, h, inverted);
                App.MAIN.post(() -> {
                    if (text != null) finishWith(text);
                    // the buffer goes back only now: the camera delivers no frame while one is being read
                    else if (camera == from && !done) from.addCallbackBuffer(data);
                });
            });
        } catch (RuntimeException ignored) {
        }
    }

    private void finishWith(String text) {
        if (done || isFinishing()) return;
        done = true;
        preview.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_TEXT, text));
        finish();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) start();
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        release();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surface) {
    }

    // ------------------------------------------------------------------ pictures

    private void pick() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE), REQ_PICK);
        } catch (Exception e) {
            App.toast(getString(R.string.no_app_found));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        App.IO.execute(() -> {
            String text = null;
            try {
                byte[] bytes;
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    for (int n; in != null && (n = in.read(buf)) > 0 && out.size() < 40_000_000; ) out.write(buf, 0, n);
                    bytes = out.toByteArray();
                }
                text = decode(bytes);
            } catch (Throwable e) {
                android.util.Log.w("Scan", e);
            }
            final String found = text;
            App.MAIN.post(() -> {
                if (isDestroyed()) return;
                if (found == null) App.toast(getString(R.string.scan_none));
                else finishWith(found);
            });
        });
    }

    /** Reads a code out of an encoded picture (JPEG, PNG, WebP...); null when there is none. Not for the main thread. */
    public static String decode(byte[] image) {
        if (image == null || image.length == 0) return null;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(image, 0, image.length, o);
            int sample = 1;
            while (Math.max(o.outWidth, o.outHeight) / sample > 2000) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            Bitmap b = BitmapFactory.decodeByteArray(image, 0, image.length, o);
            if (b == null) return null;
            if (b.hasAlpha()) {
                // a code on a transparent ground would be dark on black
                Bitmap flat = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(flat);
                c.drawColor(0xFFFFFFFF);
                c.drawBitmap(b, 0, 0, null);
                b = flat;
            }
            int w = b.getWidth(), h = b.getHeight();
            int[] px = new int[w * h];
            b.getPixels(px, 0, w, 0, 0, w, h);
            return QrDecoder.decodeRgb(px, w, h);
        } catch (Throwable e) {
            return null;
        }
    }

    /** The aiming square: the picture outside it is dimmed. The whole frame is read all the same. */
    private final class Frame extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Frame() {
            super(ScanActivity.this);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float side = Math.min(w, h) * 0.66f, left = (w - side) / 2, top = Math.max(App.dp(24), (h - side) / 2 - App.dp(30));
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0x80000000);
            c.drawRect(0, 0, w, top, paint);
            c.drawRect(0, top + side, w, h, paint);
            c.drawRect(0, top, left, top + side, paint);
            c.drawRect(left + side, top, w, top + side, paint);
            paint.setColor(0xFF6FBFAE);
            float len = App.dp(22), t = App.dp(3);
            for (int i = 0; i < 4; i++) {
                float x = i % 2 == 0 ? left : left + side, y = i < 2 ? top : top + side;
                float dx = i % 2 == 0 ? 1 : -1, dy = i < 2 ? 1 : -1;
                c.drawRect(Math.min(x, x + dx * len), Math.min(y, y + dy * t), Math.max(x, x + dx * len), Math.max(y, y + dy * t), paint);
                c.drawRect(Math.min(x, x + dx * t), Math.min(y, y + dy * len), Math.max(x, x + dx * t), Math.max(y, y + dy * len), paint);
            }
        }
    }
}
