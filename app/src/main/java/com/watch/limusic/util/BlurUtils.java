package com.watch.limusic.util;

import android.graphics.Bitmap;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.util.LruCache;
import android.widget.ImageView;

import androidx.annotation.RequiresApi;

public class BlurUtils {
    private static final LruCache<String, Bitmap> BLUR_CACHE = new LruCache<>(6);
    private static final float MAX_RADIUS = 50f; // 原上限
    private static final float EFFECTIVE_FACTOR = 0.4f; // 将0-100%线性压缩到原0-40%

    public static void applyBlurTo(ImageView target, Bitmap src, float intensity01) {
        if (target == null || src == null) return;
        float radius = mapIntensityToRadius(intensity01);
        if (Build.VERSION.SDK_INT >= 31) {
            // 先设置位图，再设置RenderEffect，避免部分机型上后设位图覆盖效果
            target.setImageBitmap(src);
            applyRenderEffect(target, radius);
        } else {
            float scale = mapIntensityToScale(intensity01);
            Bitmap small = Bitmap.createScaledBitmap(src,
                    Math.max(1, (int) (src.getWidth() * scale)),
                    Math.max(1, (int) (src.getHeight() * scale)), true);
            Bitmap blurred = boxBlur3Pass(small, (int) Math.max(1, radius));
            target.setImageBitmap(blurred);
        }
    }

    public static void clearEffect(ImageView target) {
        if (target == null) return;
        if (Build.VERSION.SDK_INT >= 31) {
            target.setRenderEffect(null);
        }
    }

    @RequiresApi(31)
    private static void applyRenderEffect(ImageView target, float radius) {
        try {
            target.setRenderEffect(RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP));
        } catch (Throwable ignore) {}
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    // 线性压缩：0..1 -> 0..(MAX_RADIUS*0.4)
    private static float mapIntensityToRadius(float intensity01) {
        float t = clamp01(intensity01);
        return t * (MAX_RADIUS * EFFECTIVE_FACTOR);
    }

    // 线性压缩：0..1 -> 0.98..(0.98 - 0.70*0.4)≈0.70（强度越大，下采样越多，但整体更温和）
    private static float mapIntensityToScale(float intensity01) {
        float t = clamp01(intensity01);
        float maxDownsample = 0.70f; // 原0.70系数
        return 0.98f - maxDownsample * (t * EFFECTIVE_FACTOR);
    }

    public static Bitmap getOrCreateBlur(String key, Bitmap src, float intensity01) {
        try {
            String cacheKey = key + "_" + (int) (intensity01 * 100);
            Bitmap cached = BLUR_CACHE.get(cacheKey);
            if (cached != null && !cached.isRecycled()) return cached;
            float scale = mapIntensityToScale(intensity01);
            Bitmap small = Bitmap.createScaledBitmap(src,
                    Math.max(1, (int) (src.getWidth() * scale)),
                    Math.max(1, (int) (src.getHeight() * scale)), true);
            Bitmap blurred = boxBlur3Pass(small, (int) Math.max(1, mapIntensityToRadius(intensity01)));
            BLUR_CACHE.put(cacheKey, blurred);
            return blurred;
        } catch (Throwable t) {
            return src;
        }
    }

    // 3-pass box blur，半径r；近似高斯，毛玻璃观感更自然
    private static Bitmap boxBlur3Pass(Bitmap src, int radius) {
        if (radius <= 1) return src;
        int w = src.getWidth();
        int h = src.getHeight();
        int[] pix = new int[w * h];
        src.getPixels(pix, 0, w, 0, 0, w, h);
        int[] temp = new int[w * h];
        boxBlur(pix, temp, w, h, radius);
        boxBlur(temp, pix, w, h, radius);
        boxBlur(pix, temp, w, h, radius);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        out.setPixels(temp, 0, w, 0, 0, w, h);
        return out;
    }

    private static void boxBlur(int[] src, int[] dst, int w, int h, int r) {
        int wm = w - 1;
        int hm = h - 1;
        int div = r + r + 1;
        int[] vmin = new int[Math.max(w, h)];

        int rsum, gsum, bsum, x, y, i, p1, p2, yi = 0, yw = 0;

        // 横向
        for (y = 0; y < h; y++) {
            rsum = gsum = bsum = 0;
            for (i = -r; i <= r; i++) {
                int p = src[yi + Math.min(wm, Math.max(i, 0))];
                rsum += (p >> 16) & 0xFF;
                gsum += (p >> 8) & 0xFF;
                bsum += p & 0xFF;
            }
            for (x = 0; x < w; x++) {
                dst[yi + x] = (0xFF << 24) | ((rsum / div) << 16) | ((gsum / div) << 8) | (bsum / div);
                p1 = src[yi + Math.max(x - r, 0)];
                p2 = src[yi + Math.min(x + r + 1, wm)];
                rsum += ((p2 >> 16) & 0xFF) - ((p1 >> 16) & 0xFF);
                gsum += ((p2 >> 8) & 0xFF) - ((p1 >> 8) & 0xFF);
                bsum += (p2 & 0xFF) - (p1 & 0xFF);
            }
            yi += w;
        }

        // 纵向
        for (x = 0; x < w; x++) {
            rsum = gsum = bsum = 0;
            int yp = -r * w;
            for (i = -r; i <= r; i++) {
                int yi2 = Math.max(0, yp) + x;
                int p = dst[yi2];
                rsum += (p >> 16) & 0xFF;
                gsum += (p >> 8) & 0xFF;
                bsum += p & 0xFF;
                yp += w;
            }
            int yi3 = x;
            for (y = 0; y < h; y++) {
                src[yi3] = (0xFF << 24) | ((rsum / div) << 16) | ((gsum / div) << 8) | (bsum / div);
                p1 = dst[Math.max(y - r, 0) * w + x];
                p2 = dst[Math.min(y + r + 1, hm) * w + x];
                rsum += ((p2 >> 16) & 0xFF) - ((p1 >> 16) & 0xFF);
                gsum += ((p2 >> 8) & 0xFF) - ((p1 >> 8) & 0xFF);
                bsum += (p2 & 0xFF) - (p1 & 0xFF);
                yi3 += w;
            }
        }
        // 输出到dst
        System.arraycopy(src, 0, dst, 0, w * h);
    }
} 