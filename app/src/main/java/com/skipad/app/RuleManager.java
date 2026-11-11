package com.skipad.app;

import android.content.Context;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 开屏广告「跳过按钮」识别规则。
 *
 * 三条命中路径：
 *   1. 控件文字 / contentDescription 命中内置正则（跳过、5s 后跳过、Skip Ad…）
 *   2. 控件 resource-id 命中特征片段（skip / ad_close / countdown…）
 *   3. 用户在界面里补充的自定义文字与 ID 片段
 *
 * 规则缓存依据 Prefs 的 rules_version 热更新，界面保存后无需重启服务。
 */
public class RuleManager {

    /** 命中结果，why 用于日志展示。 */
    public static class Match {
        public final String why;

        public Match(String why) {
            this.why = why;
        }
    }

    /** 内置文字规则：对「去掉空白后的短文本」做匹配。 */
    private static final Pattern[] TEXT_RULES = {
            // 跳过 / 跳过广告 / 跳过 5 / 跳过广告 3s / 点击跳过
            Pattern.compile("(?i)^(点击)?跳过(广告|该广告|此广告|推荐)?\\d{0,3}[sS秒]?$"),
            // 5s 后跳过 / 3秒跳过 / 广告 5s 跳过
            Pattern.compile("^\\d{1,3}[sS秒]?.{0,6}跳过$"),
            Pattern.compile("(广告|剩余|倒计时)\\d{1,3}[sS秒].{0,10}跳过"),
            // 英文：Skip / Skip Ad / Skip Ads / Skip 5s / Close Ad
            Pattern.compile("(?i)^(skip|skipad|skipads|skip\\d{1,3}s?|closead|closeads)$"),
            // 关闭广告 / 关闭该广告 / 跳过此页
            Pattern.compile("^.{0,6}(关闭广告|关闭该广告|关闭此广告|跳过此页|跳过广告).{0,4}$"),
            // 兜底：任何很短且包含「跳过」的按钮文字
            Pattern.compile("^.{0,6}跳过.{0,6}$")
    };

    /** 这些词出现在按钮上时，说明是业务流程而非开屏广告，不点。 */
    private static final Pattern[] NEGATIVE_RULES = {
            Pattern.compile("(?i)(跳过登录|跳过密码|跳过验证|跳过绑定|跳过更新|跳过教程|跳过新手|跳过引导|跳过此步骤|跳过签到|跳过设置|跳过授权|跳过备份|skip[_-]?login|skip[_-]?sign|skip[_-]?update)"),
            Pattern.compile("(以后再说|下次再说|不再提示|暂不|我知道了|取消|拒绝|不同意|同意并继续|下一步|返回|退出)")
    };

    /** 控件 ID 特征（统一小写后做 contains 判断）。 */
    private static final String[] ID_KEYS = {
            "skip", "splash_skip", "ad_skip", "close_ad", "ad_close",
            "closead", "splash_close", "adclose", "ad_splash_close",
            "countdown", "count_down", "count_down_view",
            "tt_splash_skip", "ksad_skip", "gdt_skip", "splash_ad_close"
    };

    private static RuleManager sInstance;

    private final Context context;
    private long version = -1L;
    private final List<Pattern> extraText = new ArrayList<>();
    private final List<String> extraIds = new ArrayList<>();

    public static synchronized RuleManager get(Context context) {
        if (sInstance == null) {
            sInstance = new RuleManager(context.getApplicationContext());
        }
        return sInstance;
    }

    private RuleManager(Context context) {
        this.context = context;
    }

    public synchronized void refreshIfNeeded() {
        long current = Prefs.get(context).getRulesVersion();
        if (current == version) {
            return;
        }
        rebuild();
        version = current;
    }

    private void rebuild() {
        extraText.clear();
        extraIds.clear();
        for (String line : splitLines(Prefs.get(context).getExtraKeywords())) {
            try {
                if (line.startsWith("re:")) {
                    extraText.add(Pattern.compile(line.substring(3)));
                } else {
                    extraText.add(Pattern.compile(Pattern.quote(line), Pattern.CASE_INSENSITIVE));
                }
            } catch (PatternSyntaxException ignored) {
                // 用户写错的正则直接忽略，不影响内置规则
            }
        }
        for (String line : splitLines(Prefs.get(context).getExtraIds())) {
            extraIds.add(line.toLowerCase(Locale.ROOT));
        }
    }

    private static List<String> splitLines(String raw) {
        List<String> result = new ArrayList<>();
        if (raw == null) {
            return result;
        }
        for (String line : raw.split("[\\r\\n,，;；]+")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    /** 返回 null 表示未命中。 */
    public synchronized Match matchText(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        String compact = text.replaceAll("\\s+", "");
        if (compact.length() > 18) {
            return null;
        }
        for (Pattern pattern : NEGATIVE_RULES) {
            if (pattern.matcher(compact).find()) {
                return null;
            }
        }
        for (Pattern pattern : TEXT_RULES) {
            if (pattern.matcher(compact).find()) {
                return new Match("文字:" + clip(text));
            }
        }
        for (Pattern pattern : extraText) {
            if (pattern.matcher(text).find()) {
                return new Match("自定义:" + clip(text));
            }
        }
        return null;
    }

    public synchronized boolean matchId(String viewId) {
        if (viewId == null || viewId.isEmpty()) {
            return false;
        }
        String id = viewId.toLowerCase(Locale.ROOT);
        for (String key : ID_KEYS) {
            if (id.contains(key)) {
                return true;
            }
        }
        for (String key : extraIds) {
            if (id.contains(key)) {
                return true;
            }
        }
        return false;
    }

    public synchronized int getRuleCount() {
        refreshIfNeeded();
        return TEXT_RULES.length + ID_KEYS.length + extraText.size() + extraIds.size();
    }

    public synchronized int getCustomRuleCount() {
        refreshIfNeeded();
        return extraText.size() + extraIds.size();
    }

    private static String clip(String s) {
        return s.length() > 24 ? s.substring(0, 24) + "…" : s;
    }
}
