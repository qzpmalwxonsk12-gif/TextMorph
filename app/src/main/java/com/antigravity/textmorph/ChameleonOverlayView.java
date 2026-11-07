package com.antigravity.textmorph;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.DisplayMetrics;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

public class ChameleonOverlayView extends View {

    public static class PatchItem {
        public Rect bounds;
        public String originalText;
        public String replacementText;
        public int bgColor;
        public int textColor;
        public float fontSizeScale;
        public boolean forceBold;
        public int textAlign;
        public int offsetXPx;
        public int offsetYPx;
        public int paddingPx;
    }

    public static class InspectableNode {
        public Rect bounds;
        public String text;
        public String packageName;
    }

    private final List<PatchItem> patches = new ArrayList<>();
    private final List<InspectableNode> inspectNodes = new ArrayList<>();
    private boolean inspectorMode = false;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint inspectFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint inspectBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF tempRectF = new RectF();
    private final float density;

    public ChameleonOverlayView(Context context) {
        super(context);
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        density = dm.density;

        bgPaint.setStyle(Paint.Style.FILL);

        inspectFillPaint.setStyle(Paint.Style.FILL);
        inspectFillPaint.setColor(0x2238BDF8);

        inspectBorderPaint.setStyle(Paint.Style.STROKE);
        inspectBorderPaint.setStrokeWidth(1.5f * density);
        inspectBorderPaint.setColor(0xDD38BDF8);
        inspectBorderPaint.setPathEffect(new DashPathEffect(new float[]{6 * density, 4 * density}, 0));
    }

    public void updatePatches(List<PatchItem> newPatches) {
        patches.clear();
        if (newPatches != null) {
            patches.addAll(newPatches);
        }
        postInvalidateOnAnimation();
    }

    public void setInspectorData(boolean active, List<InspectableNode> nodes) {
        this.inspectorMode = active;
        this.inspectNodes.clear();
        if (nodes != null) {
            this.inspectNodes.addAll(nodes);
        }
        postInvalidateOnAnimation();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 1. Draw all active text replacement patches
        for (int i = 0; i < patches.size(); i++) {
            PatchItem p = patches.get(i);
            if (p.bounds == null || p.bounds.width() <= 2 || p.bounds.height() <= 2) continue;

            int pad = p.paddingPx;
            float left = p.bounds.left - pad + p.offsetXPx;
            float top = p.bounds.top - pad + p.offsetYPx;
            float right = p.bounds.right + pad + p.offsetXPx;
            float bottom = p.bounds.bottom + pad + p.offsetYPx;

            // Draw seamless background patch over original text
            bgPaint.setColor(p.bgColor);
            tempRectF.set(left, top, right, bottom);
            float cornerRadius = 2f * density;
            canvas.drawRoundRect(tempRectF, cornerRadius, cornerRadius, bgPaint);

            // Configure text paint
            textPaint.setColor(p.textColor);
            textPaint.setTypeface(p.forceBold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);

            float boxW = Math.max(10f, p.bounds.width());
            float boxH = Math.max(10f, p.bounds.height());

            // Estimate whether original text was single-line or multi-line
            boolean multiLine = (p.originalText != null && p.originalText.contains("\n"))
                    || (p.replacementText != null && p.replacementText.contains("\n"))
                    || (boxH > 42f * density && p.originalText != null && p.originalText.length() > 26);

            float baseSizePx;
            if (multiLine) {
                float approxLineH = 21f * density;
                int estLines = Math.max(2, Math.round(boxH / approxLineH));
                baseSizePx = Math.min(20f * density, (boxH / estLines) * 0.68f);
            } else {
                baseSizePx = Math.min(34f * density, boxH * 0.60f);
            }
            baseSizePx = Math.max(9f * density, baseSizePx * p.fontSizeScale);
            textPaint.setTextSize(baseSizePx);

            String text = p.replacementText != null ? p.replacementText : "";

            if (!multiLine) {
                // Auto-fit single-line text if slightly wider than original bounds unless scale was boosted
                float measuredW = textPaint.measureText(text);
                if (measuredW > boxW && p.fontSizeScale <= 1.05f) {
                    float fitted = baseSizePx * (boxW / measuredW);
                    if (fitted >= 9f * density) {
                        textPaint.setTextSize(fitted);
                        measuredW = textPaint.measureText(text);
                    }
                }

                Paint.FontMetrics fm = textPaint.getFontMetrics();
                float textHeight = fm.descent - fm.ascent;
                float baselineY = p.bounds.top + (boxH - textHeight) / 2f - fm.ascent + p.offsetYPx;

                float drawX = p.bounds.left + p.offsetXPx;
                if (p.textAlign == RuleModel.ALIGN_CENTER) {
                    drawX = p.bounds.left + (boxW - measuredW) / 2f + p.offsetXPx;
                } else if (p.textAlign == RuleModel.ALIGN_RIGHT) {
                    drawX = p.bounds.right - measuredW + p.offsetXPx;
                }

                canvas.save();
                // Allow slightly wider clip in case replacement word is longer than original word
                float clipRight = Math.max(right, drawX + measuredW + 4f * density);
                canvas.clipRect(left, top, clipRight, bottom);
                canvas.drawText(text, drawX, baselineY, textPaint);
                canvas.restore();
            } else {
                // Multi-line StaticLayout rendering
                Layout.Alignment align = Layout.Alignment.ALIGN_NORMAL;
                if (p.textAlign == RuleModel.ALIGN_CENTER) {
                    align = Layout.Alignment.ALIGN_CENTER;
                } else if (p.textAlign == RuleModel.ALIGN_RIGHT) {
                    align = Layout.Alignment.ALIGN_OPPOSITE;
                }

                StaticLayout layout = StaticLayout.Builder
                        .obtain(text, 0, text.length(), textPaint, (int) boxW)
                        .setAlignment(align)
                        .setIncludePad(false)
                        .build();

                canvas.save();
                float layoutH = layout.getHeight();
                float startY = p.bounds.top + Math.max(0f, (boxH - layoutH) / 2f) + p.offsetYPx;
                canvas.clipRect(left, top, right, Math.max(bottom, startY + layoutH));
                canvas.translate(p.bounds.left + p.offsetXPx, startY);
                layout.draw(canvas);
                canvas.restore();
            }
        }

        // 2. If Live Inspector ("Tap any text to edit") is active, highlight all text nodes
        if (inspectorMode) {
            for (int i = 0; i < inspectNodes.size(); i++) {
                InspectableNode node = inspectNodes.get(i);
                if (node.bounds == null) continue;
                tempRectF.set(node.bounds);
                canvas.drawRoundRect(tempRectF, 4f * density, 4f * density, inspectFillPaint);
                canvas.drawRoundRect(tempRectF, 4f * density, 4f * density, inspectBorderPaint);
            }
        }
    }
}
