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
    private static final String K_LAUNCH_WINDOW_SEC = "launch_window_sec";
    private static final String K_FORCE_GESTURE = "force_gesture";
    private static final String K_POLLING = "polling";
    private static final String K_RETRY = "retry";
    private static final String K_DEBUG_LOG = "debug_log";
    private static final String K_EXTRA_KEYWORDS = "extra_keywords";
    private static final String K_EXTRA_IDS = "extra_ids";
    private static final String K_EXCLUDED = "excluded_apps";
    private static final String K_MEMORY = "memory";
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

    /** 兼容旧版本：新版用 getLaunchWindowSec()，0 表示不限制时长。 */
    public boolean isLaunchWindowOnly() {
        return sp.getBoolean(K_LAUNCH_ONLY, true);
    }

    public void setLaunchWindowOnly(boolean value) {
        sp.edit().putBoolean(K_LAUNCH_ONLY, value).apply();
    }

    /** 应用切到前台后的检测窗口，单位秒；0 = 全程检测。默认 20 秒。 */
    public int getLaunchWindowSec() {
        return sp.getInt(K_LAUNCH_WINDOW_SEC, 20);
    }

    public void setLaunchWindowSec(int seconds) {
        sp.edit().putInt(K_LAUNCH_WINDOW_SEC, seconds).apply();
    }

    public boolean isForceGesture() {
        return sp.getBoolean(K_FORCE_GESTURE, false);
    }

    public void setForceGesture(boolean value) {
        sp.edit().putBoolean(K_FORCE_GESTURE, value).apply();
    }

    /** 定时轮询补扫：解决自绘广告不发窗口事件的情况。 */
    public boolean isPollingEnabled() {
        return sp.getBoolean(K_POLLING, true);
    }

    public void setPollingEnabled(boolean value) {
        sp.edit().putBoolean(K_POLLING, value).apply();
    }

    /** 点击后验证是否真的生效，未生效自动换方式重试。 */
    public boolean isRetryEnabled() {
        return sp.getBoolean(K_RETRY, true);
    }

    public void setRetryEnabled(boolean value) {
        sp.edit().putBoolean(K_RETRY, value).apply();
    }

    /** 调试模式：把没命中时的可疑控件记进日志，方便自己补规则。 */
    public boolean isDebugLogEnabled() {
        return sp.getBoolean(K_DEBUG_LOG, false);
    }

    public void setDebugLogEnabled(boolean value) {
        sp.edit().putBoolean(K_DEBUG_LOG, value).apply();
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

    // ---------- 按应用记住成功命中的按钮 ----------

    /** 成功跳过后记住该 App 的按钮文字 / ID，下次作为高优先级快速通道。 */
    public synchronized void rememberHit(String pkg, String viewId, String text) {
        if (pkg == null || pkg.isEmpty()) {
            return;
        }
        try {
            JSONObject all = new JSONObject(sp.getString(K_MEMORY, "{}"));
            JSONObject item = all.optJSONObject(pkg);
            if (item == null) {
                item = new JSONObject();
            }
            if (viewId != null && !viewId.isEmpty()) {
                item.put("id", shortId(viewId));
            }
            if (text != null && !text.isEmpty()) {
                item.put("text", text.trim());
            }
            item.put("n", item.optInt("n", 0) + 1);
            all.put(pkg, item);
            sp.edit().putString(K_MEMORY, all.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public synchronized JSONObject getRemembered(String pkg) {
        try {
            return new JSONObject(sp.getString(K_MEMORY, "{}")).optJSONObject(pkg);
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized int getLearnedCount() {
        try {
            return new JSONObject(sp.getString(K_MEMORY, "{}")).length();
        } catch (Exception e) {
            return 0;
        }
    }

    public void clearMemory() {
        sp.edit().putString(K_MEMORY, "{}").apply();
    }

    private static String shortId(String id) {
        int slash = id.lastIndexOf('/');
        String shortName = slash >= 0 ? id.substring(slash + 1) : id;
        return shortName.length() > 40 ? shortName.substring(0, 40) : shortName;
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

    // ---------- 命中 / 调试日志 ----------

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
        for (int i = Math.max(0, all.length() - 60); i < all.length(); i++) {
            trimmed.put(all.opt(i));
        }
        sp.edit().putString(K_LOG, trimmed.toString()).apply();
    }

    public void clearLogs() {
        sp.edit().putString(K_LOG, "[]").apply();
    }
}
