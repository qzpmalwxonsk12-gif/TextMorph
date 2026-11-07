package com.antigravity.textmorph;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {

    private float density;
    private TextView statusBadgeText;
    private TextView statusSubText;
    private LinearLayout setupCard;
    private LinearLayout rulesContainer;
    private Switch masterSwitch;
    private Switch wandSwitch;
    private Switch sandboxSwitch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        Window win = getWindow();
        if (win != null) {
            win.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            win.setStatusBarColor(0xFF0B1120);
            win.setNavigationBarColor(0xFF0B1120);
        }

        density = getResources().getDisplayMetrics().density;
        buildUi();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshServiceStatus();
        refreshRulesList();
    }

    private int dp(float value) {
        return Math.round(value * density);
    }

    private void buildUi() {
        ScrollView rootScroll = new ScrollView(this);
        rootScroll.setBackgroundColor(0xFF0B1120);
        rootScroll.setFillViewport(true);

        LinearLayout mainCol = new LinearLayout(this);
        mainCol.setOrientation(LinearLayout.VERTICAL);
        mainCol.setPadding(dp(16), dp(20), dp(16), dp(36));

        // 1. Header Banner
        LinearLayout headerRow = new LinearLayout(this);
        headerRow.setOrientation(LinearLayout.VERTICAL);
        headerRow.setPadding(dp(4), dp(4), dp(4), dp(14));

        TextView appTitle = new TextView(this);
        appTitle.setText("TextMorph");
        appTitle.setTextColor(0xFFF8FAFC);
        appTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26f);
        appTitle.setTypeface(Typeface.DEFAULT_BOLD);
        headerRow.addView(appTitle);

        TextView appSub = new TextView(this);
        appSub.setText("Universal Client-Side Visual Text Replacer • No Root Required");
        appSub.setTextColor(0xFF38BDF8);
        appSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        headerRow.addView(appSub);

        mainCol.addView(headerRow);

        // 2. Service Status & Setup Card
        setupCard = createCardLayout(0xFF1E293B, 0xFF334155);
        statusBadgeText = new TextView(this);
        statusBadgeText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        statusBadgeText.setTypeface(Typeface.DEFAULT_BOLD);
        setupCard.addView(statusBadgeText);

        statusSubText = new TextView(this);
        statusSubText.setTextColor(0xFFCBD5E1);
        statusSubText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        statusSubText.setPadding(0, dp(4), 0, dp(12));
        setupCard.addView(statusSubText);

        LinearLayout setupBtnRow = new LinearLayout(this);
        setupBtnRow.setOrientation(LinearLayout.VERTICAL);

        Button enableAccBtn = createStyledButton(
                "1. Open Accessibility Settings (Turn On TextMorph)",
                0xFF38BDF8, 0xFF0F172A, true
        );
        enableAccBtn.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
                startActivity(intent);
                Toast.makeText(this, "Tap 'Installed apps' or 'Downloaded apps' → TextMorph", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(this, "Could not open Accessibility settings", Toast.LENGTH_SHORT).show();
            }
        });
        setupBtnRow.addView(enableAccBtn);

        Button unlockRestrictedBtn = createStyledButton(
                "OnePlus / Android 14+ Tip: Unlock 'Restricted Setting'",
                0xFF334155, 0xFFE2E8F0, false
        );
        LinearLayout.LayoutParams unlockLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        unlockLp.topMargin = dp(8);
        unlockRestrictedBtn.setLayoutParams(unlockLp);
        unlockRestrictedBtn.setOnClickListener(v -> showOnePlusRestrictedGuide());
        setupBtnRow.addView(unlockRestrictedBtn);

        setupCard.addView(setupBtnRow);
        mainCol.addView(setupCard);

        // 3. Controls & Floating Magic Wand Card
        LinearLayout controlsCard = createCardLayout(0xFF1E293B, 0xFF334155);

        TextView controlsTitle = new TextView(this);
        controlsTitle.setText("⚡ Engine & Live Inspector Controls");
        controlsTitle.setTextColor(0xFFF8FAFC);
        controlsTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        controlsTitle.setTypeface(Typeface.DEFAULT_BOLD);
        controlsTitle.setPadding(0, 0, 0, dp(8));
        controlsCard.addView(controlsTitle);

        masterSwitch = createToggleRow(
                controlsCard,
                "Master Text Replacer Engine",
                "Automatically replaces matching text across apps in real time",
                RuleStorage.isMasterEnabled(this),
                isChecked -> RuleStorage.setMasterEnabled(this, isChecked)
        );

        wandSwitch = createToggleRow(
                controlsCard,
                "Floating '🪄 Edit Text' Button",
                "Draggable button on screen: tap it inside any app to tap & edit any text live",
                RuleStorage.isFloatingWandEnabled(this),
                isChecked -> RuleStorage.setFloatingWandEnabled(this, isChecked)
        );

        sandboxSwitch = createToggleRow(
                controlsCard,
                "Enable Live Test Sandbox Below",
                "Allows TextMorph to morph the sample preview card right inside this screen",
                RuleStorage.isSandboxEnabled(this),
                isChecked -> RuleStorage.setSandboxEnabled(this, isChecked)
        );

        Button inspectNowBtn = createStyledButton(
                "🪄 Test 'Tap Any Text to Edit' On This Screen Now",
                0xFFA855F7, 0xFFFFFFFF, true
        );
        LinearLayout.LayoutParams inspectLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        inspectLp.topMargin = dp(10);
        inspectNowBtn.setLayoutParams(inspectLp);
        inspectNowBtn.setOnClickListener(v -> {
            if (!isAccessibilityServiceEnabled()) {
                Toast.makeText(this, "Enable TextMorph in Accessibility Settings first!", Toast.LENGTH_LONG).show();
                return;
            }
            TextMorphService.triggerLiveInspectorFromApp();
        });
        controlsCard.addView(inspectNowBtn);

        mainCol.addView(controlsCard);

        // 4. Built-in Live Test Sandbox Card
        LinearLayout sandboxCard = createCardLayout(0xFF0F172A, 0xFF0284C7);
        sandboxCard.setContentDescription("TEXTMORPH_SANDBOX_CONTAINER");

        TextView sbHeader = new TextView(this);
        sbHeader.setText("🧪 LIVE TEST SANDBOX (Preview Area)");
        sbHeader.setTextColor(0xFF38BDF8);
        sbHeader.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        sbHeader.setTypeface(Typeface.DEFAULT_BOLD);
        sandboxCard.addView(sbHeader);

        TextView sbHint = new TextView(this);
        sbHint.setText("Once TextMorph is enabled, the sample lines below are morphed live by your rules (or tap '🪄 Edit Text' to tap and edit them!):");
        sbHint.setTextColor(0xFF94A3B8);
        sbHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        sbHint.setPadding(0, dp(2), 0, dp(10));
        sandboxCard.addView(sbHint);

        // Sample inner mock card with realistic app texts
        LinearLayout mockAppCard = new LinearLayout(this);
        mockAppCard.setOrientation(LinearLayout.VERTICAL);
        mockAppCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable mockBg = new GradientDrawable();
        mockBg.setColor(0xFF18181B);
        mockBg.setCornerRadius(dp(12));
        mockBg.setStroke(dp(1), 0xFF27272A);
        mockAppCard.setBackground(mockBg);

        TextView sample1 = new TextView(this);
        sample1.setText("Sample Balance: $0.00");
        sample1.setTextColor(0xFF34D399);
        sample1.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        sample1.setTypeface(Typeface.DEFAULT_BOLD);
        mockAppCard.addView(sample1);

        TextView sample2 = new TextView(this);
        sample2.setText("Account Tier: Free Member");
        sample2.setTextColor(0xFFF8FAFC);
        sample2.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        sample2.setPadding(0, dp(6), 0, dp(6));
        mockAppCard.addView(sample2);

        TextView sample3 = new TextView(this);
        sample3.setText("Username: @guest_user_12r");
        sample3.setTextColor(0xFF94A3B8);
        sample3.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        mockAppCard.addView(sample3);

        sandboxCard.addView(mockAppCard);
        mainCol.addView(sandboxCard);

        // 5. Rules Header + Add Rule / Backup Row
        LinearLayout rulesHeaderRow = new LinearLayout(this);
        rulesHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
        rulesHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
        rulesHeaderRow.setPadding(dp(2), dp(6), dp(2), dp(10));

        TextView rulesTitle = new TextView(this);
        rulesTitle.setText("📋 Text Replacement Rules");
        rulesTitle.setTextColor(0xFFF8FAFC);
        rulesTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        rulesTitle.setTypeface(Typeface.DEFAULT_BOLD);
        rulesHeaderRow.addView(rulesTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button addRuleBtn = createStyledButton("+ New Rule", 0xFF10B981, 0xFF052E16, true);
        addRuleBtn.setOnClickListener(v -> showRuleEditorDialog(null));
        rulesHeaderRow.addView(addRuleBtn);

        mainCol.addView(rulesHeaderRow);

        // Rules List Container
        rulesContainer = new LinearLayout(this);
        rulesContainer.setOrientation(LinearLayout.VERTICAL);
        mainCol.addView(rulesContainer);

        // Export / Import Footer
        LinearLayout backupRow = new LinearLayout(this);
        backupRow.setOrientation(LinearLayout.HORIZONTAL);
        backupRow.setGravity(Gravity.CENTER);
        backupRow.setPadding(0, dp(12), 0, dp(16));

        Button exportBtn = createStyledButton("Export Rules to Clipboard", 0xFF1E293B, 0xFF94A3B8, false);
        exportBtn.setOnClickListener(v -> exportRulesToClipboard());

        Button importBtn = createStyledButton("Import from Clipboard", 0xFF1E293B, 0xFF94A3B8, false);
        LinearLayout.LayoutParams impLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        impLp.leftMargin = dp(10);
        importBtn.setLayoutParams(impLp);
        importBtn.setOnClickListener(v -> importRulesFromClipboard());

        backupRow.addView(exportBtn);
        backupRow.addView(importBtn);
        mainCol.addView(backupRow);

        rootScroll.addView(mainCol);
        setContentView(rootScroll);
    }

    private void showOnePlusRestrictedGuide() {
        AlertDialog.Builder b = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        b.setTitle("Unlock 'Restricted Setting' (OnePlus / Android 14+)");
        b.setMessage("On Android 14 & 15, sideloaded apps have their Accessibility switch locked by default until you allow it once:\n\n"
                + "1. Tap 'Open App Info' below.\n"
                + "2. Tap the ⋮ (three dots) in the top-right corner of the App Info screen.\n"
                + "3. Tap 'Allow restricted settings' and verify with your fingerprint/PIN.\n"
                + "4. Now go to Accessibility Settings → Installed Apps → TextMorph and turn it ON!");
        b.setPositiveButton("Open App Info", (d, w) -> {
            try {
                Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
                intent.setData(Uri.fromParts("package", getPackageName(), null));
                startActivity(intent);
            } catch (Exception ignored) {}
        });
        b.setNegativeButton("Close", null);
        b.show();
    }

    private void refreshServiceStatus() {
        boolean enabled = isAccessibilityServiceEnabled();
        boolean running = TextMorphService.isRunning();

        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));

        if (enabled && running) {
            bg.setColor(0xFF064E3B);
            bg.setStroke(dp(1.5f), 0xFF10B981);
            setupCard.setBackground(bg);
            statusBadgeText.setText("🟢 ACTIVE — TextMorph Engine is Running");
            statusBadgeText.setTextColor(0xFF34D399);
            statusSubText.setText("Your text replacement rules and floating '🪄 Edit Text' wand are active across all apps.");
        } else if (enabled) {
            bg.setColor(0xFF1E293B);
            bg.setStroke(dp(1.5f), 0xFF38BDF8);
            setupCard.setBackground(bg);
            statusBadgeText.setText("🔵 ENABLED — Starting Service...");
            statusBadgeText.setTextColor(0xFF38BDF8);
            statusSubText.setText("Accessibility permission is enabled. If overlays don't appear, toggle TextMorph off and back on in Accessibility settings.");
        } else {
            bg.setColor(0xFF27171A);
            bg.setStroke(dp(1.5f), 0xFFF59E0B);
            setupCard.setBackground(bg);
            statusBadgeText.setText("🟡 SETUP NEEDED — Enable Accessibility Service");
            statusBadgeText.setTextColor(0xFFFBBF24);
            statusSubText.setText("Tap Button 1 below to turn on TextMorph in Accessibility Settings. (If Android says 'Restricted setting', tap Button 2 first!)");
        }
    }

    private boolean isAccessibilityServiceEnabled() {
        ComponentName expected = new ComponentName(this, TextMorphService.class);
        String enabledServices = Settings.Secure.getString(
                getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        );
        if (enabledServices == null) return false;
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(enabledServices);
        while (splitter.hasNext()) {
            String compStr = splitter.next();
            ComponentName enabled = ComponentName.unflattenFromString(compStr);
            if (enabled != null && enabled.equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private void refreshRulesList() {
        if (rulesContainer == null) return;
        rulesContainer.removeAllViews();

        List<RuleModel> rules = RuleStorage.loadRules(this);
        if (rules.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No replacement rules yet. Tap '+ New Rule' above or use the '🪄 Edit Text' floating button inside any app!");
            empty.setTextColor(0xFF94A3B8);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            empty.setPadding(dp(12), dp(20), dp(12), dp(20));
            empty.setGravity(Gravity.CENTER);
            rulesContainer.addView(empty);
            return;
        }

        for (RuleModel rule : rules) {
            rulesContainer.addView(createRuleItemView(rule));
        }
    }

    private View createRuleItemView(RuleModel rule) {
        LinearLayout card = createCardLayout(0xFF1E293B, rule.enabled ? 0xFF334155 : 0xFF1E293B);

        // Top row: Find -> Replace + Enable Switch
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView findTv = new TextView(this);
        findTv.setText("Find: \"" + rule.findText + "\"");
        findTv.setTextColor(0xFF94A3B8);
        findTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        textCol.addView(findTv);

        TextView repTv = new TextView(this);
        repTv.setText("➜ \"" + rule.replaceText + "\"");
        repTv.setTextColor(rule.enabled ? 0xFF38BDF8 : 0xFF64748B);
        repTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
        repTv.setTypeface(Typeface.DEFAULT_BOLD);
        repTv.setPadding(0, dp(2), 0, dp(4));
        textCol.addView(repTv);

        topRow.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch toggle = new Switch(this);
        toggle.setChecked(rule.enabled);
        toggle.setOnCheckedChangeListener((btn, isChecked) -> {
            rule.enabled = isChecked;
            RuleStorage.addOrUpdateRule(this, rule);
            refreshRulesList();
        });
        topRow.addView(toggle);
        card.addView(topRow);

        // Metadata badges + Edit / Delete row
        LinearLayout bottomRow = new LinearLayout(this);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        bottomRow.setGravity(Gravity.CENTER_VERTICAL);
        bottomRow.setPadding(0, dp(6), 0, 0);

        String modeLabel = rule.matchMode == RuleModel.MATCH_EXACT ? "Exact"
                : (rule.matchMode == RuleModel.MATCH_REGEX ? "Regex" : "Smart Substring");
        String scopeLabel = ("*".equals(rule.targetPackage) || rule.targetPackage.isEmpty())
                ? "All Apps" : rule.targetPackage;
        String colorLabel = rule.autoColor ? "Chameleon Auto-Color"
                : String.format(Locale.US, "Custom #%06X", (0xFFFFFF & rule.customBgColor));

        TextView metaTv = new TextView(this);
        metaTv.setText(scopeLabel + " • " + modeLabel + " • " + colorLabel);
        metaTv.setTextColor(0xFF64748B);
        metaTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        bottomRow.addView(metaTv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Button editBtn = createSmallActionButton("Edit", 0xFF334155, 0xFFE2E8F0);
        editBtn.setOnClickListener(v -> showRuleEditorDialog(rule));
        bottomRow.addView(editBtn);

        Button delBtn = createSmallActionButton("Delete", 0xFF451A1C, 0xFFF87171);
        LinearLayout.LayoutParams delLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        delLp.leftMargin = dp(8);
        delBtn.setLayoutParams(delLp);
        delBtn.setOnClickListener(v -> {
            RuleStorage.deleteRule(this, rule.id);
            refreshRulesList();
        });
        bottomRow.addView(delBtn);

        card.addView(bottomRow);
        return card;
    }

    private void showRuleEditorDialog(RuleModel existing) {
        final RuleModel editing = existing != null ? existing : new RuleModel();

        AlertDialog.Builder builder = new AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert);
        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(16), dp(20), dp(8));
        form.setBackgroundColor(0xFF0F172A);

        TextView header = new TextView(this);
        header.setText(existing != null ? "Edit Replacement Rule" : "New Text Replacement Rule");
        header.setTextColor(0xFF38BDF8);
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f);
        header.setTypeface(Typeface.DEFAULT_BOLD);
        header.setPadding(0, 0, 0, dp(12));
        form.addView(header);

        EditText findEt = addFormField(form, "Find Text (word, phrase, or number):", editing.findText, "e.g. $0.00 or Subscribe");
        EditText replaceEt = addFormField(form, "Replace With:", editing.replaceText, "e.g. $99,450.00 or Subscribed ✓");
        EditText pkgEt = addFormField(form, "Target App ('*' for All Apps, or app name like 'instagram'):", editing.targetPackage, "*");

        // Match mode selector
        TextView matchLabel = new TextView(this);
        matchLabel.setText("Match Mode:");
        matchLabel.setTextColor(0xFFCBD5E1);
        matchLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        matchLabel.setPadding(0, dp(8), 0, dp(4));
        form.addView(matchLabel);

        final int[] selectedMatchMode = new int[]{editing.matchMode};
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        final Button[] modeBtns = new Button[3];
        String[] modeNames = new String[]{"Substring", "Exact Match", "Regex"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            Button b = new Button(this);
            b.setText(modeNames[i]);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) lp.leftMargin = dp(6);
            b.setLayoutParams(lp);
            b.setOnClickListener(v -> {
                selectedMatchMode[0] = idx;
                updateModeButtonStyles(modeBtns, idx);
            });
            modeBtns[i] = b;
            modeRow.addView(b);
        }
        updateModeButtonStyles(modeBtns, selectedMatchMode[0]);
        form.addView(modeRow);

        CheckBox caseCheck = new CheckBox(this);
        caseCheck.setText("Case-Sensitive Match");
        caseCheck.setTextColor(0xFFE2E8F0);
        caseCheck.setChecked(editing.caseSensitive);
        form.addView(caseCheck);

        // Color settings
        CheckBox autoColorCheck = new CheckBox(this);
        autoColorCheck.setText("Chameleon Auto-Color (Automatically samples app background & text color)");
        autoColorCheck.setTextColor(0xFF34D399);
        autoColorCheck.setChecked(editing.autoColor);
        form.addView(autoColorCheck);

        EditText bgHexEt = addFormField(
                form,
                "Fallback / Custom Background Color (HEX):",
                String.format(Locale.US, "#%06X", (0xFFFFFF & editing.customBgColor)),
                "#121212"
        );
        EditText txtHexEt = addFormField(
                form,
                "Fallback / Custom Text Color (HEX):",
                String.format(Locale.US, "#%06X", (0xFFFFFF & editing.customTextColor)),
                "#FFFFFF"
        );

        // Quick Color Presets Row
        HorizontalScrollView presetScroll = new HorizontalScrollView(this);
        LinearLayout presetRow = new LinearLayout(this);
        presetRow.setOrientation(LinearLayout.HORIZONTAL);
        presetRow.setPadding(0, dp(4), 0, dp(8));

        addColorPresetChip(presetRow, "Dark (#121212)", "#121212", "#FFFFFF", bgHexEt, txtHexEt, autoColorCheck);
        addColorPresetChip(presetRow, "AMOLED Black", "#000000", "#FFFFFF", bgHexEt, txtHexEt, autoColorCheck);
        addColorPresetChip(presetRow, "Light White", "#FFFFFF", "#111827", bgHexEt, txtHexEt, autoColorCheck);
        addColorPresetChip(presetRow, "Light Gray", "#F3F4F6", "#111827", bgHexEt, txtHexEt, autoColorCheck);
        presetScroll.addView(presetRow);
        form.addView(presetScroll);

        // Font Scale & Bold
        EditText scaleEt = addFormField(
                form,
                "Font Size Multiplier (1.0 = auto-fit, e.g. 0.9 or 1.15):",
                String.format(Locale.US, "%.2f", editing.fontSizeScale),
                "1.0"
        );

        CheckBox boldCheck = new CheckBox(this);
        boldCheck.setText("Force Bold Text");
        boldCheck.setTextColor(0xFFE2E8F0);
        boldCheck.setChecked(editing.forceBold);
        form.addView(boldCheck);

        scroll.addView(form);
        builder.setView(scroll);

        builder.setPositiveButton("Save Rule", (dialog, which) -> {
            String findStr = findEt.getText().toString();
            String repStr = replaceEt.getText().toString();
            if (findStr.isEmpty()) {
                Toast.makeText(this, "Find Text cannot be empty", Toast.LENGTH_SHORT).show();
                return;
            }
            editing.findText = findStr;
            editing.replaceText = repStr;
            String pkgStr = pkgEt.getText().toString().trim();
            editing.targetPackage = pkgStr.isEmpty() ? "*" : pkgStr;
            editing.matchMode = selectedMatchMode[0];
            editing.caseSensitive = caseCheck.isChecked();
            editing.autoColor = autoColorCheck.isChecked();
            editing.customBgColor = parseHexColorSafe(bgHexEt.getText().toString(), 0xFF121212);
            editing.customTextColor = parseHexColorSafe(txtHexEt.getText().toString(), 0xFFFFFFFF);
            try {
                float sc = Float.parseFloat(scaleEt.getText().toString().trim());
                editing.fontSizeScale = Math.max(0.5f, Math.min(2.5f, sc));
            } catch (Exception e) {
                editing.fontSizeScale = 1.0f;
            }
            editing.forceBold = boldCheck.isChecked();
            editing.enabled = true;

            RuleStorage.addOrUpdateRule(this, editing);
            refreshRulesList();
            Toast.makeText(this, "Rule saved!", Toast.LENGTH_SHORT).show();
        });
        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void addColorPresetChip(
            LinearLayout row,
            String label,
            String bgHex,
            String txtHex,
            EditText bgEt,
            EditText txtEt,
            CheckBox autoCheck
    ) {
        Button chip = createSmallActionButton(label, 0xFF1E293B, 0xFFCBD5E1);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.rightMargin = dp(6);
        chip.setLayoutParams(lp);
        chip.setOnClickListener(v -> {
            bgEt.setText(bgHex);
            txtEt.setText(txtHex);
            autoCheck.setChecked(false);
        });
        row.addView(chip);
    }

    private void updateModeButtonStyles(Button[] btns, int selectedIdx) {
        for (int i = 0; i < btns.length; i++) {
            GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(10));
            if (i == selectedIdx) {
                bg.setColor(0xFF38BDF8);
                btns[i].setTextColor(0xFF0F172A);
                btns[i].setTypeface(Typeface.DEFAULT_BOLD);
            } else {
                bg.setColor(0xFF1E293B);
                bg.setStroke(dp(1), 0xFF475569);
                btns[i].setTextColor(0xFFCBD5E1);
                btns[i].setTypeface(Typeface.DEFAULT);
            }
            btns[i].setBackground(bg);
        }
    }

    private int parseHexColorSafe(String hex, int fallback) {
        try {
            String clean = hex.trim();
            if (!clean.startsWith("#")) clean = "#" + clean;
            return Color.parseColor(clean) | 0xFF000000;
        } catch (Exception e) {
            return fallback;
        }
    }

    private EditText addFormField(LinearLayout parent, String label, String initial, String hint) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextColor(0xFFCBD5E1);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setPadding(0, dp(8), 0, dp(3));
        parent.addView(tv);

        EditText et = new EditText(this);
        et.setText(initial != null ? initial : "");
        et.setHint(hint);
        et.setHintTextColor(0xFF64748B);
        et.setTextColor(0xFFF8FAFC);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF1E293B);
        bg.setCornerRadius(dp(10));
        bg.setStroke(dp(1), 0xFF475569);
        et.setBackground(bg);
        parent.addView(et);
        return et;
    }

    private interface OnToggleChanged {
        void onToggle(boolean isChecked);
    }

    private Switch createToggleRow(
            LinearLayout parent,
            String title,
            String subtitle,
            boolean initial,
            OnToggleChanged listener
    ) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));

        LinearLayout textCol = new LinearLayout(this);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFFE2E8F0);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        textCol.addView(t);

        TextView s = new TextView(this);
        s.setText(subtitle);
        s.setTextColor(0xFF94A3B8);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        textCol.addView(s);

        row.addView(textCol, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        Switch sw = new Switch(this);
        sw.setChecked(initial);
        sw.setOnCheckedChangeListener((btn, isChecked) -> listener.onToggle(isChecked));
        row.addView(sw);

        parent.addView(row);
        return sw;
    }

    private LinearLayout createCardLayout(int bgColor, int strokeColor) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(16));
        bg.setStroke(dp(1), strokeColor);
        card.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.bottomMargin = dp(14);
        card.setLayoutParams(lp);
        return card;
    }

    private Button createStyledButton(String text, int bgColor, int textColor, boolean bold) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        if (bold) b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(12));
        b.setBackground(bg);
        return b;
    }

    private Button createSmallActionButton(String text, int bgColor, int textColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(textColor);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        b.setMinimumWidth(0);
        b.setMinWidth(0);
        b.setMinimumHeight(0);
        b.setMinHeight(0);
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(bgColor);
        bg.setCornerRadius(dp(8));
        b.setBackground(bg);
        return b;
    }

    private void exportRulesToClipboard() {
        List<RuleModel> rules = RuleStorage.loadRules(this);
        JSONArray arr = new JSONArray();
        for (RuleModel r : rules) {
            try {
                arr.put(r.toJson());
            } catch (Exception ignored) {}
        }
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("TextMorph Rules", arr.toString()));
            Toast.makeText(this, "Exported " + rules.size() + " rules to clipboard!", Toast.LENGTH_SHORT).show();
        }
    }

    private void importRulesFromClipboard() {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip() || cm.getPrimaryClip().getItemCount() == 0) {
            Toast.makeText(this, "Clipboard is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        CharSequence clipText = cm.getPrimaryClip().getItemAt(0).getText();
        if (clipText == null) return;
        try {
            JSONArray arr = new JSONArray(clipText.toString());
            List<RuleModel> imported = new ArrayList<>();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    imported.add(RuleModel.fromJson(obj));
                }
            }
            if (!imported.isEmpty()) {
                RuleStorage.saveRules(this, imported);
                refreshRulesList();
                Toast.makeText(this, "Imported " + imported.size() + " rules!", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Clipboard does not contain valid TextMorph JSON", Toast.LENGTH_SHORT).show();
        }
    }
}
