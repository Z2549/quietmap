package com.quietmap.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;

/**
 * 目标进程（高德）→ 模块 App 的诊断数据通道。
 *
 * 方向不能反过来：remote prefs 在目标进程只读，openRemoteFile 也是只读，
 * 所以「哪些钩子注册了 / 命中了多少次」只能由目标进程主动 call 进来。
 *
 * 只接受 report 方法，key/value 都做长度和字符集校验；不存任何敏感信息。
 * key 形如 reg.bg_msg_fetch / hit.bg_msg_fetch / skip.bg_msg_fetch，
 * value 对应注册标签、命中计数、跳过原因。
 */
public final class ReportProvider extends ContentProvider {

    public static final String AUTHORITY = "io.github.yigenhuobah.quietmap.qmreport";
    public static final String PREFS = "qm_report";

    public static final String KEY_LAST = "t";
    public static final String PREFIX_REG = "reg.";
    public static final String PREFIX_HIT = "hit.";
    public static final String PREFIX_SKIP = "skip.";

    private static final int MAX_KEY = 128;
    private static final int MAX_VALUE = 256;
    private static final int MAX_ITEMS = 800;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Bundle call(String method, String arg, Bundle extras) {
        if (getContext() == null) {
            return null;
        }
        if (!"report".equals(method) || extras == null) {
            return null;
        }
        String[] keys = extras.getStringArray("keys");
        String[] values = extras.getStringArray("values");
        if (keys == null || values == null || keys.length != values.length) {
            return null;
        }
        SharedPreferences sp = getContext().getSharedPreferences(PREFS, 0);
        SharedPreferences.Editor editor = sp.edit();
        int accepted = 0;
        for (int i = 0; i < keys.length && accepted < MAX_ITEMS; i++) {
            String key = keys[i];
            String value = values[i];
            if (!validKey(key) || value == null || value.length() > MAX_VALUE) {
                continue;
            }
            if (key.startsWith(PREFIX_HIT)) {
                // 命中计数取历史最大值：高德多进程各自回传，取 max 不会重复累加，
                // 也不会被另一进程的低计数覆盖回去。
                long prev = sp.getLong("v:" + key, 0L);
                long next;
                try {
                    next = Long.parseLong(value);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (next > prev) {
                    editor.putLong("v:" + key, next);
                }
            } else {
                editor.putString("v:" + key, value);
            }
            accepted++;
        }
        if (accepted > 0) {
            editor.putLong(KEY_LAST, System.currentTimeMillis());
            editor.apply();
        }
        return new Bundle();
    }

    private static boolean validKey(String key) {
        if (key == null || key.isEmpty() || key.length() > MAX_KEY) {
            return false;
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-' || c == '.';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
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
