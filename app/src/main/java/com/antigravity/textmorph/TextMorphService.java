package com.antigravity.textmorph;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.DisplayMetrics;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

public class TextMorphService extends AccessibilityService {

    private static TextMorphService instance;

    private WindowManager windowManager;
    private ChameleonOverlayView overlayView;
    private View floatingWandView;
    private WindowManager.LayoutParams wandParams;
    private View inspectorTouchLayer;
    private View liveEditorDialogView;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Executor mainExecutor = mainHandler::post;

    private List<RuleModel> cachedRules = new ArrayList<>();
    private boolean masterEnabled = true;
    private boolean floatingWandEnabled = true;
    private boolean sandboxEnabled = true;
    private boolean directEditEnabled = true;

    private boolean inspectorMode = false;
    private Bitmap lastCleanScreenshot = null;
    private long lastScreenshotTimeMs = 0;
    private boolean screenshotInFlight = false;

    private final LruCache<String, ColorSampler.ColorPair> colorCache = new LruCache<>(150);
    private final List<ChameleonOverlayView.InspectableNode> currentInspectNodes = new ArrayList<>();

    private boolean scanPending = false;
    private final Runnable scanRunnable = new Runnable() {
        @Override
        public void run() {
            scanPending = false;
            performScreenScan();
        }
    };

    public static boolean isRunning() {
        return instance != null;
    }

    public static void notifyRulesUpdated() {
        if (instance != null) {
            instance.mainHandler.post(() -> {
                instance.reloadSettings();
                instance.scheduleScan(0);
            });
        }
    }

    public static void triggerLiveInspectorFromApp() {
        if (instance != null) {
            instance.mainHandler.postDelayed(() -> instance.enterInspectorMode(), 350);
        }
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        reloadSettings();
        setupOverlayWindow();
        updateFloatingWandVisibility();
        scheduleScan(100);
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        cleanupWindows();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        cleanupWindows();
        instance = null;
        super.onDestroy();
    }

    private void cleanupWindows() {
        try {
            if (overlayView != null && windowManager != null) {
                windowManager.removeViewImmediate(overlayView);
            }
        } catch (Exception ignored) {}
        overlayView = null;

        try {
            if (floatingWandView != null && windowManager != null) {
                windowManager.removeViewImmediate(floatingWandView);
            }
        } catch (Exception ignored) {}
        floatingWandView = null;

        removeInspectorTouchLayer();
        removeLiveEditorDialog();
    }

    private void reloadSettings() {
        cachedRules = RuleStorage.loadRules(this);
        masterEnabled = RuleStorage.isMasterEnabled(this);
        floatingWandEnabled = RuleStorage.isFloatingWandEnabled(this);
        sandboxEnabled = RuleStorage.isSandboxEnabled(this);
        directEditEnabled = RuleStorage.isDirectEditEnabled(this);
        updateFloatingWandVisibility();
    }

    private void setupOverlayWindow() {
        if (overlayView != null) return;
        overlayView = new ChameleonOverlayView(this);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        // Render into display cutout / status bar coordinates so full-screen coordinates match 1:1
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

        try {
            windowManager.addView(overlayView, params);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateFloatingWandVisibility() {
        if (windowManager == null) return;
        if (floatingWandEnabled && masterEnabled) {
            if (floatingWandView == null) {
                createFloatingWand();
            } else {
                floatingWandView.setVisibility(View.VISIBLE);
            }
        } else {
            if (floatingWandView != null) {
                floatingWandView.setVisibility(View.GONE);
            }
        }
    }

    private void createFloatingWand() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float density = dm.density;

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        int padH = (int) (12 * density);
        int padV = (int) (8 * density);
        pill.setPadding(padH, padV, padH, padV);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE0F172A);
        bg.setCornerRadius(24 * density);
        bg.setStroke((int) (1.5f * density), 0xFF38BDF8);
        pill.setBackground(bg);
        pill.setElevation(12 * density);

        TextView label = new TextView(this);
        label.setText("🪄 Edit Text");
        label.setTextColor(0xFFF8FAFC);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        pill.addView(label);

        wandParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        wandParams.gravity = Gravity.TOP | Gravity.START;
        wandParams.x = dm.widthPixels - (int) (115 * density);
        wandParams.y = (int) (dm.heightPixels * 0.32f);

        pill.setOnTouchListener(new View.OnTouchListener() {
            private int initialX, initialY;
            private float initialTouchX, initialTouchY;
            private boolean moved;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = wandParams.x;
                        initialY = wandParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = (int) (event.getRawX() - initialTouchX);
                        int dy = (int) (event.getRawY() - initialTouchY);
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                            moved = true;
                        }
                        wandParams.x = initialX + dx;
                        wandParams.y = initialY + dy;
                        try {
                            windowManager.updateViewLayout(floatingWandView, wandParams);
                        } catch (Exception ignored) {}
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) {
                            if (inspectorMode) {
                                exitInspectorMode();
                            } else {
                                enterInspectorMode();
                            }
                        }
                        return true;
                }
                return false;
            }
        });

        floatingWandView = pill;
        try {
            windowManager.addView(floatingWandView, wandParams);
        } catch (Exception ignored) {}
    }

    private void enterInspectorMode() {
        if (inspectorMode) return;
        removeLiveEditorDialog();

        // Hide current patches briefly to capture clean screenshot and collect all visible text nodes
        if (overlayView != null) {
            overlayView.setVisibility(View.INVISIBLE);
        }
        if (floatingWandView != null) {
            floatingWandView.setVisibility(View.INVISIBLE);
        }

        mainHandler.postDelayed(() -> {
            captureScreenshotAndOpenInspector();
        }, 70);
    }

    private void captureScreenshotAndOpenInspector() {
        collectInspectableNodes();

        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult screenshot) {
                    try {
                        HardwareBuffer hb = screenshot.getHardwareBuffer();
                        if (hb != null) {
                            Bitmap hwBitmap = Bitmap.wrapHardwareBuffer(hb, screenshot.getColorSpace());
                            if (hwBitmap != null) {
                                lastCleanScreenshot = hwBitmap.copy(Bitmap.Config.ARGB_8888, false);
                                hwBitmap.recycle();
                            }
                            hb.close();
                        }
                    } catch (Exception ignored) {}
                    finishEnteringInspector();
                }

                @Override
                public void onFailure(int errorCode) {
                    finishEnteringInspector();
                }
            });
        } catch (Exception e) {
            finishEnteringInspector();
        }
    }

    private void finishEnteringInspector() {
        inspectorMode = true;
        if (overlayView != null) {
            overlayView.setVisibility(View.VISIBLE);
            overlayView.setInspectorData(true, currentInspectNodes);
        }
        if (floatingWandView != null && floatingWandEnabled) {
            floatingWandView.setVisibility(View.VISIBLE);
        }
        showInspectorTouchLayer();
    }

    private void exitInspectorMode() {
        inspectorMode = false;
        removeInspectorTouchLayer();
        if (overlayView != null) {
            overlayView.setInspectorData(false, null);
        }
        scheduleScan(10);
    }

    private void showInspectorTouchLayer() {
        removeInspectorTouchLayer();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float density = dm.density;

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setBackgroundColor(0x18000000);

        // Top instruction bar
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        int pad = (int) (14 * density);
        topBar.setPadding(pad, (int) (38 * density), pad, pad);
        topBar.setBackgroundColor(0xEE0F172A);

        TextView info = new TextView(this);
        info.setText("🎯 Tap any highlighted text on screen to replace it");
        info.setTextColor(0xFF38BDF8);
        info.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        info.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams infoLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        topBar.addView(info, infoLp);

        Button cancelBtn = new Button(this);
        cancelBtn.setText("✕ Close");
        cancelBtn.setTextColor(0xFFFFFFFF);
        cancelBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        GradientDrawable btnBg = new GradientDrawable();
        btnBg.setColor(0xFFEF4444);
        btnBg.setCornerRadius(16 * density);
        cancelBtn.setBackground(btnBg);
        cancelBtn.setOnClickListener(v -> exitInspectorMode());
        topBar.addView(cancelBtn);

        container.addView(topBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        container.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                int rawX = (int) event.getRawX();
                int rawY = (int) event.getRawY();
                ChameleonOverlayView.InspectableNode picked = findSmallestNodeAt(rawX, rawY);
                if (picked != null) {
                    exitInspectorMode();
                    showLiveEditorForNode(picked);
                } else {
                    Toast.makeText(TextMorphService.this, "No text box at that spot—tap inside a blue box", Toast.LENGTH_SHORT).show();
                }
            }
            return true;
        });

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
        );
        lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;

        inspectorTouchLayer = container;
        try {
            windowManager.addView(inspectorTouchLayer, lp);
        } catch (Exception ignored) {}
    }

    private void removeInspectorTouchLayer() {
        if (inspectorTouchLayer != null && windowManager != null) {
            try {
                windowManager.removeViewImmediate(inspectorTouchLayer);
            } catch (Exception ignored) {}
        }
        inspectorTouchLayer = null;
    }

    private ChameleonOverlayView.InspectableNode findSmallestNodeAt(int x, int y) {
        ChameleonOverlayView.InspectableNode best = null;
        long bestArea = Long.MAX_VALUE;
        for (ChameleonOverlayView.InspectableNode node : currentInspectNodes) {
            if (node.bounds != null && node.bounds.contains(x, y)) {
                long area = (long) node.bounds.width() * (long) node.bounds.height();
                if (area < bestArea) {
                    bestArea = area;
                    best = node;
                }
            }
        }
        return best;
    }

    private void showLiveEditorForNode(ChameleonOverlayView.InspectableNode node) {
        removeLiveEditorDialog();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float density = dm.density;

        // Sample actual pixel colors from the screenshot at the tapped node's bounds
        ColorSampler.ColorPair sampled = ColorSampler.sampleFromBitmap(lastCleanScreenshot, node.bounds);

        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.VERTICAL);
        outer.setGravity(Gravity.CENTER);
        outer.setBackgroundColor(0x88000000);
        int outerPad = (int) (18 * density);
        outer.setPadding(outerPad, outerPad, outerPad, outerPad);

        ScrollView scroll = new ScrollView(this);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(0xFF0F172A);
        cardBg.setCornerRadius(20 * density);
        cardBg.setStroke((int) (1.5f * density), 0xFF334155);
        scroll.setBackground(cardBg);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int cardPad = (int) (18 * density);
        card.setPadding(cardPad, cardPad, cardPad, cardPad);

        TextView title = new TextView(this);
        title.setText("🪄 Live Text Replacement");
        title.setTextColor(0xFF38BDF8);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(title);

        TextView pkgLabel = new TextView(this);
        pkgLabel.setText("App: " + (node.packageName != null ? node.packageName : "Current App"));
        pkgLabel.setTextColor(0xFF94A3B8);
        pkgLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        pkgLabel.setPadding(0, (int) (2 * density), 0, (int) (10 * density));
        card.addView(pkgLabel);

        // Find text field
        TextView findHeader = new TextView(this);
        findHeader.setText("Text to Find (you can trim to a single word):");
        findHeader.setTextColor(0xFFCBD5E1);
        findHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        card.addView(findHeader);

        EditText findInput = createStyledEditText(node.text, "Original text");
        card.addView(findInput);

        // Replace text field
        TextView repHeader = new TextView(this);
        repHeader.setText("Replace With:");
        repHeader.setTextColor(0xFFCBD5E1);
        repHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        repHeader.setPadding(0, (int) (10 * density), 0, 0);
        card.addView(repHeader);

        EditText repInput = createStyledEditText(node.text, "Type replacement text...");
        repInput.selectAll();
        card.addView(repInput);

        // Auto-color preview & toggle
        CheckBox autoColorCheck = new CheckBox(this);
        autoColorCheck.setText(String.format(Locale.US,
                "Chameleon Auto-Color (Sampled BG #%06X / Text #%06X)",
                (0xFFFFFF & sampled.bgColor), (0xFFFFFF & sampled.textColor)));
        autoColorCheck.setTextColor(0xFFE2E8F0);
        autoColorCheck.setChecked(true);
        card.addView(autoColorCheck);

        CheckBox boldCheck = new CheckBox(this);
        boldCheck.setText("Bold Font Style");
        boldCheck.setTextColor(0xFFE2E8F0);
        boldCheck.setChecked(false);
        card.addView(boldCheck);

        CheckBox allAppsCheck = new CheckBox(this);
        allAppsCheck.setText("Apply across ALL apps (uncheck for this app only)");
        allAppsCheck.setTextColor(0xFFE2E8F0);
        allAppsCheck.setChecked(true);
        card.addView(allAppsCheck);

        // Action buttons
        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);
        btnRow.setPadding(0, (int) (14 * density), 0, 0);

        Button cancelBtn = new Button(this);
        cancelBtn.setText("Cancel");
        cancelBtn.setTextColor(0xFFCBD5E1);
        GradientDrawable cancelBg = new GradientDrawable();
        cancelBg.setColor(0xFF1E293B);
        cancelBg.setCornerRadius(12 * density);
        cancelBtn.setBackground(cancelBg);
        cancelBtn.setOnClickListener(v -> removeLiveEditorDialog());

        Button saveBtn = new Button(this);
        saveBtn.setText("Save & Morph Now");
        saveBtn.setTextColor(0xFF0F172A);
        saveBtn.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable saveBg = new GradientDrawable();
        saveBg.setColor(0xFF38BDF8);
        saveBg.setCornerRadius(12 * density);
        saveBtn.setBackground(saveBg);

        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        saveLp.leftMargin = (int) (10 * density);

        saveBtn.setOnClickListener(v -> {
            String findStr = findInput.getText().toString();
            String repStr = repInput.getText().toString();
            if (findStr.isEmpty()) {
                Toast.makeText(TextMorphService.this, "Find text cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }
            RuleModel rule = new RuleModel();
            rule.findText = findStr;
            rule.replaceText = repStr;
            rule.targetPackage = allAppsCheck.isChecked() ? "*" : (node.packageName != null ? node.packageName : "*");
            rule.autoColor = autoColorCheck.isChecked();
            rule.customBgColor = sampled.bgColor;
            rule.customTextColor = sampled.textColor;
            rule.forceBold = boldCheck.isChecked();
            rule.enabled = true;

            // Pre-populate color cache with the exact sampled colors
            colorCache.put(getCacheKey(node.packageName, findStr), sampled);

            RuleStorage.addOrUpdateRule(TextMorphService.this, rule);
            removeLiveEditorDialog();
            Toast.makeText(TextMorphService.this, "Text replacement active!", Toast.LENGTH_SHORT).show();
        });

        btnRow.addView(cancelBtn);
        btnRow.addView(saveBtn, saveLp);
        card.addView(btnRow);

        scroll.addView(card);
        outer.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_DIM_BEHIND,
                PixelFormat.TRANSLUCENT
        );
        lp.dimAmount = 0.45f;
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;

        liveEditorDialogView = outer;
        try {
            windowManager.addView(liveEditorDialogView, lp);
            repInput.requestFocus();
            mainHandler.postDelayed(() -> {
                InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
                if (imm != null) {
                    imm.showSoftInput(repInput, InputMethodManager.SHOW_IMPLICIT);
                }
            }, 120);
        } catch (Exception ignored) {}
    }

    private EditText createStyledEditText(String initialText, String hint) {
        float density = getResources().getDisplayMetrics().density;
        EditText et = new EditText(this);
        et.setText(initialText != null ? initialText : "");
        et.setHint(hint);
        et.setHintTextColor(0xFF64748B);
        et.setTextColor(0xFFF8FAFC);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        int pad = (int) (11 * density);
        et.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1E293B);
        bg.setCornerRadius(10 * density);
        bg.setStroke((int) (1 * density), 0xFF475569);
        et.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = (int) (4 * density);
        lp.bottomMargin = (int) (4 * density);
        et.setLayoutParams(lp);
        return et;
    }

    private void removeLiveEditorDialog() {
        if (liveEditorDialogView != null && windowManager != null) {
            try {
                windowManager.removeViewImmediate(liveEditorDialogView);
            } catch (Exception ignored) {}
        }
        liveEditorDialogView = null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!masterEnabled || overlayView == null || inspectorMode || liveEditorDialogView != null) {
            if (!masterEnabled && overlayView != null) {
                overlayView.updatePatches(null);
            }
            return;
        }
        scheduleScan(12);
    }

    @Override
    public void onInterrupt() {
        if (overlayView != null) {
            overlayView.updatePatches(null);
        }
    }

    private void scheduleScan(long delayMs) {
        if (scanPending && delayMs > 0) return;
        mainHandler.removeCallbacks(scanRunnable);
        scanPending = true;
        mainHandler.postDelayed(scanRunnable, delayMs);
    }

    private void collectInspectableNodes() {
        currentInspectNodes.clear();
        DisplayMetrics dm = getResources().getDisplayMetrics();
        Rect screenBounds = new Rect(0, 0, dm.widthPixels, dm.heightPixels);

        List<AccessibilityWindowInfo> windows = getWindows();
        if (windows != null && !windows.isEmpty()) {
            for (AccessibilityWindowInfo win : windows) {
                if (win.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                        || win.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    continue;
                }
                AccessibilityNodeInfo root = win.getRoot();
                if (root != null) {
                    String pkg = root.getPackageName() != null ? root.getPackageName().toString() : "";
                    if (!isIgnoredSystemPackage(pkg)) {
                        traverseForInspect(root, pkg, screenBounds, false);
                    }
                    root.recycle();
                }
            }
        } else {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null) {
                String pkg = root.getPackageName() != null ? root.getPackageName().toString() : "";
                if (!isIgnoredSystemPackage(pkg)) {
                    traverseForInspect(root, pkg, screenBounds, false);
                }
                root.recycle();
            }
        }
    }

    private void traverseForInspect(AccessibilityNodeInfo node, String pkg, Rect screenBounds, boolean insideSandbox) {
        if (node == null || !node.isVisibleToUser()) return;

        CharSequence desc = node.getContentDescription();
        if (desc != null && "TEXTMORPH_SANDBOX_CONTAINER".equals(desc.toString())) {
            insideSandbox = true;
        }

        // If inside our own app, only inspect nodes inside the Live Test Sandbox
        boolean isOwnApp = getPackageName().equals(pkg);
        if (!isOwnApp || insideSandbox) {
            CharSequence rawText = node.getText();
            if (rawText != null && rawText.length() > 0) {
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (bounds.intersect(screenBounds) && bounds.width() > 8 && bounds.height() > 8) {
                    ChameleonOverlayView.InspectableNode item = new ChameleonOverlayView.InspectableNode();
                    item.bounds = bounds;
                    item.text = rawText.toString();
                    item.packageName = pkg;
                    currentInspectNodes.add(item);
                }
            }
        }

        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                traverseForInspect(child, pkg, screenBounds, insideSandbox);
                child.recycle();
            }
        }
    }

    private void performScreenScan() {
        if (!masterEnabled || overlayView == null || inspectorMode || liveEditorDialogView != null) {
            return;
        }
        if (cachedRules == null || cachedRules.isEmpty()) {
            overlayView.updatePatches(null);
            return;
        }

        DisplayMetrics dm = getResources().getDisplayMetrics();
        float density = dm.density;
        Rect screenBounds = new Rect(0, 0, dm.widthPixels, dm.heightPixels);
        List<ChameleonOverlayView.PatchItem> activePatches = new ArrayList<>();
        List<PendingColorRequest> missingColors = new ArrayList<>();

        AccessibilityNodeInfo activeRoot = getRootInActiveWindow();
        if (activeRoot != null) {
            String pkg = activeRoot.getPackageName() != null ? activeRoot.getPackageName().toString() : "";
            if (!isIgnoredSystemPackage(pkg)) {
                List<RuleModel> applicableRules = getApplicableRules(pkg);
                if (!applicableRules.isEmpty()) {
                    traverseAndMatch(
                            activeRoot,
                            pkg,
                            applicableRules,
                            screenBounds,
                            density,
                            false,
                            activePatches,
                            missingColors
                    );
                }
            }
            activeRoot.recycle();
        }

        overlayView.updatePatches(activePatches);

        // If any active rule uses Chameleon Auto-Color and hasn't sampled this element's color yet,
        // capture a non-intrusive background sample (throttled to respect Android's screenshot limit).
        if (!missingColors.isEmpty() && !screenshotInFlight
                && (System.currentTimeMillis() - lastScreenshotTimeMs > 1800)) {
            requestAutoColorSample(missingColors);
        }
    }

    private static class PendingColorRequest {
        String cacheKey;
        Rect bounds;
    }

    private void requestAutoColorSample(List<PendingColorRequest> requests) {
        screenshotInFlight = true;
        lastScreenshotTimeMs = System.currentTimeMillis();

        // Briefly hide our overlay for 1 frame so we sample the real underlying app colors
        if (overlayView != null) {
            overlayView.setAlpha(0f);
        }

        mainHandler.postDelayed(() -> {
            try {
                takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult screenshot) {
                        if (overlayView != null) {
                            overlayView.setAlpha(1f);
                        }
                        try {
                            HardwareBuffer hb = screenshot.getHardwareBuffer();
                            if (hb != null) {
                                Bitmap hw = Bitmap.wrapHardwareBuffer(hb, screenshot.getColorSpace());
                                if (hw != null) {
                                    Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
                                    hw.recycle();
                                    if (sw != null) {
                                        lastCleanScreenshot = sw;
                                        for (PendingColorRequest req : requests) {
                                            ColorSampler.ColorPair pair = ColorSampler.sampleFromBitmap(sw, req.bounds);
                                            colorCache.put(req.cacheKey, pair);
                                        }
                                    }
                                }
                                hb.close();
                            }
                        } catch (Exception ignored) {}
                        screenshotInFlight = false;
                        scheduleScan(16);
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        if (overlayView != null) {
                            overlayView.setAlpha(1f);
                        }
                        screenshotInFlight = false;
                    }
                });
            } catch (Exception e) {
                if (overlayView != null) {
                    overlayView.setAlpha(1f);
                }
                screenshotInFlight = false;
            }
        }, 35);
    }

    private List<RuleModel> getApplicableRules(String pkg) {
        List<RuleModel> list = new ArrayList<>();
        for (RuleModel r : cachedRules) {
            if (r.appliesToPackage(pkg)) {
                list.add(r);
            }
        }
        return list;
    }

    private boolean isIgnoredSystemPackage(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        String lower = pkg.toLowerCase(Locale.ROOT);
        return lower.equals("com.android.systemui")
                || lower.contains("inputmethod")
                || lower.contains("latinime")
                || lower.contains("gboard")
                || lower.contains("swiftkey");
    }

    private void traverseAndMatch(
            AccessibilityNodeInfo node,
            String pkg,
            List<RuleModel> rules,
            Rect screenBounds,
            float density,
            boolean insideSandbox,
            List<ChameleonOverlayView.PatchItem> outPatches,
            List<PendingColorRequest> outMissingColors
    ) {
        if (node == null || !node.isVisibleToUser()) return;

        CharSequence desc = node.getContentDescription();
        if (desc != null && "TEXTMORPH_SANDBOX_CONTAINER".equals(desc.toString())) {
            insideSandbox = true;
        }

        boolean isOwnApp = getPackageName().equals(pkg);
        if (!isOwnApp || (sandboxEnabled && insideSandbox)) {
            CharSequence rawTextCs = node.getText();
            if (rawTextCs != null && rawTextCs.length() > 0) {
                String originalText = rawTextCs.toString();
                String currentText = originalText;
                RuleModel matchedPrimaryRule = null;

                for (int i = 0; i < rules.size(); i++) {
                    RuleModel r = rules.get(i);
                    String transformed = r.applyToText(currentText);
                    if (transformed != null && !transformed.equals(currentText)) {
                        currentText = transformed;
                        if (matchedPrimaryRule == null) {
                            matchedPrimaryRule = r;
                        }
                    }
                }

                if (matchedPrimaryRule != null) {
                    // If the target node is an editable input field and Direct Edit is enabled,
                    // we can also update its text directly via ACTION_SET_TEXT!
                    if (directEditEnabled && node.isEditable()) {
                        Bundle args = new Bundle();
                        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, currentText);
                        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                    }

                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    if (bounds.intersect(screenBounds) && bounds.width() > 4 && bounds.height() > 4) {
                        ChameleonOverlayView.PatchItem patch = new ChameleonOverlayView.PatchItem();
                        patch.bounds = bounds;
                        patch.originalText = originalText;
                        patch.replacementText = currentText;
                        patch.fontSizeScale = matchedPrimaryRule.fontSizeScale;
                        patch.forceBold = matchedPrimaryRule.forceBold;
                        patch.textAlign = matchedPrimaryRule.textAlign;
                        patch.offsetXPx = Math.round(matchedPrimaryRule.offsetXDp * density);
                        patch.offsetYPx = Math.round(matchedPrimaryRule.offsetYDp * density);
                        patch.paddingPx = Math.round(matchedPrimaryRule.paddingDp * density);

                        if (matchedPrimaryRule.autoColor) {
                            String key = getCacheKey(pkg, matchedPrimaryRule.findText);
                            ColorSampler.ColorPair cachedPair = colorCache.get(key);
                            if (cachedPair != null) {
                                patch.bgColor = cachedPair.bgColor;
                                patch.textColor = cachedPair.textColor;
                            } else if (lastCleanScreenshot != null) {
                                ColorSampler.ColorPair sampled = ColorSampler.sampleFromBitmap(lastCleanScreenshot, bounds);
                                colorCache.put(key, sampled);
                                patch.bgColor = sampled.bgColor;
                                patch.textColor = sampled.textColor;
                            } else {
                                patch.bgColor = matchedPrimaryRule.customBgColor;
                                patch.textColor = matchedPrimaryRule.customTextColor;
                                PendingColorRequest req = new PendingColorRequest();
                                req.cacheKey = key;
                                req.bounds = new Rect(bounds);
                                outMissingColors.add(req);
                            }
                        } else {
                            patch.bgColor = matchedPrimaryRule.customBgColor;
                            patch.textColor = matchedPrimaryRule.customTextColor;
                        }

                        outPatches.add(patch);
                    }
                }
            }
        }

        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                traverseAndMatch(child, pkg, rules, screenBounds, density, insideSandbox, outPatches, outMissingColors);
                child.recycle();
            }
        }
    }

    private String getCacheKey(String pkg, String findText) {
        return (pkg != null ? pkg : "*") + "|" + (findText != null ? findText : "");
    }
}
