package com.antigravity.textmorph;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

public class TextMorphRuleProvider extends ContentProvider {

    public static final String AUTHORITY = "com.antigravity.textmorph.provider";
    public static final Uri CONTENT_URI = Uri.parse("content://" + AUTHORITY + "/rules");

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        Context context = getContext();
        if (context == null) return null;

        SharedPreferences prefs = context.getSharedPreferences("textmorph_prefs", Context.MODE_PRIVATE);
        String rulesJson = prefs.getString("rules_json", "[]");
        boolean masterEnabled = prefs.getBoolean("master_enabled", true);

        MatrixCursor cursor = new MatrixCursor(new String[]{"rules_json", "master_enabled"});
        cursor.addRow(new Object[]{rulesJson, masterEnabled ? 1 : 0});
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.com.antigravity.textmorph.rules";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
