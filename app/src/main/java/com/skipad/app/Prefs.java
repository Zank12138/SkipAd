package com.skipad.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * 所有配置与统计都存在本机 SharedPreferences，不联网、不上传。
 * 服务进程与界面进程相同，使用 apply() 后内存值立即可见。
 */
public class Prefs {

    private static final String FILE = "skipad_prefs";

    private static final String K_ENABLED = "enabled";
    private static final String K_TOAST = "toast";
    private static final String K_VIBRATE = "vibrate";
    private static final String K_LAUNCH_ONLY = "launch_only";
    private static final String K_FORCE_GESTURE = "force_gesture";
    private static final String K_EXTRA_KEYWORDS = "extra_keywords";
    private static final String K_EXTRA_IDS = "extra_ids";
    private static final String K_EXCLUDED = "excluded_apps";
    private static final String K_TOTAL = "total";
    private static final String K_TODAY = "today";
    private static final String K_TODAY_DATE = "today_date";
    private static final String K_LOG = "logs";
    private static final String K_HEARTBEAT = "heartbeat";
    private static final String K_RULES_VERSION = "rules_version";

    private static Prefs sInstance;
    private final SharedPreferences sp;

    public static synchronized Prefs get(Context context) {
        if (sInstance == null) {
            sInstance = new Prefs(context.getApplicationContext());
        }
        return sInstance;
    }

    private Prefs(Context context) {
        sp = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ---------- 功能开关 ----------

    public boolean isEnabled() {
        return sp.getBoolean(K_ENABLED, true);
    }

    public void setEnabled(boolean value) {
        sp.edit().putBoolean(K_ENABLED, value).apply();
    }

    public boolean isToastEnabled() {
        return sp.getBoolean(K_TOAST, true);
    }

    public void setToastEnabled(boolean value) {
        sp.edit().putBoolean(K_TOAST, value).apply();
    }

    public boolean isVibrateEnabled() {
        return sp.getBoolean(K_VIBRATE, false);
    }

    public void setVibrateEnabled(boolean value) {
        sp.edit().putBoolean(K_VIBRATE, value).apply();
    }

    public boolean isLaunchWindowOnly() {
        return sp.getBoolean(K_LAUNCH_ONLY, true);
    }

    public void setLaunchWindowOnly(boolean value) {
        sp.edit().putBoolean(K_LAUNCH_ONLY, value).apply();
    }

    public boolean isForceGesture() {
        return sp.getBoolean(K_FORCE_GESTURE, false);
    }

    public void setForceGesture(boolean value) {
        sp.edit().putBoolean(K_FORCE_GESTURE, value).apply();
    }

    // ---------- 自定义规则 ----------

    public String getExtraKeywords() {
        return sp.getString(K_EXTRA_KEYWORDS, "");
    }

    public String getExtraIds() {
        return sp.getString(K_EXTRA_IDS, "");
    }

    /** 保存规则并递增版本号，服务侧据此热更新规则缓存。 */
    public synchronized void saveRules(String keywords, String ids) {
        sp.edit()
                .putString(K_EXTRA_KEYWORDS, keywords == null ? "" : keywords)
                .putString(K_EXTRA_IDS, ids == null ? "" : ids)
                .putLong(K_RULES_VERSION, sp.getLong(K_RULES_VERSION, 0L) + 1)
                .apply();
    }

    public long getRulesVersion() {
        return sp.getLong(K_RULES_VERSION, 0L);
    }

    // ---------- 应用白名单（勾选 = 不检测） ----------

    public Set<String> getExcludedApps() {
        return new HashSet<String>(
                sp.getStringSet(K_EXCLUDED, Collections.<String>emptySet()));
    }

    public void setExcludedApps(Set<String> apps) {
        sp.edit().putStringSet(K_EXCLUDED, new HashSet<String>(apps)).apply();
    }

    // ---------- 统计 ----------

    private String today() {
        return new SimpleDateFormat("yyyyMMdd", Locale.CHINA).format(new Date());
    }

    public int getTotalCount() {
        return sp.getInt(K_TOTAL, 0);
    }

    public int getTodayCount() {
        if (!today().equals(sp.getString(K_TODAY_DATE, ""))) {
            return 0;
        }
        return sp.getInt(K_TODAY, 0);
    }

    public synchronized void addSkip() {
        SharedPreferences.Editor editor = sp.edit();
        editor.putInt(K_TOTAL, sp.getInt(K_TOTAL, 0) + 1);
        String date = today();
        if (!date.equals(sp.getString(K_TODAY_DATE, ""))) {
            editor.putString(K_TODAY_DATE, date).putInt(K_TODAY, 1);
        } else {
            editor.putInt(K_TODAY, sp.getInt(K_TODAY, 0) + 1);
        }
        editor.apply();
    }

    public void resetStats() {
        sp.edit().putInt(K_TOTAL, 0).putInt(K_TODAY, 0)
                .putString(K_TODAY_DATE, today()).apply();
    }

    public void touchHeartbeat() {
        sp.edit().putLong(K_HEARTBEAT, System.currentTimeMillis()).apply();
    }

    public long getHeartbeat() {
        return sp.getLong(K_HEARTBEAT, 0L);
    }

    // ---------- 命中日志 ----------

    public synchronized JSONArray getLogs() {
        try {
            return new JSONArray(sp.getString(K_LOG, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    public synchronized void addLog(String time, String app, String hit, String how) {
        JSONArray all = getLogs();
        try {
            JSONObject item = new JSONObject();
            item.put("t", time);
            item.put("p", app);
            item.put("h", hit);
            item.put("m", how);
            all.put(item);
        } catch (Exception ignored) {
        }
        JSONArray trimmed = new JSONArray();
        for (int i = Math.max(0, all.length() - 30); i < all.length(); i++) {
            trimmed.put(all.opt(i));
        }
        sp.edit().putString(K_LOG, trimmed.toString()).apply();
    }

    public void clearLogs() {
        sp.edit().putString(K_LOG, "[]").apply();
    }
}
