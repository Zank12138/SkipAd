package com.skipad.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private static class AppEntry {
        String label;
        String pkg;
    }

    /** 0 表示不限制，只在应用启动后的一段时间内检测。 */
    private static final int[] WINDOW_OPTIONS = {10, 20, 30, 0};

    private Prefs prefs;

    private TextView tvStatusBadge;
    private TextView tvStatusHint;
    private TextView tvTotal;
    private TextView tvToday;
    private TextView tvRuleCount;
    private TextView tvRuleSummary;
    private TextView tvLearned;
    private TextView tvLog;
    private TextView tvVersion;
    private TextView btnOpenAccessibility;
    private TextView btnExcludedApps;
    private TextView btnLaunchWindow;

    private Switch swEnabled;
    private Switch swToast;
    private Switch swVibrate;
    private Switch swForceGesture;
    private Switch swPolling;
    private Switch swRetry;
    private Switch swDebug;

    private EditText etKeywords;
    private EditText etIds;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = Prefs.get(this);
        bindViews();
        loadSettings();
        bindListeners();
        refreshAll();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshAll();
    }

    private void bindViews() {
        tvStatusBadge = (TextView) findViewById(R.id.tvStatusBadge);
        tvStatusHint = (TextView) findViewById(R.id.tvStatusHint);
        tvTotal = (TextView) findViewById(R.id.tvTotal);
        tvToday = (TextView) findViewById(R.id.tvToday);
        tvRuleCount = (TextView) findViewById(R.id.tvRuleCount);
        tvRuleSummary = (TextView) findViewById(R.id.tvRuleSummary);
        tvLearned = (TextView) findViewById(R.id.tvLearned);
        tvLog = (TextView) findViewById(R.id.tvLog);
        tvVersion = (TextView) findViewById(R.id.tvVersion);
        btnOpenAccessibility = (TextView) findViewById(R.id.btnOpenAccessibility);
        btnExcludedApps = (TextView) findViewById(R.id.btnExcludedApps);
        btnLaunchWindow = (TextView) findViewById(R.id.btnLaunchWindow);

        swEnabled = (Switch) findViewById(R.id.swEnabled);
        swToast = (Switch) findViewById(R.id.swToast);
        swVibrate = (Switch) findViewById(R.id.swVibrate);
        swForceGesture = (Switch) findViewById(R.id.swForceGesture);
        swPolling = (Switch) findViewById(R.id.swPolling);
        swRetry = (Switch) findViewById(R.id.swRetry);
        swDebug = (Switch) findViewById(R.id.swDebug);

        etKeywords = (EditText) findViewById(R.id.etKeywords);
        etIds = (EditText) findViewById(R.id.etIds);
    }

    private void loadSettings() {
        swEnabled.setChecked(prefs.isEnabled());
        swToast.setChecked(prefs.isToastEnabled());
        swVibrate.setChecked(prefs.isVibrateEnabled());
        swForceGesture.setChecked(prefs.isForceGesture());
        swPolling.setChecked(prefs.isPollingEnabled());
        swRetry.setChecked(prefs.isRetryEnabled());
        swDebug.setChecked(prefs.isDebugLogEnabled());
        btnLaunchWindow.setText(windowLabel(prefs.getLaunchWindowSec()));
        etKeywords.setText(prefs.getExtraKeywords());
        etIds.setText(prefs.getExtraIds());
    }

    private void bindListeners() {
        swEnabled.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                prefs.setEnabled(checked);
            }
        });
        swToast.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                prefs.setToastEnabled(checked);
            }
        });
        swVibrate.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                prefs.setVibrateEnabled(checked);
            }
        });
        swForceGesture.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                prefs.setForceGesture(checked);
            }
        });
        swPolling.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                prefs.setPollingEnabled(checked);
            }
        });
        swRetry.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                prefs.setRetryEnabled(checked);
            }
        });
        swDebug.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton button, boolean checked) {
                prefs.setDebugLogEnabled(checked);
            }
        });

        View.OnClickListener click = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int id = v.getId();
                if (id == R.id.btnOpenAccessibility) {
                    openAccessibilitySettings();
                } else if (id == R.id.btnGuide) {
                    showGuide();
                } else if (id == R.id.btnLaunchWindow) {
                    cycleLaunchWindow();
                } else if (id == R.id.btnSaveRules) {
                    saveRules();
                } else if (id == R.id.btnExcludedApps) {
                    showAppPicker();
                } else if (id == R.id.btnBattery) {
                    openBatterySettings();
                } else if (id == R.id.btnClearLog) {
                    prefs.clearLogs();
                    renderLog();
                } else if (id == R.id.btnClearMemory) {
                    prefs.clearMemory();
                    refreshAll();
                    Toast.makeText(MainActivity.this, "已清空应用记忆", Toast.LENGTH_SHORT).show();
                } else if (id == R.id.btnResetStats) {
                    confirmResetStats();
                }
            }
        };

        btnOpenAccessibility.setOnClickListener(click);
        findViewById(R.id.btnGuide).setOnClickListener(click);
        btnLaunchWindow.setOnClickListener(click);
        findViewById(R.id.btnSaveRules).setOnClickListener(click);
        btnExcludedApps.setOnClickListener(click);
        findViewById(R.id.btnBattery).setOnClickListener(click);
        findViewById(R.id.btnClearLog).setOnClickListener(click);
        findViewById(R.id.btnClearMemory).setOnClickListener(click);
        findViewById(R.id.btnResetStats).setOnClickListener(click);
    }

    private String windowLabel(int seconds) {
        return seconds <= 0 ? "不限" : seconds + " 秒";
    }

    private void cycleLaunchWindow() {
        int current = prefs.getLaunchWindowSec();
        int index = 0;
        for (int i = 0; i < WINDOW_OPTIONS.length; i++) {
            if (WINDOW_OPTIONS[i] == current) {
                index = i;
                break;
            }
        }
        int next = WINDOW_OPTIONS[(index + 1) % WINDOW_OPTIONS.length];
        prefs.setLaunchWindowSec(next);
        btnLaunchWindow.setText(windowLabel(next));
        Toast.makeText(this, "检测时长：" + windowLabel(next)
                + (next == 0 ? "（全天检测，注意误触）" : ""), Toast.LENGTH_SHORT).show();
    }

    private void refreshAll() {
        boolean enabled = isServiceEnabled();
        if (enabled) {
            tvStatusBadge.setText("● 服务已开启");
            tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_on);
            tvStatusBadge.setTextColor(getColor(android.R.color.white));
            tvStatusHint.setText("运行中：事件监听 + 定时轮询识别跳过按钮，点击后会验证是否生效并自动重试。若长时间无效果，请检查下方保活设置。");
            btnOpenAccessibility.setText("无障碍服务设置（已开启）");
        } else {
            tvStatusBadge.setText("● 未开启");
            tvStatusBadge.setBackgroundResource(R.drawable.bg_badge_off);
            tvStatusBadge.setTextColor(getColor(R.color.orange));
            tvStatusHint.setText("尚未开启无障碍权限，自动跳过不会生效。点击下方按钮开启后返回本页。");
            btnOpenAccessibility.setText("去开启无障碍服务");
        }

        tvTotal.setText(String.valueOf(prefs.getTotalCount()));
        tvToday.setText(String.valueOf(prefs.getTodayCount()));

        RuleManager ruleManager = RuleManager.get(this);
        tvRuleCount.setText(String.valueOf(ruleManager.getRuleCount()));
        tvRuleSummary.setText("内置 " + (ruleManager.getRuleCount() - ruleManager.getCustomRuleCount())
                + " 条 · 自定义 " + ruleManager.getCustomRuleCount() + " 条");
        tvLearned.setText("已记住 " + prefs.getLearnedCount() + " 个应用的跳过按钮");
        btnLaunchWindow.setText(windowLabel(prefs.getLaunchWindowSec()));
        btnExcludedApps.setText("设置不跳过的应用（" + prefs.getExcludedApps().size() + "）");
        // 显示的是「当前这台手机上已安装 APK」的真实版本，覆盖安装成功后这里会立刻变
        tvVersion.setText(appVersionText());
        renderLog();
    }

    /** 读取实际安装包的版本信息，用于确认升级是否生效，而不是显示写死的字符串。 */
    private String appVersionText() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            int code = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? (int) info.getLongVersionCode() : info.versionCode;
            return "版本：v" + info.versionName + " (" + code + ")";
        } catch (Exception e) {
            return "版本：未知";
        }
    }

    private void renderLog() {
        JSONArray logs = prefs.getLogs();
        if (logs.length() == 0) {
            tvLog.setText("暂无记录\n成功跳过后，这里会显示时间、应用、命中的按钮与点击方式；调试模式下还会显示未命中的候选控件。");
            return;
        }
        StringBuilder sb = new StringBuilder();
        int from = Math.max(0, logs.length() - 12);
        for (int i = logs.length() - 1; i >= from; i--) {
            JSONObject item = logs.optJSONObject(i);
            if (item == null) {
                continue;
            }
            sb.append(item.optString("t")).append("  ").append(item.optString("p")).append("\n")
                    .append("   ↳ ").append(item.optString("h"))
                    .append("  [").append(item.optString("m")).append("]").append("\n");
        }
        tvLog.setText(sb.toString().trim());
    }

    /** 读取系统无障碍开关状态（比服务心跳更可靠）。 */
    private boolean isServiceEnabled() {
        String full = getPackageName() + "/" + SkipAdService.class.getName();
        String shortName = getPackageName() + "/.SkipAdService";
        try {
            int accessibilityEnabled = Settings.Secure.getInt(getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, 0);
            if (accessibilityEnabled == 0) {
                return false;
            }
            String services = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (services == null) {
                return false;
            }
            for (String part : services.split(":")) {
                String value = part.trim();
                if (value.isEmpty()) {
                    continue;
                }
                if (value.equalsIgnoreCase(full) || value.equalsIgnoreCase(shortName)) {
                    return true;
                }
                ComponentName cn = ComponentName.unflattenFromString(value);
                if (cn != null
                        && getPackageName().equals(cn.getPackageName())
                        && SkipAdService.class.getName().equals(cn.getClassName())) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(this, "请找到「一跳而过」并打开开关", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(this, "无法打开系统设置，请手动进入 设置 → 无障碍", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openBatterySettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                return;
            } catch (Exception ignored) {
            }
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                return;
            } catch (Exception ignored) {
            }
        }
        Toast.makeText(this, "请到系统设置 → 电池 中把本应用设为「不优化」", Toast.LENGTH_LONG).show();
    }

    private void saveRules() {
        String keywords = etKeywords.getText().toString().trim();
        String ids = etIds.getText().toString().trim();
        prefs.saveRules(keywords, ids);
        RuleManager.get(this).refreshIfNeeded();
        hideKeyboard();
        Toast.makeText(this, "规则已保存，立即生效", Toast.LENGTH_SHORT).show();
        refreshAll();
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            View focus = getCurrentFocus();
            if (imm != null && focus != null) {
                imm.hideSoftInputFromWindow(focus.getWindowToken(), 0);
            }
        } catch (Throwable ignored) {
        }
    }

    private void showGuide() {
        String message =
                "1. 点「去开启无障碍服务」→ 在列表里找到「一跳而过 · 开屏广告跳过」→ 打开开关并确认授权。\n\n"
                        + "2. 返回本页，顶部显示「服务已开启」即生效。\n\n"
                        + "3. 打开任意带开屏广告的 App 测试。成功时会有提示，下方「最近跳过记录」会留下命中的按钮文字与点击方式。\n\n"
                        + "4. 某个 App 偶尔跳不过时，按顺序试：① 点「启动后检测时长」调到 30 秒或「不限」；② 打开「强制使用坐标点击」；③ 打开「调试模式」，重开那个 App，看日志里的「候选」记录，把按钮文字或 @ 后面的 ID 填进自定义规则。\n\n"
                        + "5. 服务会自动轮询补扫、点击后验证并最多重试 4 次（控件点击 → 中心 → 右上 → 右下），只有确认按钮消失才计入统计。\n\n"
                        + "6. 保活：忽略电池优化 + 自启动白名单 + 最近任务锁定。部分国产 ROM 锁屏或清理后台后会关掉无障碍服务，需要重新开启。\n\n"
                        + "本应用没有网络权限，全部配置与记录仅保存在本机。";
        new AlertDialog.Builder(this)
                .setTitle("使用步骤与排障")
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show();
    }

    private void confirmResetStats() {
        new AlertDialog.Builder(this)
                .setTitle("重置统计")
                .setMessage("清空累计与今日跳过次数？不会清空规则和记录。")
                .setNegativeButton("取消", null)
                .setPositiveButton("重置", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        prefs.resetStats();
                        refreshAll();
                    }
                })
                .show();
    }

    /** 列出有桌面图标的应用，勾选的应用不再检测。 */
    private void showAppPicker() {
        final PackageManager pm = getPackageManager();
        Intent launcherIntent = new Intent(Intent.ACTION_MAIN, null);
        launcherIntent.addCategory(Intent.CATEGORY_LAUNCHER);

        List<ResolveInfo> resolved;
        try {
            resolved = pm.queryIntentActivities(launcherIntent, 0);
        } catch (Throwable t) {
            resolved = new ArrayList<>();
        }

        final List<AppEntry> entries = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo info : resolved) {
            if (info == null || info.activityInfo == null) {
                continue;
            }
            String pkg = info.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || !seen.add(pkg)) {
                continue;
            }
            String label;
            try {
                label = info.loadLabel(pm).toString();
            } catch (Throwable t) {
                label = pkg;
            }
            AppEntry entry = new AppEntry();
            entry.label = label;
            entry.pkg = pkg;
            entries.add(entry);
        }
        Collections.sort(entries, new Comparator<AppEntry>() {
            @Override
            public int compare(AppEntry a, AppEntry b) {
                return a.label.compareToIgnoreCase(b.label);
            }
        });

        final Set<String> excluded = prefs.getExcludedApps();
        CharSequence[] items = new CharSequence[entries.size()];
        final boolean[] checked = new boolean[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            items[i] = entries.get(i).label + "\n" + entries.get(i).pkg;
            checked[i] = excluded.contains(entries.get(i).pkg);
        }

        new AlertDialog.Builder(this)
                .setTitle("不跳过的应用（勾选后不检测）")
                .setMultiChoiceItems(items, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which, boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        Set<String> result = new HashSet<>();
                        for (int i = 0; i < checked.length; i++) {
                            if (checked[i]) {
                                result.add(entries.get(i).pkg);
                            }
                        }
                        prefs.setExcludedApps(result);
                        refreshAll();
                        Toast.makeText(MainActivity.this,
                                "已排除 " + result.size() + " 个应用", Toast.LENGTH_SHORT).show();
                    }
                })
                .show();
    }
}
