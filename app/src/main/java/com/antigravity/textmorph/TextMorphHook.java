package com.antigravity.textmorph;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class TextMorphHook implements IXposedHookLoadPackage {

    private static final String LOCAL_PREFS = "textmorph_inapp_rules";
    private static final String KEY_LOCAL_RULES = "rules_json";
    private static final String KEY_HIDE_PILL = "hide_floating_pill";
    private static final String KEY_HOOK_ENABLED = "hook_enabled";
    private static final int WAND_VIEW_TAG_ID = 0x7E87409F;

    private final CopyOnWriteArrayList<RuleModel> activeRules = new CopyOnWriteArrayList<>();
    private final WeakHashMap<TextView, CharSequence> originalTextMap = new WeakHashMap<>();
    private final ThreadLocal<Boolean> isTransforming = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return Boolean.FALSE;
        }
    };

    private volatile boolean hookEnabled = true;
    private volatile boolean hideFloatingPill = false;
    private volatile boolean tapToEditArmed = false;
    private volatile String currentPackageName = "";
    private WeakReference<Activity> currentActivityRef = new WeakReference<>(null);
    private long lastProviderSyncTime = 0;

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (lpparam == null || lpparam.packageName == null) return;
        if ("com.antigravity.textmorph".equals(lpparam.packageName)) return;

        currentPackageName = lpparam.packageName;
        XposedBridge.log("TextMorphHook: Loaded into package " + currentPackageName);

        // 1. Hook Application.onCreate to load persisted rules early
        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Context appCtx = (Context) param.thisObject;
                loadAndMergeRules(appCtx);
            }
        });

        // 2. Hook TextView.setText (all overloads) — 100% Native Zero-Lag Replacement
        XposedBridge.hookAllMethods(TextView.class, "setText", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!hookEnabled || isTransforming.get()) return;
                if (param.args == null || param.args.length == 0 || !(param.args[0] instanceof CharSequence)) {
                    return;
                }
                CharSequence inputCs = (CharSequence) param.args[0];
                if (inputCs == null || inputCs.length() == 0) return;

                if (param.thisObject instanceof TextView) {
                    TextView tv = (TextView) param.thisObject;
                    // Do not transform text inside our own in-app rule editor dialogs
                    if (Boolean.TRUE.equals(tv.getTag(WAND_VIEW_TAG_ID))) {
                        return;
                    }
                    // Do not transform editable EditText while the user is actively typing in it
                    if (tv instanceof EditText && tv.hasFocus()) {
                        return;
                    }
                    synchronized (originalTextMap) {
                        if (!originalTextMap.containsKey(tv)) {
                            originalTextMap.put(tv, inputCs);
                        }
                    }
                }

                CharSequence morphed = applyRulesToCharSequence(inputCs);
                if (morphed != null) {
                    param.args[0] = morphed;
                }
            }
        });

        // 3. Hook TextView.setHint
        XposedBridge.hookAllMethods(TextView.class, "setHint", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!hookEnabled || isTransforming.get()) return;
                if (param.args != null && param.args.length > 0 && param.args[0] instanceof CharSequence) {
                    CharSequence morphed = applyRulesToCharSequence((CharSequence) param.args[0]);
                    if (morphed != null) {
                        param.args[0] = morphed;
                    }
                }
            }
        });

        // 4. Hook StaticLayout.Builder.obtain (catches Jetpack Compose & custom layout text!)
        try {
            Class<?> builderClass = Class.forName("android.text.StaticLayout$Builder");
            XposedHelpers.findAndHookMethod(
                    builderClass,
                    "obtain",
                    CharSequence.class, int.class, int.class, TextPaint.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            if (!hookEnabled || isTransforming.get()) return;
                            CharSequence source = (CharSequence) param.args[0];
                            int start = (int) param.args[1];
                            int end = (int) param.args[2];
                            if (source != null && start == 0 && end == source.length() && source.length() > 0) {
                                CharSequence morphed = applyRulesToCharSequence(source);
                                if (morphed != null) {
                                    param.args[0] = morphed;
                                    param.args[2] = morphed.length();
                                }
                            }
                        }
                    }
            );
        } catch (Throwable ignored) {}

        // 5. Hook Canvas.drawText(String, float, float, Paint) for direct canvas-drawn labels
        XposedBridge.hookAllMethods(Canvas.class, "drawText", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!hookEnabled || isTransforming.get()) return;
                if (param.args == null || param.args.length == 0) return;
                if (param.args[0] instanceof String) {
                    String s = (String) param.args[0];
                    String morphed = applyRulesToString(s);
                    if (morphed != null) {
                        param.args[0] = morphed;
                    }
                }
            }
        });

        // 6. Hook Activity.onResume to attach the in-app floating wand (if visible) and refresh views
        XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity act = (Activity) param.thisObject;
                currentActivityRef = new WeakReference<>(act);
                loadAndMergeRules(act);
                attachFloatingWandToActivity(act);
                refreshAllTextViewsInActivity(act);
            }
        });

        // 7. Hook Activity.dispatchTouchEvent for:
        //    - 3-Finger Tap anywhere on screen -> Opens Native TextMorph Menu (even when wand is hidden!)
        //    - Interactive "Tap Any Text to Edit" mode -> Intercepts tap on any TextView and opens editor!
        XposedHelpers.findAndHookMethod(Activity.class, "dispatchTouchEvent", MotionEvent.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Activity act = (Activity) param.thisObject;
                MotionEvent ev = (MotionEvent) param.args[0];
                if (ev == null) return;

                // Detect 3-finger tap anywhere in the app to summon the Native Menu
                if (ev.getActionMasked() == MotionEvent.ACTION_POINTER_DOWN && ev.getPointerCount() == 3) {
                    tapToEditArmed = false;
                    showInAppMainMenu(act);
                    param.setResult(true);
                    return;
                }

                // If "Tap Any Text to Edit" is armed, intercept the next finger tap!
                if (tapToEditArmed) {
                    if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                        return;
                    }
                    if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
                        tapToEditArmed = false;
                        updateWandBadgeAppearance(act);
                        int rawX = Math.round(ev.getRawX());
                        int rawY = Math.round(ev.getRawY());
                        TextView tappedTv = findSmallestTextViewAt(act.getWindow().getDecorView(), rawX, rawY);
                        if (tappedTv != null) {
                            showLiveEditDialogForTextView(act, tappedTv);
                        } else {
                            Toast.makeText(act, "No text view found at that spot—try tapping directly on a text label", Toast.LENGTH_SHORT).show();
                        }
                        param.setResult(true);
                    }
                }
            }
        });
    }

    private CharSequence applyRulesToCharSequence(CharSequence input) {
        if (input == null || input.length() == 0 || activeRules.isEmpty()) return null;
        isTransforming.set(Boolean.TRUE);
        try {
            String original = input.toString();
            String morphed = applyRulesToString(original);
            if (morphed == null || morphed.equals(original)) {
                return null;
            }
            return morphed;
        } finally {
            isTransforming.set(Boolean.FALSE);
        }
    }

    private String applyRulesToString(String input) {
        if (input == null || input.isEmpty() || activeRules.isEmpty()) return null;
        String current = input;
        boolean changed = false;
        for (RuleModel r : activeRules) {
            if (r.appliesToPackage(currentPackageName)) {
                String out = r.applyToText(current);
                if (out != null && !out.equals(current)) {
                    current = out;
                    changed = true;
                }
            }
        }
        return changed ? current : null;
    }

    private void loadAndMergeRules(Context context) {
        if (context == null) return;
        try {
            SharedPreferences prefs = context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE);
            hookEnabled = prefs.getBoolean(KEY_HOOK_ENABLED, true);
            hideFloatingPill = prefs.getBoolean(KEY_HIDE_PILL, false);

            List<RuleModel> merged = new ArrayList<>();

            // 1. Load rules saved directly inside this patched app
            String localJson = prefs.getString(KEY_LOCAL_RULES, "[]");
            JSONArray localArr = new JSONArray(localJson);
            for (int i = 0; i < localArr.length(); i++) {
                JSONObject o = localArr.optJSONObject(i);
                if (o != null) {
                    merged.add(RuleModel.fromJson(o));
                }
            }

            // 2. Also try syncing rules from the central TextMorph app's ContentProvider
            if (System.currentTimeMillis() - lastProviderSyncTime > 1500) {
                lastProviderSyncTime = System.currentTimeMillis();
                try {
                    Cursor c = context.getContentResolver().query(
                            Uri.parse("content://com.antigravity.textmorph.provider/rules"),
                            null, null, null, null
                    );
                    if (c != null) {
                        if (c.moveToFirst()) {
                            String remoteJson = c.getString(0);
                            JSONArray remoteArr = new JSONArray(remoteJson);
                            for (int i = 0; i < remoteArr.length(); i++) {
                                JSONObject ro = remoteArr.optJSONObject(i);
                                if (ro != null) {
                                    RuleModel rm = RuleModel.fromJson(ro);
                                    if (!containsRuleId(merged, rm.id) && rm.appliesToPackage(currentPackageName)) {
                                        merged.add(rm);
                                    }
                                }
                            }
                        }
                        c.close();
                    }
                } catch (Throwable ignored) {
                    // Central app not installed or package visibility restricted—local rules work 100% standalone!
                }
            }

            activeRules.clear();
            activeRules.addAll(merged);
        } catch (Throwable ignored) {}
    }

    private boolean containsRuleId(List<RuleModel> list, String id) {
        for (RuleModel r : list) {
            if (r.id != null && r.id.equals(id)) return true;
        }
        return false;
    }

    private void saveLocalRules(Context context) {
        if (context == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (RuleModel r : activeRules) {
                arr.put(r.toJson());
            }
            SharedPreferences prefs = context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE);
            prefs.edit()
                    .putString(KEY_LOCAL_RULES, arr.toString())
                    .putBoolean(KEY_HOOK_ENABLED, hookEnabled)
                    .putBoolean(KEY_HIDE_PILL, hideFloatingPill)
                    .apply();
        } catch (Throwable ignored) {}
    }

    private void attachFloatingWandToActivity(final Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        View decor = activity.getWindow().getDecorView();
        if (!(decor instanceof FrameLayout)) return;
        final FrameLayout root = (FrameLayout) decor;

        // Check if already attached
        View existing = root.findViewWithTag("TEXTMORPH_INAPP_WAND");
        if (existing != null) {
            existing.setVisibility(hideFloatingPill ? View.GONE : View.VISIBLE);
            updateWandBadgeAppearance(activity);
            return;
        }

        final float density = activity.getResources().getDisplayMetrics().density;
        final TextView pill = new TextView(activity);
        pill.setTag("TEXTMORPH_INAPP_WAND");
        pill.setTag(WAND_VIEW_TAG_ID, Boolean.TRUE);
        pill.setText(tapToEditArmed ? "🎯 Tap Any Text..." : "🪄");
        pill.setTextColor(0xFFF8FAFC);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        pill.setTypeface(Typeface.DEFAULT_BOLD);
        pill.setGravity(Gravity.CENTER);
        int padH = Math.round(11 * density);
        int padV = Math.round(7 * density);
        pill.setPadding(padH, padV, padH, padV);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xDD0F172A);
        bg.setCornerRadius(22 * density);
        bg.setStroke(Math.round(1.5f * density), 0xFF38BDF8);
        pill.setBackground(bg);
        pill.setElevation(16 * density);

        final FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP | Gravity.START
        );
        lp.leftMargin = activity.getResources().getDisplayMetrics().widthPixels - Math.round(64 * density);
        lp.topMargin = Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.25f);

        pill.setOnTouchListener(new View.OnTouchListener() {
            private int startLeft, startTop;
            private float downRawX, downRawY;
            private boolean dragged;

            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        startLeft = lp.leftMargin;
                        startTop = lp.topMargin;
                        downRawX = ev.getRawX();
                        downRawY = ev.getRawY();
                        dragged = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int dx = Math.round(ev.getRawX() - downRawX);
                        int dy = Math.round(ev.getRawY() - downRawY);
                        if (Math.abs(dx) > 8 || Math.abs(dy) > 8) {
                            dragged = true;
                        }
                        lp.leftMargin = Math.max(0, startLeft + dx);
                        lp.topMargin = Math.max(40, startTop + dy);
                        v.setLayoutParams(lp);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!dragged) {
                            showInAppMainMenu(activity);
                        }
                        return true;
                }
                return false;
            }
        });

        pill.setVisibility(hideFloatingPill ? View.GONE : View.VISIBLE);
        root.addView(pill, lp);
    }

    private void updateWandBadgeAppearance(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        View decor = activity.getWindow().getDecorView();
        if (decor == null) return;
        View v = decor.findViewWithTag("TEXTMORPH_INAPP_WAND");
        if (v instanceof TextView) {
            TextView pill = (TextView) v;
            pill.setVisibility(hideFloatingPill ? View.GONE : View.VISIBLE);
            pill.setText(tapToEditArmed ? "🎯 Tap Text" : "🪄");
            float density = activity.getResources().getDisplayMetrics().density;
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(tapToEditArmed ? 0xEE0284C7 : 0xDD0F172A);
            bg.setCornerRadius(22 * density);
            bg.setStroke(Math.round(1.5f * density), tapToEditArmed ? 0xFFFDE047 : 0xFF38BDF8);
            pill.setBackground(bg);
        }
    }

    private TextView findSmallestTextViewAt(View root, int rawX, int rawY) {
        if (root == null || root.getVisibility() != View.VISIBLE) return null;
        if ("TEXTMORPH_INAPP_WAND".equals(root.getTag())) return null;

        TextView best = null;
        long bestArea = Long.MAX_VALUE;

        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = vg.getChildCount() - 1; i >= 0; i--) {
                TextView childMatch = findSmallestTextViewAt(vg.getChildAt(i), rawX, rawY);
                if (childMatch != null) {
                    int w = childMatch.getWidth();
                    int h = childMatch.getHeight();
                    long area = (long) w * (long) h;
                    if (area > 0 && area < bestArea) {
                        bestArea = area;
                        best = childMatch;
                    }
                }
            }
        }

        if (best != null) return best;

        if (root instanceof TextView) {
            TextView tv = (TextView) root;
            CharSequence text = tv.getText();
            if (text != null && text.length() > 0) {
                int[] loc = new int[2];
                tv.getLocationOnScreen(loc);
                Rect r = new Rect(loc[0], loc[1], loc[0] + tv.getWidth(), loc[1] + tv.getHeight());
                if (r.contains(rawX, rawY)) {
                    return tv;
                }
            }
        }
        return null;
    }

    public void refreshAllTextViewsInActivity(Activity activity) {
        if (activity == null || activity.getWindow() == null) return;
        View decor = activity.getWindow().getDecorView();
        refreshViewTree(decor);
    }

    private void refreshViewTree(View view) {
        if (view == null) return;
        if ("TEXTMORPH_INAPP_WAND".equals(view.getTag())) return;
        if (Boolean.TRUE.equals(view.getTag(WAND_VIEW_TAG_ID))) return;

        if (view instanceof TextView && !(view instanceof EditText)) {
            TextView tv = (TextView) view;
            CharSequence baseText = null;
            synchronized (originalTextMap) {
                baseText = originalTextMap.get(tv);
            }
            if (baseText == null) {
                baseText = tv.getText();
                if (baseText != null) {
                    synchronized (originalTextMap) {
                        originalTextMap.put(tv, baseText);
                    }
                }
            }
            if (baseText != null) {
                tv.setText(baseText);
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            for (int i = 0; i < vg.getChildCount(); i++) {
                refreshViewTree(vg.getChildAt(i));
            }
        }
    }

    private void showInAppMainMenu(final Activity activity) {
        final float density = activity.getResources().getDisplayMetrics().density;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout col = new LinearLayout(activity);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(18 * density);
        col.setPadding(pad, pad, pad, pad);
        col.setBackgroundColor(0xFF0F172A);

        TextView title = createProtectedTextView(activity, "🪄 TextMorph Native Hook");
        title.setTextColor(0xFF38BDF8);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        TextView sub = createProtectedTextView(activity,
                "Running natively inside " + currentPackageName + " • Zero Scroll Lag (120Hz)");
        sub.setTextColor(0xFF34D399);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        sub.setPadding(0, Math.round(2 * density), 0, Math.round(12 * density));
        col.addView(sub);

        scroll.addView(col);
        final AlertDialog dialog = builder.setView(scroll).create();

        // 1. Tap any text on screen button
        Button tapEditBtn = createProtectedButton(activity, "🎯 Tap Any Text on Screen to Replace It", 0xFF38BDF8, 0xFF0F172A);
        tapEditBtn.setOnClickListener(v -> {
            dialog.dismiss();
            tapToEditArmed = true;
            updateWandBadgeAppearance(activity);
            Toast.makeText(activity, "🎯 Tap any text on the screen now to replace it!", Toast.LENGTH_SHORT).show();
        });
        col.addView(tapEditBtn);

        // 2. Add New Rule button
        Button addRuleBtn = createProtectedButton(activity, "➕ Add Find & Replace Rule Manually", 0xFF10B981, 0xFF052E16);
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        addLp.topMargin = Math.round(8 * density);
        addRuleBtn.setLayoutParams(addLp);
        addRuleBtn.setOnClickListener(v -> {
            dialog.dismiss();
            showRuleDialog(activity, null, "");
        });
        col.addView(addRuleBtn);

        // 3. Toggle Floating Pill visibility (3-finger tap always works!)
        CheckBox hidePillCheck = new CheckBox(activity);
        hidePillCheck.setTag(WAND_VIEW_TAG_ID, Boolean.TRUE);
        hidePillCheck.setText("Hide floating 🪄 button (Tip: Tap screen with 3 fingers anytime to open this menu!)");
        hidePillCheck.setTextColor(0xFFE2E8F0);
        hidePillCheck.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        hidePillCheck.setChecked(hideFloatingPill);
        hidePillCheck.setPadding(0, Math.round(10 * density), 0, Math.round(6 * density));
        hidePillCheck.setOnCheckedChangeListener((btn, isChecked) -> {
            hideFloatingPill = isChecked;
            saveLocalRules(activity);
            updateWandBadgeAppearance(activity);
            if (isChecked) {
                Toast.makeText(activity, "🪄 hidden! Tap with 3 fingers anytime to open TextMorph.", Toast.LENGTH_LONG).show();
            }
        });
        col.addView(hidePillCheck);

        // 4. List of active rules in this app
        TextView rulesHeader = createProtectedTextView(activity,
                "Active Rules (" + activeRules.size() + "):");
        rulesHeader.setTextColor(0xFFCBD5E1);
        rulesHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        rulesHeader.setTypeface(Typeface.DEFAULT_BOLD);
        rulesHeader.setPadding(0, Math.round(10 * density), 0, Math.round(6 * density));
        col.addView(rulesHeader);

        if (activeRules.isEmpty()) {
            TextView empty = createProtectedTextView(activity,
                    "No rules yet. Tap '🎯 Tap Any Text on Screen' above and tap any text in this app!");
            empty.setTextColor(0xFF94A3B8);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            col.addView(empty);
        } else {
            for (final RuleModel r : activeRules) {
                LinearLayout row = new LinearLayout(activity);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                int rPad = Math.round(10 * density);
                row.setPadding(rPad, rPad, rPad, rPad);
                GradientDrawable rowBg = new GradientDrawable();
                rowBg.setColor(0xFF1E293B);
                rowBg.setCornerRadius(10 * density);
                row.setBackground(rowBg);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                );
                rowLp.bottomMargin = Math.round(6 * density);
                row.setLayoutParams(rowLp);

                TextView summary = createProtectedTextView(activity,
                        "\"" + r.findText + "\" ➜ \"" + r.replaceText + "\"");
                summary.setTextColor(0xFFF8FAFC);
                summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
                row.addView(summary, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                Button delBtn = createProtectedButton(activity, "✕", 0xFF7F1D1D, 0xFFFCA5A5);
                LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(
                        Math.round(38 * density), Math.round(36 * density)
                );
                delLp.leftMargin = Math.round(6 * density);
                delBtn.setLayoutParams(delLp);
                delBtn.setPadding(0, 0, 0, 0);
                delBtn.setOnClickListener(v -> {
                    activeRules.remove(r);
                    saveLocalRules(activity);
                    refreshAllTextViewsInActivity(activity);
                    dialog.dismiss();
                    showInAppMainMenu(activity);
                });
                row.addView(delBtn);
                col.addView(row);
            }
        }

        Button closeBtn = createProtectedButton(activity, "Close", 0xFF334155, 0xFFE2E8F0);
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        closeLp.topMargin = Math.round(10 * density);
        closeBtn.setLayoutParams(closeLp);
        closeBtn.setOnClickListener(v -> dialog.dismiss());
        col.addView(closeBtn);

        dialog.show();
    }

    private void showLiveEditDialogForTextView(final Activity activity, final TextView targetTv) {
        CharSequence rawOrig = null;
        synchronized (originalTextMap) {
            rawOrig = originalTextMap.get(targetTv);
        }
        if (rawOrig == null) {
            rawOrig = targetTv.getText();
        }
        String initialText = rawOrig != null ? rawOrig.toString() : "";
        showRuleDialog(activity, targetTv, initialText);
    }

    private void showRuleDialog(final Activity activity, final TextView targetTv, String prefillFind) {
        final float density = activity.getResources().getDisplayMetrics().density;
        AlertDialog.Builder builder = new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert);

        LinearLayout form = new LinearLayout(activity);
        form.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(18 * density);
        form.setPadding(pad, pad, pad, pad);
        form.setBackgroundColor(0xFF0F172A);

        TextView header = createProtectedTextView(activity, "🪄 Replace Text Natively");
        header.setTextColor(0xFF38BDF8);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        form.addView(header);

        TextView findLbl = createProtectedTextView(activity, "Find Text (keep full phrase or trim to 1 word):");
        findLbl.setTextColor(0xFFCBD5E1);
        findLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        findLbl.setPadding(0, Math.round(10 * density), 0, Math.round(4 * density));
        form.addView(findLbl);

        final EditText findEt = new EditText(activity);
        findEt.setTag(WAND_VIEW_TAG_ID, Boolean.TRUE);
        findEt.setText(prefillFind);
        findEt.setTextColor(0xFFF8FAFC);
        findEt.setHintTextColor(0xFF64748B);
        findEt.setHint("Text to replace...");
        form.addView(findEt);

        TextView repLbl = createProtectedTextView(activity, "Replace With:");
        repLbl.setTextColor(0xFFCBD5E1);
        repLbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        repLbl.setPadding(0, Math.round(10 * density), 0, Math.round(4 * density));
        form.addView(repLbl);

        final EditText repEt = new EditText(activity);
        repEt.setTag(WAND_VIEW_TAG_ID, Boolean.TRUE);
        repEt.setText(prefillFind);
        repEt.setTextColor(0xFFF8FAFC);
        repEt.setHintTextColor(0xFF64748B);
        repEt.setHint("New text...");
        repEt.selectAll();
        form.addView(repEt);

        builder.setView(form);
        builder.setPositiveButton("Save & Replace Now", (d, w) -> {
            String f = findEt.getText().toString();
            String r = repEt.getText().toString();
            if (TextUtils.isEmpty(f)) return;

            RuleModel rule = new RuleModel();
            rule.findText = f;
            rule.replaceText = r;
            rule.targetPackage = "*";
            rule.enabled = true;
            activeRules.add(0, rule);
            saveLocalRules(activity);

            if (targetTv != null) {
                CharSequence orig = null;
                synchronized (originalTextMap) {
                    orig = originalTextMap.get(targetTv);
                }
                targetTv.setText(orig != null ? orig : f);
            }
            refreshAllTextViewsInActivity(activity);
            Toast.makeText(activity, "Replaced natively!", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private TextView createProtectedTextView(Context ctx, String text) {
        TextView tv = new TextView(ctx);
        tv.setTag(WAND_VIEW_TAG_ID, Boolean.TRUE);
        tv.setText(text);
        return tv;
    }

    private Button createProtectedButton(Context ctx, String text, int bgColor, int textColor) {
        float density = ctx.getResources().getDisplayMetrics().density;
        Button b = new Button(ctx);
        b.setTag(WAND_VIEW_TAG_ID, Boolean.TRUE);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(10 * density);
        b.setBackground(bg);
        return b;
    }
}
