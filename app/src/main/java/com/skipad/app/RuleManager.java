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
 * 命中路径：文字内置正则 → 控件 ID 特征 → 用户自定义 → 用户自定义 ID。
 * 较弱的规则（纯倒计时数字、泛化 ID）带 needEdge 标记，只允许屏幕上下边缘的控件命中，
 * 用来兼顾「识别率」和「误触率」。
 */
public class RuleManager {

    /** why 用于日志；needEdge=true 表示必须位于屏幕上下边缘区域。 */
    public static class Match {
        public final String why;
        public final boolean needEdge;

        public Match(String why, boolean needEdge) {
            this.why = why;
            this.needEdge = needEdge;
        }

        public Match(String why) {
            this(why, false);
        }
    }

    private static class TextRule {
        final Pattern pattern;
        final boolean needEdge;

        TextRule(String regex, boolean needEdge) {
            this.pattern = Pattern.compile(regex);
            this.needEdge = needEdge;
        }
    }

    /** 内置文字规则：对「去掉空白后的短文本」做匹配。 */
    private static final TextRule[] TEXT_RULES = {
            // 跳过 / 跳过广告 / 跳过 5 / 跳过广告 3s / 点击跳过（含繁体）
            new TextRule("(?i)^(点击|點擊)?(跳过|跳過|略過)(广告|廣告|该广告|此广告|推荐|推薦)?\\d{0,3}[sS秒]?$", false),
            // 5s 后跳过 / 3秒跳过
            new TextRule("^\\d{1,3}[sS秒]?.{0,6}(跳过|跳過)$", false),
            // 广告 5s 点击跳过 / 剩余 4 秒跳过
            new TextRule("(广告|廣告|剩余|剩餘|倒计时|倒計時)\\d{1,3}[sS秒].{0,10}(跳过|跳過)", false),
            // 英文：Skip / Skip Ad / Skip Ads / Skip 5s / Close Ad
            new TextRule("(?i)^(skip|skipad|skipads|skip\\d{1,3}s?|closead|closeads)$", false),
            // 关闭广告 / 跳过此页
            new TextRule("^.{0,6}(关闭广告|關閉廣告|关闭该广告|关闭此广告|跳过此页|跳过广告).{0,4}$", false),
            // 兜底：任何较短且包含「跳过」的按钮文字
            new TextRule("^.{0,6}(跳过|跳過).{0,8}$", false),
            // ↓ 以下规则本身不够明确，只允许屏幕上下边缘的控件命中
            new TextRule("^\\d{1,3}[sS秒]$", true),                        // 单独的 "5s"
            new TextRule("^(广告|廣告)\\s*\\d{1,3}[sS秒]$", true),          // "广告 5s"
            new TextRule("^(剩余|剩餘|倒计时|倒計時)\\s*\\d{1,3}[sS秒]$", true),
            new TextRule("(?i)^(×|✕|✖|close|skip)\\s*\\d{0,3}[sS秒]?$", true)
    };

    /** 业务流程里的「跳过」不是广告，直接否决。只保留高置信度的业务词。 */
    private static final Pattern[] NEGATIVE_RULES = {
            Pattern.compile("(?i)(跳过|跳過).{0,4}(登录|登錄|注册|註冊|验证|驗證|绑定|綁定|密码|密碼|实名|實名|更新|升级|教程|新手|引导|引導|授权|授權|备份|備份|签到|簽到|此步骤|设置|設定)"),
            Pattern.compile("(?i)skip[_-]?(login|sign|update|password|verify|tutorial)"),
            Pattern.compile("(以后再说|下次再说|不再提示|我知道了|同意并继续)")
    };

    /** 强特征 ID：命中即可信。 */
    private static final String[] STRONG_IDS = {
            "skip", "skip_btn", "btn_skip", "tv_skip", "iv_skip", "skip_view", "skip_time",
            "splash_skip", "ad_skip", "skip_ad", "skipad",
            "close_ad", "ad_close", "closead", "adclose", "splash_close", "ad_splash_close",
            "tt_splash_skip", "ksad_skip", "gdt_skip", "splash_ad_close"
    };

    /** 弱特征 ID：必须在屏幕边缘才采信。 */
    private static final String[] WEAK_IDS = {
            "countdown", "count_down", "count_down_view", "splash", "ad_splash", "splash_ad",
            "close_btn", "iv_close", "btn_close", "ad_close_btn", "jump_over", "jump",
            "tt_splash", "ksad_", "gdt_ad", "interaction_close"
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
        for (Pattern pattern : NEGATIVE_RULES) {
            if (pattern.matcher(compact).find()) {
                return null;
            }
        }
        // 用户自定义规则优先，且不受 18 字长度限制（长文案按钮也能补上）
        for (Pattern pattern : extraText) {
            if (pattern.matcher(text).find()) {
                return new Match("自定义:" + clip(text));
            }
        }
        if (compact.length() > 18) {
            return null;
        }
        for (TextRule rule : TEXT_RULES) {
            if (rule.pattern.matcher(compact).find()) {
                return new Match("文字:" + clip(text), rule.needEdge);
            }
        }
        return null;
    }

    /** 返回 null 表示未命中；弱特征会带 needEdge=true。 */
    public synchronized Match matchId(String viewId) {
        if (viewId == null || viewId.isEmpty()) {
            return null;
        }
        String id = viewId.toLowerCase(Locale.ROOT);
        for (String key : extraIds) {
            if (id.contains(key)) {
                return new Match("自定义ID:" + shortId(viewId));
            }
        }
        for (String key : STRONG_IDS) {
            if (id.contains(key)) {
                return new Match("ID:" + shortId(viewId));
            }
        }
        for (String key : WEAK_IDS) {
            if (id.contains(key)) {
                return new Match("弱ID:" + shortId(viewId), true);
            }
        }
        return null;
    }

    public synchronized int getRuleCount() {
        refreshIfNeeded();
        return TEXT_RULES.length + STRONG_IDS.length + WEAK_IDS.length
                + extraText.size() + extraIds.size();
    }

    public synchronized int getCustomRuleCount() {
        refreshIfNeeded();
        return extraText.size() + extraIds.size();
    }

    private static String shortId(String id) {
        if (id == null) {
            return "";
        }
        int slash = id.lastIndexOf('/');
        return slash >= 0 ? id.substring(slash + 1) : id;
    }

    private static String clip(String s) {
        return s.length() > 24 ? s.substring(0, 24) + "…" : s;
    }
}
