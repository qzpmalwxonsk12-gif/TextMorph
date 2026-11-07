package com.antigravity.textmorph;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;

import java.util.HashMap;
import java.util.Map;

public class ColorSampler {

    public static class ColorPair {
        public final int bgColor;
        public final int textColor;

        public ColorPair(int bgColor, int textColor) {
            this.bgColor = bgColor;
            this.textColor = textColor;
        }
    }

    /**
     * Samples the background and foreground text color from a clean screen Bitmap within bounds.
     */
    public static ColorPair sampleFromBitmap(Bitmap bitmap, Rect bounds) {
        if (bitmap == null || bounds == null) {
            return new ColorPair(0xFF121212, 0xFFFFFFFF);
        }

        int bw = bitmap.getWidth();
        int bh = bitmap.getHeight();

        int left = Math.max(0, Math.min(bw - 1, bounds.left));
        int top = Math.max(0, Math.min(bh - 1, bounds.top));
        int right = Math.max(0, Math.min(bw - 1, bounds.right));
        int bottom = Math.max(0, Math.min(bh - 1, bounds.bottom));

        if (right - left < 4 || bottom - top < 4) {
            int c = bitmap.getPixel(left, top) | 0xFF000000;
            return new ColorPair(c, getContrastingTextColor(c));
        }

        // 1. Sample perimeter pixels (1px inset from edges) to find the true container background color
        Map<Integer, Integer> quantizedCounts = new HashMap<>();
        Map<Integer, Integer> exactColorExample = new HashMap<>();

        int stepX = Math.max(1, (right - left) / 16);
        int stepY = Math.max(1, (bottom - top) / 8);

        int insetTop = Math.min(bottom, top + 1);
        int insetBottom = Math.max(top, bottom - 1);
        int insetLeft = Math.min(right, left + 1);
        int insetRight = Math.max(left, right - 1);

        for (int x = insetLeft; x <= insetRight; x += stepX) {
            recordSample(bitmap.getPixel(x, insetTop), quantizedCounts, exactColorExample);
            recordSample(bitmap.getPixel(x, insetBottom), quantizedCounts, exactColorExample);
        }
        for (int y = insetTop; y <= insetBottom; y += stepY) {
            recordSample(bitmap.getPixel(insetLeft, y), quantizedCounts, exactColorExample);
            recordSample(bitmap.getPixel(insetRight, y), quantizedCounts, exactColorExample);
        }

        int bestBucket = 0xFF121212;
        int maxCount = -1;
        for (Map.Entry<Integer, Integer> entry : quantizedCounts.entrySet()) {
            if (entry.getValue() > maxCount) {
                maxCount = entry.getValue();
                bestBucket = entry.getKey();
            }
        }

        int bgColor = exactColorExample.containsKey(bestBucket)
                ? (exactColorExample.get(bestBucket) | 0xFF000000)
                : 0xFF121212;

        // 2. Scan interior horizontal bands to find the foreground text color (maximum distance from bgColor)
        int bestTextColor = getContrastingTextColor(bgColor);
        double maxDist = 0;

        int midY1 = top + (bottom - top) / 2;
        int midY2 = top + (bottom - top) * 2 / 5;
        int midY3 = top + (bottom - top) * 3 / 5;
        int[] scanRows = new int[]{midY1, midY2, midY3};

        int innerStepX = Math.max(1, (right - left) / 40);
        for (int rowY : scanRows) {
            int y = Math.max(0, Math.min(bh - 1, rowY));
            for (int x = insetLeft; x <= insetRight; x += innerStepX) {
                int px = bitmap.getPixel(x, y) | 0xFF000000;
                double dist = colorDistance(bgColor, px);
                if (dist > maxDist) {
                    maxDist = dist;
                    bestTextColor = px;
                }
            }
        }

        // If no pixel had strong contrast (> 65 RGB Euclidean distance), use clean high-contrast text color
        if (maxDist < 65.0) {
            bestTextColor = getContrastingTextColor(bgColor);
        }

        return new ColorPair(bgColor, bestTextColor);
    }

    private static void recordSample(int rawPixel, Map<Integer, Integer> counts, Map<Integer, Integer> exact) {
        int rgb = rawPixel | 0xFF000000;
        // Quantize to 4 bits per channel to group anti-aliased / slight gradient neighbors
        int r = (Color.red(rgb) >> 3) << 3;
        int g = (Color.green(rgb) >> 3) << 3;
        int b = (Color.blue(rgb) >> 3) << 3;
        int bucket = Color.rgb(r, g, b);
        counts.put(bucket, counts.getOrDefault(bucket, 0) + 1);
        if (!exact.containsKey(bucket)) {
            exact.put(bucket, rgb);
        }
    }

    public static double colorDistance(int c1, int c2) {
        int dr = Color.red(c1) - Color.red(c2);
        int dg = Color.green(c1) - Color.green(c2);
        int db = Color.blue(c1) - Color.blue(c2);
        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    public static int getContrastingTextColor(int bgColor) {
        double luminance = (0.299 * Color.red(bgColor)
                + 0.587 * Color.green(bgColor)
                + 0.114 * Color.blue(bgColor)) / 255.0;
        return luminance > 0.55 ? 0xFF111827 : 0xFFF8FAFC;
    }
}
