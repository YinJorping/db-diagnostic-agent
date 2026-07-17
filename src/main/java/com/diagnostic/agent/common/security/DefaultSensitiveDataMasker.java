package com.diagnostic.agent.common.security;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

@Component
public class DefaultSensitiveDataMasker implements SensitiveDataMasker {

    // 手机号: 13812345678 → 138****5678
    private static final Pattern PHONE = Pattern.compile(
            "(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");

    // 邮箱: test@gmail.com → t***@gmail.com; test+spam@company.co.uk → t***@company.co.uk
    private static final Pattern EMAIL = Pattern.compile(
            "([a-zA-Z0-9])[^@]*(@.+)");

    // 身份证: 110101199001011234 → 110101********1234; 11010119900101123X → 110101********123X
    private static final Pattern ID_CARD = Pattern.compile(
            "(\\d{6})\\d{8}(\\d{3}[0-9Xx])");

    /**
     * SQL 字符串 Literal 脱敏 — 将比较表达式中的字符串常量替换为 {@code '***'}。
     *
     * <p>设计范围（Minimal Change — 不是 SQL Parser）:
     * <ul>
     *   <li>仅处理常见 SQL 比较运算符和 LIKE/ILIKE/正则匹配 (~) 后的单引号字符串字面量</li>
     *   <li>保留 SQL 结构（表名、列名、运算符、数字 Literal）不做变更，确保 LLM 仍可基于 SQL 结构进行诊断</li>
     *   <li>不处理 {@code IN ('a', 'b')} 列表、{@code BETWEEN 'a' AND 'b'}、{@code function('arg')} 等复杂语法</li>
     *   <li>不处理嵌套引号、转义引号、美元引号 ({@code $$...$$})</li>
     * </ul>
     *
     * <p>覆盖场景 (PostgreSQL):
     * <pre>{@code
     *   WHERE status = 'pending'       →  WHERE status = '***'
     *   WHERE name != 'admin'          →  WHERE name != '***'
     *   WHERE tag LIKE 'urgent%'       →  WHERE tag LIKE '***'
     *   WHERE role ~ 'admin.*'         →  WHERE role ~ '***'
     *   WHERE val >= '2024-01-01'      →  WHERE val >= '***'
     * }</pre>
     *
     * <p>不覆盖（已知限制）:
     * <pre>{@code
     *   IN ('a', 'b', 'c')             — 列表中的 Literal
     *   INSERT INTO t VALUES ('x')     — VALUES 中的 Literal
     *   function('arg')                — 函数参数中的 Literal
     * }</pre>
     *
     * <p>本项目的目标是保护明显的业务敏感数据泄露到 LLM Prompt，而非实现 SQL 审计系统。
     * 如需更全面的 SQL 脱敏，应在架构层面引入 SQL Parser（如 JSqlParser）而非在此处追加正则。
     */
    private static final Pattern SQL_STRING_LITERAL = Pattern.compile(
            "(=|!=|<>|>=|<=|>|<|LIKE|ILIKE|~)\\s*'[^']*'",
            Pattern.CASE_INSENSITIVE);

    @Override
    public String mask(String content) {
        if (content == null || content.isEmpty()) {
            return content;
        }
        content = PHONE.matcher(content).replaceAll("$1****$2");
        content = EMAIL.matcher(content).replaceAll("$1***$2");
        content = ID_CARD.matcher(content).replaceAll("$1********$2");
        content = SQL_STRING_LITERAL.matcher(content).replaceAll("$1'***'");
        return content;
    }
}
