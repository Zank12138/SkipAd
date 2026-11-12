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
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Toast;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 核心服务：监听窗口变化 + 定时轮询 → 遍历所有窗口的控件树 → 识别「跳过」按钮 → 模拟点击
 * → 延迟验证是否真的消失 → 未消失则自动换一种点击方式重试。
 *
 * 相比初版解决「偶尔跳不过」的几点：
 *   1. 检测窗口可调（默认 20s，可设不限），并在应用内长时间停留后重新进入界面时重置；
 *   2. 定时轮询补扫，覆盖自绘 / SurfaceView 广告不发窗口事件的情况；
 *   3. 遍历 getWindows() 的所有窗口，而不只是 active window；
 *   4. performAction 返回 true 不等于广告关掉，因此点击后验证，未生效自动重试（最多 4 次，
 *      依次尝试 控件点击 → 中心 → 右上 → 右下），只有确认按钮消失才计入统计；
 *   5. 记住每个应用成功命中的按钮文字 / ID，下次作为快速通道，绕过弱规则的边缘限制；
 *   6. 调试模式记录未命中时屏幕边缘的可疑控件，方便自己补规则。
 */
public class SkipAdService extends AccessibilityService {

    private static final long SCAN_INTERVAL_MS = 120;
    private static final long POLL_INTERVAL_MS = 400;
    private static final long CLICK_COOLDOWN_MS = 220;
    private static final long VERIFY_DELAY_MS = 550;
    private static final long SUPPRESS_AFTER_SUCCESS_MS = 1500;
    private static final long SUPPRESS_AFTER_GIVEUP_MS = 8000;
    private static final long DEBUG_LOG_INTERVAL_MS = 3000;
    private static final long RESET_WINDOW_GAP_MS = 3000;
    private static final int MAX_ATTEMPTS = 4;
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

    /** 一次已点击、等待验证的命中。 */
    private static class Pending {
        String pkg;
        String reason;
        String how;
        String viewId;
        String text;
        Rect bounds;
        int attempt;
    }

    private interface NodeVisitor {
        /** 返回 true 表示命中并提前结束遍历。 */
        boolean visit(AccessibilityNodeInfo node);
    }

    private Prefs prefs;
    private RuleManager rules;
    private Handler mainHandler;
    private Toast toast;

    private final Map<String, Long> lastScanAt = new HashMap<>();
    private final Map<String, Long> suppressUntil = new HashMap<>();
    private final Map<String, Long> launchedAt = new HashMap<>();
    private final Map<String, Long> lastStateAt = new HashMap<>();
    private final Map<String, String> labelCache = new HashMap<>();

    private String lastForegroundPkg = "";
    private long lastClickAt = 0L;
    private long lastDebugLogAt = 0L;
    private int idlePolls = 0;
    private int screenWidth = 0;
    private int screenHeight = 0;
    private boolean pollingStarted = false;
    private Pending pending;

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
        startPolling();
    }

    // ---------------- 事件入口 ----------------

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

            // 应用内停留较久后切到新界面（如应用内插屏广告），重新开启一次检测窗口
            if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
                measureScreen();          // 横竖屏切换后重新取屏幕尺寸
                idlePolls = 0;            // 有界面活动，恢复高频轮询
                Long previous = lastStateAt.get(pkg);
                if (previous == null || now - previous > RESET_WINDOW_GAP_MS) {
                    launchedAt.put(pkg, now);
                }
                lastStateAt.put(pkg, now);
            }

            if (!inWindow(pkg)) {
                return;
            }
            scan(pkg, false);
        } catch (Throwable ignored) {
            // 无障碍回调里绝不能让异常冒泡导致服务被系统判为异常
        }
    }

    private boolean inWindow(String pkg) {
        int seconds = prefs.getLaunchWindowSec();
        if (seconds <= 0) {
            return true;
        }
        Long started = launchedAt.get(pkg);
        return started != null
                && SystemClock.elapsedRealtime() - started <= seconds * 1000L;
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

    // ---------------- 扫描 + 点击闭环 ----------------

    /** fromPoll=true 时采用稍长的节流，避免和事件扫描重复。 */
    private void scan(final String pkg, boolean fromPoll) {
        long now = SystemClock.elapsedRealtime();
        long minInterval = fromPoll ? POLL_INTERVAL_MS - 80 : SCAN_INTERVAL_MS;
        Long last = lastScanAt.get(pkg);
        if (last != null && now - last < minInterval) {
            return;
        }
        Long silent = suppressUntil.get(pkg);
        if (silent != null && now < silent) {
            return;
        }
        if (now - lastClickAt < CLICK_COOLDOWN_MS) {
            return;
        }
        // 点击后先等验证结果，别急着再点一次
        if (pending != null && now - lastClickAt < VERIFY_DELAY_MS - 100) {
            return;
        }
        lastScanAt.put(pkg, now);

        if (rules == null) {
            rules = RuleManager.get(this);
        }
        rules.refreshIfNeeded();

        final List<AccessibilityNodeInfo> roots = collectRoots();
        if (roots.isEmpty()) {
            return;
        }

        JSONObject remembered = prefs.getRemembered(pkg);
        final String rememberedId = remembered == null ? "" : remembered.optString("id", "");
        final String rememberedText = remembered == null ? "" : remembered.optString("text", "");
        final Pending found = new Pending();
        found.pkg = pkg;

        AccessibilityNodeInfo targetNode = null;
        try {
            targetNode = walk(roots, new NodeVisitor() {
                @Override
                public boolean visit(AccessibilityNodeInfo node) {
                    try {
                        if (!isCandidate(node)) {
                            return false;
                        }
                        String text = textOf(node.getText());
                        String desc = textOf(node.getContentDescription());
                        String viewId = node.getViewIdResourceName();
                        Rect bounds = new Rect();
                        node.getBoundsInScreen(bounds);

                        RuleManager.Match match = null;
                        // 该应用曾经成功过的按钮，优先信任，且不受弱规则的边缘限制
                        if (!rememberedId.isEmpty() && viewId != null
                                && shortId(viewId).equalsIgnoreCase(rememberedId)) {
                            match = new RuleManager.Match("记忆ID:" + clip(rememberedId));
                        } else if (!rememberedText.isEmpty() && text != null
                                && text.trim().equalsIgnoreCase(rememberedText)) {
                            match = new RuleManager.Match("记忆文字:" + clip(rememberedText));
                        }
                        if (match == null) {
                            match = rules.matchText(text);
                        }
                        if (match == null) {
                            match = rules.matchText(desc);
                        }
                        if (match == null) {
                            match = rules.matchId(viewId);
                        }
                        if (match == null) {
                            return false;
                        }
                        if (match.needEdge && !isEdgeArea(bounds)) {
                            return false;
                        }

                        found.reason = match.why;
                        found.viewId = viewId == null ? "" : viewId;
                        found.text = text == null ? "" : text.trim();
                        found.bounds = bounds;
                        return true;
                    } catch (Throwable ignored) {
                        return false;
                    }
                }
            });

            if (targetNode == null) {
                idlePolls++;
                maybeDebugLog(roots, pkg);
                return;
            }
            idlePolls = 0;

            boolean same = pending != null && samePending(pending, pkg, found.bounds);
            if (same && pending.attempt >= MAX_ATTEMPTS) {
                giveUp(pending);
                return;
            }
            int attemptIndex = same ? pending.attempt : 0;
            String how = clickTarget(targetNode, found.bounds, attemptIndex);
            if (how == null) {
                return;   // 这次点击派发失败，不占用 pending，等待下次扫描
            }

            found.attempt = attemptIndex + 1;
            found.how = how;
            pending = found;
            lastClickAt = SystemClock.elapsedRealtime();

            if (prefs.isRetryEnabled()) {
                mainHandler.removeCallbacks(verifyRunnable);
                mainHandler.postDelayed(verifyRunnable, VERIFY_DELAY_MS);
            } else {
                confirmSuccess(found);
            }
        } finally {
            if (targetNode != null) {
                try {
                    targetNode.recycle();
                } catch (Throwable ignored) {
                }
            }
            recycleRoots(roots);
        }
    }

    private boolean samePending(Pending p, String pkg, Rect bounds) {
        return p != null && p.pkg.equals(pkg) && p.bounds != null
                && Math.abs(p.bounds.centerX() - bounds.centerX()) < 120
                && Math.abs(p.bounds.centerY() - bounds.centerY()) < 120;
    }

    /** 点击后确认按钮是否还留在屏幕上；没消失就换方式重试。 */
    private final Runnable verifyRunnable = new Runnable() {
        @Override
        public void run() {
            final Pending p = pending;
            if (p == null || sInstance == null) {
                return;
            }
            try {
                List<AccessibilityNodeInfo> roots = collectRoots();
                boolean stillThere = false;
                try {
                    stillThere = stillHasSkipButton(roots, p.bounds);
                } finally {
                    recycleRoots(roots);
                }
                if (!stillThere) {
                    confirmSuccess(p);
                } else if (p.attempt >= MAX_ATTEMPTS) {
                    giveUp(p);
                } else {
                    // 保留 pending，由轮询或这次主动调度继续换方式重试
                    mainHandler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                scan(p.pkg, true);
                            } catch (Throwable ignored) {
                            }
                        }
                    }, 450);
                }
            } catch (Throwable ignored) {
            }
        }
    };

    /** 检查原位置附近是否还存在任意「跳过类」按钮（倒计时文字变了也能识别）。 */
    private boolean stillHasSkipButton(List<AccessibilityNodeInfo> roots, final Rect near) {
        final boolean[] found = new boolean[]{false};
        AccessibilityNodeInfo kept = walk(roots, new NodeVisitor() {
            @Override
            public boolean visit(AccessibilityNodeInfo node) {
                try {
                    if (!isCandidate(node)) {
                        return false;
                    }
                    Rect rect = new Rect();
                    node.getBoundsInScreen(rect);
                    if (Math.abs(rect.centerX() - near.centerX()) > 120
                            || Math.abs(rect.centerY() - near.centerY()) > 120) {
                        return false;
                    }
                    RuleManager.Match match = rules.matchText(textOf(node.getText()));
                    if (match == null) {
                        match = rules.matchText(textOf(node.getContentDescription()));
                    }
                    if (match == null) {
                        match = rules.matchId(node.getViewIdResourceName());
                    }
                    if (match != null && (!match.needEdge || isEdgeArea(rect))) {
                        found[0] = true;
                        return true;
                    }
                } catch (Throwable ignored) {
                }
                return false;
            }
        });
        if (kept != null) {
            try {
                kept.recycle();
            } catch (Throwable ignored) {
            }
        }
        return found[0];
    }

    private void confirmSuccess(Pending p) {
        if (p == null) {
            return;
        }
        suppressUntil.put(p.pkg, SystemClock.elapsedRealtime() + SUPPRESS_AFTER_SUCCESS_MS);
        pending = null;

        prefs.addSkip();
        prefs.rememberHit(p.pkg, p.viewId, p.text);
        String how = p.how + (p.attempt > 1 ? "·重试" + p.attempt : "");
        prefs.addLog(nowText(), appLabel(p.pkg), p.reason == null ? "-" : p.reason, how);
        prefs.touchHeartbeat();

        if (prefs.isToastEnabled()) {
            showToast("已跳过开屏广告");
        }
        if (prefs.isVibrateEnabled()) {
            vibrate();
        }
    }

    private void giveUp(Pending p) {
        if (p == null) {
            return;
        }
        suppressUntil.put(p.pkg, SystemClock.elapsedRealtime() + SUPPRESS_AFTER_GIVEUP_MS);
        pending = null;
        prefs.addLog(nowText(), appLabel(p.pkg),
                p.reason == null ? "-" : p.reason, "未生效·已重试" + p.attempt + "次");
    }

    // ---------------- 控件树遍历 ----------------

    /** 收集所有窗口的根节点（多窗口 / 分屏 / 悬浮广告）。 */
    private List<AccessibilityNodeInfo> collectRoots() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            for (AccessibilityWindowInfo window : windows) {
                AccessibilityNodeInfo root = window.getRoot();
                if (root != null && !roots.contains(root)) {
                    roots.add(root);
                }
            }
        } catch (Throwable ignored) {
        }
        if (roots.isEmpty()) {
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null) {
                    roots.add(root);
                }
            } catch (Throwable ignored) {
            }
        }
        return roots;
    }

    /**
     * 广度优先遍历所有根节点。返回的命中节点不会在方法内回收，由调用方用完后 recycle。
     */
    private AccessibilityNodeInfo walk(List<AccessibilityNodeInfo> roots, NodeVisitor visitor) {
        ArrayDeque<AccessibilityNodeInfo> queue = new ArrayDeque<>();
        List<AccessibilityNodeInfo> visited = new ArrayList<>();
        for (AccessibilityNodeInfo root : roots) {
            queue.add(root);
        }
        AccessibilityNodeInfo found = null;
        int count = 0;
        while (!queue.isEmpty() && count < MAX_NODES) {
            AccessibilityNodeInfo node = queue.poll();
            if (node == null) {
                continue;
            }
            count++;
            if (!roots.contains(node)) {
                visited.add(node);
            }
            try {
                if (visitor.visit(node)) {
                    found = node;
                    break;
                }
                int childCount = node.getChildCount();
                for (int i = 0; i < childCount; i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child != null) {
                        queue.add(child);
                    }
                }
            } catch (Throwable ignored) {
                // 单个控件失效（窗口已刷新）时跳过它
            }
        }
        for (AccessibilityNodeInfo n : visited) {
            if (n == found) {
                continue;
            }
            try {
                n.recycle();
            } catch (Throwable ignored) {
            }
        }
        for (AccessibilityNodeInfo n : queue) {
            if (n != null && n != found && !roots.contains(n)) {
                try {
                    n.recycle();
                } catch (Throwable ignored) {
                }
            }
        }
        return found;
    }

    private void recycleRoots(List<AccessibilityNodeInfo> roots) {
        for (AccessibilityNodeInfo root : roots) {
            try {
                root.recycle();
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean isCandidate(AccessibilityNodeInfo node) {
        if (!node.isVisibleToUser() || !node.isEnabled()) {
            return false;
        }
        Rect rect = new Rect();
        node.getBoundsInScreen(rect);
        if (rect.width() < 12 || rect.height() < 12) {
            return false;
        }
        if (screenWidth > 0 && (rect.centerX() < 0 || rect.centerX() > screenWidth)) {
            return false;
        }
        return screenHeight <= 0
                || (rect.centerY() >= 0 && rect.centerY() <= screenHeight);
    }

    /** 弱规则只采信屏幕上下边缘的控件（比初版放宽，减少漏检）。 */
    private boolean isEdgeArea(Rect rect) {
        if (screenHeight <= 0) {
            return true;
        }
        float y = rect.centerY();
        return y < screenHeight * 0.40f || y > screenHeight * 0.62f;
    }

    // ---------------- 点击 ----------------

    /** 第 1 次用无障碍控件点击，之后依次换中心 / 右上 / 右下坐标点击。 */
    private String clickTarget(AccessibilityNodeInfo node, Rect bounds, int attemptIndex) {
        if (prefs.isForceGesture() || attemptIndex >= 1) {
            int mode = Math.max(1, attemptIndex);
            return clickByGesture(bounds, mode) ? gestureLabel(mode) : null;
        }
        String how = clickByNode(node);
        if (how != null) {
            return how;
        }
        return clickByGesture(bounds, 1) ? gestureLabel(1) : null;
    }

    private String clickByNode(AccessibilityNodeInfo node) {
        try {
            if (hasClickAction(node) && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return "控件点击";
            }
        } catch (Throwable ignored) {
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
            try {
                if (hasClickAction(parent) && parent.isVisibleToUser()
                        && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    clicked = true;
                    break;
                }
            } catch (Throwable ignored) {
            }
            current = parent;
        }
        for (AccessibilityNodeInfo p : parents) {
            try {
                p.recycle();
            } catch (Throwable ignored) {
            }
        }
        return clicked ? "父控件点击" : null;
    }

    private static boolean hasClickAction(AccessibilityNodeInfo node) {
        return (node.getActions() & AccessibilityNodeInfo.ACTION_CLICK) != 0;
    }

    private static String gestureLabel(int mode) {
        switch (mode) {
            case 2:
                return "坐标·右上";
            case 3:
                return "坐标·右下";
            case 4:
                return "坐标·左侧";
            default:
                return "坐标点击";
        }
    }

    private boolean clickByGesture(Rect bounds, int mode) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        int x;
        int y;
        switch (mode) {
            case 2:
                x = bounds.right - Math.max(4, bounds.width() / 5);
                y = bounds.top + Math.max(4, bounds.height() / 4);
                break;
            case 3:
                x = bounds.right - Math.max(4, bounds.width() / 5);
                y = bounds.bottom - Math.max(4, bounds.height() / 4);
                break;
            case 4:
                x = bounds.left + Math.max(4, bounds.width() / 5);
                y = bounds.centerY();
                break;
            default:
                x = bounds.centerX();
                y = bounds.centerY();
                break;
        }
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

    // ---------------- 调试日志 ----------------

    /** 没命中时把屏幕边缘的短文本 / ID 记下来，方便用户自己补规则。 */
    private void maybeDebugLog(List<AccessibilityNodeInfo> roots, final String pkg) {
        if (!prefs.isDebugLogEnabled()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastDebugLogAt < DEBUG_LOG_INTERVAL_MS) {
            return;
        }
        lastDebugLogAt = now;

        final StringBuilder sb = new StringBuilder();
        final int[] count = new int[]{0};
        AccessibilityNodeInfo kept = walk(roots, new NodeVisitor() {
            @Override
            public boolean visit(AccessibilityNodeInfo node) {
                if (count[0] >= 10) {
                    return true;
                }
                try {
                    if (!isCandidate(node)) {
                        return false;
                    }
                    Rect rect = new Rect();
                    node.getBoundsInScreen(rect);
                    if (!isEdgeArea(rect)) {
                        return false;
                    }
                    String text = textOf(node.getText());
                    String id = node.getViewIdResourceName();
                    if ((text == null || text.trim().isEmpty()) && (id == null || id.isEmpty())) {
                        return false;
                    }
                    String label = text == null || text.trim().isEmpty()
                            ? "-" : clip(text.trim());
                    sb.append(label).append(" @")
                            .append(id == null ? "-" : shortId(id)).append(" ; ");
                    count[0]++;
                } catch (Throwable ignored) {
                }
                return false;
            }
        });
        if (kept != null) {
            try {
                kept.recycle();
            } catch (Throwable ignored) {
            }
        }
        if (sb.length() > 0) {
            prefs.addLog(nowText(), appLabel(pkg), "候选:" + clip(sb.toString()), "调试");
        }
    }

    // ---------------- 轮询与辅助 ----------------

    private void startPolling() {
        if (pollingStarted || mainHandler == null) {
            return;
        }
        pollingStarted = true;
        mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (sInstance == null || mainHandler == null) {
                return;
            }
            long interval = POLL_INTERVAL_MS;
            try {
                if (prefs.isEnabled() && prefs.isPollingEnabled()) {
                    String pkg = lastForegroundPkg;
                    if (!pkg.isEmpty() && !shouldIgnore(pkg) && inWindow(pkg)) {
                        // 连续多次没命中就降频，避免「不限」模式下长期空扫耗电
                        interval = idlePolls > 6 ? 1500 : POLL_INTERVAL_MS;
                        scan(pkg, true);
                    }
                }
            } catch (Throwable ignored) {
            }
            mainHandler.postDelayed(this, interval);
        }
    };

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

    private static String nowText() {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(new Date());
    }

    private static String textOf(CharSequence cs) {
        return cs == null ? null : cs.toString();
    }

    private static String shortId(String id) {
        if (id == null) {
            return "-";
        }
        int slash = id.lastIndexOf('/');
        return slash >= 0 ? id.substring(slash + 1) : id;
    }

    private static String clip(String s) {
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
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
        pollingStarted = false;
        if (mainHandler != null) {
            mainHandler.removeCallbacks(pollRunnable);
            mainHandler.removeCallbacks(verifyRunnable);
        }
        pending = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        sInstance = null;
        pollingStarted = false;
        if (mainHandler != null) {
            mainHandler.removeCallbacks(pollRunnable);
            mainHandler.removeCallbacks(verifyRunnable);
        }
        pending = null;
        super.onDestroy();
    }
}
