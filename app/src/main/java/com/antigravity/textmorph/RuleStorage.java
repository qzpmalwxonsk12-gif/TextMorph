package com.antigravity.textmorph;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class RuleStorage {
    private static final String PREF_NAME = "textmorph_prefs";
    private static final String KEY_RULES = "rules_json";
    private static final String KEY_MASTER_ENABLED = "master_enabled";
    private static final String KEY_FLOATING_WAND = "floating_wand_enabled";
    private static final String KEY_SANDBOX_ENABLED = "sandbox_test_enabled";
    private static final String KEY_DIRECT_EDIT_NODES = "direct_edit_nodes";

    public static List<RuleModel> loadRules(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String raw = prefs.getString(KEY_RULES, null);
        List<RuleModel> list = new ArrayList<>();
        if (raw == null) {
            // Seed a sample demo rule so the user can test right away in the built-in Sandbox
            RuleModel demo = new RuleModel();
            demo.findText = "Sample Balance: $0.00";
            demo.replaceText = "Sample Balance: $99,450.00";
            demo.targetPackage = "*";
            demo.autoColor = true;
            list.add(demo);
            saveRules(context, list);
            return list;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj != null) {
                    list.add(RuleModel.fromJson(obj));
                }
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public static void saveRules(Context context, List<RuleModel> rules) {
        JSONArray arr = new JSONArray();
        for (RuleModel r : rules) {
            try {
                arr.put(r.toJson());
            } catch (Exception ignored) {
            }
        }
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_RULES, arr.toString()).apply();
        TextMorphService.notifyRulesUpdated();
    }

    public static void addOrUpdateRule(Context context, RuleModel rule) {
        List<RuleModel> rules = loadRules(context);
        boolean found = false;
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).id.equals(rule.id)) {
                rules.set(i, rule);
                found = true;
                break;
            }
        }
        if (!found) {
            rules.add(0, rule);
        }
        saveRules(context, rules);
    }

    public static void deleteRule(Context context, String ruleId) {
        List<RuleModel> rules = loadRules(context);
        for (int i = rules.size() - 1; i >= 0; i--) {
            if (rules.get(i).id.equals(ruleId)) {
                rules.remove(i);
            }
        }
        saveRules(context, rules);
    }

    public static boolean isMasterEnabled(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_MASTER_ENABLED, true);
    }

    public static void setMasterEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_MASTER_ENABLED, enabled).apply();
        TextMorphService.notifyRulesUpdated();
    }

    public static boolean isFloatingWandEnabled(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_FLOATING_WAND, true);
    }

    public static void setFloatingWandEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_FLOATING_WAND, enabled).apply();
        TextMorphService.notifyRulesUpdated();
    }

    public static boolean isSandboxEnabled(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SANDBOX_ENABLED, true);
    }

    public static void setSandboxEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_SANDBOX_ENABLED, enabled).apply();
        TextMorphService.notifyRulesUpdated();
    }

    public static boolean isDirectEditEnabled(Context context) {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_DIRECT_EDIT_NODES, true);
    }

    public static void setDirectEditEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_DIRECT_EDIT_NODES, enabled).apply();
        TextMorphService.notifyRulesUpdated();
    }
}
