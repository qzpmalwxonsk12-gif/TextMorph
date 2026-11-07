package com.antigravity.textmorph;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RuleModel {
    public static final int MATCH_SUBSTRING = 0;
    public static final int MATCH_EXACT = 1;
    public static final int MATCH_REGEX = 2;

    public static final int ALIGN_LEFT = 0;
    public static final int ALIGN_CENTER = 1;
    public static final int ALIGN_RIGHT = 2;

    public String id;
    public String findText;
    public String replaceText;
    public String targetPackage; // "*" for all apps, or package substring/exact name
    public int matchMode;        // 0 = Substring, 1 = Exact, 2 = Regex
    public boolean caseSensitive;
    public boolean autoColor;    // Chameleon auto-sampling from screen pixels
    public int customBgColor;
    public int customTextColor;
    public float fontSizeScale;  // 1.0f default
    public boolean forceBold;
    public int textAlign;        // 0 = Left/Auto, 1 = Center, 2 = Right
    public int offsetXDp;
    public int offsetYDp;
    public int paddingDp;
    public boolean enabled;

    public RuleModel() {
        this.id = UUID.randomUUID().toString();
        this.findText = "";
        this.replaceText = "";
        this.targetPackage = "*";
        this.matchMode = MATCH_SUBSTRING;
        this.caseSensitive = false;
        this.autoColor = true;
        this.customBgColor = 0xFF121212;
        this.customTextColor = 0xFFFFFFFF;
        this.fontSizeScale = 1.0f;
        this.forceBold = false;
        this.textAlign = ALIGN_LEFT;
        this.offsetXDp = 0;
        this.offsetYDp = 0;
        this.paddingDp = 1;
        this.enabled = true;
    }

    public boolean appliesToPackage(String pkg) {
        if (!enabled) return false;
        if (findText == null || findText.isEmpty()) return false;
        if (targetPackage == null || targetPackage.trim().isEmpty() || targetPackage.equals("*")) {
            return true;
        }
        if (pkg == null) return false;
        String filter = targetPackage.trim().toLowerCase();
        return pkg.toLowerCase().contains(filter);
    }

    /**
     * Returns transformed text if this rule matches input, or null if no match.
     */
    public String applyToText(String input) {
        if (!enabled || input == null || input.isEmpty() || findText == null || findText.isEmpty()) {
            return null;
        }
        try {
            if (matchMode == MATCH_EXACT) {
                boolean matched = caseSensitive
                        ? input.equals(findText)
                        : input.equalsIgnoreCase(findText) || input.trim().equalsIgnoreCase(findText.trim());
                return matched ? replaceText : null;
            } else if (matchMode == MATCH_REGEX) {
                int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                Pattern pattern = Pattern.compile(findText, flags);
                Matcher matcher = pattern.matcher(input);
                if (matcher.find()) {
                    return matcher.replaceAll(replaceText != null ? replaceText : "");
                }
                return null;
            } else {
                // MATCH_SUBSTRING (default)
                if (caseSensitive) {
                    if (input.contains(findText)) {
                        return input.replace(findText, replaceText != null ? replaceText : "");
                    }
                } else {
                    int flags = Pattern.LITERAL | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                    Pattern pattern = Pattern.compile(findText, flags);
                    Matcher matcher = pattern.matcher(input);
                    if (matcher.find()) {
                        return matcher.replaceAll(Matcher.quoteReplacement(replaceText != null ? replaceText : ""));
                    }
                }
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    public JSONObject toJson() throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put("id", id);
        obj.put("findText", findText);
        obj.put("replaceText", replaceText);
        obj.put("targetPackage", targetPackage);
        obj.put("matchMode", matchMode);
        obj.put("caseSensitive", caseSensitive);
        obj.put("autoColor", autoColor);
        obj.put("customBgColor", customBgColor);
        obj.put("customTextColor", customTextColor);
        obj.put("fontSizeScale", (double) fontSizeScale);
        obj.put("forceBold", forceBold);
        obj.put("textAlign", textAlign);
        obj.put("offsetXDp", offsetXDp);
        obj.put("offsetYDp", offsetYDp);
        obj.put("paddingDp", paddingDp);
        obj.put("enabled", enabled);
        return obj;
    }

    public static RuleModel fromJson(JSONObject obj) {
        RuleModel r = new RuleModel();
        r.id = obj.optString("id", UUID.randomUUID().toString());
        r.findText = obj.optString("findText", "");
        r.replaceText = obj.optString("replaceText", "");
        r.targetPackage = obj.optString("targetPackage", "*");
        r.matchMode = obj.optInt("matchMode", MATCH_SUBSTRING);
        r.caseSensitive = obj.optBoolean("caseSensitive", false);
        r.autoColor = obj.optBoolean("autoColor", true);
        r.customBgColor = obj.optInt("customBgColor", 0xFF121212);
        r.customTextColor = obj.optInt("customTextColor", 0xFFFFFFFF);
        r.fontSizeScale = (float) obj.optDouble("fontSizeScale", 1.0);
        r.forceBold = obj.optBoolean("forceBold", false);
        r.textAlign = obj.optInt("textAlign", ALIGN_LEFT);
        r.offsetXDp = obj.optInt("offsetXDp", 0);
        r.offsetYDp = obj.optInt("offsetYDp", 0);
        r.paddingDp = obj.optInt("paddingDp", 1);
        r.enabled = obj.optBoolean("enabled", true);
        return r;
    }
}
