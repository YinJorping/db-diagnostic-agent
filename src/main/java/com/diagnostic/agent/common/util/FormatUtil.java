package com.diagnostic.agent.common.util;

/** 诊断格式化工具方法。 */
public final class FormatUtil {

    private FormatUtil() {
    }

    /**
     * 将 0-1 之间的 double 格式化为百分比字符串。
     * 例如 formatPercent(0.85) → "85%", formatPercent(0.997) → "100%"
     */
    public static String formatPercent(double v) {
        return String.format("%.0f%%", v * 100);
    }
}
