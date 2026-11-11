package com.skipad.app;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 核心服务：监听窗口变化 → 遍历控件树 → 识别「跳过」按钮 → 模拟点击。
 *
 * 防误触策略（很重要）：
 *   - 默认只在「应用切换到前台后的 10 秒内」工作；
 *   - 排除系统 UI / 输入法 / 桌面 / 用户勾选的应用；
 *   - 控件必须可见、可用、尺寸合理、坐标在屏幕内；
 *   - ID 命中还要求控件位于屏幕上下边缘区域（开屏按钮常见位置）；
 *   - 同一应用每 120ms 最多扫描一次，成功点击后冷却 900ms、该应用静默 1.3s；
 *   - 文本含「跳过登录 / 跳过验证」等业务词时直接忽略。
 */
public class SkipAdService extends AccessibilityService {

    private static final long SCAN_INTERVAL_MS = 120;
    private static final long CLICK_COOLDOWN_MS = 900;
    private static final long PACKAGE_SUPPRESS_MS = 1300;
    private static final long LAUNCH_WINDOW_MS = 10000;
    private static final int MAX_NODES = 260;

    private static volatile SkipAdService sInstance;

    /** 不应被检测的系统组件（输入法尤其要排除，避免打字时误点）。 */
    private static final String[] IGNORED_PREFIXES = {
            "com.android.systemui", "com.android.settings", "com.android.launcher",
            "com.android.inputmethod", "com.android.keyguard", "com.android.phone",
            "com.google.android.inputmethod", "com.google.android.googlequicksearchbox",
            "com.miui.home", "com.miui.securitycenter", "com.miui.powerkeeper",
            "com.huawei.android.launcher", "com.oppo.launcher", "com.vivo.launcher",
            "com.coloros", "com.oplus", "com.heytap",
            "com.sohu.inputmethod", "com.baidu.input", "com.iflytek.inputmethod",
            "com.tencent.qqpinyin", "com.sogou.inputmethod", "com.jinke.inputmethod"
    };

    private static final String[] IGNORED_EXACT = {
            "android", "com.android.systemui", "com.android.settings",
            "com.android.providers.settings", "com.android.dialer", "com.android.mms"
    };

    private Prefs prefs;
    private RuleManager rules;
    private Handler mainHandler;
    private Toast toast;

    private final Map<String, Long> lastScanAt = new HashMap<>();
    private final Map<String, Long> suppressUntil = new HashMap<>();
    private final Map<String, Long> launchedAt = new HashMap<>();
    private final Map<String, String> labelCache = new HashMap<>();

    private String lastForegroundPkg = "";
    private long lastClickAt = 0L;
    private int screenWidth = 0;
    private int screenHeight = 0;

    public static boolean isRunning() {
        return sInstance != null;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        prefs = Prefs.get(this);
        rules = RuleManager.get(this);
        rules.refreshIfNeeded();
        mainHandler = new Handler(Looper.getMainLooper());
        measureScreen();
        prefs.touchHeartbeat();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || sInstance == null) {
            return;
        }
        try {
            int type = event.getEventType();
            if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    && type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                return;
            }

            CharSequence pkgChar = event.getPackageName();
            if (pkgChar == null) {
                return;
            }
            final String pkg = pkgChar.toString();

            CharSequence cls = event.getClassName();
            if (cls != null) {
                String className = cls.toString();
                if (className.contains("Toast") || className.contains("InputMethod")) {
                    return;
                }
            }

            if (prefs == null) {
                prefs = Prefs.get(this);
            }
            if (!prefs.isEnabled() || shouldIgnore(pkg)) {
                return;
            }

            long now = SystemClock.elapsedRealtime();
            if (!pkg.equals(lastForegroundPkg)) {
                lastForegroundPkg = pkg;
                launchedAt.put(pkg, now);
            } else if (!launchedAt.containsKey(pkg)) {
                launchedAt.put(pkg, now);
            }

            if (prefs.isLaunchWindowOnly()) {
                Long started = launchedAt.get(pkg);
                if (started == null || now - started > LAUNCH_WINDOW_MS) {
                    return;
                }
            }

            Long last = lastScanAt.get(pkg);
            if (last != null && now - last < SCAN_INTERVAL_MS) {
                return;
            }
            lastScanAt.put(pkg, now);

            Long silent = suppressUntil.get(pkg);
            if (silent != null && now < silent) {
                return;
            }
            if (now - lastClickAt < CLICK_COOLDOWN_MS) {
                return;
            }

            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                return;
            }
            scanAndClick(root, pkg);
        } catch (Throwable ignored) {
            // 无障碍回调里绝不能让异常冒泡导致服务被系统判为异常
        }
    }

    private boolean shouldIgnore(String pkg) {
        if (getPackageName().equals(pkg)) {
            return true;
        }
        for (String exact : IGNORED_EXACT) {
            if (exact.equals(pkg)) {
                return true;
            }
        }
        for (String prefix : IGNORED_PREFIXES) {
            if (pkg.startsWith(prefix)) {
                return true;
            }
        }
        return prefs.getExcludedApps().contains(pkg);
    }

    /** 广度优先遍历控件树，找到第一个可信的「跳过」控件并点击。 */
    private void scanAndClick(AccessibilityNodeInfo root, String pkg) {
        if (rules == null) {
            rules = RuleManager.get(this);
        }
        rules.refreshIfNeeded();

        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        List<AccessibilityNodeInfo> visited = new ArrayList<>();
        queue.add(root);

        AccessibilityNodeInfo target = null;
        String reason = null;
        int count = 0;

        while (!queue.isEmpty() && count < MAX_NODES) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) {
                continue;
            }
            count++;
            visited.add(node);
            try {
                if (!isCandidate(node)) {
                    addChildren(node, queue);
                    continue;
                }

                RuleManager.Match match = rules.matchText(textOf(node.getText()));
                if (match == null) {
                    match = rules.matchText(textOf(node.getContentDescription()));
                }
                if (match == null) {
                    String viewId = node.getViewIdResourceName();
                    if (rules.matchId(viewId) && isEdgeArea(node)) {
                        match = new RuleManager.Match("ID:" + shortId(viewId));
                    }
                }

                if (match != null) {
                    target = node;
                    reason = match.why;
                    break;
                }
                addChildren(node, queue);
            } catch (Throwable ignored) {
                // 单个控件失效（窗口已刷新）时跳过它
            }
        }

        if (target != null) {
            String how = clickNode(target);
            if (how != null) {
                onSkipped(pkg, reason, how);
            }
        }

        // 把遍历过程中产生的副本统一回收，释放 binder 引用
        for (AccessibilityNodeInfo n : visited) {
            if (n == root) {
                continue;
            }
            try {
                n.recycle();
            } catch (Throwable ignored) {
            }
        }
        while (!queue.isEmpty()) {
            AccessibilityNodeInfo n = queue.poll();
            try {
                if (n != null && n != root) {
                    n.recycle();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void addChildren(AccessibilityNodeInfo node, ArrayDeque<AccessibilityNodeInfo> queue) {
        int childCount = node.getChildCount();
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                queue.add(child);
            }
        }
    }

    private boolean isCandidate(AccessibilityNodeInfo node) {
        if (!node.isVisibleToUser() || !node.isEnabled()) {
            return false;
        }
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        if (rect.width() < 18 || rect.height() < 18) {
            return false;
        }
        if (screenWidth > 0 && (rect.centerX() < 0 || rect.centerX() > screenWidth)) {
            return false;
        }
        if (screenHeight > 0 && (rect.centerY() < 0 || rect.centerY() > screenHeight)) {
            return false;
        }
        return true;
    }

    /** ID 命中门槛更严：只接受屏幕上部或下部的控件。 */
    private boolean isEdgeArea(AccessibilityNodeInfo node) {
        if (screenHeight <= 0) {
            return true;
        }
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        float y = rect.centerY();
        return y < screenHeight * 0.35f || y > screenHeight * 0.68f;
    }

    /** 返回点击方式描述；返回 null 表示点击失败。 */
    private String clickNode(AccessibilityNodeInfo node) {
        if (!prefs.isForceGesture()) {
            if (hasClickAction(node) && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return "控件点击";
            }

            // 命中的常是不可点击的 TextView，向上找可点击的父容器
            List<AccessibilityNodeInfo> parents = new ArrayList<>();
            AccessibilityNodeInfo current = node;
            boolean clicked = false;
            for (int i = 0; i < 3; i++) {
                AccessibilityNodeInfo parent;
                try {
                    parent = current.getParent();
                } catch (Throwable t) {
                    break;
                }
                if (parent == null) {
                    break;
                }
                parents.add(parent);
                if (hasClickAction(parent) && parent.isVisibleToUser()
                        && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    clicked = true;
                    break;
                }
                current = parent;
            }
            for (AccessibilityNodeInfo p : parents) {
                try {
                    p.recycle();
                } catch (Throwable ignored) {
                }
            }
            if (clicked) {
                return "父控件点击";
            }
        }
        return clickByGesture(node) ? "坐标点击" : null;
    }

    private static boolean hasClickAction(AccessibilityNodeInfo node) {
        return (node.getActions() & AccessibilityNodeInfo.ACTION_CLICK) != 0;
    }

    /** 对自绘 / Flutter / WebView 广告，用无障碍手势点击控件中心。 */
    private boolean clickByGesture(AccessibilityNodeInfo node) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        int x = rect.centerX();
        int y = rect.centerY();
        if (screenWidth > 0 && (x < 0 || x >= screenWidth)) {
            return false;
        }
        if (screenHeight > 0 && (y < 0 || y >= screenHeight)) {
            return false;
        }
        try {
            Path path = new Path();
            path.moveTo(x, y);
            GestureDescription.Builder builder = new GestureDescription.Builder();
            builder.addStroke(new GestureDescription.StrokeDescription(path, 0, 60));
            return dispatchGesture(builder.build(), null, mainHandler);
        } catch (Throwable t) {
            return false;
        }
    }

    private void onSkipped(String pkg, String reason, String how) {
        long now = SystemClock.elapsedRealtime();
        lastClickAt = now;
        suppressUntil.put(pkg, now + PACKAGE_SUPPRESS_MS);

        prefs.addSkip();
        String time = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
        prefs.addLog(time, appLabel(pkg), reason == null ? "-" : reason, how);
        prefs.touchHeartbeat();

        if (prefs.isToastEnabled()) {
            showToast("已跳过开屏广告");
        }
        if (prefs.isVibrateEnabled()) {
            vibrate();
        }
    }

    private void showToast(final String message) {
        if (mainHandler == null) {
            return;
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (toast != null) {
                        toast.cancel();
                    }
                    toast = Toast.makeText(SkipAdService.this, message, Toast.LENGTH_SHORT);
                    toast.show();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    private void vibrate() {
        try {
            Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (vibrator == null || !vibrator.hasVibrator()) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(25,
                        VibrationEffect.DEFAULT_AMPLITUDE));
            } else {
                vibrator.vibrate(25);
            }
        } catch (Throwable ignored) {
        }
    }

    private String appLabel(String pkg) {
        String cached = labelCache.get(pkg);
        if (cached != null) {
            return cached;
        }
        String label;
        try {
            PackageManager pm = getPackageManager();
            ApplicationInfo info = pm.getApplicationInfo(pkg, 0);
            label = pm.getApplicationLabel(info).toString();
        } catch (Throwable t) {
            label = pkg.contains(".") ? pkg.substring(pkg.lastIndexOf('.') + 1) : pkg;
        }
        if (label.length() > 14) {
            label = label.substring(0, 14) + "…";
        }
        labelCache.put(pkg, label);
        return label;
    }

    private static String textOf(CharSequence cs) {
        return cs == null ? null : cs.toString();
    }

    private static String shortId(String id) {
        if (id == null) {
            return "";
        }
        int slash = id.lastIndexOf('/');
        return slash >= 0 ? id.substring(slash + 1) : id;
    }

    private void measureScreen() {
        try {
            WindowManager wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(metrics);
            screenWidth = metrics.widthPixels;
            screenHeight = metrics.heightPixels;
        } catch (Throwable t) {
            screenWidth = 0;
            screenHeight = 0;
        }
    }

    @Override
    public void onInterrupt() {
        // 系统临时收回权限，无需特殊处理
    }

    @Override
    public boolean onUnbind(Intent intent) {
        sInstance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        super.onDestroy();
    }
}
